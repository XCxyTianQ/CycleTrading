package com.cycletrading.core.futures;

import org.bukkit.Material;

/** 交易所标准合约品种（品种 + 固定数量）。 */
public record Commodity(String key, Material material, int amount) {

    /** 数量折合的堆数（64 一组）。 */
    public int stacks() {
        return (amount + 63) / 64;
    }
}
