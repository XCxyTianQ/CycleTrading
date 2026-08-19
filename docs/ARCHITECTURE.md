# CycleTrading 架构设计 · 市场 · 银行 · 奢侈品商店 · 邮箱 · 定期债券 · 期货交割 · 期权 · 市场锚点

> 目标环境：AzureBranches EXP5Plus（Folia fork，MC 26.1.2，Java 25）
> 当前版本：**v1.2.0** · 七大板块 + 市场价值锚点体系；死亡保险已移除

## 1. 设计原则

- **绿宝石是唯一通货**：不做以物易物。所有挂单价格、成交结算、税费均以绿宝石计；绿宝石是背包中的真实物品（`Material.EMERALD`），不进虚拟账户。
- **托管式挂单（escrow）**：上架瞬间物品离开卖家背包、序列化进入系统存档，杜绝"卖出后手上还有一份"的复制漏洞；下架/成交才归还或交付。
- **原子成交**：`ACTIVE → SOLD` 迁移经 `ConcurrentHashMap.computeIfPresent` 原子完成，多买家并发抢单只有一个赢家；扣款或物品反序列化失败自动回滚占用并退款。
- **离线可交易**：卖家离线照常可被购买，收益进邮箱；买家背包满则物品进邮箱；`/ct collect` 一键领取（放不下的部分留在邮箱，不落地、不消失）。
- **Folia 线程纪律**：买卖双方各自的物品操作只在自己 entity 线程上执行（`Scheduler.onPlayer`，实体 retired 时自动走邮箱兜底）；跨玩家结算不阻塞任何 region 线程。
- **零第三方运行时依赖**：JSON 持久化用服务端自带 gson 2.13.2（与 libraries 运行时同版本），无 SQLite/ORM。

## 2. 业务流

```
卖家                                 市场系统                              买家
  │ /ct sell 64                           │                                   │
  ├── 手持物品 ──► 托管存档 ──► ACTIVE 挂单 │                                   │
  │                                       │ /ct market 浏览（分页 GUI）          │
  │                                       │ ◄──────────────────────────────────┤
  │                                       │ 点击挂单 → 确认界面（余额预检）      │
  │                                       │ ◄── 确认购买                        │
  │                                       ├─ 原子占用 ACTIVE→SOLD              │
  │                                       ├─ 扣买家绿宝石（失败→回滚）          │
  │                                       ├─ 物品交付买家（满→邮箱）            │
  │                                       └─ 收益(税后) → 卖家背包/邮箱         │
  │ 上线收绿宝石 / 离线进邮箱 ◄────────────┘                                   │
  └─ /ct collect 领取邮箱
```

命令树：`/ct market [页]`（浏览/购买）、`/ct sell <价格>`（手持物品上架）、`/ct my`（查看/下架）、`/ct collect`（领邮箱）、`/ct admin reload`。

## 3. 数据模型（`plugins/cycletrading/data/market.json`，Gson 序列化）

```jsonc
{
  "listings": [{
    "id": 1,                 // 单调递增，重启后 max+1 续
    "seller": "uuid",        // UUID 字符串
    "sellerName": "…",       // 展示名缓存
    "item": "Base64…",       // ItemStack.serializeAsBytes()
    "price": 64,             // 绿宝石
    "createdAt": 1750000000000,
    "status": "ACTIVE",      // ACTIVE | SOLD | CANCELLED
    "buyer": null            // SOLD 时记录
  }],
  "mailbox": [{
    "owner": "uuid",
    "item": "Base64…",       // 与 emeralds 至少一项有效
    "emeralds": 0,
    "createdAt": 1750000000000
  }]
}
```

- 内存态：`ConcurrentHashMap<Long,Listing>` + `CopyOnWriteArrayList<MailEntry>`；
- 落盘：单线程 IO 执行器，每次变更异步写；写路径 `tmp + ATOMIC_MOVE`；禁用时同步 flush；损坏存档自动改名 `.corrupt-<ts>` 并空载启动。

## 4. 代码结构

```
src/main/java/com/cycletrading/
├── CycleTradingPlugin.java    入口：装配 Storage/Market/GUI/命令，配置访问器
├── core/
│   ├── Listing.java           挂单模型（纯数据，Gson 友好）
│   ├── MailEntry.java         邮箱条目
│   ├── Items.java             物品 ↔ Base64；绿宝石计数
│   ├── Market.java            核心业务：create/tryBuy/cancel/collect + 原子状态机
│   └── Storage.java           JSON 加载/异步落盘/flush
├── sched/Scheduler.java       Folia entity/async 调度封装（离线兜底回调）
├── gui/GuiManager.java        市场分页 GUI / 确认购买 / 我的挂单 + 点击路由
└── command/CycleTradingCommand.java   /ct 命令树 + Tab 补全
```

## 5. 一致性 & 安全要点

| 风险 | 对策 |
|---|---|
| 复制物品 | 上架即托管（物品离包入档），唯一出口是成交交付或下架归还 |
| 并发抢单 | `computeIfPresent` 原子状态迁移，单赢家 |
| 扣款失败/存档损坏 | 占用回滚 + 退款；`ERROR` 路径保证买卖双方无损 |
| 跨线程物品操作 | 全部走目标玩家 entity 线程；离线自动邮箱 |
| 丢档 | 每次变更异步原子写；停机 flush；损坏文件保留副本 |
| 定价异常 | `min-price`/`max-price` 边界校验；价格单位绿宝石 |
| 刷绿宝石 | 结算金额 = 买家实付 − 税，税后收益由公式统一导出 |

## 6. 配置（`plugins/cycletrading/config.yml`）

```yaml
tax-percent: 0.0          # 成交税（%），0 = 完全自由贸易
min-price: 1              # 价格下限（绿宝石）
max-price: 100000000      # 价格上限（绿宝石，虚拟结算支持大额）
bank:
  max-balance: 999999999999  # 账户余额上限（存款/后台设置受限；市场收益入账不受限）
  ledger-keep: 5000          # 审计流水保留条数
```

## 7. 模块二：银行（虚拟绿宝石数据库）

**动机**：实物绿宝石最大堆叠 64（绿宝石块也仅 9×64），大额交易受背包空间与堆叠双重限制。银行提供虚拟余额（`long`），虚拟绿宝石与实物具有同等交易效力，由服主/后台管理。

### 7.1 核心规则

| 操作 | 说明 |
|---|---|
| `/ct bank` | 查看余额 + 背包实物 + 冻结状态 |
| `/ct bank deposit [数量\|all]` | 实物 → 虚拟（默认全部；受余额上限约束） |
| `/ct bank withdraw <数量\|all>` | 虚拟 → 实物（按背包空间自动部分提取，未提取部分留在银行） |
| `/ct bank send <玩家> <数量>` | 虚拟转账（离线亦可收） |
| 后台管理 | `view / set / add / remove / freeze / unfreeze / ledger`（需 `cycletrading.admin`） |

### 7.2 市场结算集成（虚拟优先）

- **买家支付**：银行余额 ≥ 价格 → 虚拟扣款；否则实物绿宝石兜底（保持"同实物交易效力"）；冻结账户禁止购买。
- **卖家收益**：税后收益直接入账银行（`SELL` 流水）——彻底摆脱 64 堆叠与背包空间问题；离线卖家照常收款。
- **失败回滚同源退还**：虚拟支付失败/物品反序列化失败时，退款回到原支付渠道（虚拟 → `REFUND` 入账；实物 → 背包返还）。

### 7.3 一致性与审计

- 余额变更全部在 `ConcurrentHashMap.compute` 内原子完成，并同步追加流水（类型 + 金额 + **变更后余额** `balanceAfter`，可逐条对账）；
- 流水容量 `ledger-keep`（默认 5000，先进先出）；管理员可 `ledger <玩家> [条数]` 追查；
- 冻结语义：禁止存款/取款/转账/市场购买，但**允许收益入账**（不阻断他人交易闭环）；
- 存款/取款在玩家 entity 线程执行；`credit`/`tryDebit` 为纯数据操作，任意线程安全。

### 7.4 数据模型（`plugins/cycletrading/data/bank.json`）

```jsonc
{
  "accounts": [{
    "owner": "uuid", "name": "…",
    "balance": 100400,        // long，无堆叠上限
    "frozen": false,
    "createdAt": 1786739596296, "updatedAt": 1786739596490
  }],
  "ledger": [{
    "id": 1, "ts": 1786739596297,
    "type": "ADMIN_SET",       // DEPOSIT/WITHDRAW/SEND_OUT/SEND_IN/BUY/SELL/REFUND/ADMIN_*/FREEZE/UNFREEZE
    "owner": "uuid", "counterpart": null,
    "amount": 100000, "balanceAfter": 100000
  }]
}
```

## 8. 模块三：奢侈品商店（珍稀物品交易）

**定位**：奢侈品性质珍稀物品交易。**仅管理员可挂售**（供给受控）；成交价由**管理员基础定价 + 系统按银行全体玩家总存量加权**动态得出，经济膨胀时价格自动上升，保证稀缺性。

### 8.1 动态定价公式

```
成交价 = max(基础定价, round(基础定价 × 倍率))
倍率   = 1 + 全服玩家银行总存量 ÷ 定价锚点（受 max-multiplier 上限约束）
```

- `supply-anchor` 默认 1,000,000：总存量 = 锚点时倍率为 2×；总存量 0 时保底 1×（即基础价）；
- `max-multiplier` 默认 100×，防止价格失控；
- 总存量由银行原子计数器 O(1) 实时维护（不含国库），买入即减少存量、压低后续倍率 → 系统自我稳定。

### 8.2 经济闭环（货币回收池）

- 成交款进入**系统国库**（`Bank.SYSTEM` 账户，不计入玩家总存量）→ 奢侈品消费是**货币回收池**，抑制通胀；
- 国库对管理员只读（`/ct bank admin view SYSTEM`、`ledger SYSTEM` 可查账），禁止 set/add/remove/freeze、禁止转账进出；
- 买家支付与市场一致：银行余额优先，实物兜底；价格可超 int 上限（虚拟长整型结算）。

### 8.3 命令

| 命令 | 说明 |
|---|---|
| `/ct lux [页]` | 打开奢侈品商店 GUI（全体玩家） |
| `/ct lux status` | 查看经济总存量、定价锚点、当前倍率（管理员额外显示国库） |
| `/ct lux list <基础价>` | 手持珍稀物品挂售（**仅管理员**） |
| `/ct lux remove <编号>` | 下架（仅管理员，物品归还背包/邮箱） |

GUI 中每件商品显示：基础定价、当前成交价、当前倍率、挂售管理员；确认界面显示实时价格与余额。

### 8.4 数据模型（`plugins/cycletrading/data/luxury.json`）

```jsonc
{ "listings": [{
  "id": 1, "item": "Base64…",
  "basePrice": 10000,        // 管理员基础定价
  "listedBy": "admin",
  "createdAt": 1750000000000,
  "status": "ACTIVE",        // ACTIVE | SOLD | CANCELLED
  "buyer": null, "soldAt": 0, "soldPrice": 0   // 实际成交价（审计）
}]}
```

## 9. 模块四：定档死亡保险（已于 v1.1.0 移除）

> **已删除**（v1.1.0）：代码、命令、配置、存档处理全部移除；旧 `insurance.json` 可手动删除。
> 移除原因：冗余；该 fork 的死亡掉落/保留机制与保险回滚存在兼容性风险（详见发布记录 v0.1.0）。

**定位**：服务器关闭死亡不掉落后，玩家提前用绿宝石购买定档保单，死亡时按档位回滚物品。保费统一由管理员在 `config.yml` 规定，构成经济闭环：**保费 → 国库（回收）；档位 4 补偿 ← 国库（出账）**。

### 9.1 档位表（默认价格，可配置）

| 档位 | 保费 | 回滚范围（按掉落表槽位） |
|---|---|---|
| 1 | 10 绿宝石 | 快捷栏（0-8，9 格） |
| 2 | 20 绿宝石 | 快捷栏 + 第一排（0-17，18 格） |
| 3 | 40 绿宝石 | 全部物品栏 + 快捷栏（0-35，36 格） |
| 4 | 64 绿宝石 | **完全回滚**（0-40，含盔甲/副手）+ **10 绿宝石补偿入虚拟账户** |

- 保单**单次有效**：死亡触发回滚后即消耗，需重新投保；重复购买覆盖旧保单。
- 投保支付：银行余额优先，背包实物兜底；保费记 `INSURANCE_PAID`（玩家）/`INSURANCE_PREMIUM`（国库）流水。

### 9.2 回滚机制（防丢失）

```
死亡(PlayerDeathEvent, 玩家region线程)
  ├─ 消费保单（无保单→不干预）
  ├─ 按档位把受保物品从掉落表摘离 → 持久化托管（insurance.json deathStashes）
  │    —— 摘离后物品不会落地，岩浆/虚空死亡同样安全
  └─ 档位4：银行.credit(10绿宝石, INSURANCE流水)（出国库）

重生(PlayerRespawnEvent, 玩家region线程)
  ├─ 取出托管 → 按【原槽位】还原（保快捷栏/第一排布局）
  ├─ 槽位被占 → 入背包；背包满 → 邮箱（/ct collect）
  └─ 提示回滚件数（档位4附补偿说明）
```

- 托管持久化：服务器在死亡与重生之间崩溃/重启，物品不丢（重启后重生存量照常还原）；
- 与 `keepInventory=true` 兼容（掉落为空时仅档位 4 补偿生效）；
- Folia 线程纪律：死亡/重生事件本身就在玩家 region 线程，物品操作直接安全。

### 9.3 命令

| 命令 | 说明 |
|---|---|
| `/ct ins` | 查看档位价格表与当前保单 |
| `/ct ins buy <1-4>` | 购买/覆盖保单 |
| `/ct ins admin view <玩家>` | 查看保单（管理员） |
| `/ct ins admin set <玩家> <1-4\|0>` | 设置/清除保单（管理员，0=清除） |

### 9.4 数据模型（`plugins/cycletrading/data/insurance.json`）

```jsonc
{
  "policies": [{ "owner": "uuid", "name": "…", "tier": 2, "premium": 20, "createdAt": 1786740786611 }],
  "stashes": [{
    "owner": "uuid", "name": "…", "tier": 3, "createdAt": 1786740786611,
    "items": [{ "slot": 5, "item": "Base64…" }]   // 原槽位 + 物品
  }]
}
```

## 10. 模块五：邮箱系统（独立化）

**定位**：从市场模块剥离为独立服务。**只收不存**——仅系统投递（市场购买溢出/奢侈品交付溢出/保险回滚溢出），玩家无法存入物品；每玩家**储量上限 27 条**。

### 10.1 容量纪律（满箱兜底策略）

| 投递路径 | 满箱（27/27）行为 |
|---|---|
| 市场购买 / 奢侈品购买 | **前置空间检查**：背包放得下 或 邮箱未满才允许成交；两者皆无 → `NO_SPACE` 拒绝购买（先付款后交付的崩溃窗口被消除） |
| 市场/奢侈品下架 | 同样前置检查，无空间则拒绝下架、挂单保持 ACTIVE |
| 死亡保险回滚 | 物品是玩家的，**绝不丢物**：满箱时剩余物品留在死亡托管（insurance.json），清理后 `/ct collect` 自动重试 |

### 10.2 领取方式

- `/ct mail` —— 邮箱 GUI（27 格 = 储量上限，来源/时间标注），**点击单件领取**；
- `/ct collect` —— 一键领取邮箱全部 + 保险托管重试；放不下的部分保留原地（领取而非存储）。

### 10.3 数据模型（`plugins/cycletrading/data/mailbox.json`，独立存档）

```jsonc
{ "entries": [{
  "owner": "uuid", "item": "Base64…", "emeralds": 0,
  "createdAt": 1786740786611, "source": "MARKET"   // MARKET | LUXURY | INSURANCE
}]}
```

- 旧版（模块一~四）market.json 内嵌的 mailbox 字段**自动迁移**至独立存档；
- 容量可在 `config.yml` 的 `mailbox.capacity` 调整（默认 27）。

## 11. 验证记录（2026-08-15）

- 离线编译：`gradlew build --offline` ✅
- EXP5Plus 实机：插件加载/启用/停用正常，`/ct help`、`/ct admin reload` 经 RCON 验证 ✅，配置落盘 ✅，无报错 ✅
- 银行后台链路 RCON 实测：view（无账户）→ set 100000 → add 500 → remove 100 → freeze → unfreeze → ledger（7 条流水含余额对账）✅；`bank.json` 落盘正确（balance 100400）✅；停机 flush 正常 ✅
- 奢侈品动态定价 RCON 实测：总存量 100,400 → 倍率 **1.100×**；注入 100 万 → 倍率 **2.100×**（公式验证 ✅）；国库只读保护 ✅；`luxury.json` 落盘 ✅
- 死亡保险 RCON 实测：admin set 档位4 → view 显示(保费64) → set 0 清除 → view 无保单 → set 档位2；`insurance.json` 落盘正确（tier 2, premium 20）✅；停机 flush 正常 ✅
- 邮箱模块 RCON 实测：`/ct mail`、`/ct collect` 玩家命令控制台正确拒绝 ✅；邮箱重构后旧数据（市场/银行/奢侈品/保险）全部兼容加载 ✅；`mailbox.json` 独立存档落盘 ✅
- 待补：真实玩家客户端的存取款/转账/买卖/奢侈品购买/**死亡回滚**/**邮箱容量 27 满箱拒绝购买**全流程 E2E

## 12. 模块六：定期债券（经济回收池）

**定位**：消耗玩家绿宝石的金融产品——本金锁死、仅虚拟交易、到期本息自动结算。

### 12.1 产品设计（五档）

| 档位 | 期限(游戏日) | 基础利率(单期) | 最低购买量 |
|---|---|---|---|
| 1 | 3 | 1.00% | 100 |
| 2 | 7 | 2.00% | 500 |
| 3 | 14 | 3.50% | 2,000 |
| 4 | 30 | 5.00% | 10,000 |
| 5 | 60 | 8.00% | 50,000 |

（全部可配置：`bond.t1~t5-days/rate/min`）

### 12.2 核心规则

- **利率锁定**：实际利率 = 基础利率 ×（1 + 全服玩家银行总存量 ÷ 利率锚点），封顶 `max-multiplier`（默认 3×），**购买瞬间锁定**存入债券；
- **退一法**：利率先取整到基点（floor），利息 = 本金 × 基点 ÷ 10000（长整型运算，天然向下取整）；
- **本金锁死**：购买即从银行余额扣走（`BOND_BUY` 流水），不可交易/转账/提取，并从玩家总存量中移除——连锁压低保值品价格与后续利率（自我稳定的回收池）；
- **仅虚拟**：只接受银行余额，无实物通道；最低购买量按档位校验；
- **游戏日**：期限按世界时间 24000 tick/天计；全局线程每 20 秒轮询到期队列，结算本息入账（`BOND_REDEEM` 流水），在线玩家即时通知；
- 到期结算在停服期间顺延：重启后轮询任务自动补结。

### 12.3 命令

| 命令 | 说明 |
|---|---|
| `/ct bond` | 我的债券 GUI（在持/已结算、剩余游戏日、锁定利率） |
| `/ct bond info` | 五档行情：期限/基础利率/实际利率/最低购买量 + 当前倍率 |
| `/ct bond buy <档位> <金额>` | 购买（利率按当前总存量锁定） |
| `/ct bond admin stats` | 全服在持笔数/总锁定金额（管理员） |
| `/ct bond admin view <玩家>` | 查看玩家债券（管理员） |

### 12.4 数据模型（`plugins/cycletrading/data/bonds.json`）

```jsonc
{ "bonds": [{
  "id": 1, "owner": "uuid", "name": "…",
  "tier": 2, "principal": 5000,
  "rateBp": 600,                 // 锁定利率基点（6.00%）
  "createdAt": 1786740786611,
  "boughtAt": 240000,            // 购买时世界时间
  "matureAt": 408000,            // 到期世界时间（=boughtAt+期限×24000）
  "status": "ACTIVE", "interest": 0, "redeemedAt": 0
}]}
```

## 13. 模块七：期货交割市场（大宗批量）

**定位**：面向大宗批量供应的标准期货市场。**标准化合约 + 全额保证金 + 交割日制度**，零违约风险。

### 13.1 标准合约（默认 9 个大宗品种，可配置）

| 品种 | 材料 | 数量 | 邮箱占用 |
|---|---|---|---|
| oak_log | OAK_LOG | 640（10 组） | 10 格 |
| coal_block | COAL_BLOCK | 320（5 组） | 5 格 |
| iron_block | IRON_BLOCK | 320（5 组） | 5 格 |
| gold_block | GOLD_BLOCK | 320（5 组） | 5 格 |
| redstone_block | REDSTONE_BLOCK | 320（5 组） | 5 格 |
| lapis_block | LAPIS_BLOCK | 320（5 组） | 5 格 |
| nether_quartz | QUARTZ | 64（1 组） | 1 格 |
| diamond_block | DIAMOND_BLOCK | 64（1 组） | 1 格 |
| netherite_block | NETHERITE_BLOCK | 64（1 组） | 1 格 |

### 13.2 标准期货原则落地

| 原则 | 实现 |
|---|---|
| **标准化合约** | 品种+数量固定；开仓必须手持**正好**标准数量，否则拒绝 |
| **全额保证金** | 卖方开仓托管全部商品（OPEN）；买方成交全额付款锁定（LOCKED）→ 零违约 |
| **交割日制度** | 期限从标准档（1/3/7/14/30 游戏日，可配置）选择；全局线程每 20 秒轮询 |
| **实物交割** | 到期商品**入买方邮箱**（Folia 全局线程纯数据安全）；邮箱格位不足自动重试（绝不丢物）；成交前买方须预留邮箱格位 |
| **货款结算** | 交割完成后卖方货款（税后）入银行（FUTURES_SELL） |
| **合同锁定** | 成交后不可退出；未成交前卖方可撤单（商品归还，无空间则拒绝撤单） |
| **管理员权力** | 强制交割 / 强制撤销（撤销=退款买家+商品退卖方邮箱） |

### 13.3 命令

| 命令 | 说明 |
|---|---|
| `/ct fut [页]` | 期货市场 GUI（合约品种/数量/价格/期限） |
| `/ct fut info` | 标准合约列表 + 可选交割期限 |
| `/ct fut open <价格> <期限>` | 手持标准数量商品开仓 |
| `/ct fut my` | 我的合约（卖方：撤单；买方：交割状态） |
| `/ct fut cancel <编号>` | 撤单（仅未成交） |
| `/ct fut admin stats/deliver/cancel` | 后台管理（仅管理员） |

### 13.4 数据模型（`plugins/cycletrading/data/futures.json`）

```jsonc
{ "contracts": [{
  "id": 1, "seller": "uuid", "sellerName": "…",
  "buyer": null, "buyerName": null,
  "item": "Base64…",          // 托管商品（交割重试时更新为剩余量）
  "price": 50000, "termDays": 3,
  "createdAt": 1786740786611,
  "lockedAt": 0, "matureAt": 0,
  "status": "OPEN",           // OPEN | LOCKED | DELIVERED | WITHDRAWN | CANCELLED
  "deliveredAt": 0
}]}
```

## 14. 模块八：期权市场（v1.1.0）

**定位**：标准期权原则的游戏化落地——欧式、现金结算、卖方全额保证金。

- **类型**：看涨 CALL / 看跌 PUT；标的 = 期货 9 个标准大宗品种（单位 = 整批合约）；
- **现金结算**（到期只结算钱，不动实物）：
  - CALL 赔付 = `min(max(0, S-K), K)`（封顶 = 行权价）；PUT 赔付 = `max(0, K-S)`；
- **权利金**：卖方定价，买方成交时付给卖方（买方只有权利、无义务，最多亏权利金）；
- **全额保证金**：卖方开仓即托管 K（最大赔付上限），到期赔付后余额退还 → 零违约；
- **结算价（方案A）**：期货近期成交均价（每品种保留最近 10 笔，期货交割时自动入库）
  → 管理员参考价（config `options.reference`）→ **无锚禁止挂卖**；
- 到期由全局线程每 20 秒轮询结算（纯数据，Folia 安全）；停服期间顺延，重启补结；
- 流水类型：`OPTION_OPEN / OPTION_PREMIUM / OPTION_PREMIUM_IN / OPTION_PAYOUT / OPTION_MARGIN_RETURN / OPTION_REFUND`。

**命令**：`/ct opt [页]`（市场 GUI）、`/ct opt help`（通俗指南）、`/ct opt info`（结算价与来源）、
`/ct opt open <call|put> <品种> <行权价> <权利金> <期限>`、`/ct opt my`、`/ct opt cancel <编号>`、
`/ct opt admin stats|settle|cancel`（管理员）。

**数据模型**（`plugins/cycletrading/data/options.json`）：

```jsonc
{ "contracts": [{
  "id": 1, "seller": "uuid", "sellerName": "…", "buyer": null, "buyerName": null,
  "type": "CALL", "commodity": "oak_log",
  "strike": 800, "premium": 50, "termDays": 3,
  "createdAt": 1786740786611, "lockedAt": 0, "matureAt": 0,
  "status": "OPEN",            // OPEN | LOCKED | SETTLED | WITHDRAWN | CANCELLED
  "settlementPrice": 0, "payout": 0, "settledAt": 0
}]}
```

## 15. 模块九：市场价值锚点（v1.2.0）

**动机**：玩家自由定价无参照（64 绿宝石买 63 绿宝石、27 绿宝石一盒牛排）。锚点**下放**：不设管理员价目表，改用游戏自带官方汇率 + 市场自我学习。

- **第一批基础价 = 村民交易表**：启动时枚举村民报价（13 职业 × 等级 1-5，临时实体读取后立即移除），仅注册纯绿宝石双边交易，折算为**毫绿宝石（mE）/件**单价（处理 20 小麦 = 1 绿宝石 这类分数价，实测注册 22 条目）；
  - 区块加载：本 fork 无常驻区块，用插件区块票强制加载出生点区块；失败则首个玩家加入时在其所在区域兜底注册；
- **动态调整**：市场每笔成交价进入滚动窗口（默认 10 笔）→ 锚点 = 成交均价（有成交）→ 村民基础价（无成交）→ 无锚；重启后学习窗口从 prices.json 恢复，村民表确定性重注册；
- **软区间**：挂单总价须落在 [参考总价 ÷ band, 参考总价 × band]（默认 2.0 = 0.5×~2×，0 = 不限制）；无锚物品自由定价；
- **通货禁挂**：绿宝石/绿宝石块禁止上架（存取/兑换走银行）；
- 展示：`/ct sell` 提示参考单价与来源（村民交易/市场成交均价）；市场 GUI 每条挂单显示参考价。

## 16. 发布记录

### v2.0.0（2026-08-19）—— 系统化重构

交易功能行为零变化（全模块 RCON 回归通过、JSON 存档零迁移），工程结构系统化：

- **util/**：`Money`（金额/毫绿宝石/基点利率统一格式化，消灭 3 处重复）、`MaturityQueue<T extends Matures>`（统一到期队列，债券/期货交割/期货头寸/期权四处轮询共用）
- **storage/**：`JsonRepository<T>` 泛型 JSON 仓储（加载/原子写/损坏隔离统一），`Storage` 瘦身为仓储注册表 + 快照编排（消灭 ~150 行重复代码）
- **command/**：1450 行巨类拆分为薄分发器 `CycleTradingCommand` + 十模块命令类（Market/Bank/Luxury/Bond/Futures/Options/Gold/Cb/Admin + `Cmd` 共享助手）
- 数据模型统一实现 `Matures` 接口

### v1.2.0（2026-08-15）—— 市场价值锚点

- 村民交易表注册基础价（22 条目实测）+ 市场成交价滚动学习 + 挂单软区间约束（默认 0.5×~2×）+ 绿宝石禁挂
- 价格学习数据独立存档 `prices.json`

### v1.1.0（2026-08-15）—— 期权 + 保险移除

- 新增：期权市场（欧式/现金结算/全额保证金/方案A结算价）
- 移除：死亡保险板块（代码/命令/配置/存档处理全部删除，旧 `insurance.json` 可手动清理）
- 期货联动：交割成交价自动入库 → 期权结算价来源
- 发布方式：**GitHub Actions 自动构建 + 打 tag 自动发布**（`.github/workflows/build-release.yml`）

### v1.0.0（2026-08-15）—— 首个正式版

- 打包：`release/cycletrading-1.0.0.zip`（jar + config.yml + DEPLOY.md）
- 启用：市场 / 银行 / 奢侈品商店 / 邮箱 / **定期债券** / **期货交割市场**（六大板块）
- 死亡保险暂缓（`insurance.enabled: false` 默认关闭）

### v0.1.0（2026-08-15）—— 四板块先行包

- 打包：`release/cycletrading-0.1.0.zip`（jar + config.yml + DEPLOY.md）
- 启用：市场 / 银行 / 奢侈品商店 / 邮箱
- **死亡保险暂缓**：`insurance.enabled: false` 默认关闭。原因与教训：
  1) 本 fork 的 `PlayerDeathEvent` 掉落表是 `TransformingRandomAccessList`，写入 null 再 `removeIf` 会 NPE（已定位）；
  2) `getItemsToKeep()` 机制在本 fork 会与地面掉落**双重结算**（反编译证实掉落消费者 `lambda$die$0` 无条件生成地面掉落）→ 复制漏洞；
  3) 后续启用前须回归：摘离掉落表（`remove(0)` 顺序比对）+ 持久化托管 + 重生原槽位还原方案，需真人实测无复制后再开启。

## 13. 路线图（后续增量）

1. **搜索/筛选/排序**（按物品名、价格区间、价格排序）
2. **拆栈上架**（当前整组上架）
3. **挂单过期机制** + **税费管理命令**
4. **成交记录/统计**（玩家流水，可审计）——银行 ledger 已具备逐笔对账能力
5. 多模块预留：NPC 商店、周期结算（Cycle 概念）、拍卖——均复用绿宝石通货与邮箱基础设施
