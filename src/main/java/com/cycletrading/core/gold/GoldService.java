package com.cycletrading.core.gold;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.bank.Bank;
import com.cycletrading.core.bank.TxEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;

/**
 * 投资金条（国库股）：恒定发行、即买即卖、价格与国库绿宝石挂钩。
 *
 * - 恒定发行 N 根（config gold.total）；首次启动国库一次性注资 gold.seed 作准备金（不占玩家存量）；
 * - 价格 = 国库余额 ÷ 发行量（整数地板）——纯公式定价，无操纵；
 * - 即买即卖（仅虚拟余额）：买入 → 绿宝石进国库（价格↑、玩家存量↓）；卖出 → 国库付款（价格↓、存量↑）；
 * - 偿付性天然成立：在外金条 ≤ N，卖出总额 ≤ 国库余额，国库不可能被挤兑破产；
 * - 全额准备金：国库余额 − 在外金条×现价 = 央行可自由支配资金（撒钱/补贴不得动用准备金）。
 */
public final class GoldService {

    public enum TradeResult { SUCCESS, FROZEN, INSUFFICIENT_FUNDS, INSUFFICIENT_BARS, INVALID_AMOUNT, DISABLED }

    private final CycleTradingPlugin plugin;
    private final ConcurrentHashMap<String, Long> holdings = new ConcurrentHashMap<>(); // uuid → 持有金条数
    private final AtomicBoolean seeded = new AtomicBoolean();

    private Bank bank;

    public GoldService(CycleTradingPlugin plugin) {
        this.plugin = plugin;
    }

    public void attachBank(Bank bank) {
        this.bank = bank;
    }

    // ---------- 行情 ----------

    /** 恒定发行量。 */
    public long total() {
        return Math.max(1, plugin.goldTotal());
    }

    /** 国库余额（金条定价基准）。 */
    public long treasury() {
        return bank.balance(Bank.SYSTEM);
    }

    /** 当前金条单价（绿宝石，整数地板）。 */
    public long price() {
        return treasury() / total();
    }

    /** 在外金条数（玩家持有合计）。 */
    public long outstanding() {
        long sum = 0;
        for (long v : holdings.values()) {
            sum += v;
        }
        return sum;
    }

    public long held(String uuid) {
        return holdings.getOrDefault(uuid, 0L);
    }

    /** 准备金占用 = 在外金条 × 现价。 */
    public long reserved() {
        return outstanding() * price();
    }

    /** 央行可自由支配资金 = 国库余额 − 准备金占用。 */
    public long freeTreasury() {
        return treasury() - reserved();
    }

    /** 首次启动注资（一次）。 */
    public void seedIfNeeded() {
        if (!seeded.compareAndSet(false, true)) {
            return;
        }
        long seed = plugin.goldSeed();
        if (seed > 0) {
            bank.credit(Bank.SYSTEM, "SYSTEM", seed, TxEntry.GOLD_SEED);
            plugin.getLogger().info("Gold: treasury seeded with " + seed + " emeralds (reserve backing "
                    + total() + " bars, initial price " + price() + ")");
        }
        plugin.storage().requestSave();
    }

    // ---------- 交易（即买即卖，玩家线程） ----------

    /** 买入：按当前价从国库买入 qty 根。 */
    public TradeResult buy(Player p, long qty) {
        if (!plugin.goldEnabled()) {
            return TradeResult.DISABLED;
        }
        if (qty <= 0) {
            return TradeResult.INVALID_AMOUNT;
        }
        String uuid = p.getUniqueId().toString();
        if (bank.isFrozen(uuid)) {
            return TradeResult.FROZEN;
        }
        long cost = price() * qty;
        if (cost <= 0) {
            return TradeResult.INVALID_AMOUNT;
        }
        if (bank.balance(uuid) < cost) {
            return TradeResult.INSUFFICIENT_FUNDS;
        }
        if (!bank.debit(uuid, cost, TxEntry.GOLD_BUY)) {
            return TradeResult.INSUFFICIENT_FUNDS;
        }
        bank.credit(Bank.SYSTEM, "SYSTEM", cost, TxEntry.GOLD_ISSUE);
        holdings.merge(uuid, qty, Long::sum);
        plugin.storage().requestSave();
        return TradeResult.SUCCESS;
    }

    /** 卖出：按当前价卖回给国库。 */
    public TradeResult sell(Player p, long qty) {
        if (!plugin.goldEnabled()) {
            return TradeResult.DISABLED;
        }
        if (qty <= 0) {
            return TradeResult.INVALID_AMOUNT;
        }
        String uuid = p.getUniqueId().toString();
        if (bank.isFrozen(uuid)) {
            return TradeResult.FROZEN;
        }
        if (held(uuid) < qty) {
            return TradeResult.INSUFFICIENT_BARS;
        }
        long proceeds = price() * qty;
        if (proceeds > 0) {
            bank.debit(Bank.SYSTEM, proceeds, TxEntry.GOLD_REDEEM);
            bank.credit(uuid, p.getName(), proceeds, TxEntry.GOLD_SELL);
        }
        holdings.compute(uuid, (k, v) -> {
            long nv = (v == null ? 0 : v) - qty;
            return nv <= 0 ? null : nv;
        });
        plugin.storage().requestSave();
        return TradeResult.SUCCESS;
    }

    // ---------- 存档 ----------

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new HashMap<>();
        holdings.forEach((uuid, n) -> {
            if (n > 0) {
                out.put(uuid, n);
            }
        });
        return out;
    }

    public List<String> holders() {
        return new ArrayList<>(holdings.keySet());
    }

    public void restore(Map<String, Long> data, boolean seededFlag) {
        if (data != null) {
            data.forEach((uuid, n) -> {
                if (n > 0) {
                    holdings.put(uuid, n);
                }
            });
        }
        if (seededFlag) {
            seeded.set(true);
        }
    }

    public boolean isSeeded() {
        return seeded.get();
    }
}
