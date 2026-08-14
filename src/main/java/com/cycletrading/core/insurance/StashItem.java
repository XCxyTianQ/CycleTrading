package com.cycletrading.core.insurance;

/** 受保物品条目：原背包槽位 + 物品 Base64。 */
public final class StashItem {

    public int slot;    // 原背包槽位（0-8 快捷栏, 9-35 物品栏, 36-39 盔甲, 40 副手）
    public String item; // Base64

    public StashItem() {
        // Gson
    }

    public StashItem(int slot, String item) {
        this.slot = slot;
        this.item = item;
    }
}
