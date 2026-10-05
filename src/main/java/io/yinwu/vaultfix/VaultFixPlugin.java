package io.yinwu.vaultfix;

import io.papermc.paper.event.block.VaultChangeStateEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Vault;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * YinwuVaultFix —— 让试炼宝库（trial vault）的「开过就不再给你开」玩家黑名单失效。
 *
 * <p>原版判定链（26.3 / {@code VaultBlockEntity$Server#tryInsertKey}，由字节码核对）：
 * <pre>
 *   canEjectReward(config, state)         // keyItem 非空 且 状态 != INACTIVE，否则 VaultBlock#useItemOn 直接返回
 *   isValidToInsert(config, itemStack)    // isSameItemSameComponents 且 数量 >= 配置数量
 *   serverData.hasRewardedPlayer(player)  // ← 黑名单：命中就 playInsertFailSound 然后返回
 *   items = resolveItemsToEject(...)      // ← 就是一次战利品表 roll：table.getRandomItems(params)
 *   if (items.isEmpty()) return;          // ← roll 空：什么都不做【且不扣钥匙】
 *   config.keyItem().consume(...);        // ← 扣钥匙在这一步之后
 *   serverData.addToRewardedPlayers(player);   // ← 记名单在最后
 * </pre>
 *
 * <h2>1.0.2 的行为规格（用户确认）</h2>
 * <b>清理范围 = 全部玩家；但必须等「当前钥匙的产出结束」再清。</b>
 * <ol>
 *   <li><b>右键时看状态</b>：ACTIVE → 立刻清空全部黑名单；EJECTING / UNLOCKING →
 *       <b>只记一个「待清理」标记，不动数据</b>（此刻正在吐当前钥匙的奖励）。</li>
 *   <li><b>产出结束时执行</b>：状态回到 ACTIVE 的那一刻，若有标记就清空全部黑名单。</li>
 *   <li><b>取消 INACTIVE 转变</b>：让宝库不变暗（{@link VaultChangeStateEvent} 可取消）。</li>
 * </ol>
 * 全流程**不给玩家任何提示**（规格要求）。
 *
 * <h2>⚠ 两个已经踩过的坑，别再改回去</h2>
 * <ol>
 *   <li><b>不要"点击时硬把 blockstate 拨成 ACTIVE"</b>（1.0.0 的 force-active-on-click）：
 *       它骗过了 {@code canEjectReward} 的状态检查，但方块实体还在 EJECTING/UNLOCKING 循环里，
 *       结果是【钥匙被消耗、宝库不给奖励】（实测连吞 5 把）。</li>
 *   <li><b>不要在产出过程中清黑名单</b>（1.0.0 / 1.0.1 的 clear-on-state-change）：
 *       在 EJECTING/UNLOCKING 里改宝库数据会扰乱原版产出流程，
 *       表现为【第一次能开、之后永远开不出东西，而且钥匙不扣】
 *       （因为 {@code resolveItemsToEject} 返回空 → 原版直接 return 且不扣钥匙）。</li>
 * </ol>
 *
 * <p>纯 Bukkit/Paper API（只用了 {@link LootTable} 的只读 roll 做诊断），不含 NMS、不做反射；
 * 只挂事件、不使用调度器，因此 Folia / Canvas 下天然线程安全（事件在方块所属 region 线程触发）。
 */
public final class VaultFixPlugin extends JavaPlugin implements Listener {

    /** 「待清理」标记的存活时间：超过就丢弃，防止永久挂着（规格：60 秒）。 */
    private static final long PENDING_TTL_MS = 60_000L;
    /** debug 日志的每键限流窗口（避免每 tick 刷屏）。 */
    private static final long DEBUG_THROTTLE_MS = 30_000L;

    // ---- config.yml ----
    private boolean normalVaults = true;
    private boolean ominousVaults = true;
    private boolean keepActive = true;
    private boolean autoUnfreeze = true;
    private long unfreezeThreshold = 37200L;
    private String permission = "";
    private boolean debug = false;

    /** 宝库坐标 → 记下「待清理」的时刻。等它回到 ACTIVE 再清空全部黑名单。 */
    private final Map<String, Long> pendingClear = new HashMap<>();
    /** 日志限流：键 → 上次输出时刻。 */
    private final Map<String, Long> lastLogAt = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("[vaultfix] 已启用：普通宝库=" + normalVaults
                + "，不祥宝库=" + ominousVaults
                + "，取消INACTIVE转变=" + keepActive
                + "，生效权限=" + (permission.isEmpty() ? "（所有人）" : permission)
                + "，清理方式=等产出结束后清空全部"
                + "，自动解冻=" + autoUnfreeze + "，解冻阈值=" + unfreezeThreshold + " 刻");
    }

    @Override
    public void onDisable() {
        pendingClear.clear();
        lastLogAt.clear();
        getLogger().info("[vaultfix] 已停用");
    }

    private void loadSettings() {
        reloadConfig();
        normalVaults = !"ominous".equalsIgnoreCase(vaultTypes());
        ominousVaults = !"normal".equalsIgnoreCase(vaultTypes());
        keepActive = getConfig().getBoolean("keep-active", true);
        autoUnfreeze = getConfig().getBoolean("auto-unfreeze", true);
        unfreezeThreshold = getConfig().getLong("unfreeze-threshold-ticks", 37200L);
        permission = String.valueOf(getConfig().getString("permission", "")).trim();
        debug = getConfig().getBoolean("debug", false);
        if (getConfig().isSet("force-active-on-click")) {
            getLogger().warning("[vaultfix] 配置里的 force-active-on-click 已废弃（会导致"
                    + "「钥匙被消耗但宝库不给奖励」），现已忽略；请改用 keep-active。");
        }
        if (getConfig().isSet("clear-on-state-change")) {
            getLogger().warning("[vaultfix] 配置里的 clear-on-state-change 已废弃（在产出过程中清名单会"
                    + "扰乱原版流程，导致之后再也开不出奖励）；现在固定为「等产出结束后清空全部」。");
        }
    }

    private String vaultTypes() {
        String raw = String.valueOf(getConfig().getString("vault-types", "both")).trim();
        if (raw.equalsIgnoreCase("normal") || raw.equalsIgnoreCase("ominous")) {
            return raw.toLowerCase(Locale.ROOT);
        }
        if (!raw.equalsIgnoreCase("both") && !raw.isEmpty()) {
            getLogger().warning("[vaultfix] vault-types 的值 \"" + raw
                    + "\" 无法识别（只接受 normal / ominous / both），已按 both 处理。");
        }
        return "both";
    }

    // ------------------------------------------------------------------
    // 1) 右键宝库
    //     ACTIVE          → 立刻清空全部黑名单
    //     EJECTING/UNLOCK → 只记「待清理」，等产出结束（规格要求）
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!appliesTo(block, player)) {
            return;
        }
        sweepPending();

        org.bukkit.block.data.type.Vault.State state = vaultState(block);
        String key = key(block);

        // ⚠ 1.0.3 遗漏了 INACTIVE —— 而它恰恰是最需要清的状态：
        //   宝库变暗的原因就是「4 格内没有还没领过的玩家」，玩家在黑名单里就永远没资格，
        //   于是一直暗着、怎么点都没反应。清掉之后玩家恢复资格，原版 ticker 下一 tick 就会点亮它。
        if (state == org.bukkit.block.data.type.Vault.State.ACTIVE
                || state == org.bukkit.block.data.type.Vault.State.INACTIVE) {
            // 先检查是否被"冻结"（存档里的游戏刻在未来）—— 冻结的宝库永远不更新状态。
            if (autoUnfreeze) {
                tryUnfreeze(liveVault(block), block);
            }
            // 宝库空闲：现在清最安全，原版流程马上就能用上干净的黑名单。
            int removed = clearAllRewarded(block);
            pendingClear.remove(key);
            debugThrottled("click:" + key, player.getName() + " 点击 " + describe(block)
                + "：状态=" + state
                + "，手持=" + player.getInventory().getItemInMainHand().getType()
                + "，效果=" + describeEffects(player)
                + "，距离=" + distanceTo(player, block) + " 格"
                + "，清空全部黑名单 " + removed + " 人");
        } else if (state == org.bukkit.block.data.type.Vault.State.EJECTING || state == org.bukkit.block.data.type.Vault.State.UNLOCKING) {
            // ⚠ 产出中：绝不动数据（1.0.1 就是在这里出的问题）。
            pendingClear.put(key, System.currentTimeMillis());
            debugThrottled("defer:" + key, player.getName() + " 点击 " + describe(block)
                    + "：状态=" + state + "（产出中）→ 记为待清理，等回到 ACTIVE 再清");
        } else {
            debugThrottled("click:" + key, player.getName() + " 点击 " + describe(block)
                    + "：状态=" + state + "，不处理（未知状态）");
        }
    }

    // ------------------------------------------------------------------
    // 2) 状态变化
    //     ACTIVE        → 执行「待清理」（产出已结束）
    //     INACTIVE      → 取消，宝库不变暗
    // ------------------------------------------------------------------
        // ------------------------------------------------------------------
    // 3) 区块加载：自动解冻（宝库 + 试炼刷怪笼）
    //    世界被拷贝/回滚后，方块实体里存的是旧世界更高的绝对游戏刻，
    //    这类实体永远等不到那个时刻 —— 走到哪修到哪，玩家一靠近就自动恢复。
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        if (!autoUnfreeze || event.isNewChunk()) {
            return;
        }
        unfreezeIn(event.getChunk(), null);
    }
@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onStateChange(VaultChangeStateEvent event) {
        Block block = event.getBlock();
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Vault data)) {
            return;
        }
        if (!vaultTypeEnabled(data.isOminous())) {
            return;
        }
        String key = key(block);

        // ★ 诊断：进入 EJECTING 就意味着【钥匙被接受了】（canEjectReward / isValidToInsert /
        //   hasRewardedPlayer / resolveItemsToEject 四步全过）。没有这一行 = 插入被拒。
        if (event.getNewState() == org.bukkit.block.data.type.Vault.State.EJECTING) {
            getLogger().info("[vaultfix] " + describe(block)
                    + " ★ 插入成功：状态进入 EJECTING（钥匙已被接受，开始产出战利品）");
        }
        // 只拦「变成 INACTIVE」：宝库变暗是因为原版把已获奖玩家从 connectedPlayers 里排除。
        // 必须放行 ACTIVE→EJECTING→UNLOCKING→ACTIVE 这条正常产出循环，否则动画会卡死。
        if (keepActive
                && event.getCurrentState() == org.bukkit.block.data.type.Vault.State.ACTIVE
                && event.getNewState() == org.bukkit.block.data.type.Vault.State.INACTIVE) {
            event.setCancelled(true);
            debugThrottled("keep:" + key, describe(block) + " 拦下 "
                    + event.getCurrentState() + "→" + event.getNewState() + "，保持可用");
        }

        // 产出结束（回到 ACTIVE）→ 执行之前记下的清理
        if (event.getNewState() != org.bukkit.block.data.type.Vault.State.EJECTING
                && event.getNewState() != org.bukkit.block.data.type.Vault.State.UNLOCKING) {
            Long since = pendingClear.remove(key);
            if (since != null) {
                int removed = clearAllRewarded(block);
                debugThrottled("done:" + key, describe(block) + " 产出结束（回到 ACTIVE）→ "
                        + "执行待清理，清空全部黑名单 " + removed + " 人"
                        + "（标记建立于 " + ((System.currentTimeMillis() - since) / 1000) + " 秒前）");
            }
        }
    }

    // ------------------------------------------------------------------
    // 命令：/vaultfix reload | inspect
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                loadSettings();
                sender.sendMessage(Component.text("[vaultfix] 配置已重载", NamedTextColor.GREEN));
                getLogger().info("[vaultfix] 配置已重载");
                return true;
            }
            case "unfreeze" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("[vaultfix] unfreeze 只能由玩家执行（扫描你所在世界的已加载区块）", NamedTextColor.RED));
                    return true;
                }
                unfreezeLoaded(player);
                sender.sendMessage(Component.text("[vaultfix] 已开始扫描已加载区块（每区块在自己的区域线程上跑），修复结果见服务端日志"
                        , NamedTextColor.GREEN));
                return true;
            }            case "inspect" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("[vaultfix] inspect 只能由玩家执行", NamedTextColor.RED));
                    return true;
                }
                inspect(player);
                return true;
            }
            default -> {
                sender.sendMessage(Component.text("[vaultfix] /" + label + " reload —— 重载配置", NamedTextColor.AQUA));
                sender.sendMessage(Component.text("[vaultfix] /" + label + " inspect —— 查看你正对的宝库状态与黑名单", NamedTextColor.AQUA));
                sender.sendMessage(Component.text("[vaultfix] /" + label + " unfreeze —— 扫描并解冻所有被冻结的宝库（长冷却/永远暗着）", NamedTextColor.AQUA));
                return true;
            }
        }
    }

    /**
     * 体检报告：状态 / 黑名单 / 展示位 / 战利品表能否 roll 出东西 / 下次状态更新时刻。
     * <b>结果同时写进服务端日志</b>（玩家消息不进日志，远端排查只能靠这个）。
     */
    private void inspect(Player player) {
        Block block = player.getTargetBlockExact(8);
        if (block == null || !(block.getBlockData() instanceof org.bukkit.block.data.type.Vault data)) {
            player.sendMessage(Component.text("[vaultfix] 请对着一个试炼宝库方块（8 格内）再执行", NamedTextColor.YELLOW));
            return;
        }
        List<String> report = new ArrayList<>();
        report.add("[vaultfix] " + describe(block));
        report.add("  类型：" + (data.isOminous() ? "不祥宝库" : "普通宝库")
                + "　状态：" + data.getVaultState());

        Vault vault = liveVault(block);
        if (vault == null) {
            report.add("  读不到方块实体（区块未加载？）");
        } else {
            var rewarded = vault.getRewardedPlayers();
            StringBuilder ids = new StringBuilder();
            int shown = 0;
            for (UUID id : rewarded) {
                if (shown++ == 3) {
                    ids.append("…");
                    break;
                }
                ids.append(ids.length() == 0 ? "" : ", ").append(id.toString(), 0, 8);
            }
            report.add("  黑名单人数：" + rewarded.size()
                    + (rewarded.contains(player.getUniqueId()) ? "（你在名单里）" : "（你不在名单里）")
                    + (ids.length() > 0 ? "　[" + ids + "]" : ""));
            report.add("  在场玩家：" + vault.getConnectedPlayers().size()
                    + "　钥匙：" + vault.getKeyItem().getType());
            report.add("  展示位物品：" + describeItem(vault.getDisplayedItem())
                    + "　展示位战利品表：" + vault.getDisplayedLootTable());
            report.add("  战利品表：" + vault.getLootTable()
                    + "　下次状态更新：" + vault.getNextStateUpdateTime());
            // 直接 roll 一次：判断「表本身能不能出东西」——这是排查"点了没反应"的关键数据
            report.add("  试 roll 一次：" + tryRoll(vault, block));
            report.add("  待清理标记：" + (pendingClear.containsKey(key(block)) ? "有（等待产出结束）" : "无"));
        }

        for (String line : report) {
            player.sendMessage(Component.text(line, NamedTextColor.GRAY));
            getLogger().info(line);
        }
    }

    /** 用 Bukkit API 直接 roll 一次战利品表（只读，不改变宝库状态）。 */
    private String tryRoll(Vault vault, Block block) {
        try {
            var table = vault.getLootTable();
            if (table == null) {
                return "战利品表为空对象 ✗";
            }
            LootContext context = new LootContext.Builder(block.getLocation())
                    .build();
            Collection<ItemStack> items = table.populateLoot(new Random(), context);
            if (items == null || items.isEmpty()) {
                return "roll 出 0 件 ✗（表里没有可产出的条目 —— 这会直接导致「点了没反应且不扣钥匙」）";
            }
            StringBuilder sb = new StringBuilder("roll 出 " + items.size() + " 件 ✓：");
            int n = 0;
            for (ItemStack item : items) {
                if (n++ == 3) {
                    sb.append(" …");
                    break;
                }
                sb.append(' ').append(item.getType()).append('×').append(item.getAmount());
            }
            return sb.toString();
        } catch (Throwable t) {
            return "roll 失败：" + t;
        }
    }

    private String describeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "（空）";
        }
        return item.getType() + "×" + item.getAmount();
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 这个方块 / 这个玩家是否归本插件管。 */
    private boolean appliesTo(Block block, Player player) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Vault data)) {
            return false;
        }
        if (!vaultTypeEnabled(data.isOminous())) {
            return false;
        }
        if (player != null && !permission.isEmpty() && !player.hasPermission(permission)) {
            return false;
        }
        return true;
    }

    private boolean vaultTypeEnabled(boolean ominous) {
        return ominous ? ominousVaults : normalVaults;
    }

    private org.bukkit.block.data.type.Vault.State vaultState(Block block) {
        return block.getBlockData() instanceof org.bukkit.block.data.type.Vault data
                ? data.getVaultState()
                : org.bukkit.block.data.type.Vault.State.INACTIVE;
    }

    /** 清空该宝库的整份黑名单（配了权限时只清有权限的玩家）。返回清掉的人数。 */
    private int clearAllRewarded(Block block) {
        Vault vault = liveVault(block);
        if (vault == null) {
            return 0;
        }
        List<UUID> rewarded = new ArrayList<>(vault.getRewardedPlayers());
        if (rewarded.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (UUID id : rewarded) {
            if (!mayBenefit(id)) {
                continue; // 配了权限时，别把没权限的玩家也一起放行
            }
            if (vault.removeRewardedPlayer(id)) {
                removed++;
            }
        }
        return removed;
    }

    /** 配了权限时，只有在线且有权限的玩家才享受「黑名单失效」。 */
    private boolean mayBenefit(UUID id) {
        if (permission.isEmpty()) {
            return true;
        }
        Player online = Bukkit.getPlayer(id);
        return online != null && online.hasPermission(permission);
    }

    /** 丢弃超时的「待清理」标记（防止宝库一直没回到 ACTIVE 时永久挂着）。 */
    private void sweepPending() {
        if (pendingClear.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        pendingClear.entrySet().removeIf(e -> now - e.getValue() > PENDING_TTL_MS);
    }

    /**
     * 拿「活体」TileState：改动直接落进线上方块实体（CraftVault 内部走 getSnapshot()，
     * VaultServerData 的增删都会 markChanged），不需要再 update() 写回。
     */
    private Vault liveVault(Block block) {
        try {
            BlockState state = block.getState(false);
            return state instanceof Vault vault ? vault : null;
        } catch (Throwable t) {
            warnThrottled("read:" + key(block), "[vaultfix] 读取 " + describe(block) + " 的活体状态失败：" + t);
            return null;
        }
    }

    /**
     * 检测并修复被「冻结」的宝库：存档里的 state_updating_resumes_at（= getNextStateUpdateTime）
     * 大于当前游戏刻时，原版 tick() 里的 `gameTime >= stateUpdatingResumesAt` 永远不成立
     * → 宝库永远不更新状态、shared_data 永远为空、一直 INACTIVE
     * → 表现是【永远暗着 + 插钥匙毫无反应 + 钥匙也不扣】，看起来像"超长冷却"。
     * 世界被预拷贝/回滚过时会出现（方块实体带着旧世界更高的绝对游戏刻）。
     */
    private boolean tryUnfreeze(Vault vault, Block block) {
        if (vault == null) {
            return false;
        }
        try {
            long now = block.getWorld().getGameTime();
            long next = vault.getNextStateUpdateTime();
            if (next - now > unfreezeThreshold) {
                vault.setNextStateUpdateTime(0L);
                getLogger().info("[vaultfix] " + describe(block) + " 状态更新被冻结（计划 " + next
                        + "，当前 " + now + "，差 " + (next - now) + " 刻）→ 已归零解冻，下一 tick 恢复");
                return true;
            }
        } catch (Throwable t) {
            warnThrottled("unfreeze:" + key(block), "[vaultfix] 解冻 " + describe(block) + " 失败：" + t);
        }
        return false;
    }

    /**
     * 扫描已加载区块里的宝库与试炼刷怪笼。
     * <b>Folia 安全</b>：每个区块派发到它【自己的区域线程】去读方块实体，
     * 绝不在当前线程直接碰别的区块（否则就是跨区域访问 ✗）。
     */
    private void unfreezeLoaded(Player player) {
        var world = player.getWorld();
        int chunks = 0;
        for (var chunk : world.getLoadedChunks()) {
            chunks++;
            int cx = chunk.getX();
            int cz = chunk.getZ();
            getServer().getRegionScheduler().execute(this, world, cx, cz,
                    () -> unfreezeIn(world.getChunkAt(cx, cz), player));
        }
        getLogger().info("[vaultfix] " + player.getName() + " 执行 unfreeze：已派发 " + chunks
                + " 个已加载区块（每区块在自己的区域线程上扫描宝库 + 试炼刷怪笼），结果见后续日志");
    }

    /** 扫描一个区块：宝库 + 试炼刷怪笼。player 为 null 表示不做权限过滤（区块加载自动解冻用）。 */
    private int unfreezeIn(org.bukkit.Chunk chunk, Player player) {
        int count = 0;
        if (player != null && !permission.isEmpty() && !player.hasPermission(permission)) {
            return 0;
        }
        try {
            for (BlockState state : chunk.getTileEntities()) {
                if (state instanceof Vault) {
                    if (tryUnfreeze(liveVault(state.getBlock()), state.getBlock())) {
                        count++;
                    }
                } else if (state instanceof org.bukkit.block.TrialSpawner) {
                    if (tryUnfreezeSpawner(state.getBlock())) {
                        count++;
                    }
                }
            }
        } catch (Throwable t) {
            warnThrottled("scan:" + chunk.getX() + "," + chunk.getZ(), "[vaultfix] 扫描区块失败：" + t);
        }
        return count;
    }

    /**
     * 试炼刷怪笼的冷却同样是【绝对游戏刻】（cooldown_ends_at / next_mob_spawns_at），
     * 世界被预拷贝或回滚后会落到未来 → 刷怪笼永远停在 cooldown 态、永远不出怪。
     */
    private boolean tryUnfreezeSpawner(Block block) {
        try {
            BlockState state = block.getState(false);
            if (!(state instanceof org.bukkit.block.TrialSpawner spawner)) {
                return false;
            }
            long now = block.getWorld().getGameTime();
            boolean changed = false;
            long cd = spawner.getCooldownEnd();
            if (cd - now > unfreezeThreshold) {
                spawner.setCooldownEnd(0L);
                changed = true;
            }
            long next = spawner.getNextSpawnAttempt();
            if (next - now > unfreezeThreshold) {
                spawner.setNextSpawnAttempt(0L);
                changed = true;
            }
            if (changed) {
                getLogger().info("[vaultfix] " + describe(block) + " 试炼刷怪笼冷却被冻结（cooldown_ends_at "
                        + cd + "，next_mob_spawns_at " + next + "，当前 " + now + "）→ 已归零解冻");
            }
            return changed;
        } catch (Throwable t) {
            warnThrottled("unfreezeSpawner:" + key(block), "[vaultfix] 解冻试炼刷怪笼 " + describe(block) + " 失败：" + t);
        }
        return false;
    }
    /** 身上生效的效果（如 trial_omen / bad_omen），用于诊断不祥宝库为什么拒绝插入。 */
    private String describeEffects(Player player) {
        var effects = player.getActivePotionEffects();
        if (effects.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (var effect : effects) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(effect.getType().getKey().getKey());
        }
        return sb.toString();
    }

    /** 玩家到方块中心的距离；宝库的激活范围只有 4 格，站远了插钥匙等于没插。 */
    private String distanceTo(Player player, Block block) {
        try {
            return String.format(Locale.ROOT, "%.1f",
                    player.getLocation().distance(block.getLocation().add(0.5, 0.5, 0.5)));
        } catch (Throwable t) {
            return "?";
        }
    }
    /** 只在 debug 打开时输出，且同一个键 30 秒内最多一条（避免每 tick 刷屏）。 */
    private void debugThrottled(String key, String message) {
        if (!debug) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastLogAt.get(key);
        if (last != null && now - last < DEBUG_THROTTLE_MS) {
            return;
        }
        lastLogAt.put(key, now);
        getLogger().info("[vaultfix] " + message);
    }

    /** 异常始终记录（限流），不再因为 debug=false 而完全静默。 */
    private void warnThrottled(String key, String message) {
        long now = System.currentTimeMillis();
        Long last = lastLogAt.get(key);
        if (last != null && now - last < DEBUG_THROTTLE_MS) {
            return;
        }
        lastLogAt.put(key, now);
        getLogger().warning(message);
    }

    private String key(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private String describe(Block block) {
        return block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ();
    }
}
