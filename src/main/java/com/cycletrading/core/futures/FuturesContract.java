package com.cycletrading.core.futures;

import com.cycletrading.util.Matures;

/** 期货合约：标准化品种+数量，卖方商品全额托管，买方货款全额锁定，到期实物交割。 */
public final class FuturesContract implements Matures {

    public static final String OPEN = "OPEN";            // 挂单（待成交，可撤单）
    public static final String LOCKED = "LOCKED";        // 已成交（双担保锁定，待交割）
    public static final String DELIVERED = "DELIVERED";  // 交割完成
    public static final String WITHDRAWN = "WITHDRAWN";  // 成交前撤单
    public static final String CANCELLED = "CANCELLED";  // 管理员撤销（已成交合约：退款+退货）

    public long id;
    public String seller;      // 卖方 UUID
    public String sellerName;
    public String buyer;       // 买方 UUID（LOCKED 起有效）
    public String buyerName;
    public String item;        // 托管商品 Base64（交割失败时更新为剩余量）
    public long price;         // 成交价（绿宝石）
    public int termDays;       // 交割期限（游戏日）
    public long createdAt;     // epoch ms
    public long lockedAt;      // 成交时间（世界 fullTime）
    public long matureAt;      // 交割时间（世界 fullTime）
    public String status;
    public long deliveredAt;   // epoch ms

    public FuturesContract() {
        // Gson
    }

    public FuturesContract(long id, String seller, String sellerName, String item, long price,
            int termDays, long createdAt) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.item = item;
        this.price = price;
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

    @Override
    public long matureAt() {
        return matureAt;
    }
}
