package com.cycletrading.core.insurance;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.Items;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.bank.TxEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 定档死亡保险服务。
 *
 * 保单单次有效：死亡时按档位将受保物品摘离掉落表（托管持久化），
 * 重生后按原槽位还原（占用则入背包/邮箱）；档位 4 额外补偿 10 绿宝石入虚拟账户。
 * 经济闭环：保费 → 系统国库（货币回收）；档位 4 补偿 ← 国库出账。
 */
public final class InsuranceService {

    /** 档位覆盖范围：1=快捷栏(0-8) 2=+第一排(0-17) 3=全部物品栏+快捷栏(0-35) 4=完全(0-40) */
    public static final int[] TIER_LIMIT = {0, 9, 18, 36, 41};

    public enum BuyResult { SUCCESS, FROZEN, INSUFFICIENT_FUNDS, INVALID_TIER, DISABLED }

    private final CycleTradingPlugin plugin;
    private final ConcurrentHashMap<String, InsurancePolicy> policies = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<DeathStash> stashes = new CopyOnWriteArrayList<>();

    private Bank bank;

    public InsuranceService(CycleTradingPlugin plugin) {
        this.plugin = plugin;
    }

    public void attachBank(Bank bank) {
        this.bank = bank;
    }

    // ---------- 查询 ----------

    public InsurancePolicy policy(String uuid) {
        return policies.get(uuid);
    }

    /** 档位保费（绿宝石），非法档位返回 -1。 */
    public long premiumOf(int tier) {
        return switch (tier) {
            case 1 -> plugin.insT1Price();
            case 2 -> plugin.insT2Price();
            case 3 -> plugin.insT3Price();
            case 4 -> plugin.insT4Price();
            default -> -1;
        };
    }

    // ---------- 投保 ----------

    /** 投保（覆盖旧保单）。必须在玩家线程调用。保费：银行余额优先，实物兜底，入国库。 */
    public BuyResult buy(Player p, int tier) {
        if (!plugin.insuranceEnabled()) {
            return BuyResult.DISABLED;
        }
        long premium = premiumOf(tier);
        if (premium < 0) {
            return BuyResult.INVALID_TIER;
        }
        String uuid = p.getUniqueId().toString();
        if (bank.isFrozen(uuid)) {
            return BuyResult.FROZEN;
        }
        boolean payVirtual = bank.balance(uuid) >= premium;
        boolean physicalOk = premium <= Integer.MAX_VALUE
                && p.getInventory().containsAtLeast(Items.emeralds(1), (int) premium);
        if (!payVirtual && !physicalOk) {
            return BuyResult.INSUFFICIENT_FUNDS;
        }
        boolean paidVirtual = false;
        if (payVirtual) {
            if (bank.debit(uuid, premium, TxEntry.INSURANCE_PAID)) {
                paidVirtual = true;
            } else {
                payVirtual = false;
            }
        }
        if (!paidVirtual) {
            Map<Integer, ItemStack> left = p.getInventory().removeItem(Items.emeralds((int) premium));
            if (!left.isEmpty()) {
                return BuyResult.INSUFFICIENT_FUNDS;
            }
        }
        // 保费入国库（货币回收）
        bank.credit(Bank.SYSTEM, "SYSTEM", premium, TxEntry.INSURANCE_PREMIUM);
        policies.put(uuid, new InsurancePolicy(uuid, p.getName(), tier, premium, System.currentTimeMillis()));
        plugin.storage().requestSave();
        return BuyResult.SUCCESS;
    }

    // ---------- 死亡 / 重生（由 InsuranceListener 调用） ----------

    /** 死亡时消费保单。返回被消耗的保单；无保单返回 null。 */
    public InsurancePolicy consume(String uuid) {
        InsurancePolicy pol = policies.remove(uuid);
        if (pol != null) {
            plugin.storage().requestSave();
        }
        return pol;
    }

    /** 档位覆盖的掉落槽位数。 */
    public static int tierLimit(int tier) {
        return tier >= 1 && tier <= 4 ? TIER_LIMIT[tier] : 0;
    }

    /** 记录死亡托管（持久化，防服务器崩溃丢物）。 */
    public void addStash(DeathStash stash) {
        if (stash != null) {
            stashes.add(stash);
            plugin.storage().requestSave();
        }
    }

    /** 重生时取出并清除该玩家的托管。 */
    public DeathStash takeStash(String uuid) {
        for (DeathStash s : stashes) {
            if (uuid.equals(s.owner)) {
                stashes.remove(s);
                plugin.storage().requestSave();
                return s;
            }
        }
        return null;
    }

    /** 交付结果：restored=本次成功还原件数，pending=仍暂存件数。 */
    public record DeliverResult(int restored, int pending, int tier) { }

    /** 该玩家当前暂存物品件数。 */
    public int pendingCount(String uuid) {
        for (DeathStash s : stashes) {
            if (uuid.equals(s.owner)) {
                return s.items.size();
            }
        }
        return 0;
    }

    /**
     * 尝试交付该玩家的死亡托管：原槽位 → 背包 → 邮箱（上限 27）。
     * 邮箱满时剩余物品留在托管中，玩家清理后 /ct collect 重试，绝不丢物。
     * 必须在玩家 entity 线程调用。
     */
    public DeliverResult deliverPending(Player p) {
        String uuid = p.getUniqueId().toString();
        DeathStash stash = null;
        for (DeathStash s : stashes) {
            if (uuid.equals(s.owner)) {
                stash = s;
                break;
            }
        }
        if (stash == null) {
            return new DeliverResult(0, 0, 0);
        }
        int before = stash.items.size();
        List<StashItem> left = new ArrayList<>();
        for (StashItem si : stash.items) {
            ItemStack it;
            try {
                it = Items.fromBase64(si.item);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Insurance stash item deserialization failed for " + p.getName());
                continue;
            }
            boolean placed = false;
            if (si.slot >= 0 && si.slot < 41) {
                ItemStack cur = p.getInventory().getItem(si.slot);
                if (cur == null || cur.getType().isAir()) {
                    p.getInventory().setItem(si.slot, it);
                    placed = true;
                }
            }
            if (!placed) {
                Map<Integer, ItemStack> ov = p.getInventory().addItem(it);
                for (ItemStack stack : ov.values()) {
                    if (!plugin.mailbox().add(uuid, stack, "INSURANCE")) {
                        left.add(new StashItem(si.slot, Items.toBase64(stack)));
                    }
                }
            }
        }
        int tier = stash.tier;
        if (left.isEmpty()) {
            stashes.remove(stash);
        } else {
            stash.items = left;
        }
        plugin.storage().requestSave();
        return new DeliverResult(before - left.size(), left.size(), tier);
    }

    /** 档位 4 死亡补偿：10 绿宝石入虚拟账户。 */
    public void compensate(String uuid, String name, int tier) {
        if (tier == 4 && plugin.insuranceEnabled()) {
            long comp = plugin.insT4Compensation();
            if (comp > 0) {
                bank.credit(uuid, name, comp, TxEntry.INSURANCE);
            }
        }
    }

    // ---------- 后台管理 ----------

    public InsurancePolicy adminView(String uuid) {
        return policies.get(uuid);
    }

    /** tier 0 = 清除保单。 */
    public void adminSet(String uuid, String name, int tier) {
        if (tier <= 0) {
            policies.remove(uuid);
        } else {
            policies.put(uuid, new InsurancePolicy(uuid, name, tier, premiumOf(tier), System.currentTimeMillis()));
        }
        plugin.storage().requestSave();
    }

    // ---------- 存档 ----------

    public List<InsurancePolicy> policiesSnapshot() {
        return new ArrayList<>(policies.values());
    }

    public List<DeathStash> stashesSnapshot() {
        return new ArrayList<>(stashes);
    }

    public void restorePolicy(InsurancePolicy p) {
        if (p != null) {
            policies.put(p.owner, p);
        }
    }

    public void restoreStash(DeathStash s) {
        if (s != null) {
            stashes.add(s);
        }
    }
}
