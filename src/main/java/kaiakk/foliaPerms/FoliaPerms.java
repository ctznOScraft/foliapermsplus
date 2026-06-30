package kaiakk.foliaPerms;

import kaiakk.foliaPerms.api.FoliaPermsAPI;
import kaiakk.foliaPerms.commands.FpermCommand;
import kaiakk.foliaPerms.events.PlayerListener;
import kaiakk.foliaPerms.permissions.PermissionService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FoliaPerms - A simple permission manager for Folia servers.
 * 
 * This plugin provides:
 * - User and group-based permission management
 * - YAML-based persistence
 * - GUI editor for permissions
 * - Folia-compatible thread-safe operations
 */
public final class FoliaPerms extends JavaPlugin implements FoliaPermsAPI {
    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private PermissionService permissionService;
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();
    private boolean tablistFormatting = true;
    private boolean chatFormatting = true;

    @Override
    public void onLoad() {
        if (!isFolia()) {
            getLogger().severe("Error detected!");
            getLogger().severe("FoliaPerms is a Folia-only plugin!");
            getLogger().severe("It appears you are running a normal Bukkit/Paper/Spigot server.");
            getLogger().severe("This plugin will now disable itself, goodbye.");
            getLogger().severe("Disabling FoliaPerms...");
            getServer().getPluginManager().disablePlugin(this);
        } else {
            getLogger().info("Folia environment detected. FoliaPerms is ready to enable.");
            getLogger().info("Enabling FoliaPerms v" + getDescription().getVersion() + "...");
            getLogger().info("Loading all permissions data...");
        }
    }
    
    @Override
    public void onEnable() {
        getLogger().info("FoliaPerms v" + getDescription().getVersion() + " enabled successfully. Welcome to the Folia environment!");

        saveDefaultConfig();

        this.permissionService = new PermissionService(this);
        try {
            this.permissionService.load();
            getLogger().info("Loaded permissions data.");
        } catch (Exception e) {
            kaiakk.foliaPerms.internal.ErrorHandler.handle(this, "Failed to load permissions data", e);
        }

        String defaultGroup = getConfig().getString("default-group", "default");
        permissionService.setDefaultGroupName(defaultGroup);
        permissionService.ensureDefaultGroup();
        getLogger().info("Default group set to '" + permissionService.getDefaultGroupName() + "'.");

        if (getCommand("fperm") != null) {
            getCommand("fperm").setExecutor(new FpermCommand(this));
            getCommand("fperm").setTabCompleter(new kaiakk.foliaPerms.commands.FpermTabCompleter(this));
        }

        this.tablistFormatting = getConfig().getBoolean("tablist-formatting", true);
        this.chatFormatting = getConfig().getBoolean("chat-formatting", true);

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new kaiakk.foliaPerms.events.PluginEnableListener(this), this);
        getServer().getPluginManager().registerEvents(new kaiakk.foliaPerms.gui.GuiListener(), this);
        if (chatFormatting) {
            getServer().getPluginManager().registerEvents(new kaiakk.foliaPerms.events.ChatListener(this), this);
            getLogger().info("Chat prefix/suffix formatting enabled.");
        }

        getServer().getServicesManager().register(FoliaPermsAPI.class, this, this, ServicePriority.Normal);
        getLogger().info("FoliaPerms API registered with ServicesManager.");

        try {
            permissionService.gatherRegisteredPermissions(this);
            getLogger().info("Gathered " + permissionService.getRegisteredPermissions().size() + " permissions from plugins.");
            // Log first 10 permissions
            int count = 0;
            for (String p : permissionService.getRegisteredPermissions()) {
                if (count++ < 10) {
                    getLogger().info(" - " + p);
                }
            }
            if (permissionService.getRegisteredPermissions().size() > 10) {
                getLogger().info(" ... and " + (permissionService.getRegisteredPermissions().size() - 10) + " more");
            }
            refreshAllAttachments();
            getLogger().info("Permission attachments initialized for " + Bukkit.getOnlinePlayers().size() + " players.");
        } catch (Exception e) {
            kaiakk.foliaPerms.internal.ErrorHandler.handle(this, "Failed to gather registered permissions", e);
        }

        registerIntegrations();
    }

    /**
     * Hooks into optional third-party plugins (Vault, PlaceholderAPI) when they
     * are installed. Each hook lives in its own method and is guarded by a
     * plugin-presence check plus a Throwable catch, so a missing soft dependency
     * never loads its integration classes or breaks startup.
     */
    private void registerIntegrations() {
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            registerVault();
        } else {
            getLogger().info("Vault not found; skipping Vault chat/permission hook.");
        }
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            registerPlaceholderAPI();
        } else {
            getLogger().info("PlaceholderAPI not found; skipping placeholder expansion.");
        }
    }

    private void registerVault() {
        try {
            kaiakk.foliaPerms.integration.VaultPermission vaultPerm = new kaiakk.foliaPerms.integration.VaultPermission(this);
            getServer().getServicesManager().register(net.milkbowl.vault.permission.Permission.class, vaultPerm, this, ServicePriority.Highest);

            kaiakk.foliaPerms.integration.VaultChat vaultChat = new kaiakk.foliaPerms.integration.VaultChat(this, vaultPerm);
            getServer().getServicesManager().register(net.milkbowl.vault.chat.Chat.class, vaultChat, this, ServicePriority.Highest);

            getLogger().info("Hooked into Vault: registered FoliaPerms permission and chat providers.");
        } catch (Throwable t) {
            getLogger().warning("Failed to hook into Vault: " + t.getMessage());
        }
    }

    private void registerPlaceholderAPI() {
        try {
            new kaiakk.foliaPerms.integration.FoliaPermsExpansion(this).register();
            getLogger().info("Registered PlaceholderAPI expansion 'foliaperms' (%foliaperms_prefix%, _suffix%, _group%, _weight%).");
        } catch (Throwable t) {
            getLogger().warning("Failed to register PlaceholderAPI expansion: " + t.getMessage());
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("FoliaPerms v" + getDescription().getVersion() + " disabling...");
        getLogger().info("Saving permissions...");
        if (this.permissionService != null) {
            try {
                this.permissionService.save();
                getLogger().info("Permissions saved.");
            } catch (Exception e) {
                getLogger().severe("Failed to save permissions: " + e.getMessage());
            }
        }
        
        // Clean up all attachments
        cleanupAllAttachments();
        getLogger().info("FoliaPerms disabled successfully.");
    }

    public PermissionService getPermissionService() {
        return this.permissionService;
    }

    /**
     * Runs a task on the region thread that owns the given player. On Folia this
     * is mandatory: a player's permission attachment must only be mutated from
     * its owning region thread. Falls back gracefully on non-Folia servers.
     */
    private void runForPlayer(Player player, Runnable task) {
        if (player == null) return;
        try {
            player.getScheduler().run(this, t -> task.run(), null);
        } catch (Throwable foliaUnavailable) {
            try {
                if (Bukkit.isPrimaryThread()) {
                    task.run();
                } else {
                    Bukkit.getScheduler().runTask(this, task);
                }
            } catch (Throwable t) {
                getLogger().warning("Could not schedule attachment refresh for "
                        + player.getName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Refreshes permission attachment for a specific player. Safe to call from
     * any thread; the actual work is dispatched onto the player's region thread.
     */
    public void refreshPlayerAttachment(Player player) {
        if (player == null || permissionService == null) return;
        runForPlayer(player, () -> applyAttachment(player));
    }

    /**
     * Rebuilds a player's attachment from scratch with only the permissions they
     * actually have. We intentionally do NOT enumerate every registered
     * permission and set it to {@code false}: doing so was both a major
     * performance sink and the reason operators lost their op-default
     * permissions (an explicit {@code false} overrides the op default).
     */
    private void applyAttachment(Player player) {
        try {
            UUID id = player.getUniqueId();
            PermissionAttachment old = attachments.remove(id);
            if (old != null) {
                try { player.removeAttachment(old); } catch (Exception ignored) {}
            }

            PermissionAttachment attach = player.addAttachment(this);
            attachments.put(id, attach);

            for (String node : permissionService.computeEffectivePermissions(id)) {
                attach.setPermission(node, true);
            }

            player.recalculatePermissions();
            try {
                player.updateCommands();
            } catch (Throwable t) {
                getLogger().fine("Could not update command tree for " + player.getName() + ": " + t.getMessage());
            }
            if (tablistFormatting) {
                try {
                    player.playerListName(buildDisplayName(id, player.getName()));
                } catch (Throwable t) {
                    getLogger().fine("Could not update tab-list name for " + player.getName() + ": " + t.getMessage());
                }
            }
            getLogger().fine("Refreshed permission attachment for " + player.getName());
        } catch (Exception e) {
            getLogger().severe("Failed to refresh attachment for " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Refreshes permission attachments for all online players.
     */
    public void refreshAllAttachments() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            refreshPlayerAttachment(p);
        }
    }

    /**
     * Refreshes only the online members of a group. Used when a group's
     * permissions change, so we avoid touching every online player.
     */
    public void refreshGroupMembers(String group) {
        if (group == null || permissionService == null) return;
        String key = group.toLowerCase();
        for (Player p : Bukkit.getOnlinePlayers()) {
            var ud = permissionService.getUser(p.getUniqueId());
            if (ud != null && ud.getGroups().contains(key)) {
                refreshPlayerAttachment(p);
            }
        }
    }

    /**
     * Removes a player's permission attachment (called on quit).
     */
    public void removePlayerAttachment(UUID playerId) {
        PermissionAttachment old = attachments.remove(playerId);
        if (old != null) {
            try {
                Player p = Bukkit.getPlayer(playerId);
                if (p != null) {
                    p.removeAttachment(old);
                    getLogger().fine("Removed attachment for player " + playerId);
                }
            } catch (Exception e) {
                getLogger().warning("Error removing attachment for " + playerId + ": " + e.getMessage());
            }
        }
    }

    /**
     * Cleans up all player attachments (called on disable).
     */
    private void cleanupAllAttachments() {
        for (UUID id : attachments.keySet()) {
            removePlayerAttachment(id);
        }
        attachments.clear();
        getLogger().info("All permission attachments cleaned up.");
    }

    @Override
    public boolean hasPermission(Player player, String permissionNode) {
        if (player == null) return false;
        return this.permissionService != null && this.permissionService.hasPermission(player.getUniqueId(), permissionNode);
    }

    @Override
    public boolean hasPermission(java.util.UUID playerUuid, String permissionNode) {
        if (playerUuid == null) return false;
        return this.permissionService != null && this.permissionService.hasPermission(playerUuid, permissionNode);
    }

    @Override
    public Set<String> getPlayerGroups(Player player) {
        if (player == null || this.permissionService == null) return Collections.emptySet();
        var ud = this.permissionService.getUser(player.getUniqueId());
        if (ud == null) return Collections.emptySet();
        return Collections.unmodifiableSet(new HashSet<>(ud.getGroups()));
    }

    @Override
    public String getPrimaryGroup(Player player) {
        var groups = getPlayerGroups(player);
        return groups.stream().findFirst().orElse(null);
    }

    @Override
    public String getPrefix(Player player) {
        if (player == null || permissionService == null) return "";
        return permissionService.resolvePrefix(player.getUniqueId());
    }

    @Override
    public String getSuffix(Player player) {
        if (player == null || permissionService == null) return "";
        return permissionService.resolveSuffix(player.getUniqueId());
    }

    /**
     * Builds the tab-list display name as {@code prefix + name + suffix},
     * translating legacy {@code &} colour codes in the prefix/suffix.
     */
    public Component buildDisplayName(UUID id, String name) {
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
        String prefix = permissionService.resolvePrefix(id);
        String suffix = permissionService.resolveSuffix(id);
        return Component.empty()
                .append(legacy.deserialize(prefix))
                .append(Component.text(name))
                .append(legacy.deserialize(suffix));
    }
}