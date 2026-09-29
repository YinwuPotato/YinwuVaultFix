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
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * YinwuVaultFix —— 让试炼宝库（trial vault）的"开过就不再给你开"玩家黑名单失效。
 *
 * <p>原版判定链（26.3 / {@code VaultBlockEntity$Server#tryInsertKey}，由字节码核对）：
 * <pre>
 *   canEjectReward(config, state)         // 方块状态必须是 ACTIVE，否则 VaultBlock#useItemOn 直接返回
 *   isValidToInsert(config, itemStack)    // 手上必须是宝库钥匙（普通 / 不祥）
 *   serverData.hasRewardedPlayer(player)  // ← 黑名单：命中就 playInsertFailSound(VAULT_REJECT_REWARDED_PLAYER) 然后返回
 *   resolveItemsToEject(...)              // 正常吐战利品，并把该玩家记进 rewardedPlayers
 * </pre>
 *
 * <p>本插件的两条对策：
 * <ol>
 *   <li><b>右键时先抹掉本人记录</b>：PlayerInteractEvent 在 ServerPlayerGameMode#useItemOn 里
 *       早于方块逻辑触发（字节码偏移 120 处 callPlayerInteractEvent，方块逻辑在其后），
 *       所以在这里把玩家从 rewardedPlayers 里删掉，同一次点击的 hasRewardedPlayer 就是 false，
 *       原版流程照常走完 —— 钥匙照样消耗、战利品照样吐、玩家照样被重新记进名单。</li>
 *   <li><b>状态变化时清名单</b>：VaultChangeStateEvent 触发时继续清空，
 *       这样宝库不会因为"附近玩家都开过了"而变暗（原版会把已获奖玩家从 connectedPlayers 里排除，
 *       进而使宝库进入 INACTIVE），一直保持 ACTIVE，随时可以再开。</li>
 * </ol>
 *
 * <p>纯 Bukkit/Paper API，不含 NMS、不做反射；只挂事件、不使用调度器，
 * 因此 Folia / Canvas 下天然线程安全（事件在方块所属 region 线程上触发）。
 */
public final class VaultFixPlugin extends JavaPlugin implements Listener {

    // ---- config.yml ----
    private boolean normalVaults = true;
    private boolean ominousVaults = true;
    private boolean forceActive = true;
    private boolean clearOnStateChange = true;
    private String permission = "";
    private boolean debug = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("[vaultfix] 已启用：普通宝库=" + normalVaults
                + "，不祥宝库=" + ominousVaults
                + "，点击时拉回ACTIVE=" + forceActive
                + "，状态变化清名单=" + clearOnStateChange
                + "，生效权限=" + (permission.isEmpty() ? "（所有人）" : permission));
    }

    @Override
    public void onDisable() {
        getLogger().info("[vaultfix] 已停用");
    }

    private void loadSettings() {
        reloadConfig();
        normalVaults = !"ominous".equalsIgnoreCase(vaultTypes());
        ominousVaults = !"normal".equalsIgnoreCase(vaultTypes());
        forceActive = getConfig().getBoolean("force-active-on-click", true);
        clearOnStateChange = getConfig().getBoolean("clear-on-state-change", true);
        permission = String.valueOf(getConfig().getString("permission", "")).trim();
        debug = getConfig().getBoolean("debug", false);
    }

    private String vaultTypes() {
        String raw = String.valueOf(getConfig().getString("vault-types", "both")).trim();
        if (raw.equalsIgnoreCase("normal") || raw.equalsIgnoreCase("ominous")) {
            return raw.toLowerCase(Locale.ROOT);
        }
        return "both";
    }

    // ------------------------------------------------------------------
    // 1) 右键宝库：先把本人从黑名单里删掉
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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

        // 状态：原版要求这次点击时方块状态就是 ACTIVE，否则连 tryInsertKey 都不会调用。
        boolean nudged = forceActive && forceActiveState(block);

        boolean cleared = clearRewardedPlayer(block, player.getUniqueId());
        if (debug && (cleared || nudged)) {
            getLogger().info("[vaultfix] " + player.getName() + " 点击 " + describe(block)
                    + "：清黑名单=" + cleared + "，拉回ACTIVE=" + nudged);
        }
    }

    // ------------------------------------------------------------------
    // 2) 状态变化：继续清空名单，让宝库不变暗、随时可开
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStateChange(VaultChangeStateEvent event) {
        if (!clearOnStateChange) {
            return;
        }
        Block block = event.getBlock();
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Vault data)) {
            return;
        }
        if (!vaultTypeEnabled(data.isOminous())) {
            return;
        }
        Vault vault = liveVault(block);
        if (vault == null) {
            return;
        }
        List<UUID> rewarded = new ArrayList<>(vault.getRewardedPlayers());
        if (rewarded.isEmpty()) {
            return;
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
        if (removed > 0 && debug) {
            getLogger().info("[vaultfix] " + describe(block) + " " + event.getCurrentState()
                    + "→" + event.getNewState() + "：清除黑名单 " + removed + " 人");
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
            case "inspect" -> {
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
                return true;
            }
        }
    }

    /** 看一眼你正对的宝库：类型 / 状态 / 黑名单人数 / 你在不在名单里。 */
    private void inspect(Player player) {
        Block block = player.getTargetBlockExact(8);
        if (block == null || !(block.getBlockData() instanceof org.bukkit.block.data.type.Vault data)) {
            player.sendMessage(Component.text("[vaultfix] 请对着一个试炼宝库方块（8 格内）再执行", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("[vaultfix] " + describe(block), NamedTextColor.AQUA));
        player.sendMessage(Component.text("  类型：" + (data.isOminous() ? "不祥宝库" : "普通宝库")
                + "　状态：" + data.getVaultState(), NamedTextColor.GRAY));

        Vault vault = liveVault(block);
        if (vault == null) {
            player.sendMessage(Component.text("  读不到方块实体（区块未加载？）", NamedTextColor.RED));
            return;
        }
        var rewarded = vault.getRewardedPlayers();
        player.sendMessage(Component.text("  黑名单人数：" + rewarded.size()
                + (rewarded.contains(player.getUniqueId()) ? "（你在名单里）" : "（你不在名单里）"), NamedTextColor.GRAY));
        player.sendMessage(Component.text("  在场玩家：" + vault.getConnectedPlayers().size()
                + "　钥匙：" + vault.getKeyItem().getType(), NamedTextColor.GRAY));
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

    /** 配了权限时，只有在线且有权限的玩家才享受"黑名单失效"。 */
    private boolean mayBenefit(UUID id) {
        if (permission.isEmpty()) {
            return true;
        }
        Player online = Bukkit.getPlayer(id);
        return online != null && online.hasPermission(permission);
    }

    /**
     * 拿"活体" TileState：改动直接落进线上方块实体（CraftVault 内部走 getSnapshot()，
     * VaultServerData 的增删都会 markChanged），不需要再 update() 写回。
     */
    private Vault liveVault(Block block) {
        try {
            BlockState state = block.getState(false);
            return state instanceof Vault vault ? vault : null;
        } catch (Throwable t) {
            if (debug) {
                getLogger().warning("[vaultfix] 读取 " + describe(block) + " 的活体状态失败：" + t);
            }
            return null;
        }
    }

    /** 把某个玩家从该宝库的黑名单里删掉。 */
    private boolean clearRewardedPlayer(Block block, UUID id) {
        Vault live = liveVault(block);
        if (live != null) {
            return live.removeRewardedPlayer(id);
        }
        // 兜底：快照状态改完再写回（老式 TileState 用法）
        try {
            BlockState snapshot = block.getState();
            if (snapshot instanceof Vault vault && vault.removeRewardedPlayer(id)) {
                vault.update(true, false);
                return true;
            }
        } catch (Throwable t) {
            getLogger().warning("[vaultfix] 写入 " + describe(block) + " 的黑名单失败：" + t);
        }
        return false;
    }

    /**
     * 原版 VaultBlock#useItemOn 第一件事就是判断方块状态是不是 ACTIVE，
     * 不是就直接返回、连钥匙都不看。被黑名单冻成 INACTIVE 的宝库要立刻可用，
     * 就得把状态先拨回 ACTIVE（宝库的 ticker 下一 tick 会按实际数据重算，不会留下副作用）。
     */
    private boolean forceActiveState(Block block) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Vault data)) {
            return false;
        }
        if (data.getVaultState() == org.bukkit.block.data.type.Vault.State.ACTIVE) {
            return false;
        }
        data.setVaultState(org.bukkit.block.data.type.Vault.State.ACTIVE);
        block.setBlockData(data, false);
        return true;
    }

    private String describe(Block block) {
        return block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ();
    }
}
