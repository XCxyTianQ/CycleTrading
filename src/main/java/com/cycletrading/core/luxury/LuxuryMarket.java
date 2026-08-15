package com.cycletrading.core.luxury;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.Items;
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
 * 奢侈品商店：仅管理员挂售珍稀物品。
 *
 * 动态定价（保证稀缺性）：
 *   成交价 = max(基础定价, round(基础定价 × 倍率))
 *   倍率   = 1 + 全服玩家银行总存量 ÷ 定价锚点，受 max-multiplier 上限约束
 *
 * 结算：买方货款（税后）直接入【挂售管理员】银行账户（交易基本语义：卖方必须收款）。
 * 买家支付与市场一致：虚拟余额优先，实物兜底。
 */
public final class LuxuryMarket {

    public enum BuyResult { SUCCESS, NOT_FOUND, NOT_ACTIVE, INSUFFICIENT_FUNDS, FROZEN, NO_SPACE, ERROR }

    public enum RemoveResult { SUCCESS, NOT_FOUND, NOT_ACTIVE, NO_SPACE, ERROR }

    private final CycleTradingPlugin plugin;
    private final ConcurrentHashMap<Long, LuxuryListing> listings = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    private Bank bank;

    public LuxuryMarket(CycleTradingPlugin plugin) {
        this.plugin = plugin;
    }

    public void attach(Bank bank) {
        this.bank = bank;
    }

    // ---------- 定价 ----------

    /** 当前动态倍率 = 1 + 总存量 ÷ 锚点（上限约束）。 */
    public double multiplier() {
        long m = bank.playerSupply();
        double raw = 1.0 + (double) m / plugin.luxurySupplyAnchor();
        return Math.min(raw, plugin.luxuryMaxMultiplier());
    }

    /** 某基础定价的当前成交价（保底 = 基础价）。 */
    public long effectivePrice(long base) {
        return Math.max(base, Math.round(base * multiplier()));
    }

    // ---------- 查询 ----------

    public List<LuxuryListing> activeNewestFirst() {
        return listings.values().stream()
                .filter(LuxuryListing::isActive)
                .sorted(Comparator.comparingLong((LuxuryListing l) -> l.id).reversed())
                .toList();
    }

    // ---------- 挂售 / 下架（仅管理员） ----------

    /** 创建挂单（调用方已在玩家线程移除手持物品，权限已校验）。 */
    public LuxuryListing create(String listedBy, String listedByUuid, ItemStack item, long basePrice) {
        long id = nextId.getAndIncrement();
        LuxuryListing l = new LuxuryListing(id, Items.toBase64(item), basePrice, listedBy, listedByUuid, System.currentTimeMillis());
        listings.put(id, l);
        plugin.storage().requestSave();
        return l;
    }

    /** 下架：物品归还背包（放不下进邮箱）。 */
    public RemoveResult remove(Player admin, long id) {
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        listings.computeIfPresent(id, (k, cur) -> {
            if (cur.isActive()) {
                cur.status = LuxuryListing.CANCELLED;
                ok.set(true);
            }
            return cur;
        });
        if (!ok.get()) {
            LuxuryListing cur = listings.get(id);
            return cur == null ? RemoveResult.NOT_FOUND : RemoveResult.NOT_ACTIVE;
        }
        LuxuryListing l = listings.get(id);
        ItemStack item;
        try {
            item = Items.fromBase64(l.item);
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Luxury listing #" + id + " item deserialization failed: " + ex.getMessage());
            return RemoveResult.ERROR;
        }
        if (!Items.canFit(admin.getInventory(), item) && !plugin.mailbox().hasRoom(admin.getUniqueId().toString())) {
            l.status = LuxuryListing.ACTIVE; // 归还失败，恢复挂单
            return RemoveResult.NO_SPACE;
        }
        Map<Integer, ItemStack> ov = admin.getInventory().addItem(item);
        for (ItemStack it : ov.values()) {
            if (!plugin.mailbox().add(admin.getUniqueId().toString(), it, "LUXURY")) {
                l.status = LuxuryListing.ACTIVE;
                return RemoveResult.NO_SPACE;
            }
        }
        plugin.storage().requestSave();
        return RemoveResult.SUCCESS;
    }

    /**
     * 购买。必须在买家 entity 线程调用。
     * 顺序：倍率定价 → 余额预检 → 原子占用 → 支付（虚拟优先/实物兜底）→ 反序列化 → 交付 → 成交款入国库。
     * 失败路径回滚占用并同源退款。
     */
    public BuyResult buy(Player buyer, long id) {
        String buyerUuid = buyer.getUniqueId().toString();
        LuxuryListing l = listings.get(id);
        if (l == null) {
            return BuyResult.NOT_FOUND;
        }
        if (bank.isFrozen(buyerUuid)) {
            return BuyResult.FROZEN;
        }
        long price = effectivePrice(l.basePrice);

        // 托管物品反序列化预检（失败即取消异常挂单）
        ItemStack item;
        try {
            item = Items.fromBase64(l.item);
        } catch (RuntimeException ex) {
            listings.computeIfPresent(id, (k, cur) -> {
                if (cur.isActive()) {
                    cur.status = LuxuryListing.CANCELLED;
                }
                return cur;
            });
            plugin.getLogger().severe("Luxury listing #" + id + " item deserialization failed, listing cancelled: " + ex.getMessage());
            return BuyResult.ERROR;
        }

        // 交付空间预检：背包放得下 或 邮箱未满（邮箱只收不存，上限 27）
        if (!Items.canFit(buyer.getInventory(), item) && !plugin.mailbox().hasRoom(buyerUuid)) {
            return BuyResult.NO_SPACE;
        }

        boolean payVirtual = bank.balance(buyerUuid) >= price;
        boolean physicalOk = price <= Integer.MAX_VALUE
                && buyer.getInventory().containsAtLeast(Items.emeralds(1), (int) price);
        if (!payVirtual && !physicalOk) {
            return BuyResult.INSUFFICIENT_FUNDS;
        }

        // 原子占用
        AtomicReference<Boolean> reserved = new AtomicReference<>(false);
        listings.computeIfPresent(id, (k, cur) -> {
            if (cur.isActive()) {
                cur.status = LuxuryListing.SOLD;
                cur.buyer = buyerUuid;
                cur.soldAt = System.currentTimeMillis();
                cur.soldPrice = price;
                reserved.set(true);
            }
            return cur;
        });
        if (!reserved.get()) {
            return BuyResult.NOT_ACTIVE;
        }

        // 支付（奢侈品专属流水类型 LUX_BUY，虚拟/实物均留痕）
        boolean paidVirtual = false;
        if (payVirtual) {
            if (bank.debit(buyerUuid, price, TxEntry.LUX_BUY)) {
                paidVirtual = true;
            } else {
                payVirtual = false;
            }
        }
        if (!paidVirtual) {
            Map<Integer, ItemStack> left = buyer.getInventory().removeItem(Items.emeralds((int) price));
            if (!left.isEmpty()) {
                l.status = LuxuryListing.ACTIVE;
                l.buyer = null;
                return BuyResult.INSUFFICIENT_FUNDS;
            }
            bank.recordTrace(buyerUuid, buyer.getName(), TxEntry.LUX_BUY, price);
        }

        // 交付买家（预检保证成功；失败则回滚 + 同源退款）
        Map<Integer, ItemStack> overflow = buyer.getInventory().addItem(item);
        for (ItemStack it : overflow.values()) {
            if (!plugin.mailbox().add(buyerUuid, it, "LUXURY")) {
                l.status = LuxuryListing.ACTIVE;
                l.buyer = null;
                if (paidVirtual) {
                    bank.credit(buyerUuid, buyer.getName(), price, TxEntry.REFUND);
                } else {
                    buyer.getInventory().addItem(Items.emeralds((int) price));
                }
                plugin.getLogger().severe("Luxury listing #" + id + " delivery failed (mailbox full), buyer refunded");
                return BuyResult.ERROR;
            }
        }

        // 结算卖方：税后货款直接入【挂售管理员】银行账户（交易基本语义：卖方必须收款）
        long earnings = price - taxOf(price);
        String listerUuid = l.listedByUuid;
        if (earnings > 0 && listerUuid != null) {
            bank.credit(listerUuid, l.listedBy, earnings, TxEntry.LUX_SELL);
        }
        if (listerUuid != null) {
            Player lister = plugin.getServer().getPlayer(UUID.fromString(listerUuid));
            if (lister != null && lister.isOnline()) {
                Scheduler.onPlayer(plugin, lister, sp -> sp.sendMessage("§a你的奢侈品 #" + id
                        + " 已售出，货款 §e" + earnings + " 绿宝石§a已入银行"
                        + (taxOf(price) > 0 ? "§7（税后）" : "")), null);
            }
        }

        plugin.storage().requestSave();
        return BuyResult.SUCCESS;
    }

    private long taxOf(long price) {
        double pct = plugin.taxPercent();
        if (pct <= 0) {
            return 0;
        }
        return (long) Math.floor(price * pct / 100.0);
    }

    // ---------- 存档 ----------

    public List<LuxuryListing> snapshot() {
        return new ArrayList<>(listings.values());
    }

    public void restore(LuxuryListing l) {
        if (l != null) {
            // 旧版数据迁移：无 listedByUuid 时按名字解析（仅影响升级前未售出的挂单）
            if (l.listedByUuid == null && l.listedBy != null) {
                l.listedByUuid = plugin.getServer().getOfflinePlayer(l.listedBy).getUniqueId().toString();
            }
            listings.put(l.id, l);
        }
    }

    public void rebuildNextId() {
        long max = listings.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
        nextId.set(max + 1);
    }
}
