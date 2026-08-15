package com.cycletrading.core.bank;

/** 审计流水条目：每次余额变更一条，记录变更后余额，供后台对账。 */
public final class TxEntry {

    // 交易类型
    public static final String DEPOSIT = "DEPOSIT";
    public static final String WITHDRAW = "WITHDRAW";
    public static final String SEND_OUT = "SEND_OUT";
    public static final String SEND_IN = "SEND_IN";
    public static final String BUY = "BUY";          // 市场购买支付
    public static final String LUX_BUY = "LUX_BUY";  // 奢侈品购买支付（买方个人流水专属标记）
    public static final String SELL = "SELL";        // 市场售出收益
    public static final String LUX_SELL = "LUX_SELL"; // 奢侈品成交款入国库
    public static final String BOND_BUY = "BOND_BUY";                 // 债券购买（本金锁定扣款）
    public static final String BOND_REDEEM = "BOND_REDEEM";           // 债券到期本息入账
    public static final String FUTURES_BUY = "FUTURES_BUY";           // 期货成交付款（锁定）
    public static final String FUTURES_SELL = "FUTURES_SELL";         // 期货交割货款入账
    public static final String FUTURES_REFUND = "FUTURES_REFUND";     // 期货撤销退款
    public static final String FUT_POS_OPEN = "FUT_POS_OPEN";         // 期货开仓（保证金扣款）
    public static final String FUT_POS_MARGIN = "FUT_POS_MARGIN";     // 保证金入清算所
    public static final String FUT_POS_RETURN = "FUT_POS_RETURN";     // 头寸结算实付（玩家收入）
    public static final String FUT_POS_SETTLE = "FUT_POS_SETTLE";     // 清算所结算支出
    public static final String OPTION_OPEN = "OPTION_OPEN";           // 期权开仓（保证金托管扣款）
    public static final String OPTION_PREMIUM = "OPTION_PREMIUM";     // 期权权利金（买方支出）
    public static final String OPTION_PREMIUM_IN = "OPTION_PREMIUM_IN"; // 期权权利金（卖方收入）
    public static final String OPTION_PAYOUT = "OPTION_PAYOUT";       // 期权到期赔付（买方收入）
    public static final String OPTION_MARGIN_RETURN = "OPTION_MARGIN_RETURN"; // 期权保证金退还
    public static final String OPTION_REFUND = "OPTION_REFUND";       // 期权撤销退款（买方权利金）
    public static final String TAX = "TAX";                           // 成交税入国库
    public static final String GOLD_SEED = "GOLD_SEED";               // 金条准备金一次性注资
    public static final String GOLD_BUY = "GOLD_BUY";                 // 购买金条（买方支出）
    public static final String GOLD_ISSUE = "GOLD_ISSUE";             // 购金条款入国库
    public static final String GOLD_SELL = "GOLD_SELL";               // 卖出金条（卖方收入）
    public static final String GOLD_REDEEM = "GOLD_REDEEM";           // 金条赎回国库付款
    public static final String CB_DISTRIBUTE = "CB_DISTRIBUTE";       // 央行人均发行
    public static final String CB_SPEND = "CB_SPEND";                 // 央行定向支出
    public static final String CB_GRANT = "CB_GRANT";                 // 央行补贴（玩家收入）
    public static final String CB_TAX = "CB_TAX";                     // 央行定向征税（国库收入）
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
