package com.cycletrading.sched;

import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Folia 线程调度封装。
 *
 * 纪律：一切背包/世界/实体操作必须在玩家所属的 entity 线程执行。
 * 跨玩家结算（买家付款 → 卖家收款）通过 {@link #onPlayer} 投递到卖家线程，
 * 卖家离线（实体 retired）时由 fallback 走邮箱兜底。
 */
public final class Scheduler {

    private Scheduler() {
    }

    /** 在玩家 entity 线程执行任务；玩家离线/实体卸载时执行 fallback。 */
    public static void onPlayer(Plugin plugin, Player p, Consumer<Player> task, Runnable fallback) {
        p.getScheduler().run(plugin, s -> task.accept(p), fallback == null ? () -> { } : fallback);
    }

    /** 异步线程执行（IO 等）。 */
    public static void async(Plugin plugin, Runnable task) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, s -> task.run());
    }
}
