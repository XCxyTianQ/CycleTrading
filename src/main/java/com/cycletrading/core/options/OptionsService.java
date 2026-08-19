package com.cycletrading.core.options;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.Items;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.bank.TxEntry;
import com.cycletrading.util.MaturityQueue;
import com.cycletrading.core.futures.Commodity;
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
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 欧式期权（标准期权原则的游戏化落地）：
 *
 * - 类型：看涨 CALL / 看跌 PUT；标的 = 期货 9 个标准大宗品种（整批合约价）；
 * - 欧式：到期日才结算；现金结算（不动实物）：
 *     CALL 赔付 = min(max(0, S-K), K)，PUT 赔付 = max(0, K-S)（S = 到期结算价，K = 行权价）；
 * - 权利金：卖方定价，买方成交时付给卖方（买方只有权利无义务）；
 * - 全额保证金：卖方开仓即托管 K（最大赔付上限），到期赔付后余额退还 → 零违约；
 * - 结算价（方案A）：期货近期成交均价 → 管理员参考价 → 无锚禁止挂卖；
 * - 全局线程每 20 秒轮询到期（纯数据操作，Folia 安全）。
 */
public final class OptionsService {

    public enum OpenResult { SUCCESS, FROZEN, INSUFFICIENT_FUNDS, INVALID_TYPE, INVALID_COMMODITY,
        NO_ANCHOR, INVALID_STRIKE, INVALID_PREMIUM, INVALID_TERM, DISABLED }

    public enum BuyResult { SUCCESS, NOT_FOUND, NOT_ACTIVE, SELF_PURCHASE, INSUFFICIENT_FUNDS, FROZEN, ERROR }

    public enum CancelResult { SUCCESS, NOT_FOUND, NOT_ACTIVE, NOT_OWNER }

    public static final long DAY_TICKS = 24000L;

    private final CycleTradingPlugin plugin;
    private final ConcurrentHashMap<Long, OptionContract> contracts = new ConcurrentHashMap<>();
    private final MaturityQueue<OptionContract> settlement = new MaturityQueue<>();
    private final AtomicLong nextId = new AtomicLong(1);

    private Bank bank;
    private PriceHistory prices;

    public OptionsService(CycleTradingPlugin plugin) {
        this.plugin = plugin;
    }

    public void attach(Bank bank, PriceHistory prices) {
        this.bank = bank;
        this.prices = prices;
    }

    /** 启动到期轮询（全局线程，每 20 秒）。 */
    public void start() {
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> checkSettlement(), 400L, 400L);
    }

    // ---------- 结算价（方案A） ----------

    /** 品种当前结算价锚：期货近期均价 → 管理员参考价 → null（无锚，禁止挂卖）。 */
    public Long settlementPrice(String commodityKey) {
        Commodity c = commodity(commodityKey);
        if (c == null) {
            return null;
        }
        Double avg = prices.average(c.material());
        if (avg != null) {
            return Math.max(1, Math.round(avg));
        }
        long ref = plugin.optionReference(commodityKey);
        return ref > 0 ? ref : null;
    }

    /** 结算价来源说明（供 info/help 展示）。 */
    public String settlementSource(String commodityKey) {
        Commodity c = commodity(commodityKey);
        if (c == null) {
            return "未知品种";
        }
        Double avg = prices.average(c.material());
        if (avg != null) {
            return "期货近期均价（" + prices.count(c.material()) + " 笔）";
        }
        long ref = plugin.optionReference(commodityKey);
        return ref > 0 ? "管理员参考价" : "无锚（禁止挂卖）";
    }

    private Commodity commodity(String key) {
        for (Commodity c : plugin.futuresCommodities()) {
            if (c.key().equals(key)) {
                return c;
            }
        }
        return null;
    }

    // ---------- 查询 ----------

    public List<OptionContract> openNewestFirst() {
        return contracts.values().stream()
                .filter(OptionContract::isOpen)
                .sorted(Comparator.comparingLong((OptionContract c) -> c.id).reversed())
                .toList();
    }

    public List<OptionContract> ofSeller(String uuid) {
        return contracts.values().stream()
                .filter(c -> c.seller.equals(uuid))
                .sorted(Comparator.comparingLong((OptionContract c) -> c.id).reversed())
                .toList();
    }

    public List<OptionContract> ofBuyer(String uuid) {
        return contracts.values().stream()
                .filter(c -> c.buyer != null && c.buyer.equals(uuid))
                .sorted(Comparator.comparingLong((OptionContract c) -> c.id).reversed())
                .toList();
    }

    public int countByStatus(String status) {
        return (int) contracts.values().stream().filter(c -> c.status.equals(status)).count();
    }

    /** 锁定中的卖方保证金（LOCKED 合约行权价合计，供经济公报）。 */
    public long lockedValue() {
        long sum = 0;
        for (OptionContract c : contracts.values()) {
            if (c.isLocked()) {
                sum += c.strike;
            }
        }
        return sum;
    }

    // ---------- 开仓（卖方，玩家线程） ----------

    /** 开仓：卖方托管行权价作为全额保证金。 */
    public OpenResult validateOpen(Player seller, String type, String commodityKey, long strike, long premium, int termDays) {
        if (!plugin.optionsEnabled()) {
            return OpenResult.DISABLED;
        }
        if (!type.equalsIgnoreCase(OptionContract.CALL) && !type.equalsIgnoreCase(OptionContract.PUT)) {
            return OpenResult.INVALID_TYPE;
        }
        if (commodity(commodityKey) == null) {
            return OpenResult.INVALID_COMMODITY;
        }
        if (settlementPrice(commodityKey) == null) {
            return OpenResult.NO_ANCHOR;
        }
        if (strike <= 0) {
            return OpenResult.INVALID_STRIKE;
        }
        if (premium <= 0) {
            return OpenResult.INVALID_PREMIUM;
        }
        if (!plugin.futuresTerms().contains(termDays)) {
            return OpenResult.INVALID_TERM;
        }
        String uuid = seller.getUniqueId().toString();
        if (bank.isFrozen(uuid)) {
            return OpenResult.FROZEN;
        }
        if (bank.balance(uuid) < strike) {
            return OpenResult.INSUFFICIENT_FUNDS;
        }
        return OpenResult.SUCCESS;
    }

    /** 执行开仓（预检通过后调用；玩家线程）。返回合约；并发扣款失败返回 null。 */
    public OptionContract open(Player seller, String type, String commodityKey, long strike, long premium, int termDays) {
        String uuid = seller.getUniqueId().toString();
        if (!bank.debit(uuid, strike, TxEntry.OPTION_OPEN)) {
            return null;
        }
        long id = nextId.getAndIncrement();
        OptionContract c = new OptionContract(id, uuid, seller.getName(),
                type.toUpperCase(), commodityKey, strike, premium, termDays, System.currentTimeMillis());
        contracts.put(id, c);
        plugin.storage().requestSave();
        return c;
    }

    // ---------- 成交（买方，玩家线程） ----------

    public BuyResult validateBuy(Player buyer, long id) {
        String buyerUuid = buyer.getUniqueId().toString();
        OptionContract c = contracts.get(id);
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
        long premium = c.premium;
        boolean payVirtual = bank.balance(buyerUuid) >= premium;
        boolean physicalOk = premium <= Integer.MAX_VALUE
                && buyer.getInventory().containsAtLeast(Items.emeralds(1), (int) premium);
        if (!payVirtual && !physicalOk) {
            return BuyResult.INSUFFICIENT_FUNDS;
        }
        return BuyResult.SUCCESS;
    }

    /** 成交（预检通过后调用；玩家线程）。返回合约；并发扣款/占用失败返回 null。 */
    public OptionContract buy(Player buyer, long id) {
        String buyerUuid = buyer.getUniqueId().toString();
        OptionContract c = contracts.get(id);
        long premium = c.premium;
        boolean paidVirtual = false;
        if (bank.balance(buyerUuid) >= premium) {
            if (bank.debit(buyerUuid, premium, TxEntry.OPTION_PREMIUM)) {
                paidVirtual = true;
            }
        }
        if (!paidVirtual) {
            Map<Integer, ItemStack> left = buyer.getInventory().removeItem(Items.emeralds((int) premium));
            if (!left.isEmpty()) {
                return null;
            }
        }
        AtomicReference<Boolean> reserved = new AtomicReference<>(false);
        contracts.computeIfPresent(id, (k, cur) -> {
            if (cur.isOpen()) {
                cur.status = OptionContract.LOCKED;
                cur.buyer = buyerUuid;
                cur.buyerName = buyer.getName();
                cur.lockedAt = worldTime();
                cur.matureAt = cur.lockedAt + cur.termDays * DAY_TICKS;
                reserved.set(true);
            }
            return cur;
        });
        if (!reserved.get()) {
            // 占用失败：回滚权利金
            if (paidVirtual) {
                bank.credit(buyerUuid, buyer.getName(), premium, TxEntry.REFUND);
            } else {
                buyer.getInventory().addItem(Items.emeralds((int) premium));
            }
            return null;
        }
        // 权利金即时归卖方
        bank.credit(c.seller, c.sellerName, premium, TxEntry.OPTION_PREMIUM_IN);
        settlement.add(c);
        Player seller = plugin.getServer().getPlayer(UUID.fromString(c.seller));
        if (seller != null && seller.isOnline()) {
            Scheduler.onPlayer(plugin, seller, sp -> sp.sendMessage("§a你的期权 #" + id
                    + " 已成交！权利金 §e" + premium + " 绿宝石§a已入账，到期后按结算价赔付"), null);
        }
        plugin.storage().requestSave();
        return c;
    }

    // ---------- 撤单（卖方，玩家线程；仅 OPEN） ----------

    public CancelResult cancel(Player seller, long id) {
        String uuid = seller.getUniqueId().toString();
        OptionContract c = contracts.get(id);
        if (c == null) {
            return CancelResult.NOT_FOUND;
        }
        if (!c.isOpen()) {
            return CancelResult.NOT_ACTIVE;
        }
        if (!c.seller.equals(uuid)) {
            return CancelResult.NOT_OWNER;
        }
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        contracts.computeIfPresent(id, (k, cur) -> {
            if (cur.isOpen() && cur.seller.equals(uuid)) {
                cur.status = OptionContract.WITHDRAWN;
                ok.set(true);
            }
            return cur;
        });
        if (!ok.get()) {
            return CancelResult.NOT_ACTIVE;
        }
        bank.credit(uuid, c.sellerName, c.strike, TxEntry.OPTION_MARGIN_RETURN);
        plugin.storage().requestSave();
        return CancelResult.SUCCESS;
    }

    // ---------- 到期结算（全局线程轮询） ----------

    private long worldTime() {
        org.bukkit.World w = plugin.getServer().getWorlds().stream()
                .filter(x -> x.getEnvironment() == org.bukkit.World.Environment.NORMAL)
                .findFirst().orElse(null);
        return w == null ? 0 : w.getFullTime();
    }

    private void checkSettlement() {
        long now = worldTime();
        while (!settlement.isEmpty() && settlement.peek().matureAt <= now) {
            OptionContract c = settlement.poll();
            OptionContract cur = contracts.get(c.id);
            if (cur == null || !cur.isLocked()) {
                continue;
            }
            settle(cur);
        }
    }

    /** 到期现金结算（纯数据，全局线程安全）。 */
    private void settle(OptionContract c) {
        Long anchor = settlementPrice(c.commodity);
        long s = anchor == null ? c.strike : anchor; // 无锚兜底：按行权价结算（赔付 0）
        long payout;
        if (c.isCall()) {
            payout = Math.min(Math.max(0, s - c.strike), c.strike); // 赔付封顶 = 行权价
        } else {
            payout = Math.max(0, c.strike - s);
        }
        if (payout > 0) {
            bank.credit(c.buyer, c.buyerName, payout, TxEntry.OPTION_PAYOUT);
        }
        long marginReturn = c.strike - payout;
        if (marginReturn > 0) {
            bank.credit(c.seller, c.sellerName, marginReturn, TxEntry.OPTION_MARGIN_RETURN);
        }
        c.settlementPrice = s;
        c.payout = payout;
        c.status = OptionContract.SETTLED;
        c.settledAt = System.currentTimeMillis();
        notify(c.buyer, "§a期权 #" + c.id + " 已到期结算：结算价 §e" + s + " §a· 赔付 §e" + payout + " 绿宝石§a已入银行");
        notify(c.seller, "§a期权 #" + c.id + " 已到期结算：结算价 §e" + s + " §a· 赔付 §e" + payout
                + " §a· 保证金余额 §e" + marginReturn + " 绿宝石§a已退还");
        plugin.storage().requestSave();
    }

    // ---------- 管理员 ----------

    /** 强制结算（LOCKED 合约立即按当前锚结算）。 */
    public boolean adminSettle(long id) {
        OptionContract c = contracts.get(id);
        if (c == null || !c.isLocked()) {
            return false;
        }
        settlement.remove(c);
        settle(c);
        return true;
    }

    /** 强制撤销：OPEN → 退保证金；LOCKED → 退保证金给卖方 + 退权利金给买方。 */
    public boolean adminCancel(long id) {
        OptionContract c = contracts.get(id);
        if (c == null || !(c.isOpen() || c.isLocked())) {
            return false;
        }
        if (c.isLocked()) {
            bank.credit(c.buyer, c.buyerName, c.premium, TxEntry.OPTION_REFUND);
        }
        bank.credit(c.seller, c.sellerName, c.strike, TxEntry.OPTION_MARGIN_RETURN);
        c.status = OptionContract.CANCELLED;
        notify(c.buyer, "§c期权 #" + id + " 已被管理员撤销，权利金已退还银行账户");
        notify(c.seller, "§c期权 #" + id + " 已被管理员撤销，保证金已退还银行账户");
        plugin.storage().requestSave();
        return true;
    }

    // ---------- 内部 ----------

    private void notify(String uuid, String msg) {
        if (uuid == null) {
            return;
        }
        Player p = plugin.getServer().getPlayer(UUID.fromString(uuid));
        if (p != null && p.isOnline()) {
            Scheduler.onPlayer(plugin, p, sp -> sp.sendMessage(msg), null);
        }
    }

    // ---------- 存档 ----------

    public List<OptionContract> snapshot() {
        return new ArrayList<>(contracts.values());
    }

    public void restore(OptionContract c) {
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
    public long daysLeft(OptionContract c) {
        long left = c.matureAt - worldTime();
        if (left <= 0) {
            return 0;
        }
        return (left + DAY_TICKS - 1) / DAY_TICKS;
    }
}
