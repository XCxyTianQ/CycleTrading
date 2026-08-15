package com.cycletrading.core.prices;

import com.cycletrading.CycleTradingPlugin;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.ItemStack;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 市场价值锚点（锚点下放：村民交易表 → 市场成交学习）。
 *
 * - 基础价：启动时枚举【村民交易表】（职业 × 等级 1-5，临时实体读取报价后立即移除），
 *   仅注册纯绿宝石双边交易并折算为毫绿宝石（mE）/件的单价（处理 20 小麦 = 1 绿宝石 这类分数价）；
 * - 动态调整：市场每笔成交价进入滚动窗口（默认 10 笔），锚点 = 成交均价（有成交）→ 村民基础价（无成交）→ 无锚；
 * - 软区间：挂单总价须落在 [锚点×amount ÷ band, 锚点×amount × band]（band 可配置，0 = 不限制）。
 */
public final class PriceAnchor implements Listener {

    private static final long MILLI = 1000L;

    private final CycleTradingPlugin plugin;
    private final ConcurrentHashMap<Material, Long> villagerBase = new ConcurrentHashMap<>(); // mE/件
    private final ConcurrentHashMap<Material, ArrayDeque<Long>> recent = new ConcurrentHashMap<>(); // 近期成交 mE/件
    private final AtomicBoolean bootstrapped = new AtomicBoolean();

    public PriceAnchor(CycleTradingPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- 村民交易表注册（锚点下放第一步） ----------

    /** 启动后异步注册：请求加载出生点区块后，在对应 region 线程上枚举村民报价。 */
    public void bootstrapVillagers() {
        AtomicInteger tries = new AtomicInteger();
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            World w = overworld();
            if (w == null) {
                return;
            }
            Location loc = w.getSpawnLocation();
            int cx = loc.getBlockX() >> 4;
            int cz = loc.getBlockZ() >> 4;
            if (!w.isChunkLoaded(cx, cz)) {
                // 本 fork 不加载常驻区块：插件区块票强制加载（失败则等待首个玩家加入兜底）
                try {
                    w.addPluginChunkTicket(cx, cz, plugin);
                } catch (Exception ignored) {
                    // 跨线程受限时忽略，交给 join 兜底
                }
                if (tries.incrementAndGet() > 60) {
                    task.cancel();
                    plugin.getLogger().warning("PriceAnchor: spawn chunk never loaded; will bootstrap on first player join");
                }
                return;
            }
            task.cancel();
            plugin.getServer().getRegionScheduler().execute(plugin, loc, () -> {
                enumerate(loc);
                try {
                    w.removePluginChunkTicket(cx, cz, plugin);
                } catch (Exception ignored) {
                    // 忽略
                }
            });
        }, 40L, 100L);
    }

    /** 兜底：首个玩家加入时在其所在区域（区块必已加载）执行注册。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!bootstrapped.compareAndSet(false, true)) {
            return;
        }
        Player p = e.getPlayer();
        Location loc = p.getLocation();
        plugin.getServer().getRegionScheduler().execute(plugin, loc, () -> enumerate(loc));
    }

    private void enumerate(Location loc) {
        World w = loc.getWorld();
        if (w == null) {
            return;
        }
        Villager v = null;
        try {
            v = w.spawn(loc, Villager.class);
            v.setAI(false);
            v.setSilent(true);
            v.setInvulnerable(true);
            v.setPersistent(false);
            int entries = 0;
            for (Villager.Profession prof : Villager.Profession.values()) {
                if (prof == Villager.Profession.NONE || prof == Villager.Profession.NITWIT) {
                    continue;
                }
                v.setProfession(prof);
                for (int level = 1; level <= 5; level++) {
                    v.setVillagerLevel(level);
                    for (MerchantRecipe r : v.getRecipes()) {
                        if (derive(r)) {
                            entries++;
                        }
                    }
                }
            }
            v.remove();
            plugin.getLogger().info("PriceAnchor: villager trade table registered (" + entries
                    + " entries across " + (Villager.Profession.values().length - 2) + " professions)");
        } catch (Exception ex) {
            if (v != null) {
                v.remove();
            }
            plugin.getLogger().warning("PriceAnchor: villager bootstrap failed: " + ex.getMessage());
        }
    }

    /** 从一条村民报价推导基础价（仅纯绿宝石双边交易）。返回是否注册了新条目。 */
    private boolean derive(MerchantRecipe r) {
        ItemStack result = r.getResult();
        if (result == null || result.getType().isAir()) {
            return false;
        }
        List<ItemStack> ings = r.getIngredients();
        if (ings == null || ings.isEmpty()) {
            return false;
        }
        long emeraldIn = 0;
        long emeraldOut = 0;
        Material item = null;
        long itemAmt = 0;
        boolean mixed = false;
        for (ItemStack it : ings) {
            if (it == null || it.getType().isAir()) {
                continue;
            }
            if (it.getType() == Material.EMERALD) {
                emeraldIn += it.getAmount();
            } else {
                if (item != null && item != it.getType()) {
                    mixed = true;
                }
                item = it.getType();
                itemAmt += it.getAmount();
            }
        }
        if (result.getType() == Material.EMERALD) {
            emeraldOut = result.getAmount();
        }
        boolean resultItem = result.getType() != Material.EMERALD;
        // 村民收购：单一物品 → 绿宝石（e.g. 20 小麦 = 1 绿宝石）
        if (emeraldOut > 0 && emeraldIn == 0 && item != null && !mixed && !resultItem) {
            return putFirst(item, emeraldOut * MILLI / itemAmt);
        }
        // 村民出售：绿宝石 → 单一物品（e.g. 1 绿宝石 = 4 面包）
        if (resultItem && emeraldIn > 0 && item == null) {
            return putFirst(result.getType(), emeraldIn * MILLI / result.getAmount());
        }
        return false;
    }

    private boolean putFirst(Material m, long milli) {
        if (m == null || milli <= 0) {
            return false;
        }
        return villagerBase.putIfAbsent(m, milli) == null;
    }

    // ---------- 锚点查询 ----------

    /** 锚点单价（mE/件）：近期成交均价 → 村民基础价 → 0（无锚）。 */
    public long anchorMilli(Material m) {
        ArrayDeque<Long> dq = recent.get(m);
        if (dq != null && !dq.isEmpty()) {
            long sum = 0;
            for (long x : dq) {
                sum += x;
            }
            return sum / dq.size();
        }
        return villagerBase.getOrDefault(m, 0L);
    }

    public boolean hasAnchor(Material m) {
        return anchorMilli(m) > 0;
    }

    /** 锚点来源（展示用）。 */
    public String anchorSource(Material m) {
        ArrayDeque<Long> dq = recent.get(m);
        if (dq != null && !dq.isEmpty()) {
            return "市场成交均价";
        }
        return villagerBase.containsKey(m) ? "村民交易" : "无参考";
    }

    /** 挂单软区间校验：band ≤ 0 = 不限制；无锚 = 不限制。 */
    public boolean inBand(Material m, long amount, long price) {
        long anchor = anchorMilli(m);
        if (anchor <= 0) {
            return true;
        }
        double band = plugin.anchorBand();
        if (band <= 0) {
            return true;
        }
        double lo = anchor * amount / band;
        double hi = anchor * amount * band;
        double priceMilli = price * (double) MILLI;
        return priceMilli >= lo && priceMilli <= hi;
    }

    // ---------- 市场成交学习 ----------

    /** 记录一笔市场成交（单位 mE/件），滚动窗口。 */
    public void record(Material m, long unitMilli) {
        if (m == null || unitMilli <= 0) {
            return;
        }
        ArrayDeque<Long> dq = recent.computeIfAbsent(m, k -> new ArrayDeque<>());
        synchronized (dq) {
            dq.addLast(unitMilli);
            int keep = Math.max(1, plugin.anchorHistory());
            while (dq.size() > keep) {
                dq.removeFirst();
            }
        }
        plugin.storage().requestSave();
    }

    // ---------- 存档（仅成交学习窗口；村民基础价每次启动重新注册，确定性） ----------

    public Map<String, List<Long>> snapshot() {
        Map<String, List<Long>> out = new HashMap<>();
        recent.forEach((m, dq) -> {
            synchronized (dq) {
                if (!dq.isEmpty()) {
                    out.put(m.name(), new ArrayList<>(dq));
                }
            }
        });
        return out;
    }

    public void restore(Map<String, List<Long>> data) {
        if (data == null) {
            return;
        }
        data.forEach((name, list) -> {
            Material m = Material.matchMaterial(name);
            if (m != null && list != null && !list.isEmpty()) {
                recent.put(m, new ArrayDeque<>(list));
            }
        });
    }

    // ---------- 内部 ----------

    private World overworld() {
        return plugin.getServer().getWorlds().stream()
                .filter(x -> x.getEnvironment() == World.Environment.NORMAL)
                .findFirst().orElse(null);
    }
}
