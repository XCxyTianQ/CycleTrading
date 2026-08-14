package com.cycletrading.core;

/** 一条市场挂单。字段全部可 JSON 序列化（Gson），只存 UUID 字符串与 Base64 物品。 */
public final class Listing {

    public static final String ACTIVE = "ACTIVE";
    public static final String SOLD = "SOLD";
    public static final String CANCELLED = "CANCELLED";

    public long id;
    public String seller;       // 卖家 UUID
    public String sellerName;   // 卖家名（展示用缓存）
    public String item;         // 托管物品 Base64
    public long price;          // 绿宝石价格
    public long createdAt;      // epoch ms
    public String status;
    public String buyer;        // 成交买家 UUID（SOLD 时有效）

    public Listing() {
        // Gson
    }

    public Listing(long id, String seller, String sellerName, String item, long price, long createdAt) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.item = item;
        this.price = price;
        this.createdAt = createdAt;
        this.status = ACTIVE;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }
}
