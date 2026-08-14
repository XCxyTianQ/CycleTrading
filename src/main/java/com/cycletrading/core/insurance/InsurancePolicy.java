package com.cycletrading.core.insurance;

/** 死亡保险保单：单次有效，死亡触发回滚后消耗。字段可 JSON 序列化。 */
public final class InsurancePolicy {

    public String owner;     // 玩家 UUID
    public String name;
    public int tier;         // 1-4
    public long premium;     // 已付保费（绿宝石）
    public long createdAt;

    public InsurancePolicy() {
        // Gson
    }

    public InsurancePolicy(String owner, String name, int tier, long premium, long createdAt) {
        this.owner = owner;
        this.name = name;
        this.tier = tier;
        this.premium = premium;
        this.createdAt = createdAt;
    }
}
