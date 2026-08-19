package com.cycletrading.core.futures;

import com.cycletrading.util.Matures;

/** 期货多空头寸：保证金交易，到期/平仓按结算价现金结算，盈亏封顶 = 保证金。 */
public final class FuturesPosition implements Matures {

    public static final String LONG = "LONG";
    public static final String SHORT = "SHORT";

    public static final String OPEN = "OPEN";
    public static final String SETTLED = "SETTLED";

    public long id;
    public String owner;       // 玩家 UUID
    public String name;
    public String type;        // LONG | SHORT
    public String commodity;   // 品种 key
    public long entry;         // 入场价（绿宝石/整批合约）
    public long qty;           // 合约数量
    public long margin;        // 保证金 = entry × qty
    public int termDays;       // 期限（游戏日）
    public long createdAt;     // epoch ms
    public long openedAt;      // 开仓世界时间
    public long matureAt;      // 到期世界时间
    public String status;
    public long settlementPrice; // 结算价 S
    public long pnl;             // 实际盈亏（clamp 后，正盈负亏）
    public long payout;          // 实付 = margin + max(pnl,0)
    public long settledAt;       // epoch ms

    public FuturesPosition() {
        // Gson
    }

    public FuturesPosition(long id, String owner, String name, String type, String commodity,
            long entry, long qty, long margin, int termDays, long createdAt, long openedAt, long matureAt) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.type = type;
        this.commodity = commodity;
        this.entry = entry;
        this.qty = qty;
        this.margin = margin;
        this.termDays = termDays;
        this.createdAt = createdAt;
        this.openedAt = openedAt;
        this.matureAt = matureAt;
        this.status = OPEN;
    }

    public boolean isOpen() {
        return OPEN.equals(status);
    }

    public boolean isLong() {
        return LONG.equals(type);
    }

    @Override
    public long matureAt() {
        return matureAt;
    }
}
