package com.cycletrading.core.insurance;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.Items;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 死亡保险事件处理。
 *
 * 死亡：消费保单 → 按档位读取背包槽位（事件触发时背包尚未清空，41 槽全量可读，槽位精确）
 *      → 受保物品【从掉落表摘离】并持久化托管（insurance.json deathStashes）。
 * 重生：按原槽位还原（槽位被占入背包，溢出进邮箱；邮箱满则暂存托管，清理后 /ct collect 重试）。
 *
 * 防复制关键（本 fork 实测反编译验证）：
 * 1) 该 fork 的 PlayerDeathEvent 掉落表是 TransformingRandomAccessList —— 向其中写入 null 再
 *    removeIf 会 NPE（此前崩溃的根因），必须用 remove(int) 删除；
 * 2) 掉落表只含非空格物品且按槽位升序排列 —— 受保物品必然位于表头，按顺序比对 drops.get(0)
 *    并 remove(0) 即可精确摘离（消失诅咒物品不在掉落表中，自动跳过不前进）；
 * 3) 不用 itemsToKeep：本 fork 的掉落消费者 lambda$die$0 无条件生成地面掉落，若同时保留在
 *    背包会双重结算造成复制。摘离掉落表后，受保物品只存在于托管一处，绝无复制。
 */
public final class InsuranceListener implements Listener {

    private final CycleTradingPlugin plugin;
    private final InsuranceService service;

    public InsuranceListener(CycleTradingPlugin plugin, InsuranceService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        if (!plugin.insuranceEnabled()) {
            return; // 保险板块关闭：完全不介入死亡事件
        }
        Player p = e.getEntity();
        String uuid = p.getUniqueId().toString();
        InsurancePolicy pol = service.consume(uuid);
        if (pol == null) {
            return;
        }
        int tier = pol.tier;
        int limit = InsuranceService.tierLimit(tier);
        ItemStack[] contents = p.getInventory().getContents(); // 0-8 快捷栏, 9-35 物品栏, 36-39 盔甲, 40 副手
        DeathStash stash = new DeathStash(uuid, p.getName(), tier, System.currentTimeMillis());
        java.util.List<ItemStack> drops = e.getDrops();
        int n = Math.min(limit, contents.length);
        for (int i = 0; i < n; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir() || it.getAmount() <= 0) {
                continue; // 空格：不在掉落表中
            }
            stash.items.add(new StashItem(i, Items.toBase64(it)));
            // 受保物品按槽位升序排在掉落表最前段；表头匹配则摘离（remove(0) 后后续条目自动前移）
            if (!drops.isEmpty() && drops.get(0).equals(it)) {
                drops.remove(0);
            }
            // 不匹配 = 消失诅咒（未进入掉落表）：跳过，索引不前移
        }
        service.addStash(stash);
        // 档位 4 补偿：10 绿宝石入虚拟账户（出国库）
        service.compensate(uuid, p.getName(), tier);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        InsuranceService.DeliverResult r = service.deliverPending(p);
        if (r.tier() == 0) {
            return; // 无托管
        }
        String msg = "§a死亡保险(档位" + r.tier() + ")已生效：回滚 §e" + r.restored() + " §a件物品"
                + (r.tier() == 4 ? "，并已补偿 §e" + plugin.insT4Compensation() + " §a绿宝石到银行账户" : "");
        if (r.pending() > 0) {
            msg += "§c；仍有 " + r.pending() + " 件因邮箱已满暂存（邮箱上限 27），清理后 /ct collect 领取";
        }
        p.sendMessage(msg);
    }
}
