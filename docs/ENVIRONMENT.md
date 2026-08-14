# CycleTrading 开发环境档案

> 生成日期：2026-08-15 · 目标：经济/交易类功能插件（CycleTrading）
> 工作区：`F:\AzureCore\CycleTrading`

## 1. 目标服务端：AzureBranches EXP5Plus

| 项 | 值 |
|---|---|
| 服务端 jar | `F:\AzureCore\AzureBranches\folia-server\build\libs\azurebranches-server-26.1.2-AB-0002-EXP5Plus.jar`（54,181,339 B） |
| 类型 | Folia 下游派生（PaperMC Folia + 命令方块 OCC EXP 系统） |
| 内部版本 | `26.1.2-AB-0002-EXP5Plus`（version_history: `26.1.2-5-62dc0f2 (MC: 26.1.2)`） |
| 服务端运行目录 | 同目录（`config/`、`libraries/`、`plugins/`、`versions/`、`server.properties` 等） |
| patched NMS jar | `...\build\libs\versions\26.1.2\folia-26.1.2.jar`（29 MB，编译期 NMS 类路径） |
| 运行时库 | `...\build\libs\libraries\**\*.jar`（106 个：netty / authlib / gson 等） |
| 已装插件 | reasonmc-folia-0.1.0.jar（AI NPC）、spark、bStats —— **尚无经济类插件，绿地** |
| 启动 | `java -Xmx2G -jar azurebranches-server-26.1.2-AB-0002-EXP5Plus.jar nogui` |
| 关键配置 | 端口 25566；`online-mode=false`；RCON 25575；level-name `reasonmc-test`；eula 已接受 |

## 2. Minecraft 版本：26.1.2（Mojang 官方口径）

来源：Mojang 官方版本清单（实测抓取，非记忆）：

- 官方清单：`https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`
- 版本详情：`https://piston-meta.mojang.com/v1/packages/edcfd100a4856650b6e9797bac8f7fd76821979e/26.1.2.json`

| 项 | 值 |
|---|---|
| id | `26.1.2`，type = **release** |
| releaseTime | **2026-04-09T10:12:23Z**（清单收录时间 2026-08-12） |
| 最新正式版（抓取时） | **26.2**；最新快照 26.3-snapshot-8 |
| 官方要求 Java | **25**（`javaVersion.majorVersion=25`） |
| 官方服务端 jar | `https://piston-data.mojang.com/v1/objects/97ccd4c0ed3f81bbb7bfacddd1090b0c56f9bc51/server.jar` |
| assetIndex | `30`（sha1 aa83698c…） |
| minimumLauncherVersion | 21 |

> 本世界线 MC 采用日期式版本号；26.1.2 即官方 release 名称，直接以它为准。

## 3. 工具链（已核实可用）

| 组件 | 位置 | 说明 |
|---|---|---|
| JDK | `C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot`（Temurin 25.0.3 LTS，`JAVA_HOME` 已设） | 与 Mojang 官方要求的 Java 25 一致 |
| Gradle 9.4.1 | `C:\Users\admin\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1` | 本工程 wrapper 锁定 9.4.1，发行版已缓存 → **完全离线** |
| Gradle 9.6.1 | `E:\NumenFork\work\tools\gradle-9.6.1`（另有 `gradle-9.6.1-bin.zip` 备份） | 参考工程（NumenFork）使用的版本 |
| paper-api | `io.papermc.paper:paper-api:26.1.2.build.74-stable`（已缓存于用户级 Gradle 缓存，jar + pom 齐全） | 含 Folia 调度器 API（RegionizedServer / EntityScheduler / GlobalRegionScheduler） |

## 4. 参考开发环境：E:\NumenFork

- `E:\NumenFork\plugins\folia-plugin` —— **ReasonMC Folia 插件**（同版本、已验证可跑在 EXP4/EXP5 上的完整参考）：
  - `build.gradle`：java 插件 + Java 25 toolchain + `--release 25`；`compileOnly` paper-api + patched jar + libraries fileTree
  - `plugin.yml`：`api-version: "1.21"`、`folia-supported: true`
  - `Folia.java`：global/region/entity/async 四种调度封装（Folia 线程纪律样板）
  - 构建命令：`& E:\NumenFork\work\tools\gradle-9.6.1\bin\gradle.bat build`
- `E:\NumenFork\work\tools\`：`paper-api-26.1.2.jar`、`client-26.1.2.jar`（Fabric 侧）、loom 源码等
- `E:\NumenFork\BasicMod\`：Fabric mod 示例（numen-fabric-26.1.2-0.1.1.jar）
- 注意：NumenFork 主体是 Fabric/Loom 环境，**Folia 插件开发只参照 `plugins/folia-plugin`**

## 5. 本工程（F:\AzureCore\CycleTrading）

```
CycleTrading/
├── settings.gradle            rootProject = cycletrading
├── build.gradle               java + Java25 + paper-api + patched jar + libraries
├── gradle.properties          -Xmx2G, parallel, caching
├── gradlew / gradlew.bat / gradle/wrapper/   锁 Gradle 9.4.1（离线可用）
├── .gitignore
├── src/main/java/com/cycletrading/CycleTradingPlugin.java
└── src/main/resources/plugin.yml   api-version 1.21, folia-supported: true
```

构建（已验证成功，全离线）：

```powershell
cd F:\AzureCore\CycleTrading
.\gradlew.bat build --offline
# 产物: build\libs\cycletrading-folia-0.1.0-SNAPSHOT.jar
```

已验证：2026-08-15 离线构建 BUILD SUCCESSFUL，jar 内含 `plugin.yml` + 主类。

## 6. 注意事项 / 下一步

1. **网络**：pwsh 可访问 mojang.com；services.gradle.org 不可达 → 构建一律加 `--offline`（wrapper 已 `validateDistributionUrl=false`，不会联网校验）。新依赖需先确认已在本机缓存，或从其他机拷贝。
2. **Folia 线程纪律**：经济/交易涉及世界与实体操作时必须走 region/global/entity 调度（参考 ReasonMC 的 `Folia.java`），禁止跨线程直接触碰区块/实体。
3. **api-version**：参考插件用 `"1.21"` 且已在 EXP4/EXP5 实测运行；AzureBranches 的 `gradle.properties` 亦提供 `apiVersion=26.1.2` 备选。
4. **NMS 使用**：patched jar（folia-26.1.2.jar）供 `net.minecraft.*` 编译期引用；运行时这些类由服务端提供，切勿打进插件。
5. 可选冒烟验证（未执行，需要时再做）：把 jar 放入服务端 `plugins\` 目录，按第 1 节启动服务端，观察控制台 `CycleTrading v... enabled`。
