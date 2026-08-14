package com.cycletrading.core.insurance;

import java.util.ArrayList;
import java.util.List;

/** 死亡托管：受保物品在死亡时摘离掉落表，持久化至玩家重生后原槽位还原。 */
public final class DeathStash {

    public String owner;    // 玩家 UUID
    public String name;
    public int tier;        // 触发时的档位
    public long createdAt;
    public List<StashItem> items = new ArrayList<>();

    public DeathStash() {
        // Gson
    }

    public DeathStash(String owner, String name, int tier, long createdAt) {
        this.owner = owner;
        this.name = name;
        this.tier = tier;
        this.createdAt = createdAt;
    }
}
