package com.cycletrading.core.options;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.futures.FuturesContract;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 大宗品种价格历史（期权结算价来源之一）。
 * 期货每笔交割成交价按品种记录（保留最近 KEEP 笔），期权到期取均价；
 * 内存态，重启后由期货已交割合约重建（DELIVERED 记录持久化于 futures.json）。
 */
public final class PriceHistory {

    /** 每品种保留的最近成交笔数。 */
    public static final int KEEP = 10;

    private final CycleTradingPlugin plugin;
    private final Map<Material, ArrayDeque<Long>> history = new ConcurrentHashMap<>();

    public PriceHistory(CycleTradingPlugin plugin) {
        this.plugin = plugin;
    }

    /** 记录一笔成交价（期货交割时调用）。 */
    public synchronized void record(Material m, long price) {
        if (m == null || price <= 0) {
            return;
        }
        ArrayDeque<Long> dq = history.computeIfAbsent(m, k -> new ArrayDeque<>());
        dq.addLast(price);
        while (dq.size() > KEEP) {
            dq.removeFirst();
        }
    }

    /** 近期成交均价；无成交返回 null。 */
    public synchronized Double average(Material m) {
        ArrayDeque<Long> dq = history.get(m);
        if (dq == null || dq.isEmpty()) {
            return null;
        }
        long sum = 0;
        for (long p : dq) {
            sum += p;
        }
        return (double) sum / dq.size();
    }

    public synchronized int count(Material m) {
        ArrayDeque<Long> dq = history.get(m);
        return dq == null ? 0 : dq.size();
    }

    /** 从期货已交割合约重建价格历史（启动时调用）。 */
    public void rebuild(List<FuturesContract> delivered) {
        for (FuturesContract c : delivered) {
            try {
                ItemStack it = com.cycletrading.core.Items.fromBase64(c.item);
                record(it.getType(), c.price);
            } catch (RuntimeException ignored) {
                // 损坏记录跳过
            }
        }
    }
}
