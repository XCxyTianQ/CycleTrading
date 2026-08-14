package com.cycletrading.core.bond;

/** 定期债券：本金锁定，到期本息自动入账。利率以基点（万分之一）存储，购买时锁定。 */
public final class Bond {

    public static final String ACTIVE = "ACTIVE";
    public static final String REDEEMED = "REDEEMED";

    public long id;
    public String owner;      // 玩家 UUID
    public String name;
    public int tier;          // 1-5
    public long principal;    // 本金（绿宝石）
    public int rateBp;        // 锁定利率（基点：3.00% = 300）
    public long createdAt;    // epoch ms
    public long boughtAt;     // 购买时世界时间（fullTime）
    public long matureAt;     // 到期世界时间（fullTime，= boughtAt + 期限×24000）
    public String status;
    public long interest;     // 结算利息（REDEEMED 时）
    public long redeemedAt;   // epoch ms

    public Bond() {
        // Gson
    }

    public Bond(long id, String owner, String name, int tier, long principal, int rateBp,
            long createdAt, long boughtAt, long matureAt) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.tier = tier;
        this.principal = principal;
        this.rateBp = rateBp;
        this.createdAt = createdAt;
        this.boughtAt = boughtAt;
        this.matureAt = matureAt;
        this.status = ACTIVE;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }
}
