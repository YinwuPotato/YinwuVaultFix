# YinwuVaultFix

Van 服（Canvas 26.3 / Folia 系）插件：**让试炼宝库（trial vault）的"开过就不再给你开"玩家黑名单失效**，同一个宝库可以反复开。

> 你提到 CarpetTIS Addition 里有对应配置项 —— 那是 Fabric 侧的 Carpet 扩展，Canvas 是 Paper 系（Bukkit）核心，装不了 Carpet。
> 所以这里用插件在服务端实现同样的效果：把原版那条"已获奖玩家"判定绕过去。

**当前版本：1.1.0** | 作者：Yinwu

---

## 1. 黑名单到底是什么（从线上 jar 反编译核对，不是猜的）

26.3 的判定链在 `net.minecraft.world.level.block.entity.vault.VaultBlockEntity$Server#tryInsertKey`：

```java
canEjectReward(config, state)          // keyItem 非空 且 state != INACTIVE
isValidToInsert(config, itemStack)     // 同物品同组件 且 数量 >= 配置数量
serverData.hasRewardedPlayer(player)   // ← 黑名单：命中就 playInsertFailSound(VAULT_REJECT_REWARDED_PLAYER) 然后 return
items = resolveItemsToEject(...)       // = table.getRandomItems(params)，一次战利品表 roll
if (items.isEmpty()) return;           // roll 空 → 什么都不做【且不扣钥匙】
config.keyItem().consume(...);         // 扣钥匙在这里
serverData.addToRewardedPlayers(...);  // 记名单在最后（所以"没记名单"= 根本没走到这一步）
```

那个"黑名单"就是每个宝库方块实体里的 `VaultServerData.rewardedPlayers`（`Set<UUID>`）。
副带效果：原版还会把已获奖玩家从 `VaultSharedData.connectedPlayers` 里排除，所以附近玩家都开过之后，宝库会变成 `INACTIVE`（变暗、不显示物品）——这也是玩家"开不了了"的直观感受来源。

**好消息**：Canvas 的 API 直接暴露了这块数据，插件完全不用碰 NMS：

```java
org.bukkit.block.Vault                 // TileState
  Collection<UUID> getRewardedPlayers();
  boolean removeRewardedPlayer(UUID);
  boolean addRewardedPlayer(UUID);
  boolean hasRewardedPlayer(UUID);
  Set<UUID> getConnectedPlayers();
io.papermc.paper.event.block.VaultChangeStateEvent
org.bukkit.block.data.type.Vault       // INACTIVE / ACTIVE / UNLOCKING / EJECTING + isOminous()
```

> ⚠️ 状态枚举是 `org.bukkit.block.data.type.Vault.State`，**不是** `org.bukkit.block.Vault`（那是方块实体接口，没有 State）。

---

## 2. 插件怎么做的

### 状态机（原版四态循环）

```
ACTIVE ──插钥匙成功──→ EJECTING ──玩家拿走奖励──→ INACTIVE ──有资格玩家靠近──→ ACTIVE
                                                        ↑
                            注意：产出流程【必须】经过 EJECTING→INACTIVE
```

### 三条处理路径

| 时机 | 状态 | 动作 |
|---|---|---|
| 右键宝库（`PlayerInteractEvent`） | `ACTIVE` 或 **`INACTIVE`** | 立刻清空**全部**黑名单；`auto-unfreeze` 开启时顺带解冻 |
| 右键宝库 | `EJECTING` / `UNLOCKING` | **只记「待清理」标记，绝不动数据** |
| 状态变化（`VaultChangeStateEvent`） | 新状态离开产出态 | 执行之前记下的「待清理」 |
| 状态变化 | `ACTIVE → INACTIVE` | `keep-active` 为 true 时**取消**这次转变（只拦这一种） |
| 区块加载（`ChunkLoadEvent`） | — | `auto-unfreeze` 开启时解冻该区块内的宝库与试炼刷怪笼 |

**`INACTIVE` 也必须清**——这一点很反直觉但很关键：宝库变暗的原因就是"4 格内没有还没领过的玩家"，玩家在黑名单里就永远没资格、永远暗着，于是**怎么点都没反应**。清掉之后玩家恢复资格，原版 ticker 下一 tick 就会自己点亮它。

### `keep-active` 只拦「空闲 ACTIVE → INACTIVE」

必须放行 `ACTIVE→EJECTING→UNLOCKING→INACTIVE` 这条正常产出循环，否则动画会卡死。所以只拦"空闲时变暗"这一种。

**不变的部分**：钥匙照样每次消耗（一次开箱一把钥匙）、战利品表不动、宝库外观/位置不动、不祥之兆要求不动。变的是"你已经领过了"这条限制没了。

### 自动解冻（1.1.0）

世界被拷贝/回滚后，方块实体里存的是**旧世界更高的绝对游戏刻**，这类实体永远等不到那个时刻，表现为宝库**长冷却 / 永远暗着**。插件在区块加载时逐区块扫一遍，把"冻结"的宝库与试炼刷怪笼按需把 `nextStateUpdateTime` 归零。

### Folia 线程

事件回调本身在方块所属 region 线程上触发，天然安全。**唯一的调度器使用**是 `unfreeze` 扫描：用 `RegionScheduler` 逐区块派发，每个区块在自己的区域线程上跑。

**全流程不给玩家任何提示**（规格要求，无音效无文字）。

---

## 3. 文件与构建

```
van-vaultfix/
├─ build-javac.bat                          # 双击构建（需要 JDK 25）
├─ parent/pom.xml                           # 父 POM（无 Maven 构建时可忽略）
├─ src/main/java/io/yinwu/vaultfix/VaultFixPlugin.java
└─ src/main/resources/
   ├─ plugin.yml                            # name/api-version '26.3'/folia-supported
   └─ config.yml                            # 释放到 plugins/YinwuVaultFix/config.yml
```

**构建坑（已踩过）**：`canvas-api-26.3.build.947-alpha.jar` 自己的类引用了 adventure / guava / annotations / bungee-chat，
只把 API jar 丢给 `-cp` 会报一堆"程序包 org.bukkit 不存在"。`build-javac.bat` 已经把 `..\Van\libraries\` 下**全部** jar 拼进 classpath（运行时也是这么加载的）。

手工构建（不想用 bat 时）：

```bat
cd /d <本项目目录>
set "API=..\Van\libraries\io\canvasmc\canvas\canvas-api\26.3.build.947-alpha\canvas-api-26.3.build.947-alpha.jar"
set "CP=%API%"
for /r "..\Van\libraries" %%F in (*.jar) do call set "CP=%%CP%%;%%F"
javac -encoding UTF-8 --release 21 -proc:none -cp "%CP%" -d target\classes src\main\java\io\yinwu\vaultfix\VaultFixPlugin.java
jar --create --file yinwu-vaultfix-1.1.0.jar -C target\classes . -C src\main\resources .
```

> ⚠️ 两个 `-C` 各需要一个 `.`，否则会打成 `target\classes\-C` 这种错误条目。
> 版本号需三处同步：`src\main\resources\plugin.yml` 的 `version`、jar 文件名、（无 pom 参与）。

---

## 4. 安装与生效

1. 把 `yinwu-vaultfix-1.1.0.jar` 放进 `Van\plugins\`
2. **需要重启 Van 才会加载**（Bukkit 插件只在启动时加载）
   - Van 的 `start.bat` 已经带自动重启循环，所以在 Van 控制台敲 `stop` 就行：约 5 秒后它自己回来，启动完约 10 秒。
3. 启动日志里应出现（**注意前缀是 `[vaultfix]` 不是 `[YinwuVaultFix]`**）：

```
[vaultfix] 已启用：普通宝库=true，不祥宝库=true，取消INACTIVE转变=true，生效权限=（所有人），清理方式=等产出结束后清空全部，自动解冻=true，解冻阈值=37200 刻
```

4. 配置在 `Van\plugins\YinwuVaultFix\config.yml`，改完用 `/vaultfix reload` 即时生效（本插件不做任何缓存，无需重启）

---

## 5. 配置（`Van\plugins\YinwuVaultFix\config.yml`）

| 键 | 默认 | 说明 |
|---|---|---|
| `vault-types` | `both` | `both` = 普通 + 不祥；`normal` = 只普通；`ominous` = 只不祥。无法识别的值会警告并按 `both` 处理 |
| `keep-active` | `true` | 取消「空闲 `ACTIVE → INACTIVE`」转变，宝库不变暗、随时可开。**绝不拦产出循环里的 `→INACTIVE`** |
| `permission` | 空 | 留空 = 所有玩家都享受；填 `yinwu.vaultfix.use` = 只给有该权限的玩家（LuckPerms 授权即可） |
| `debug` | `false` | 点击 / 待清理 / 拦下 的详细日志（已限流：每键 30 秒一条） |
| `auto-unfreeze` | `true` | 区块加载时自动解冻被冻结的宝库与试炼刷怪笼 |
| `unfreeze-threshold-ticks` | `37200` | 判定"冻结"的游戏刻阈值（37200 刻 = 31 分钟） |

> 上表就是全部生效的键。**不在表里的键一律不被读取**——写了也无效，且不会有任何提示
> （下节两个键例外：它们会额外打印一条废弃警告）。

### ⚠️ 两个已废弃的键

如果你从旧版本升级过来，配置里可能还留着这两个键——**它们已失效，代码会打印警告并忽略**：

| 废弃键 | 为何废弃 |
|---|---|
| `force-active-on-click` | 点击时硬把 blockstate 拨成 `ACTIVE`。会骗过状态检查，但方块实体还在 EJECTING/UNLOCKING 循环里 → **钥匙被消耗、宝库不给奖励**（1.0.0 的 bug，实测连吞 5 把）。改用 `keep-active` |
| `clear-on-state-change` | 在产出过程中清黑名单会扰乱原版流程 → **第一次能开，之后永远开不出东西且钥匙不扣**（1.0.1）。现在固定为「等产出结束后清空全部」 |

---

## 6. 命令

| 命令 | 说明 | 权限 | 默认 |
|---|---|---|---|
| `/vaultfix inspect` | 对着宝库执行：类型 / 状态 / 黑名单人数+UUID / 在场玩家 / 钥匙 / 展示位物品 / 战利品表 / 下次状态更新 / 试 roll 一次（只读）/ 待清理标记。**报告同时写进服务端日志** | `yinwu.vaultfix.admin` | OP |
| `/vaultfix reload` | 重载配置 | `yinwu.vaultfix.admin` | OP |
| `/vaultfix unfreeze` | 扫描你所在世界的已加载区块，解冻所有被冻结的宝库（长冷却/永远暗着）。只能由玩家执行 | `yinwu.vaultfix.admin` | OP |

别名：`/vf`

> `config.yml` 里的 `permission` 与命令权限 `yinwu.vaultfix.admin` 是**两回事**：前者控制"谁能享受黑名单失效"，后者控制"谁能用管理命令"。

---

## 7. 验收

推荐流程：

1. 重启 Van 后，`/vaultfix inspect` 对着一个**从没开过**的宝库 → 黑名单人数应为 0
2. 用普通钥匙开一次 → 拿到战利品（原版行为：这时你会被记进名单）
3. 手里再拿一把钥匙，**再点同一个宝库** → 应该还能开（不加这个插件时，这里会被拒合并播放 `VAULT_REJECT_REWARDED_PLAYER` 音效）
4. 再 `/vaultfix inspect` → 黑名单人数回到 0；宝库不应长期变暗
5. 不祥宝库同理，需要先喝不祥之瓶拿"不祥试炼之兆"（那是原版条件，本插件不动它）

排查长冷却/永远暗着的宝库：`/vaultfix unfreeze`，然后看服务端日志。

临时停用：把 `config.yml` 的 `permission` 填一个没人有的权限，或直接删掉 jar 重启。

---

## 8. 已知边界

- **只影响"你已领过"这条判定**，钥匙消耗、战利品表、不祥之兆要求全部保持原版。
- 配了 `permission` 时，清理只针对**在线且拥有该权限**的玩家（离线玩家的旧记录会在他们下次点击时清掉）。
- 默认配置下这是**全员无限制刷宝库**（普通+不祥都能无限开）。如果你只想给自己/某个组用，务必配 `permission` + LuckPerms。
- 宝库密集区（试炼密室）反复开箱会让掉落物变多，注意清理/性能。插件在点击、状态变化、区块加载三种时机工作；`unfreeze` 扫描是一次性的人工动作。
- 已开过的宝库不会因为卸载插件而"复原"——插件只是不再清名单而已；名单里当时有谁，就还是只有谁被拒。

---

## 9. 回滚

删除 `Van\plugins\yinwu-vaultfix-1.*.jar`（`Van\plugins\YinwuVaultFix\` 配置目录可留可删）→ 重启 Van。
行为立刻恢复成原版：每个玩家对每个宝库只能领一次。

---

# 版本历史与四个坑（每个都有线上日志实锤）

> ⚠️ 这一节是**防回退**用的。下面四条任意一条被"优化"掉，都会重现对应的 bug。

| 版本 | 当时的做法 | 现象 | 根因 |
|---|---|---|---|
| 1.0.0 | 点击时**硬把 blockstate 拨成 ACTIVE** | **连续 5 把钥匙被吞、宝库不吐奖励** | 骗过了 `canEjectReward`，但方块实体还在 EJECTING/UNLOCKING 循环里 |
| 1.0.1 | **产出过程中就清黑名单** | 第一次能开，之后**永远**开不出东西（钥匙不扣） | 在 EJECTING/UNLOCKING 里改宝库数据，扰乱了原版产出流程 |
| 1.0.3 | 拦下**所有** `→INACTIVE` 转变 | 宝库**永远卡在 EJECTING**（实测 1 分钟以上） | `EJECTING→INACTIVE` 是产出循环的必经之路，拦掉就卡死 |
| 1.0.4 | 只处理 ACTIVE 与产出中，**漏了 INACTIVE** | 暗着的宝库**怎么点都没反应** | 玩家在黑名单里 → 永远没资格 → 宝库永远暗 → 点击被"不处理" |

## 当前（1.1.0）的正确行为

```
右键 → 读 blockstate 状态
  ├─ ACTIVE          → 立刻清空全部黑名单（原版接着产出）
  ├─ INACTIVE        → 立刻清空全部黑名单（玩家恢复资格 → 下一 tick 宝库自己亮起）
  └─ EJECTING/UNLOCK → 只记「待清理」标记，【绝不动数据】
                       等状态【离开】产出态时执行清理

keep-active：只拦「空闲 ACTIVE → INACTIVE」（玩家在附近时不变暗）
             绝不拦 EJECTING/UNLOCKING → INACTIVE
auto-unfreeze：区块加载时解冻 nextStateUpdateTime 落在未来的方块实体
玩家提示：无（规格要求）
```

## 诊断手段（排查同类问题首选）

```
/vaultfix inspect    → 状态 / 黑名单人数+UUID / 在场玩家 / 钥匙 /
                       展示位物品 / 战利品表 / 下次状态更新 /
                       试 roll 一次（用 Bukkit API 直接 roll，只读）/
                       待清理标记
                     报告【同时写进服务端日志】(玩家消息不进日志，远端排查靠它)
/vaultfix unfreeze   → 逐区块扫描解冻（长冷却/永远暗着的宝库）
debug: true          → 点击/待清理/拦下 的详细日志（已限流：每键 30 秒一条）
日志关键行           → "[vaultfix] … ★ 插入成功：状态进入 EJECTING"
                       出现这行 = 钥匙被接受（判定链四步全过）
                       不出现     = 插入被拒（卡在前四步之一）
```

---

## License | 许可证

LGPL-3.0 —— 见 [LICENSE](LICENSE)。
