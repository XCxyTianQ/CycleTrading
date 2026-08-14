package com.cycletrading.core;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.bank.TxEntry;
import com.cycletrading.sched.Scheduler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 绿宝石挂单市场核心。
 *
 * 一致性设计：
 * - 挂单状态迁移 ACTIVE → SOLD / CANCELLED 通过 ConcurrentHashMap.computeIfPresent 原子完成，
 *   多线程并发购买同一条挂单时只有一个赢家；
 * - 全部物品/通货操作只在操作者自己的 entity 线程上执行（Folia 纪律）；
 * - 卖家收益直接入账银行（虚拟绿宝石）；
 * - 交付空间前置检查：背包放得下或邮箱未满才允许成交（邮箱只收不存、储量上限 27）；
 * - 每次变更后触发异步落盘。
 */
public final class Market {

    public enum BuyResult { SUCCESS, NOT_FOUND, NOT_ACTIVE, SELF_PURCHASE, INSUFFICIENT_FUNDS, FROZEN, NO_SPACE, ERROR }

    public enum CancelResult { SUCCESS, NOT_FOUND, NOT_ACTIVE, NOT_OWNER, NO_SPACE, ERROR }

    private final CycleTradingPlugin plugin;
    private final ConcurrentHashMap<Long, Listing> listings = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    private Bank bank;

    public Market(CycleTradingPlugin plugin) {
        this.plugin = plugin;
    }

    public void attachBank(Bank bank) {
        this.bank = bank;
    }

    // ---------- 查询（供 GUI / 存档） ----------

    public List<Listing> activeNewestFirst() {
        return listings.values().stream()
                .filter(Listing::isActive)
                .sorted(Comparator.comparingLong((Listing l) -> l.id).reversed())
                .toList();
    }

    public List<Listing> listingsOf(String uuid) {
        return listings.values().stream()
                .filter(l -> l.isActive() && l.seller.equals(uuid))
                .sorted(Comparator.comparingLong((Listing l) -> l.id).reversed())
                .toList();
    }

    public List<Listing> listingsSnapshot() {
        return new ArrayList<>(listings.values());
    }

    // ---------- 挂单 ----------

    /** 创建挂单（调用方已在玩家线程移除手持物品）。 */
    public Listing create(String sellerUuid, String sellerName, ItemStack item, long price) {
        long id = nextId.getAndIncrement();
        Listing l = new Listing(id, sellerUuid, sellerName, Items.toBase64(item), price, System.currentTimeMillis());
        listings.put(id, l);
        plugin.storage().requestSave();
        return l;
    }

    /**
     * 购买。必须在买家 entity 线程调用。
     * 顺序：反序列化预检 → 交付空间预检 → 余额预检 → 原子占用(ACTIVE→SOLD)
     *      → 支付（虚拟优先，实物兜底）→ 交付买家（溢出进邮箱）→ 卖家收益入账银行。
     * 任何失败路径回滚占用并退款（虚拟/实物同源退还），保证买卖双方无损。
     */
    public BuyResult tryBuy(Player buyer, long id) {
        String buyerUuid = buyer.getUniqueId().toString();
        Listing l = listings.get(id);
        if (l == null) {
            return BuyResult.NOT_FOUND;
        }
        if (buyerUuid.equals(l.seller)) {
            return BuyResult.SELF_PURCHASE;
        }
        if (bank.isFrozen(buyerUuid)) {
            return BuyResult.FROZEN;
        }
        long price = l.price;

        // 托管物品反序列化预检（失败即取消异常挂单，避免成交后无法交付）
        ItemStack item;
        try {
            item = Items.fromBase64(l.item);
        } catch (RuntimeException ex) {
            listings.computeIfPresent(id, (k, cur) -> {
                if (cur.isActive()) {
                    cur.status = Listing.CANCELLED;
                }
                return cur;
            });
            plugin.getLogger().severe("Listing #" + id + " item deserialization failed, listing cancelled: " + ex.getMessage());
            return BuyResult.ERROR;
        }

        // 交付空间预检：背包放得下 或 邮箱未满（邮箱只收不存，上限 27）
        boolean fit = Items.canFit(buyer.getInventory(), item) || plugin.mailbox().hasRoom(buyerUuid);
        if (!fit) {
            return BuyResult.NO_SPACE;
        }

        // 支付方式：虚拟余额优先（突破 64 堆叠），不足时实物兜底（价格超出 int 上限只能虚拟）
        boolean payVirtual = bank.balance(buyerUuid) >= price;
        boolean physicalOk = price <= Integer.MAX_VALUE
                && buyer.getInventory().containsAtLeast(Items.emeralds(1), (int) price);
        if (!payVirtual && !physicalOk) {
            return BuyResult.INSUFFICIENT_FUNDS;
        }

        // 原子占用：并发下仅一个买家能拿到 ACTIVE → SOLD 迁移
        AtomicReference<Boolean> reserved = new AtomicReference<>(false);
        listings.computeIfPresent(id, (k, cur) -> {
            if (cur.isActive()) {
                cur.status = Listing.SOLD;
                cur.buyer = buyerUuid;
                reserved.set(true);
            }
            return cur;
        });
        if (!reserved.get()) {
            return BuyResult.NOT_ACTIVE;
        }

        // 支付（同线程执行；虚拟扣款并发失败时自动实物兜底，仍失败则回滚）
        boolean paidVirtual = false;
        if (payVirtual) {
            if (bank.tryDebit(buyerUuid, price)) {
                paidVirtual = true;
            } else {
                payVirtual = false;
            }
        }
        if (!paidVirtual) {
            Map<Integer, ItemStack> left = buyer.getInventory().removeItem(Items.emeralds((int) price));
            if (!left.isEmpty()) {
                l.status = Listing.ACTIVE;
                l.buyer = null;
                return BuyResult.INSUFFICIENT_FUNDS;
            }
        }

        // 交付买家：背包放不下进邮箱（预检保证成功；失败则回滚 + 同源退款）
        Map<Integer, ItemStack> overflow = buyer.getInventory().addItem(item);
        for (ItemStack it : overflow.values()) {
            if (!plugin.mailbox().add(buyerUuid, it, "MARKET")) {
                l.status = Listing.ACTIVE;
                l.buyer = null;
                if (paidVirtual) {
                    bank.credit(buyerUuid, buyer.getName(), price, TxEntry.REFUND);
                } else {
                    buyer.getInventory().addItem(Items.emeralds((int) price));
                }
                plugin.getLogger().severe("Listing #" + id + " delivery failed (mailbox full), buyer refunded");
                return BuyResult.ERROR;
            }
        }

        // 结算卖家：税后收益直接入账银行（虚拟绿宝石，与实物同效力）
        long earnings = price - taxOf(price);
        String sellerUuid = l.seller;
        Player seller = plugin.getServer().getPlayer(UUID.fromString(sellerUuid));
        if (earnings > 0) {
            bank.credit(sellerUuid, l.sellerName, earnings, TxEntry.SELL);
        }
        if (seller != null && seller.isOnline()) {
            String msg = "§a你的挂单 #" + id + " 已售出，收益 §e" + earnings + " 绿宝石§a已存入银行"
                    + (taxOf(price) > 0 ? "§7（税后）" : "") + "，/ct bank 查看";
            Scheduler.onPlayer(plugin, seller, sp -> sp.sendMessage(msg), null);
        }

        plugin.storage().requestSave();
        return BuyResult.SUCCESS;
    }

    /** 下架（卖家）。物品归还背包，放不下进邮箱（背包与邮箱均无空间则拒绝下架）。 */
    public CancelResult cancel(Player seller, long id) {
        String sellerUuid = seller.getUniqueId().toString();
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        listings.computeIfPresent(id, (k, cur) -> {
            if (cur.isActive() && cur.seller.equals(sellerUuid)) {
                cur.status = Listing.CANCELLED;
                ok.set(true);
            }
            return cur;
        });
        if (!ok.get()) {
            Listing cur = listings.get(id);
            if (cur == null) {
                return CancelResult.NOT_FOUND;
            }
            if (!cur.isActive()) {
                return CancelResult.NOT_ACTIVE;
            }
            return CancelResult.NOT_OWNER;
        }
        Listing l = listings.get(id);
        ItemStack item;
        try {
            item = Items.fromBase64(l.item);
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Listing #" + id + " item deserialization failed on cancel: " + ex.getMessage());
            return CancelResult.ERROR;
        }
        if (!Items.canFit(seller.getInventory(), item) && !plugin.mailbox().hasRoom(sellerUuid)) {
            l.status = Listing.ACTIVE; // 归还失败，恢复挂单
            return CancelResult.NO_SPACE;
        }
        Map<Integer, ItemStack> ov = seller.getInventory().addItem(item);
        for (ItemStack it : ov.values()) {
            if (!plugin.mailbox().add(sellerUuid, it, "MARKET")) {
                l.status = Listing.ACTIVE;
                return CancelResult.NO_SPACE;
            }
        }
        plugin.storage().requestSave();
        return CancelResult.SUCCESS;
    }

    // ---------- 内部 ----------

    private long taxOf(long price) {
        double pct = plugin.taxPercent();
        if (pct <= 0) {
            return 0;
        }
        return (long) Math.floor(price * pct / 100.0);
    }

    // ---------- 存档恢复 ----------

    public void restoreListing(Listing l) {
        if (l != null) {
            listings.put(l.id, l);
        }
    }

    public void rebuildNextId() {
        long max = listings.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
        nextId.set(max + 1);
    }
}
