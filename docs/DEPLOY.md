# CycleTrading v1.0.1 部署说明（修补版）

以【绿宝石】为唯一通货的 Minecraft 经济/交易插件（AzureBranches EXP5Plus / Folia，MC 26.1.2，Java 25）。

## 包内容

```
cycletrading-1.0.0/
├── cycletrading-folia-1.0.0.jar   插件本体
├── config.yml                     默认配置（首次启动自动生成到 plugins/cycletrading/）
└── DEPLOY.md                      本说明
```

## 适用环境

- AzureBranches **EXP5Plus**（Folia fork，MC 26.1.2）或兼容 Folia 服务端
- Java 25
- 无第三方运行时依赖（JSON 持久化使用服务端自带 gson）

## 安装

1. 将 `cycletrading-folia-1.0.0.jar` 放入服务端 `plugins/` 目录
2. 启动服务端（确认日志出现 `CycleTrading v1.0.0 enabled`）
3. 如需调整配置：编辑 `plugins/cycletrading/config.yml` 后执行 `/ct admin reload`（控制台或 OP）

## 已启用板块（六大板块）

| 板块 | 说明 | 入口 |
|---|---|---|
| **绿宝石挂单市场** | 玩家自由挂单/购买，绿宝石结算；上架即托管防复制；卖家收益入银行 | `/ct market` `/ct sell` `/ct my` |
| **银行** | 虚拟绿宝石数据库（突破 64 堆叠）；存取/转账/冻结/后台管理/审计流水 | `/ct bank ...` |
| **奢侈品商店** | 仅管理员挂售珍稀物品；成交价 = 基础价 × (1 + 总存量 ÷ 锚点) 动态加权 | `/ct lux ...` |
| **邮箱** | 只收不存（仅系统投递），每玩家储量上限 27；GUI 单件领取 | `/ct mail` `/ct collect` |
| **定期债券** | 五档定期（3/7/14/30/60 游戏日）；利率=基础×总存量倍率购买时锁定；退一法取整；本金锁死仅虚拟 | `/ct bond ...` |
| **期货交割市场** | 9 个标准大宗合约；全额保证金零违约；按游戏日到期实物交割入买方邮箱 | `/ct fut ...` |

> 死亡保险板块**暂缓发布**（`insurance.enabled: false`，代码保留）。开启前请先完成真机回归测试。

## 命令速查

```
/ct market [页]             市场分页 GUI
/ct sell <价格>             手持物品上架（整组出售）
/ct my                      我的挂单（点击下架取回）
/ct mail                    邮箱 GUI（27 格，点击单件领取）
/ct collect                 一键领取邮箱
/ct bank                    查看银行余额
/ct bank deposit [数量|all]  实物绿宝石存入
/ct bank withdraw <数量|all> 虚拟余额提取实物
/ct bank send <玩家> <数量>  虚拟转账
/ct lux [页]                奢侈品商店 GUI
/ct lux status              经济总存量 / 定价倍率
/ct lux list <基础价>        挂售奢侈品（仅管理员）
/ct lux remove <编号>        下架奢侈品（仅管理员）
/ct bond                    我的定期债券 GUI
/ct bond info               五档利率/期限/最低购买量
/ct bond buy <档位> <金额>   购买定期债券（仅虚拟余额）
/ct fut [页]                期货市场 GUI（标准大宗合约）
/ct fut info                标准合约品种与交割期限
/ct fut open <价格> <期限>    手持标准数量商品开仓
/ct fut my                  我的期货合约（撤单/交割状态）
/ct fut cancel <编号>        撤单（仅未成交）
/ct bank admin view|set|add|remove|freeze|unfreeze|ledger  后台管理（仅管理员）
/ct bond admin stats|view   债券管理（仅管理员）
/ct fut admin stats|deliver|cancel  期货管理（仅管理员）
/ct admin reload            重载配置（仅管理员）
```

权限：`cycletrading.use`（默认全员）/ `cycletrading.admin`（默认 OP）。

## 配置参考（config.yml 节选）

```yaml
tax-percent: 0.0          # 市场/期货成交税（%），0 = 自由贸易
min-price: 1              # 挂单价格下限（绿宝石）
max-price: 100000000      # 挂单价格上限

bank:
  max-balance: 999999999999  # 账户余额上限（市场收益入账不受限）
  ledger-keep: 5000          # 审计流水保留条数

luxury:
  supply-anchor: 1000000     # 定价锚点：总存量=锚点时倍率=2
  max-multiplier: 100.0      # 倍率上限
  max-base-price: 100000000  # 管理员基础定价上限

insurance:
  enabled: false             # 保险板块暂缓，默认关闭
  t1-price: 10 / t2-price: 20 / t3-price: 40 / t4-price: 64
  t4-compensation: 10

mailbox:
  capacity: 27               # 邮箱储量上限（条/玩家）

bond:
  enabled: true
  rate-anchor: 1000000       # 利率锚点
  max-multiplier: 3.0        # 利率倍率上限
  t1-days: 3  t1-rate: 1.0  t1-min: 100      # 五档：期限/基础利率/最低购买量
  t2-days: 7  t2-rate: 2.0  t2-min: 500
  t3-days: 14 t3-rate: 3.5  t3-min: 2000
  t4-days: 30 t4-rate: 5.0  t4-min: 10000
  t5-days: 60 t5-rate: 8.0  t5-min: 50000

futures:
  enabled: true
  terms: [1, 3, 7, 14, 30]   # 允许的交割期限（游戏日）
  contracts:                 # 标准合约（品种 = 材料 + 数量）
    oak_log:      { material: OAK_LOG, amount: 640 }
    coal_block:   { material: COAL_BLOCK, amount: 320 }
    iron_block:   { material: IRON_BLOCK, amount: 320 }
    gold_block:   { material: GOLD_BLOCK, amount: 320 }
    redstone_block: { material: REDSTONE_BLOCK, amount: 320 }
    lapis_block:  { material: LAPIS_BLOCK, amount: 320 }
    nether_quartz: { material: QUARTZ, amount: 64 }
    diamond_block: { material: DIAMOND_BLOCK, amount: 64 }
    netherite_block: { material: NETHERITE_BLOCK, amount: 64 }
```

## 数据文件（plugins/cycletrading/data/，JSON）

| 文件 | 内容 |
|---|---|
| `market.json` | 挂单（含托管物品） |
| `bank.json` | 账户 + 审计流水 + 国库 |
| `luxury.json` | 奢侈品挂单 |
| `mailbox.json` | 邮箱条目 |
| `bonds.json` | 定期债券 |
| `futures.json` | 期货合约（托管商品） |
| `insurance.json` | 保单 + 死亡托管（保险板块数据） |

- 每次变更异步原子落盘（tmp + 原子替换），停服时 flush；损坏存档自动改名 `.corrupt-<时间戳>` 保留
- **备份建议**：定期备份整个 `plugins/cycletrading/` 目录

## 已知事项

- 经济模型：卖家收益/奢侈品成交款/保费/期货货款全部走银行虚拟余额；奢侈品、保费与债券本金构成国库与流动性回收池
- Folia 线程纪律已内置：跨玩家结算经 entity 线程投递，离线自动走邮箱；期货交割/债券到期在全局线程轮询（纯数据操作）
- 待补：真实玩家客户端的全流程 E2E 回归（买卖/存取/满箱边界/债券到期/期货交割）；保险板块修复后另行发布
