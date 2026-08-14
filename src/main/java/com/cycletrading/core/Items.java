package com.cycletrading.core;

import java.util.Base64;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** 物品序列化与绿宝石通货操作。 */
public final class Items {

    /** 唯一通货：绿宝石。 */
    public static final Material CURRENCY = Material.EMERALD;

    private Items() {
    }

    /** ItemStack → Base64（基于 Paper serializeAsBytes，稳定可逆）。 */
    public static String toBase64(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    /** Base64 → ItemStack。 */
    public static ItemStack fromBase64(String b64) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
    }

    /** 玩家背包中的绿宝石总数（含副手）。 */
    public static int currencyCount(Player p) {
        int total = 0;
        for (ItemStack it : p.getInventory().all(CURRENCY).values()) {
            total += it.getAmount();
        }
        return total;
    }

    /** 绿宝石堆叠。 */
    public static ItemStack emeralds(int n) {
        return new ItemStack(CURRENCY, n);
    }

    /** 背包（含快捷栏，36 格）能否完整容纳该物品堆。 */
    public static boolean canFit(org.bukkit.inventory.PlayerInventory inv, ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
            return true;
        }
        int room = 0;
        int need = item.getAmount();
        int max = item.getMaxStackSize();
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType() == Material.AIR) {
                room += max;
            } else if (s.isSimilar(item)) {
                room += Math.max(0, max - s.getAmount());
            }
            if (room >= need) {
                return true;
            }
        }
        return false;
    }
}
