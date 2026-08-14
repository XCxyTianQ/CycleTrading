package com.cycletrading.core.bond;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.bank.TxEntry;
import com.cycletrading.sched.Scheduler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 定期债券服务（经济回收池）。
 *
 * - 五档定期，期限按游戏日（24000 tick/天）计；
 * - 实际利率 = 基础利率 × (1 + 全服玩家银行总存量 ÷ 利率锚点)，封顶 max-multiplier，
 *   **购买瞬间锁定**（本金/利率/到期时间记录在债券上，持有期内不变）；
 * - 退一法：利率取整到基点（floor），利息 = 本金 × 基点 ÷ 10000（长整型，天然向下取整）；
 * - 仅虚拟交易：本金从银行余额扣走并锁死（不可交易/转账/提取），从玩家总存量中移除；
 * - 到期由全局线程每 20 秒轮询结算，本息自动入账银行（BOND_REDEEM 流水）。
 */
public final class BondService {

    public enum BuyResult { SUCCESS, FROZEN, INSUFFICIENT_FUNDS, INVALID_TIER, INVALID_AMOUNT, BELOW_MINIMUM, DISABLED }

    public static final long DAY_TICKS = 24000L;
    public static final int TIERS = 5;

    private final CycleTradingPlugin plugin;
    private final ConcurrentHashMap<Long, Bond> bonds = new ConcurrentHashMap<>();
    private final PriorityQueue<Bond> maturity = new PriorityQueue<>(Comparator.comparingLong(b -> b.matureAt));
    private final AtomicLong nextId = new AtomicLong(1);

    private Bank bank;

    public BondService(CycleTradingPlugin plugin) {
        this.plugin = plugin;
    }

    public void attachBank(Bank bank) {
        this.bank = bank;
    }

    /** 启动到期轮询（全局线程，每 20 秒 = 400 tick）。 */
    public void start() {
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> checkMaturity(), 400L, 400L);
    }

    // ---------- 利率 ----------

    /** 当前利率倍率 = 1 + 总存量 ÷ 锚点（上限约束）。 */
    public double rateMultiplier() {
        double raw = 1.0 + (double) bank.playerSupply() / plugin.bondRateAnchor();
        return Math.min(raw, plugin.bondMaxMultiplier());
    }

    /** 某档位当前实际利率（基点，退一法取整）。 */
    public int currentRateBp(int tier) {
        return (int) Math.floor(plugin.bondBaseRate(tier) * rateMultiplier() * 100.0);
    }

    /** 利息 = 本金 × 基点 ÷ 10000（退一法，长整型）。 */
    public static long interestOf(long principal, int rateBp) {
        return principal * rateBp / 10000L;
    }

    // ---------- 查询 ----------

    public List<Bond> activeBondsOf(String uuid) {
        return bonds.values().stream()
                .filter(b -> b.isActive() && b.owner.equals(uuid))
                .sorted(Comparator.comparingLong((Bond b) -> b.matureAt))
                .toList();
    }

    public List<Bond> redeemedOf(String uuid) {
        return bonds.values().stream()
                .filter(b -> !b.isActive() && b.owner.equals(uuid))
                .sorted(Comparator.comparingLong((Bond b) -> b.redeemedAt).reversed())
                .toList();
    }

    public int activeCount() {
        return (int) bonds.values().stream().filter(Bond::isActive).count();
    }

    public long totalLocked() {
        long sum = 0;
        for (Bond b : bonds.values()) {
            if (b.isActive()) {
                sum += b.principal;
            }
        }
        return sum;
    }

    // ---------- 购买 ----------

    /** 购买预检（无副作用；玩家线程调用）。 */
    public BuyResult validate(Player p, int tier, long amount) {
        if (!plugin.bondEnabled()) {
            return BuyResult.DISABLED;
        }
        if (tier < 1 || tier > TIERS) {
            return BuyResult.INVALID_TIER;
        }
        if (amount <= 0) {
            return BuyResult.INVALID_AMOUNT;
        }
        if (amount < plugin.bondMin(tier)) {
            return BuyResult.BELOW_MINIMUM;
        }
        String uuid = p.getUniqueId().toString();
        if (bank.isFrozen(uuid)) {
            return BuyResult.FROZEN;
        }
        if (bank.balance(uuid) < amount) {
            return BuyResult.INSUFFICIENT_FUNDS;
        }
        return BuyResult.SUCCESS;
    }

    /**
     * 执行购买（预检通过后调用；玩家线程）。仅虚拟余额：本金扣出并锁死。
     * 返回创建的债券（含锁定利率）；并发余额变化导致扣款失败时返回 null。
     */
    public Bond create(Player p, int tier, long amount) {
        String uuid = p.getUniqueId().toString();
        long now = worldTime();
        int rateBp = currentRateBp(tier);
        if (!bank.debit(uuid, amount, TxEntry.BOND_BUY)) {
            return null;
        }
        Bond b = new Bond(nextId.getAndIncrement(), uuid, p.getName(), tier, amount, rateBp,
                System.currentTimeMillis(), now, now + plugin.bondDays(tier) * DAY_TICKS);
        bonds.put(b.id, b);
        maturity.add(b);
        plugin.storage().requestSave();
        return b;
    }

    // ---------- 到期结算 ----------

    private long worldTime() {
        World w = plugin.getServer().getWorlds().stream()
                .filter(x -> x.getEnvironment() == World.Environment.NORMAL)
                .findFirst().orElse(null);
        return w == null ? 0 : w.getFullTime();
    }

    /** 全局线程轮询：结算所有到期债券。 */
    private void checkMaturity() {
        long now = worldTime();
        while (!maturity.isEmpty() && maturity.peek().matureAt <= now) {
            Bond b = maturity.poll();
            Bond cur = bonds.get(b.id);
            if (cur == null || !cur.isActive()) {
                continue;
            }
            settle(cur);
        }
    }

    private void settle(Bond b) {
        long interest = interestOf(b.principal, b.rateBp);
        b.interest = interest;
        b.status = Bond.REDEEMED;
        b.redeemedAt = System.currentTimeMillis();
        bank.credit(b.owner, b.name, b.principal + interest, TxEntry.BOND_REDEEM);
        Player p = plugin.getServer().getPlayer(UUID.fromString(b.owner));
        if (p != null && p.isOnline()) {
            String msg = "§a定期债券到期！本金 §e" + b.principal + " §a+ 利息 §e" + interest
                    + " 绿宝石§a已入账银行（锁定利率 " + fmtRate(b.rateBp) + "）";
            Scheduler.onPlayer(plugin, p, sp -> sp.sendMessage(msg), null);
        }
        plugin.storage().requestSave();
    }

    // ---------- 存档 ----------

    public List<Bond> snapshot() {
        return new ArrayList<>(bonds.values());
    }

    public void restore(Bond b) {
        if (b != null) {
            bonds.put(b.id, b);
            if (b.isActive()) {
                maturity.add(b);
            }
        }
    }

    public void rebuildNextId() {
        long max = bonds.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
        nextId.set(max + 1);
    }

    // ---------- 展示 ----------

    /** 剩余游戏日（向上取整，0 = 即将结算）。 */
    public long daysLeft(Bond b) {
        long left = b.matureAt - worldTime();
        if (left <= 0) {
            return 0;
        }
        return (left + DAY_TICKS - 1) / DAY_TICKS;
    }

    public static String fmtRate(int rateBp) {
        return String.format("%.2f", rateBp / 100.0) + "%";
    }
}
