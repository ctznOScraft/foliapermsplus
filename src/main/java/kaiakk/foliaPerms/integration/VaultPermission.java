package kaiakk.foliaPerms.integration;

import kaiakk.foliaPerms.FoliaPerms;
import kaiakk.foliaPerms.permissions.PermissionService;
import kaiakk.foliaPerms.permissions.UserData;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Vault {@link Permission} provider backed by FoliaPerms. Lets Vault-aware
 * plugins query and modify permissions and group membership through Vault, so
 * EssentialsX and similar plugins see the same data FoliaPerms manages.
 */
public class VaultPermission extends Permission {
    private final FoliaPerms fp;
    private final PermissionService service;

    public VaultPermission(FoliaPerms plugin) {
        this.fp = plugin;
        this.plugin = plugin; // Vault's protected field
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

    @Override
    public boolean hasSuperPermsCompat() {
        return true;
    }

    @Override
    public boolean hasGroupSupport() {
        return true;
    }

    // ---- Player permissions ----

    @Override
    public boolean playerHas(String world, String player, String permission) {
        UUID id = uuid(player);
        return id != null && service.hasPermission(id, permission);
    }

    @Override
    public boolean playerAdd(String world, String player, String permission) {
        UUID id = uuid(player);
        if (id == null) return false;
        service.addUserPermission(id, permission);
        save();
        return true;
    }

    @Override
    public boolean playerRemove(String world, String player, String permission) {
        UUID id = uuid(player);
        if (id == null) return false;
        service.removeUserPermission(id, permission);
        save();
        return true;
    }

    // ---- Group permissions ----

    @Override
    public boolean groupHas(String world, String group, String permission) {
        return service.groupHasDirectPermission(group, permission);
    }

    @Override
    public boolean groupAdd(String world, String group, String permission) {
        service.addGroupPermission(group, permission);
        save();
        return true;
    }

    @Override
    public boolean groupRemove(String world, String group, String permission) {
        service.removeGroupPermission(group, permission);
        save();
        return true;
    }

    // ---- Group membership ----

    @Override
    public boolean playerInGroup(String world, String player, String group) {
        UUID id = uuid(player);
        if (id == null) return false;
        if (service.isDefaultGroup(group)) return true; // everyone is in the default group
        UserData ud = service.getUser(id);
        return ud != null && ud.getGroups().contains(group.toLowerCase());
    }

    @Override
    public boolean playerAddGroup(String world, String player, String group) {
        UUID id = uuid(player);
        if (id == null) return false;
        service.addUserToGroup(id, group);
        save();
        return true;
    }

    @Override
    public boolean playerRemoveGroup(String world, String player, String group) {
        UUID id = uuid(player);
        if (id == null) return false;
        service.removeUserFromGroup(id, group);
        save();
        return true;
    }

    @Override
    public String[] getPlayerGroups(String world, String player) {
        UUID id = uuid(player);
        Set<String> groups = new HashSet<>();
        if (id != null) {
            UserData ud = service.getUser(id);
            if (ud != null) groups.addAll(ud.getGroups());
        }
        groups.add(service.getDefaultGroupName()); // implicit default group
        return groups.toArray(new String[0]);
    }

    @Override
    public String getPrimaryGroup(String world, String player) {
        UUID id = uuid(player);
        return id == null ? service.getDefaultGroupName() : service.getPrimaryGroup(id);
    }

    @Override
    public String[] getGroups() {
        return service.getGroups().keySet().toArray(new String[0]);
    }
}
