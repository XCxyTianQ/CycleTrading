package com.cycletrading.core.futures;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.Items;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.bank.TxEntry;
import com.cycletrading.util.MaturityQueue;
import com.cycletrading.sched.Scheduler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 期货交割市场（标准期货原则）：
 *
 * - 标准化合约：品种与数量固定（config futures.contracts，默认 9 个大宗品种）；
 * - 全额保证金：卖方开仓托管全部商品（OPEN），买方成交全额付款锁定（LOCKED），零违约风险；
 * - 合同锁定：成交后不可退出，到期实物交割；未成交前卖方可撤单取回商品；
 * - 交割：到期由全局线程轮询（每 20 秒），商品**入买方邮箱**（Folia 安全，纯数据操作；
 *   邮箱不足时按剩余量重试，绝不丢物），卖方货款（税后）入银行；
 * - 管理员可强制交割 / 撤销（撤销=退款+退货入卖方邮箱）。
 */
public final class FuturesService {

    public enum BuyResult { SUCCESS, NOT_FOUND, NOT_ACTIVE, SELF_PURCHASE, INSUFFICIENT_FUNDS, FROZEN, NO_SPACE, ERROR }

    public enum CancelResult { SUCCESS, NOT_FOUND, NOT_ACTIVE, NOT_OWNER, NO_SPACE, ERROR }

    public enum PosOpenResult { SUCCESS, FROZEN, INSUFFICIENT_FUNDS, INVALID_TYPE, INVALID_COMMODITY, NO_ANCHOR, INVALID_QTY, INVALID_TERM, DISABLED }

    public enum PosCloseResult { SUCCESS, NOT_FOUND, NOT_ACTIVE, NOT_OWNER }

    public static final long DAY_TICKS = 24000L;

    private final CycleTradingPlugin plugin;
    private final ConcurrentHashMap<Long, FuturesContract> contracts = new ConcurrentHashMap<>();
    private final MaturityQueue<FuturesContract> settlement = new MaturityQueue<>();
    private final AtomicLong nextId = new AtomicLong(1);

    private final ConcurrentHashMap<Long, FuturesPosition> positions = new ConcurrentHashMap<>();
    private final MaturityQueue<FuturesPosition> posQueue = new MaturityQueue<>();
    private final AtomicLong nextPosId = new AtomicLong(1);

    private Bank bank;

    public FuturesService(CycleTradingPlugin plugin) {
        this.plugin = plugin;
    }

    public void attachBank(Bank bank) {
        this.bank = bank;
    }

    /** 启动交割/头寸轮询（全局线程，每 20 秒）。 */
    public void start() {
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            checkSettlement();
            checkPositions();
        }, 400L, 400L);
    }

    // ---------- 查询 ----------

    public List<FuturesContract> openNewestFirst() {
        return contracts.values().stream()
                .filter(FuturesContract::isOpen)
                .sorted(Comparator.comparingLong((FuturesContract c) -> c.id).reversed())
                .toList();
    }

    public List<FuturesContract> ofSeller(String uuid) {
        return contracts.values().stream()
                .filter(c -> c.seller.equals(uuid))
                .sorted(Comparator.comparingLong((FuturesContract c) -> c.id).reversed())
                .toList();
    }

    public List<FuturesContract> ofBuyer(String uuid) {
        return contracts.values().stream()
                .filter(c -> c.buyer != null && c.buyer.equals(uuid))
                .sorted(Comparator.comparingLong((FuturesContract c) -> c.id).reversed())
                .toList();
    }

    /** 已交割合约（供期权价格历史重建）。 */
    public List<FuturesContract> deliveredContracts() {
        return contracts.values().stream()
                .filter(c -> FuturesContract.DELIVERED.equals(c.status))
                .toList();
    }

    public int countByStatus(String status) {
        return (int) contracts.values().stream().filter(c -> c.status.equals(status)).count();
    }

    /** 锁定中的买方资金（LOCKED 合约价格合计，供经济公报）。 */
    public long lockedValue() {
        long sum = 0;
        for (FuturesContract c : contracts.values()) {
            if (c.isLocked()) {
                sum += c.price;
            }
        }
        return sum;
    }

    // ---------- 开仓（卖方，玩家线程） ----------

    /** 开仓：商品已由调用方从手中移除（品种/数量已校验）。 */
    public FuturesContract open(Player seller, ItemStack goods, long price, int termDays) {
        long id = nextId.getAndIncrement();
        FuturesContract c = new FuturesContract(id, seller.getUniqueId().toString(), seller.getName(),
                Items.toBase64(goods), price, termDays, System.currentTimeMillis());
        contracts.put(id, c);
        plugin.storage().requestSave();
        return c;
    }

    // ---------- 成交（买方，玩家线程） ----------

    public BuyResult validateBuy(Player buyer, long id) {
        String buyerUuid = buyer.getUniqueId().toString();
        FuturesContract c = contracts.get(id);
        if (c == null) {
            return BuyResult.NOT_FOUND;
        }
        if (!c.isOpen()) {
            return BuyResult.NOT_ACTIVE;
        }
        if (buyerUuid.equals(c.seller)) {
            return BuyResult.SELF_PURCHASE;
        }
        if (bank.isFrozen(buyerUuid)) {
            return BuyResult.FROZEN;
        }
        // 交割空间预检：商品一律入邮箱，需预留堆数格位
        ItemStack goods;
        try {
            goods = Items.fromBase64(c.item);
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Futures #" + id + " goods deserialization failed: " + ex.getMessage());
            return BuyResult.ERROR;
        }
        int stacks = (goods.getAmount() + 63) / 64;
        if (plugin.mailbox().roomLeft(buyerUuid) < stacks) {
            return BuyResult.NO_SPACE;
        }
        long price = c.price;
        boolean payVirtual = bank.balance(buyerUuid) >= price;
        boolean physicalOk = price <= Integer.MAX_VALUE
                && buyer.getInventory().containsAtLeast(Items.emeralds(1), (int) price);
        if (!payVirtual && !physicalOk) {
            return BuyResult.INSUFFICIENT_FUNDS;
        }
        return BuyResult.SUCCESS;
    }

    /** 成交（预检通过后调用；玩家线程）。返回成交合约；并发扣款失败返回 null。 */
    public FuturesContract buy(Player buyer, long id) {
        String buyerUuid = buyer.getUniqueId().toString();
        long price = contracts.get(id).price;
        boolean paidVirtual = false;
        if (bank.balance(buyerUuid) >= price) {
            if (bank.debit(buyerUuid, price, TxEntry.FUTURES_BUY)) {
                paidVirtual = true;
            }
        }
        if (!paidVirtual) {
            Map<Integer, ItemStack> left = buyer.getInventory().removeItem(Items.emeralds((int) price));
            if (!left.isEmpty()) {
                return null;
            }
        }
        AtomicReference<Boolean> reserved = new AtomicReference<>(false);
        contracts.computeIfPresent(id, (k, cur) -> {
            if (cur.isOpen()) {
                cur.status = FuturesContract.LOCKED;
                cur.buyer = buyerUuid;
                cur.buyerName = buyer.getName();
                cur.lockedAt = worldTime();
                cur.matureAt = cur.lockedAt + cur.termDays * DAY_TICKS;
                reserved.set(true);
            }
            return cur;
        });
        if (!reserved.get()) {
            // 占用失败：回滚付款
            if (paidVirtual) {
                bank.credit(buyerUuid, buyer.getName(), price, TxEntry.REFUND);
            } else {
                buyer.getInventory().addItem(Items.emeralds((int) price));
            }
            return null;
        }
        FuturesContract c = contracts.get(id);
        settlement.add(c);
        Player seller = plugin.getServer().getPlayer(UUID.fromString(c.seller));
        if (seller != null && seller.isOnline()) {
            Scheduler.onPlayer(plugin, seller, sp -> sp.sendMessage("§a你的期货合约 #" + id
                    + " 已成交！货款将在交割时入账银行"), null);
        }
        plugin.storage().requestSave();
        return c;
    }

    // ---------- 撤单（卖方，玩家线程；仅 OPEN） ----------

    public CancelResult cancel(Player seller, long id) {
        String uuid = seller.getUniqueId().toString();
        FuturesContract c = contracts.get(id);
        if (c == null) {
            return CancelResult.NOT_FOUND;
        }
        if (!c.isOpen()) {
            return CancelResult.NOT_ACTIVE;
        }
        if (!c.seller.equals(uuid)) {
            return CancelResult.NOT_OWNER;
        }
        ItemStack goods;
        try {
            goods = Items.fromBase64(c.item);
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Futures #" + id + " goods deserialization failed on cancel: " + ex.getMessage());
            return CancelResult.ERROR;
        }
        if (!Items.canFit(seller.getInventory(), goods) && plugin.mailbox().roomLeft(uuid) < (goods.getAmount() + 63) / 64) {
            return CancelResult.NO_SPACE;
        }
        Map<Integer, ItemStack> ov = seller.getInventory().addItem(goods);
        for (ItemStack it : ov.values()) {
            if (!plugin.mailbox().add(uuid, it, "FUTURES")) {
                return CancelResult.NO_SPACE;
            }
        }
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        contracts.computeIfPresent(id, (k, cur) -> {
            if (cur.isOpen() && cur.seller.equals(uuid)) {
                cur.status = FuturesContract.WITHDRAWN;
                ok.set(true);
            }
            return cur;
        });
        if (!ok.get()) {
            return CancelResult.NOT_ACTIVE;
        }
        plugin.storage().requestSave();
        return CancelResult.SUCCESS;
    }

    // ---------- 交割（全局线程轮询） ----------

    private long worldTime() {
        org.bukkit.World w = plugin.getServer().getWorlds().stream()
                .filter(x -> x.getEnvironment() == org.bukkit.World.Environment.NORMAL)
                .findFirst().orElse(null);
        return w == null ? 0 : w.getFullTime();
    }

    private void checkSettlement() {
        long now = worldTime();
        while (!settlement.isEmpty() && settlement.peek().matureAt <= now) {
            FuturesContract c = settlement.poll();
            FuturesContract cur = contracts.get(c.id);
            if (cur == null || !cur.isLocked()) {
                continue;
            }
            settle(cur);
        }
    }

    /**
     * 交割：商品入买方邮箱（纯数据，全局线程安全）。
     * 邮箱格位不足 → 更新剩余量并重新入队，下一轮（20 秒后）重试，绝不丢物。
     * 全部交付后卖方货款（税后）入银行。
     */
    private void settle(FuturesContract c) {
        ItemStack goods;
        try {
            goods = Items.fromBase64(c.item);
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Futures #" + c.id + " goods deserialization failed at settlement, refunding buyer");
            bank.credit(c.buyer, c.buyerName, c.price, TxEntry.REFUND);
            c.status = FuturesContract.CANCELLED;
            plugin.storage().requestSave();
            return;
        }
        long remaining = 0;
        int total = goods.getAmount();
        while (total > 0) {
            int chunk = Math.min(64, total);
            total -= chunk;
            ItemStack piece = goods.clone();
            piece.setAmount(chunk);
            if (!plugin.mailbox().add(c.buyer, piece, "FUTURES")) {
                remaining += total + chunk;
                break;
            }
        }
        if (remaining > 0) {
            ItemStack left = goods.clone();
            left.setAmount((int) remaining);
            c.item = Items.toBase64(left);
            settlement.add(c); // 重试
            plugin.storage().requestSave();
            return;
        }
        long earnings = c.price - taxOf(c.price);
        bank.credit(c.seller, c.sellerName, earnings, TxEntry.FUTURES_SELL);
        // 成交税入国库
        long tax = taxOf(c.price);
        if (tax > 0) {
            bank.credit(Bank.SYSTEM, "SYSTEM", tax, TxEntry.TAX);
        }
        c.status = FuturesContract.DELIVERED;
        c.deliveredAt = System.currentTimeMillis();
        // 交割价入库 → 期权结算价来源
        plugin.priceHistory().record(goods.getType(), c.price);
        notify(c.buyer, "§a期货合约 #" + c.id + " 已交割！商品已入邮箱（/ct mail 领取）");
        notify(c.seller, "§a期货合约 #" + c.id + " 交割完成，货款 §e" + earnings + " 绿宝石§a已入银行"
                + (taxOf(c.price) > 0 ? "§7（税后）" : ""));
        plugin.storage().requestSave();
    }

    // ---------- 管理员 ----------

    /** 强制交割（LOCKED 合约立即结算）。 */
    public boolean adminDeliver(long id) {
        FuturesContract c = contracts.get(id);
        if (c == null || !c.isLocked()) {
            return false;
        }
        settlement.remove(c);
        settle(c);
        return true;
    }

    /** 强制撤销：已成交 → 退款买家 + 商品退卖方邮箱；挂单 → 商品退卖方邮箱。 */
    public boolean adminCancel(long id) {
        FuturesContract c = contracts.get(id);
        if (c == null || !(c.isOpen() || c.isLocked())) {
            return false;
        }
        if (c.isLocked()) {
            bank.credit(c.buyer, c.buyerName, c.price, TxEntry.FUTURES_REFUND);
        }
        ItemStack goods;
        try {
            goods = Items.fromBase64(c.item);
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Futures #" + id + " goods deserialization failed on admin cancel");
            return false;
        }
        int total = goods.getAmount();
        while (total > 0) {
            int chunk = Math.min(64, total);
            total -= chunk;
            ItemStack piece = goods.clone();
            piece.setAmount(chunk);
            plugin.mailbox().add(c.seller, piece, "FUTURES");
        }
        c.status = FuturesContract.CANCELLED;
        notify(c.buyer, "§c期货合约 #" + id + " 已被管理员撤销，货款已退还银行账户");
        notify(c.seller, "§c期货合约 #" + id + " 已被管理员撤销，商品已退入邮箱");
        plugin.storage().requestSave();
        return true;
    }

    // ---------- 内部 ----------

    private long taxOf(long price) {
        double pct = plugin.taxPercent();
        if (pct <= 0) {
            return 0;
        }
        return (long) Math.floor(price * pct / 100.0);
    }

    private void notify(String uuid, String msg) {
        if (uuid == null) {
            return;
        }
        Player p = plugin.getServer().getPlayer(UUID.fromString(uuid));
        if (p != null && p.isOnline()) {
            Scheduler.onPlayer(plugin, p, sp -> sp.sendMessage(msg), null);
        }
    }

    // ---------- 多空头寸（保证金交易） ----------

    /** 品种结算价锚：期货近期成交均价 → 管理员参考价 → null（无锚禁止开仓）。 */
    public Long anchorOf(String key) {
        for (com.cycletrading.core.futures.Commodity c : plugin.futuresCommodities()) {
            if (c.key().equals(key)) {
                Double avg = plugin.priceHistory().average(c.material());
                if (avg != null) {
                    return Math.max(1, Math.round(avg));
                }
                long ref = plugin.optionReference(key);
                return ref > 0 ? ref : null;
            }
        }
        return null;
    }

    /** 开仓预检（多/空）。 */
    public PosOpenResult validateOpenPos(Player p, String type, String key, long qty, int termDays) {
        if (!plugin.futuresEnabled()) {
            return PosOpenResult.DISABLED;
        }
        if (!type.equalsIgnoreCase(FuturesPosition.LONG) && !type.equalsIgnoreCase(FuturesPosition.SHORT)) {
            return PosOpenResult.INVALID_TYPE;
        }
        Long entry = anchorOf(key);
        if (entry == null) {
            return PosOpenResult.NO_ANCHOR;
        }
        if (qty <= 0) {
            return PosOpenResult.INVALID_QTY;
        }
        if (!plugin.futuresTerms().contains(termDays)) {
            return PosOpenResult.INVALID_TERM;
        }
        String uuid = p.getUniqueId().toString();
        if (bank.isFrozen(uuid)) {
            return PosOpenResult.FROZEN;
        }
        if (bank.balance(uuid) < entry * qty) {
            return PosOpenResult.INSUFFICIENT_FUNDS;
        }
        return PosOpenResult.SUCCESS;
    }

    /** 开仓：100% 保证金（入场价×数量）→ 清算所账户。返回头寸；并发扣款失败返回 null。 */
    public FuturesPosition openPos(Player p, String type, String key, long qty, int termDays) {
        Long entry = anchorOf(key);
        long margin = entry * qty;
        String uuid = p.getUniqueId().toString();
        if (!bank.debit(uuid, margin, TxEntry.FUT_POS_OPEN)) {
            return null;
        }
        bank.credit(Bank.CLEARING, "CLEARING", margin, TxEntry.FUT_POS_MARGIN);
        long now = worldTime();
        long id = nextPosId.getAndIncrement();
        FuturesPosition pos = new FuturesPosition(id, uuid, p.getName(), type.toUpperCase(), key,
                entry, qty, margin, termDays, System.currentTimeMillis(), now, now + termDays * DAY_TICKS);
        positions.put(id, pos);
        posQueue.add(pos);
        plugin.storage().requestSave();
        return pos;
    }

    /** 提前平仓（按当前锚即时结算）。 */
    public PosCloseResult closePos(Player p, long id) {
        String uuid = p.getUniqueId().toString();
        FuturesPosition pos = positions.get(id);
        if (pos == null) {
            return PosCloseResult.NOT_FOUND;
        }
        if (!pos.isOpen()) {
            return PosCloseResult.NOT_ACTIVE;
        }
        if (!pos.owner.equals(uuid)) {
            return PosCloseResult.NOT_OWNER;
        }
        posQueue.remove(pos);
        settlePosition(pos);
        return PosCloseResult.SUCCESS;
    }

    private void checkPositions() {
        long now = worldTime();
        while (!posQueue.isEmpty() && posQueue.peek().matureAt <= now) {
            FuturesPosition pos = posQueue.poll();
            FuturesPosition cur = positions.get(pos.id);
            if (cur == null || !cur.isOpen()) {
                continue;
            }
            settlePosition(cur);
        }
    }

    /** 现金结算：盈亏 clamp 到 ±保证金，保证金退还 + 盈利从清算所支付。 */
    private void settlePosition(FuturesPosition pos) {
        Long anchor = anchorOf(pos.commodity);
        long s = anchor == null ? pos.entry : anchor; // 无锚兜底按入场价（盈亏 0）
        long raw = pos.isLong() ? (s - pos.entry) * pos.qty : (pos.entry - s) * pos.qty;
        long pnl = Math.max(-pos.margin, Math.min(pos.margin, raw));
        long payout = pos.margin + Math.max(0, pnl);
        pos.settlementPrice = s;
        pos.pnl = pnl;
        pos.payout = payout;
        pos.status = FuturesPosition.SETTLED;
        pos.settledAt = System.currentTimeMillis();
        if (payout > 0) {
            bank.debit(Bank.CLEARING, payout, TxEntry.FUT_POS_SETTLE);
            bank.credit(pos.owner, pos.name, payout, TxEntry.FUT_POS_RETURN);
        }
        notify(pos.owner, "§a期货" + (pos.isLong() ? "多单" : "空单") + " #" + pos.id + " 已结算：结算价 §e" + s
                + " §a· 盈亏 §e" + (pnl >= 0 ? "+" : "") + pnl + " §a· 实付 §e" + payout + " 绿宝石§a已入银行");
        plugin.storage().requestSave();
    }

    public List<FuturesPosition> positionsOf(String uuid) {
        return positions.values().stream()
                .filter(p -> p.owner.equals(uuid))
                .sorted(Comparator.comparingLong((FuturesPosition p) -> p.id).reversed())
                .toList();
    }

    public int posCountByStatus(String status) {
        return (int) positions.values().stream().filter(p -> p.status.equals(status)).count();
    }

    /** 未平仓保证金合计（清算敞口）。 */
    public long openExposure() {
        long sum = 0;
        for (FuturesPosition p : positions.values()) {
            if (p.isOpen()) {
                sum += p.margin;
            }
        }
        return sum;
    }

    /** 头寸剩余游戏日（向上取整）。 */
    public long posDaysLeft(FuturesPosition p) {
        long left = p.matureAt - worldTime();
        if (left <= 0) {
            return 0;
        }
        return (left + DAY_TICKS - 1) / DAY_TICKS;
    }

    public List<FuturesPosition> positionsSnapshot() {
        return new ArrayList<>(positions.values());
    }

    public void restorePosition(FuturesPosition p) {
        if (p != null) {
            positions.put(p.id, p);
            if (p.isOpen()) {
                posQueue.add(p);
            }
        }
    }

    public void rebuildPosId() {
        long max = positions.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
        nextPosId.set(max + 1);
    }

    // ---------- 存档 ----------

    public List<FuturesContract> snapshot() {
        return new ArrayList<>(contracts.values());
    }

    public void restore(FuturesContract c) {
        if (c != null) {
            contracts.put(c.id, c);
            if (c.isLocked()) {
                settlement.add(c);
            }
        }
    }

    public void rebuildNextId() {
        long max = contracts.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
        nextId.set(max + 1);
    }

    /** 剩余游戏日（向上取整）。 */
    public long daysLeft(FuturesContract c) {
        long left = c.matureAt - worldTime();
        if (left <= 0) {
            return 0;
        }
        return (left + DAY_TICKS - 1) / DAY_TICKS;
    }
}
