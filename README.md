# YinwuVaultFix

Van 服（Canvas 26.3 / Folia 系）插件：**让试炼宝库（trial vault）的"开过就不再给你开"玩家黑名单失效**，同一个宝库可以反复开。

> 你提到 CarpetTIS Addition 里有对应配置项 —— 那是 Fabric 侧的 Carpet 扩展，Canvas 是 Paper 系（Bukkit）核心，装不了 Carpet。
> 所以这里用插件在服务端实现同样的效果：把原版那条"已获奖玩家"判定绕过去。

## 1. 黑名单到底是什么（从你线上 jar 反编译核对，不是猜的）

26.3 的判定链在 `net.minecraft.world.level.block.entity.vault.VaultBlockEntity$Server#tryInsertKey`：

```java
canEjectReward(config, state)          // 方块状态必须是 ACTIVE，否则 VaultBlock#useItemOn 直接返回
isValidToInsert(config, itemStack)     // 手上必须是宝库钥匙（普通 trial_key / 不祥 ominous_trial_key）
serverData.hasRewardedPlayer(player)   // ← 黑名单：命中就 playInsertFailSound(VAULT_REJECT_REWARDED_PLAYER) 然后 return
resolveItemsToEject(...)               // 正常吐战利品，并把玩家写进 rewardedPlayers
```

那个"黑名单"就是每个宝库方块实体里的 `VaultServerData.rewardedPlayers`（`Set<UUID>`）。
副带效果：原版还会把已获奖玩家从 `VaultSharedData.connectedPlayers` 里排除，所以附近玩家都开过之后，宝库会变成 `INACTIVE`（变暗、不显示物品）——这也是玩家"开不了了"的直观感受来源。

**好消息**：Canvas 的 API 直接暴露了这块数据，插件完全不用碰 NMS：

```java
org.bukkit.block.Vault                 // TileState
  Collection<UUID> getRewardedPlayers();
  boolean removeRewardedPlayer(UUID);  // ← 我们要的
  boolean addRewardedPlayer(UUID);
  boolean hasRewardedPlayer(UUID);
  Set<UUID> getConnectedPlayers();
io.papermc.paper.event.block.VaultChangeStateEvent
org.bukkit.block.data.type.Vault       // INACTIVE / ACTIVE / UNLOCKING / EJECTING + isOminous()
```

## 2. 插件怎么做的

| 时机 | 动作 | 为什么有效 |
|---|---|---|
| 玩家右键宝库（`PlayerInteractEvent`） | 把该玩家从 `rewardedPlayers` 里删掉；若宝库当前是 `INACTIVE`，先把方块状态拨回 `ACTIVE` | Bukkit 事件在 `ServerPlayerGameMode#useItemOn` 里**早于方块逻辑**触发（字节码偏移 120 处 `callPlayerInteractEvent`，方块逻辑在其后），所以同一次点击里的 `hasRewardedPlayer` 已经是 `false`；`ACTIVE` 是原版进 `tryInsertKey` 的前置条件 |
| 宝库状态变化（`VaultChangeStateEvent`） | 继续清空该宝库的名单 | 名单一直空着，玩家不会被排除出 `connectedPlayers`，宝库不会变暗，随时可再开 |

**不变的部分**：钥匙照样每次消耗（一次开箱一把钥匙）、战利品表不动、宝库外观/位置不动。变的是"你已经领过了"这条限制没了。

线程安全：只用事件回调、不注册任何调度器，事件本身在方块所属 region 线程上触发，因此 Folia / Canvas 下天然安全（`plugin.yml` 里已声明 `folia-supported: true`）。

## 3. 文件与构建

```
van-vaultfix/
├─ build-javac.bat                        # 双击构建（需要 JDK 25）
├─ src/main/java/io/yinwu/vaultfix/VaultFixPlugin.java
└─ src/main/resources/
   ├─ plugin.yml                          # name/api-version '26.3'/folia-supported
   └─ config.yml                          # 释放到 plugins/YinwuVaultFix/config.yml
```

**构建坑（已踩过）**：`canvas-api-26.3.build.947-alpha.jar` 自己的类引用了 adventure / guava / annotations / bungee-chat，
只把 API jar 丢给 `-cp` 会报一堆"程序包 org.bukkit 不存在"。`build-javac.bat` 已经把 `..\Van\libraries\` 下**全部** jar 拼进 classpath（运行时也是这么加载的）。

手工构建（不想用 bat 时）：

```bat
cd /d <本项目目录>
set "API=..\Van\libraries\io\canvasmc\canvas\canvas-api\26.3.build.947-alpha\canvas-api-26.3.build.947-alpha.jar"
set "CP=%API%"
for /r "..\Van\libraries" %%F in (*.jar) do call set "CP=%%CP%%;%%F"
javac -encoding UTF-8 --release 21 -proc:none -cp "%CP%" -d out src\main\java\io\yinwu\vaultfix\VaultFixPlugin.java
jar --create --file yinwu-vaultfix-1.0.0.jar -C out . -C src\main\resources .
```

## 4. 安装与生效

1. ✅ jar 已放入 `Van\plugins\yinwu-vaultfix-1.0.0.jar`（7,384 字节，SHA-256 `6D6D83E7E4C3F044D205FDB088B339327FF5B3F133F0F1D8364786A544B863F3`）
2. ⏳ **需要重启 Van 才会加载**（Bukkit 插件只在启动时加载）
   - Van 的 `start.bat` 已经带自动重启循环，所以在 Van 控制台敲 `stop` 就行：约 5 秒后它自己回来，启动完约 10 秒。
3. 启动日志里应出现：`[YinwuVaultFix] 启用：普通宝库=true，不祥宝库=true，点击时拉回ACTIVE=true，状态变化清名单=true，生效权限=（所有人）`
4. 配置在 `Van\plugins\YinwuVaultFix\config.yml`，改完用 `/vaultfix reload` 即时生效（无需重启）

## 5. 配置（`Van\plugins\YinwuVaultFix\config.yml`）

| 键 | 默认 | 说明 |
|---|---|---|
| `vault-types` | `both` | `both` = 普通 + 不祥；`normal` = 只普通；`ominous` = 只不祥 |
| `force-active-on-click` | `true` | 点击时若宝库被黑名单冻成 `INACTIVE`，先拨回 `ACTIVE`，让这一次点击就能开。`false` 则这种宝库要点第二次 |
| `clear-on-state-change` | `true` | 状态变化时清名单，宝库不会变暗、随时可开 |
| `permission` | 空 | 留空 = 所有玩家都享受；填 `yinwu.vaultfix.use` = 只给有该权限的玩家（LuckPerms 授权即可） |
| `debug` | `false` | 每次清名单在控制台打一行日志 |

## 6. 验收

```
/vaultfix inspect      # 对着宝库执行：类型 / 状态 / 黑名单人数 / 你在不在名单里 / 在场玩家数
/vaultfix reload       # 重载配置
```

推荐流程：

1. 重启 Van 后，`/vaultfix inspect` 对着一个**从没开过**的宝库 → 黑名单人数应为 0
2. 用普通钥匙开一次 → 拿到战利品（原版行为：这时你会被记进名单）
3. 手里再拿一把钥匙，**再点同一个宝库** → 应该还能开（不加这个插件时，这里会被拒合并播放 `VAULT_REJECT_REWARDED_PLAYER` 音效）
4. 再 `/vaultfix inspect` → 黑名单人数回到 0（或 1，取决于是否正好在状态切换的瞬间查看）；宝库不应长期变暗
5. 不祥宝库同理，需要先喝不祥之瓶拿"不祥试炼之兆"（那是原版条件，本插件不动它）

临时停用：`/vaultfix reload` 前把 `config.yml` 的 `permission` 填一个没人有的权限，或直接删掉 jar 重启。

## 7. 已知边界

- **只影响"你已领过"这条判定**，钥匙消耗、战利品表、不祥之兆要求全部保持原版。
- `force-active-on-click` 会临时把方块状态写成 `ACTIVE`，宝库自己的 ticker 下一 tick 会按真实数据重算，不会留下脏状态；但它会顺带触发一次 `VaultChangeStateEvent`。
- 配了 `permission` 时，"状态变化清名单"只清**在线且拥有该权限**的玩家（离线玩家的旧记录会在他们下次点击时清掉）。
- 默认配置下这是**全员无限制刷宝库**（普通+不祥都能无限开）。如果你只想给自己/某个组用，务必配 `permission` + LuckPerms。
- 宝库密集区（试炼密室）反复开箱会让掉落物变多，注意清理/性能；插件本身不产生额外 tick 开销（只在点击和状态变化时工作）。

## 8. 回滚

删除 `Van\plugins\yinwu-vaultfix-1.0.0.jar`（`Van\plugins\YinwuVaultFix\` 配置目录可留可删）→ 重启 Van。行为立刻恢复成原版：每个玩家对每个宝库只能领一次。

已开过的宝库不会因为卸载插件而"复原"——插件只是不再清名单而已；名单里当时有谁，就还是只有谁被拒。


---

# 版本历史与四个坑（每个都有线上日志实锤）

> ⚠️ 这一节是**防回退**用的。下面四条任意一条被"优化"掉，都会重现对应的 bug。

## 状态机（原版四态循环）

```
ACTIVE ──插钥匙成功──→ EJECTING ──玩家拿走奖励──→ INACTIVE ──有资格玩家靠近──→ ACTIVE
                                                        ↑
                            注意：产出流程【必须】经过 EJECTING→INACTIVE
```

## 原版判定链（`VaultBlockEntity$Server#tryInsertKey`，字节码核对）

```java
canEjectReward(config, state)         // keyItem 非空 且 state != INACTIVE
isValidToInsert(config, itemStack)    // 同物品同组件 且 数量 >= 配置数量
serverData.hasRewardedPlayer(player)  // 命中 → playInsertFailSound 然后 return（不扣钥匙）
items = resolveItemsToEject(...)      // = table.getRandomItems(params)，就是一次战利品表 roll
if (items.isEmpty()) return;          // roll 空 → 什么都不做【且不扣钥匙】
config.keyItem().consume(...);        // 扣钥匙在这里
serverData.addToRewardedPlayers(...); // 记名单在最后（所以"没记名单"= 根本没走到这一步）
```

## 四个坑

| 版本 | 当时的做法 | 现象 | 根因 |
|---|---|---|---|
| 1.0.0 | 点击时**硬把 blockstate 拨成 ACTIVE** | **连续 5 把钥匙被吞、宝库不吐奖励** | 骗过了 `canEjectReward`，但方块实体还在 EJECTING/UNLOCKING 循环里 |
| 1.0.1 | **产出过程中就清黑名单** | 第一次能开，之后**永远**开不出东西（钥匙不扣） | 在 EJECTING/UNLOCKING 里改宝库数据，扰乱了原版产出流程 |
| 1.0.3 | 拦下**所有** `→INACTIVE` 转变 | 宝库**永远卡在 EJECTING**（实测 1 分钟以上） | `EJECTING→INACTIVE` 是产出循环的必经之路，拦掉就卡死 |
| 1.0.4 | 只处理 ACTIVE 与产出中，**漏了 INACTIVE** | 暗着的宝库**怎么点都没反应** | 玩家在黑名单里 → 永远没资格 → 宝库永远暗 → 点击被"不处理" |

## 当前（1.0.4）的正确行为

```
右键 → 读 blockstate 状态
  ├─ ACTIVE          → 立刻清空全部黑名单（原版接着产出）
  ├─ INACTIVE        → 立刻清空全部黑名单（玩家恢复资格 → 下一 tick 宝库自己亮起）
  └─ EJECTING/UNLOCK → 只记「待清理」标记，【绝不动数据】
                       等状态【离开】产出态（→ACTIVE 或 →INACTIVE）时执行清理

keep-active：只拦「空闲 ACTIVE → INACTIVE」（玩家在附近时不变暗）
             绝不拦 EJECTING/UNLOCKING → INACTIVE
玩家提示：无（规格要求）
```

## 诊断手段（排查同类问题首选）

```
/vaultfix inspect    → 状态 / 黑名单人数+UUID / 在场玩家 / 钥匙 /
                       展示位物品 / 战利品表 / 下次状态更新 /
                       试 roll 一次（用 Bukkit API 直接 roll，只读）/
                       待清理标记
                     报告【同时写进服务端日志】(玩家消息不进日志，远端排查靠它)
debug: true          → 点击/待清理/拦下 的详细日志（已限流：每键 30 秒一条）
```
