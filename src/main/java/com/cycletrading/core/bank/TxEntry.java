package com.cycletrading.core.bank;

/** 审计流水条目：每次余额变更一条，记录变更后余额，供后台对账。 */
public final class TxEntry {

    // 交易类型
    public static final String DEPOSIT = "DEPOSIT";
    public static final String WITHDRAW = "WITHDRAW";
    public static final String SEND_OUT = "SEND_OUT";
    public static final String SEND_IN = "SEND_IN";
    public static final String BUY = "BUY";          // 市场购买支付
    public static final String SELL = "SELL";        // 市场售出收益
    public static final String LUX_SELL = "LUX_SELL"; // 奢侈品成交款入国库
    public static final String INSURANCE_PAID = "INSURANCE_PAID";     // 投保缴费（玩家支出）
    public static final String INSURANCE_PREMIUM = "INSURANCE_PREMIUM"; // 保费入国库
    public static final String INSURANCE = "INSURANCE";               // 档位4死亡补偿（入玩家账户）
    public static final String BOND_BUY = "BOND_BUY";                 // 债券购买（本金锁定扣款）
    public static final String BOND_REDEEM = "BOND_REDEEM";           // 债券到期本息入账
    public static final String FUTURES_BUY = "FUTURES_BUY";           // 期货成交付款（锁定）
    public static final String FUTURES_SELL = "FUTURES_SELL";         // 期货交割货款入账
    public static final String FUTURES_REFUND = "FUTURES_REFUND";     // 期货撤销退款
    public static final String REFUND = "REFUND";    // 交易失败退款
    public static final String ADMIN_SET = "ADMIN_SET";
    public static final String ADMIN_ADD = "ADMIN_ADD";
    public static final String ADMIN_REMOVE = "ADMIN_REMOVE";
    public static final String FREEZE = "FREEZE";
    public static final String UNFREEZE = "UNFREEZE";

    public long id;
    public long ts;            // epoch ms
    public String type;
    public String owner;       // 本方 UUID
    public String counterpart; // 对方 UUID（转账等），可空
    public long amount;        // 正数
    public long balanceAfter;  // 变更后余额

    public TxEntry() {
        // Gson
    }

    public TxEntry(long id, long ts, String type, String owner, String counterpart, long amount, long balanceAfter) {
        this.id = id;
        this.ts = ts;
        this.type = type;
        this.owner = owner;
        this.counterpart = counterpart;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
    }
}
