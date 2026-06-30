package kaiakk.foliaPerms.integration;

import kaiakk.foliaPerms.FoliaPerms;
import kaiakk.foliaPerms.permissions.GroupData;
import kaiakk.foliaPerms.permissions.PermissionService;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * Vault {@link Chat} provider backed by FoliaPerms. Lets Vault-aware plugins
 * (EssentialsX, TAB via Vault, chat plugins, ...) read and write the
 * prefixes/suffixes managed by FoliaPerms, keeping everything in sync.
 *
 * <p>Only prefix/suffix are backed by real data; the arbitrary info-node
 * getters/setters return defaults / no-op, as FoliaPerms does not store
 * generic meta nodes.
 */
public class VaultChat extends Chat {
    private final FoliaPerms plugin;
    private final PermissionService service;

    public VaultChat(FoliaPerms plugin, Permission permission) {
        super(permission);
        this.plugin = plugin;
        this.service = plugin.getPermissionService();
    }

    private UUID uuid(String player) {
        if (player == null) return null;
        return Bukkit.getOfflinePlayer(player).getUniqueId();
    }

    private void save() {
        if (service != null) service.saveAsync();
    }

    @Override
    public String getName() {
        return "FoliaPerms";
    }

    @Override
    public boolean isEnabled() {
        return service != null;
    }

    // ---- Player prefix / suffix ----

    @Override
    public String getPlayerPrefix(String world, String player) {
        UUID id = uuid(player);
        return id == null ? "" : service.resolvePrefix(id);
    }

    @Override
    public void setPlayerPrefix(String world, String player, String prefix) {
        UUID id = uuid(player);
        if (id != null) { service.setUserPrefix(id, prefix); save(); }
    }

    @Override
    public String getPlayerSuffix(String world, String player) {
        UUID id = uuid(player);
        return id == null ? "" : service.resolveSuffix(id);
    }

    @Override
    public void setPlayerSuffix(String world, String player, String suffix) {
        UUID id = uuid(player);
        if (id != null) { service.setUserSuffix(id, suffix); save(); }
    }

    // ---- Group prefix / suffix ----

    @Override
    public String getGroupPrefix(String world, String group) {
        GroupData gd = service.getGroup(group);
        return gd != null && gd.getPrefix() != null ? gd.getPrefix() : "";
    }

    @Override
    public void setGroupPrefix(String world, String group, String prefix) {
        service.setGroupPrefix(group, prefix);
        save();
    }

    @Override
    public String getGroupSuffix(String world, String group) {
        GroupData gd = service.getGroup(group);
        return gd != null && gd.getSuffix() != null ? gd.getSuffix() : "";
    }

    @Override
    public void setGroupSuffix(String world, String group, String suffix) {
        service.setGroupSuffix(group, suffix);
        save();
    }

    // ---- Generic info nodes: weight is exposed, everything else is default ----

    @Override
    public int getPlayerInfoInteger(String world, String player, String node, int defaultValue) {
        return defaultValue;
    }

    @Override
    public void setPlayerInfoInteger(String world, String player, String node, int value) {
    }

    @Override
    public int getGroupInfoInteger(String world, String group, String node, int defaultValue) {
        if ("weight".equalsIgnoreCase(node)) {
            GroupData gd = service.getGroup(group);
            if (gd != null) return gd.getWeight();
        }
        return defaultValue;
    }

    @Override
    public void setGroupInfoInteger(String world, String group, String node, int value) {
        if ("weight".equalsIgnoreCase(node)) {
            service.setGroupWeight(group, value);
            save();
        }
    }

    @Override
    public double getPlayerInfoDouble(String world, String player, String node, double defaultValue) {
        return defaultValue;
    }

    @Override
    public void setPlayerInfoDouble(String world, String player, String node, double value) {
    }

    @Override
    public double getGroupInfoDouble(String world, String group, String node, double defaultValue) {
        return defaultValue;
    }

    @Override
    public void setGroupInfoDouble(String world, String group, String node, double value) {
    }

    @Override
    public boolean getPlayerInfoBoolean(String world, String player, String node, boolean defaultValue) {
        return defaultValue;
    }

    @Override
    public void setPlayerInfoBoolean(String world, String player, String node, boolean value) {
    }

    @Override
    public boolean getGroupInfoBoolean(String world, String group, String node, boolean defaultValue) {
        return defaultValue;
    }

    @Override
    public void setGroupInfoBoolean(String world, String group, String node, boolean value) {
    }

    @Override
    public String getPlayerInfoString(String world, String player, String node, String defaultValue) {
        if ("prefix".equalsIgnoreCase(node)) return getPlayerPrefix(world, player);
        if ("suffix".equalsIgnoreCase(node)) return getPlayerSuffix(world, player);
        return defaultValue;
    }

    @Override
    public void setPlayerInfoString(String world, String player, String node, String value) {
        if ("prefix".equalsIgnoreCase(node)) setPlayerPrefix(world, player, value);
        else if ("suffix".equalsIgnoreCase(node)) setPlayerSuffix(world, player, value);
    }

    @Override
    public String getGroupInfoString(String world, String group, String node, String defaultValue) {
        if ("prefix".equalsIgnoreCase(node)) return getGroupPrefix(world, group);
        if ("suffix".equalsIgnoreCase(node)) return getGroupSuffix(world, group);
        return defaultValue;
    }

    @Override
    public void setGroupInfoString(String world, String group, String node, String value) {
        if ("prefix".equalsIgnoreCase(node)) setGroupPrefix(world, group, value);
        else if ("suffix".equalsIgnoreCase(node)) setGroupSuffix(world, group, value);
    }
}
