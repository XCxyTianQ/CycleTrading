package com.cycletrading.core;

/** 邮箱条目：系统投递物。item 与 emeralds 至少一项有效。 */
public final class MailEntry {

    public String owner;     // 归属玩家 UUID
    public String item;      // Base64 物品，可空
    public int emeralds;     // 绿宝石数量，可 0
    public long createdAt;
    public String source;    // 来源：MARKET / LUXURY / INSURANCE

    public MailEntry() {
        // Gson
    }

    public MailEntry(String owner, String item, int emeralds, long createdAt, String source) {
        this.owner = owner;
        this.item = item;
        this.emeralds = emeralds;
        this.createdAt = createdAt;
        this.source = source;
    }
}
