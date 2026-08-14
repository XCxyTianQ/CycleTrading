package com.cycletrading.core.bank;

/** 银行账户：虚拟绿宝石余额（long，无堆叠限制）。字段可 JSON 序列化。 */
public final class BankAccount {

    public String owner;      // 玩家 UUID
    public String name;       // 玩家名缓存
    public long balance;      // 虚拟绿宝石
    public boolean frozen;    // 冻结：禁止存/取/转账/市场支付（收益入账仍允许）
    public long createdAt;
    public long updatedAt;

    public BankAccount() {
        // Gson
    }

    public BankAccount(String owner, String name, long balance, boolean frozen, long createdAt, long updatedAt) {
        this.owner = owner;
        this.name = name;
        this.balance = balance;
        this.frozen = frozen;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
