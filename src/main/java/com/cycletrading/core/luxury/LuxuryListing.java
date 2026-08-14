package com.cycletrading.core.luxury;

/** 奢侈品挂单：仅管理员可挂售。价格 = 基础定价 × 动态倍率（由银行总存量加权）。 */
public final class LuxuryListing {

    public static final String ACTIVE = "ACTIVE";
    public static final String SOLD = "SOLD";
    public static final String CANCELLED = "CANCELLED";

    public long id;
    public String item;        // 托管物品 Base64
    public long basePrice;     // 管理员基础定价（绿宝石）
    public String listedBy;    // 挂售管理员名
    public long createdAt;     // epoch ms
    public String status;
    public String buyer;       // 成交买家 UUID（SOLD 时有效）
    public long soldAt;        // 成交时间
    public long soldPrice;     // 实际成交价（动态定价，审计用）

    public LuxuryListing() {
        // Gson
    }

    public LuxuryListing(long id, String item, long basePrice, String listedBy, long createdAt) {
        this.id = id;
        this.item = item;
        this.basePrice = basePrice;
        this.listedBy = listedBy;
        this.createdAt = createdAt;
        this.status = ACTIVE;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }
}
