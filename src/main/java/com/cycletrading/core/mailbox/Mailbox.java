package com.cycletrading.core.mailbox;

import com.cycletrading.CycleTradingPlugin;
import com.cycletrading.core.Items;
import com.cycletrading.core.MailEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 邮箱：只收不存的系统投递通道。
 *
 * - 仅系统投递（市场/奢侈品/保险溢出），玩家无法存入物品；
 * - 每玩家储量上限 {@link #capacity()}（默认 27 条），满箱拒绝投递（调用方兜底）；
 * - 领取：/ct mail GUI 单件领取 或 /ct collect 一键领取；放不下的部分保留在邮箱。
 */
public final class Mailbox {

    public record CollectResult(int items, long emeralds) { }

    public enum ClaimResult { SUCCESS, PARTIAL, INVENTORY_FULL, NOT_FOUND }

    private final CycleTradingPlugin plugin;
    private final CopyOnWriteArrayList<MailEntry> entries = new CopyOnWriteArrayList<>();

    public Mailbox(CycleTradingPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- 容量与查询 ----------

    /** 每玩家储量上限。 */
    public int capacity() {
        return plugin.mailboxCapacity();
    }

    public int count(String uuid) {
        int n = 0;
        for (MailEntry m : entries) {
            if (uuid.equals(m.owner)) {
                n++;
            }
        }
        return n;
    }

    public boolean hasRoom(String uuid) {
        return count(uuid) < capacity();
    }

    /** 剩余格位。 */
    public int roomLeft(String uuid) {
        return Math.max(0, capacity() - count(uuid));
    }

    public List<MailEntry> entriesOf(String uuid) {
        return entries.stream().filter(m -> uuid.equals(m.owner)).toList();
    }

    // ---------- 系统投递 ----------

    /** 投递物品。满箱返回 false（物品未收下，调用方兜底）。 */
    public boolean add(String owner, ItemStack item, String source) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return true;
        }
        if (!hasRoom(owner)) {
            return false;
        }
        entries.add(new MailEntry(owner, Items.toBase64(item), 0, System.currentTimeMillis(), source));
        plugin.storage().requestSave();
        return true;
    }

    /** 投递绿宝石（兼容旧数据路径）。满箱返回 false。 */
    public boolean addEmeralds(String owner, int n, String source) {
        if (n <= 0) {
            return true;
        }
        if (!hasRoom(owner)) {
            return false;
        }
        entries.add(new MailEntry(owner, null, n, System.currentTimeMillis(), source));
        plugin.storage().requestSave();
        return true;
    }

    // ---------- 领取 ----------

    /** 领取单个条目（/ct mail GUI 点击）。放不下的部分保留在该条目。 */
    public ClaimResult claim(Player p, int idx) {
        String uuid = p.getUniqueId().toString();
        List<MailEntry> mine = entriesOf(uuid);
        if (idx < 0 || idx >= mine.size()) {
            return ClaimResult.NOT_FOUND;
        }
        MailEntry m = mine.get(idx);
        if (m.item != null) {
            ItemStack it;
            try {
                it = Items.fromBase64(m.item);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Mail item deserialization failed, skipped: " + ex.getMessage());
                return ClaimResult.NOT_FOUND;
            }
            Map<Integer, ItemStack> ov = p.getInventory().addItem(it);
            int leftAmt = ov.values().stream().mapToInt(ItemStack::getAmount).sum();
            if (leftAmt == 0) {
                entries.remove(m);
                plugin.storage().requestSave();
                return ClaimResult.SUCCESS;
            }
            if (leftAmt == it.getAmount()) {
                return ClaimResult.INVENTORY_FULL;
            }
            m.item = Items.toBase64(ov.values().iterator().next());
            plugin.storage().requestSave();
            return ClaimResult.PARTIAL;
        }
        if (m.emeralds > 0) {
            Map<Integer, ItemStack> ov = p.getInventory().addItem(Items.emeralds(m.emeralds));
            int leftAmt = ov.values().stream().mapToInt(ItemStack::getAmount).sum();
            if (leftAmt == 0) {
                entries.remove(m);
                plugin.storage().requestSave();
                return ClaimResult.SUCCESS;
            }
            if (leftAmt == m.emeralds) {
                return ClaimResult.INVENTORY_FULL;
            }
            m.emeralds = leftAmt;
            plugin.storage().requestSave();
            return ClaimResult.PARTIAL;
        }
        entries.remove(m); // 空条目清理
        plugin.storage().requestSave();
        return ClaimResult.SUCCESS;
    }

    /** 一键领取全部。放不下的部分保留。 */
    public CollectResult collect(Player p) {
        String uuid = p.getUniqueId().toString();
        int items = 0;
        long emeralds = 0;
        boolean changed = false;
        for (MailEntry m : entries) {
            if (!uuid.equals(m.owner)) {
                continue;
            }
            if (m.item != null) {
                ItemStack it;
                try {
                    it = Items.fromBase64(m.item);
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Mail item deserialization failed, skipped: " + ex.getMessage());
                    continue;
                }
                Map<Integer, ItemStack> ov = p.getInventory().addItem(it);
                int leftAmt = ov.values().stream().mapToInt(ItemStack::getAmount).sum();
                if (leftAmt == 0) {
                    entries.remove(m);
                    items++;
                } else {
                    m.item = Items.toBase64(ov.values().iterator().next());
                    if (leftAmt < it.getAmount()) {
                        items++;
                    }
                }
                changed = true;
            } else if (m.emeralds > 0) {
                Map<Integer, ItemStack> ov = p.getInventory().addItem(Items.emeralds(m.emeralds));
                int leftAmt = ov.values().stream().mapToInt(ItemStack::getAmount).sum();
                long got = m.emeralds - leftAmt;
                if (got > 0) {
                    emeralds += got;
                }
                if (leftAmt == 0) {
                    entries.remove(m);
                } else {
                    m.emeralds = leftAmt;
                }
                changed = true;
            }
        }
        if (changed) {
            plugin.storage().requestSave();
        }
        return new CollectResult(items, emeralds);
    }

    // ---------- 存档 ----------

    public List<MailEntry> snapshot() {
        return new ArrayList<>(entries);
    }

    public void restore(MailEntry m) {
        if (m != null) {
            entries.add(m);
        }
    }
}
