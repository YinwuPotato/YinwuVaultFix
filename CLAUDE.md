# YinwuVaultFix —— 改代码前必读

## 这个插件干什么
让试炼宝库（trial vault）的「开过就不再给你开」玩家黑名单失效，实现**可重复开启**。

## 四条硬约束（违反任意一条都会重现线上 bug）

1. **绝不"点击时硬把 blockstate 拨成 ACTIVE"**。
   那会骗过 `canEjectReward` 的状态检查，但方块实体还在 EJECTING/UNLOCKING 循环里
   → 结果是【钥匙被消耗、宝库不给奖励】（1.0.0，实测连吞 5 把）。

2. **绝不在 `EJECTING` / `UNLOCKING` 期间修改宝库数据**（清黑名单也不行）。
   那是产出过程，改数据会扰乱原版流程 → 【第一次能开，之后永远开不出东西】（1.0.1）。
   正确做法：记一个「待清理」标记，等状态**离开**产出态再清。

3. **绝不拦 `EJECTING → INACTIVE`**。
   那是原版产出循环的必经之路（ACTIVE→EJECTING→INACTIVE→ACTIVE）。
   拦掉它宝库会**永远卡在 EJECTING**（1.0.3，实测卡 1 分钟以上）。
   `keep-active` 只允许拦「空闲 `ACTIVE → INACTIVE`」。

4. **`INACTIVE` 状态也必须清黑名单**。
   宝库变暗的原因就是「4 格内没有还没领过的玩家」，玩家在黑名单里就永远没资格、永远暗着
   → 怎么点都没反应（1.0.4 之前的表现）。清掉后下一 tick 它会自己亮起。

另：**不要给玩家任何提示**（规格要求，无音效无文字）。

## 原版判定链（26.3，字节码核对）
```java
canEjectReward(config, state)         // keyItem 非空 && state != INACTIVE
isValidToInsert(config, itemStack)    // isSameItemSameComponents 且 数量 >= 配置数量
serverData.hasRewardedPlayer(player)  // 命中 → 失败音效 + return（不扣钥匙）
items = resolveItemsToEject(...)      // = table.getRandomItems(params)（一次战利品表 roll）
if (items.isEmpty()) return;          // 空 → 什么都不做【且不扣钥匙】
config.keyItem().consume(...);        // 扣钥匙
serverData.addToRewardedPlayers(...); // 记名单
```
**推论**：钥匙不扣 + 无反应 = 卡在前四步之一；"没记名单" = 没走到最后一步。

## 构建
- Van 树用 `build-javac.bat`（需 canvas-api；本机无 `Van\libraries` 时用 .buildcache 兜底）
- 手动等价命令：`javac -encoding UTF-8 --release 21 -proc:none -cp <canvas-api;canvas-26.3.jar;libraries\**\*.jar> -d target\classes <src>`
  然后 `jar --create --file X -C target\classes . -C src\main\resources .`
  ⚠️ 两个 `-C` 各需要一个 `.`，否则会打成 `target\classes\-C` 这种错误条目
- 版本号三处同步：`src\main\resources\plugin.yml` 的 `version`、jar 文件名、（无 pom 参与）

## 关键 API 备忘
- 状态枚举是 **`org.bukkit.block.data.type.Vault.State`**（`org.bukkit.block.Vault` 是方块实体接口，没有 State）
- `block.getState(false)` = 非快照（活体）方块实体；快照路径要 `update(false, false)`，**不要**用 `update(true, …)`（会把旧 blockstate 写回）
- `VaultChangeStateEvent` 可取消（`setCancelled`）；`PlayerKickEvent.Cause` 也有专门枚举值
- `Player#getLuck()` 在 26.3 的 API 里不存在（LootContext 不要设 luck）