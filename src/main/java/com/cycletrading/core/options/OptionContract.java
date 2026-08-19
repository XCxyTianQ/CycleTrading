package com.cycletrading.core.options;

import com.cycletrading.util.Matures;

/** 欧式期权合约：现金结算，卖方全额保证金托管（行权价），到期按结算价赔付。 */
public final class OptionContract implements Matures {

    public static final String CALL = "CALL";
    public static final String PUT = "PUT";

    public static final String OPEN = "OPEN";            // 挂单（待成交，可撤单）
    public static final String LOCKED = "LOCKED";        // 已成交（保证金+权利金锁定，待到期）
    public static final String SETTLED = "SETTLED";      // 到期结算完成
    public static final String WITHDRAWN = "WITHDRAWN";  // 成交前撤单
    public static final String CANCELLED = "CANCELLED";  // 管理员撤销

    public long id;
    public String seller;      // 卖方 UUID
    public String sellerName;
    public String buyer;       // 买方 UUID（LOCKED 起有效）
    public String buyerName;
    public String type;        // CALL | PUT
    public String commodity;   // 标的品种 key（如 oak_log）
    public long strike;        // 行权价（绿宝石/整批合约）
    public long premium;       // 权利金（买方付给卖方，成交时即结算）
    public int termDays;       // 期限（游戏日）
    public long createdAt;     // epoch ms
    public long lockedAt;      // 成交时间（世界 fullTime）
    public long matureAt;      // 到期时间（世界 fullTime）
    public String status;
    public long settlementPrice; // 到期结算价 S
    public long payout;          // 赔付额
    public long settledAt;       // epoch ms

    public OptionContract() {
        // Gson
    }

    public OptionContract(long id, String seller, String sellerName, String type, String commodity,
            long strike, long premium, int termDays, long createdAt) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.type = type;
        this.commodity = commodity;
        this.strike = strike;
        this.premium = premium;
        this.termDays = termDays;
        this.createdAt = createdAt;
        this.status = OPEN;
    }

    public boolean isOpen() {
        return OPEN.equals(status);
    }

    public boolean isLocked() {
        return LOCKED.equals(status);
    }

    public boolean isCall() {
        return CALL.equals(type);
    }

    @Override
    public long matureAt() {
        return matureAt;
    }
}
