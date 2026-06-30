package kaiakk.foliaPerms.permissions;

import kaiakk.foliaPerms.FoliaPerms;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Core permission system logic and data management.
 * Handles user/group permissions with caching and async operations.
 */
public class PermissionService {
    private final JavaPlugin plugin;
    private final YamlStorage storage;

    private final Map<UUID, UserData> users = new ConcurrentHashMap<>();
    private final Map<String, GroupData> groups = new ConcurrentHashMap<>();
    private final java.util.Set<String> registeredPermissions = ConcurrentHashMap.newKeySet();

    // Cache for sorted permissions (for UI efficiency)
    private List<String> cachedSortedPermissions = null;

    // Name of the implicit default group applied to every player (LuckPerms-style).
    private volatile String defaultGroupName = "default";

    public PermissionService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.storage = new YamlStorage(plugin);
    }

    public void load() {
        users.clear();
        groups.clear();
        Map<UUID, UserData> loadedUsers = storage.loadUsers();
        Map<String, GroupData> loadedGroups = storage.loadGroups();
        users.putAll(loadedUsers);
        groups.putAll(loadedGroups);
        plugin.getLogger().info("Loaded " + users.size() + " users and " + groups.size() + " groups from permissions.yml");
        if (!users.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (UUID id : users.keySet()) {
                if (count++ < 5) sb.append(id.toString()).append(", ");
            }
            if (users.size() > 5) sb.append("... and ").append(users.size() - 5).append(" more");
            plugin.getLogger().fine("Loaded user UUIDs: " + sb.toString());
        }
        if (!groups.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (String g : groups.keySet()) {
                if (count++ < 5) sb.append(g).append(", ");
            }
            if (groups.size() > 5) sb.append("... and ").append(groups.size() - 5).append(" more");
            plugin.getLogger().fine("Loaded groups: " + sb.toString());
        }
    }

    public void loadAsync(Runnable callback) {
        plugin.getLogger().info("Scheduling async permissions load (background thread)");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, UserData> loadedUsers = storage.loadUsers();
            Map<String, GroupData> loadedGroups = storage.loadGroups();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                users.clear();
                groups.clear();
                users.putAll(loadedUsers);
                groups.putAll(loadedGroups);
                plugin.getLogger().info("Loaded " + users.size() + " users and " + groups.size() + " groups from permissions.yml");
                if (callback != null) {
                    try { callback.run(); } catch (Throwable t) { kaiakk.foliaPerms.internal.ErrorHandler.handle(plugin, "Exception in load callback", t); }
                }
            });
        });
    }

    public void gatherRegisteredPermissions(org.bukkit.plugin.Plugin plugin) {
        registeredPermissions.clear();
        cachedSortedPermissions = null; // Invalidate cache
        
        var pm = plugin.getServer().getPluginManager();
        for (org.bukkit.permissions.Permission p : pm.getPermissions()) {
            if (p == null) continue;
            registeredPermissions.add(p.getName());
            if (p.getChildren() != null) {
                registeredPermissions.addAll(p.getChildren().keySet());
            }
        }

        try {
            Object server = plugin.getServer();
            java.lang.reflect.Method getCommandMap = server.getClass().getMethod("getCommandMap");
            Object commandMap = getCommandMap.invoke(server);
            if (commandMap != null) {
                java.lang.reflect.Field knownField = null;
                Class<?> cmClass = commandMap.getClass();
                while (cmClass != null) {
                    try {
                        knownField = cmClass.getDeclaredField("knownCommands");
                        break;
                    } catch (NoSuchFieldException ignored) {
                        cmClass = cmClass.getSuperclass();
                    }
                }
                if (knownField != null) {
                    knownField.setAccessible(true);
                    Object known = knownField.get(commandMap);
                    if (known instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, org.bukkit.command.Command> knownMap = (java.util.Map<String, org.bukkit.command.Command>) known;
                        for (org.bukkit.command.Command cmd : knownMap.values()) {
                            if (cmd == null) continue;
                            String perm = cmd.getPermission();
                            if (perm != null && !perm.isBlank()) registeredPermissions.add(perm);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            kaiakk.foliaPerms.internal.ErrorHandler.warn(plugin, "Could not gather command-map permissions!", t);
        }
    }

    /**
     * Gets registered permissions, sorted and cached for UI efficiency.
     */
    public List<String> getRegisteredPermissionsSorted() {
        if (cachedSortedPermissions == null) {
            cachedSortedPermissions = registeredPermissions.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
        }
        return cachedSortedPermissions;
    }

    public java.util.Set<String> getRegisteredPermissions() {
        return java.util.Collections.unmodifiableSet(registeredPermissions);
    }

    public java.util.Set<String> getAllowedPermissions(UUID id) {
        var result = new java.util.HashSet<String>();
        for (String node : registeredPermissions) {
            if (hasPermission(id, node)) result.add(node);
        }
        return result;
    }

    /**
     * Computes the effective set of permission nodes that should be granted to a
     * player, by directly unioning the player's own permissions with the
     * permissions of every group they belong to. Wildcard nodes are expanded
     * against the currently registered permissions so child nodes are granted
     * too: the bare {@code "*"} grants every registered permission (all
     * permissions, LuckPerms-style) and {@code "essentials.*"} grants every
     * permission under that prefix.
     *
     * <p>This is O(directPerms) in the common case (and only O(directPerms ×
     * registeredPerms) when wildcards are present), as opposed to
     * {@link #getAllowedPermissions(UUID)} which always scans every registered
     * permission. It is what the attachment refresh path uses, so it must stay
     * cheap.
     */
    public java.util.Set<String> computeEffectivePermissions(UUID id) {
        var direct = new java.util.HashSet<String>();
        var groupChain = new java.util.HashSet<String>();
        UserData ud = users.get(id);
        if (ud != null) {
            direct.addAll(ud.getPermissions());
            for (String g : ud.getGroups()) {
                collectGroupAndParents(g, groupChain);
            }
        }
        // The default group (and its parents) is granted to every player implicitly.
        collectGroupAndParents(defaultGroupName, groupChain);

        for (String g : groupChain) {
            GroupData gd = groups.get(g);
            if (gd != null) direct.addAll(gd.getPermissions());
        }

        var result = new java.util.HashSet<String>();
        for (String node : direct) {
            result.add(node);
            if (node.equals("*")) {
                // "all permissions": grant every permission we know about.
                result.addAll(registeredPermissions);
            } else if (node.endsWith(".*")) {
                String prefix = node.substring(0, node.length() - 1); // keep trailing dot
                for (String reg : registeredPermissions) {
                    if (reg.startsWith(prefix)) result.add(reg);
                }
            }
        }
        return result;
    }

    public void save() throws IOException {
        storage.save(users, groups);
    }

    public void saveAsync() {
        Map<UUID, UserData> usersSnapshot = new HashMap<>();
        for (Map.Entry<UUID, UserData> e : users.entrySet()) {
            UUID id = e.getKey();
            UserData orig = e.getValue();
            UserData copy = new UserData(id);
            copy.getPermissions().addAll(orig.getPermissions());
            copy.getGroups().addAll(orig.getGroups());
            copy.setPrefix(orig.getPrefix());
            copy.setSuffix(orig.getSuffix());
            usersSnapshot.put(id, copy);
        }

        Map<String, GroupData> groupsSnapshot = new HashMap<>();
        for (Map.Entry<String, GroupData> e : groups.entrySet()) {
            String key = e.getKey();
            GroupData orig = e.getValue();
            GroupData copy = new GroupData(key);
            copy.getPermissions().addAll(orig.getPermissions());
            copy.getMembers().addAll(orig.getMembers());
            copy.getParents().addAll(orig.getParents());
            copy.setPrefix(orig.getPrefix());
            copy.setSuffix(orig.getSuffix());
            copy.setWeight(orig.getWeight());
            groupsSnapshot.put(key, copy);
        }

        plugin.getLogger().fine("Scheduling async permissions save.");
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    storage.save(usersSnapshot, groupsSnapshot);
                    plugin.getLogger().fine("Async save completed successfully.");
                } catch (IOException ex) {
                    plugin.getLogger().severe("Async save failed: " + ex.getMessage());
                }
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Async scheduler unavailable, falling back to background thread: " + t.getMessage());
            Thread thr = new Thread(() -> {
                try {
                    storage.save(usersSnapshot, groupsSnapshot);
                    plugin.getLogger().fine("Background thread save completed.");
                } catch (IOException ex) {
                    plugin.getLogger().severe("Async save failed: " + ex.getMessage());
                }
            }, "FoliaPerms-Save");
            thr.setDaemon(true);
            thr.start();
        }
    }

    public UserData getOrCreateUser(UUID id) {
        return users.computeIfAbsent(id, UserData::new);
    }

    public UserData getUser(UUID id) {
        return users.get(id);
    }

    /**
     * Refreshes the live attachment of a single online player (if any). The
     * refresh itself is dispatched onto the correct region thread by
     * {@link FoliaPerms#refreshPlayerAttachment}, so this is safe to call from
     * any thread.
     */
    private void refreshPlayer(UUID id) {
        if (plugin instanceof FoliaPerms fp) {
            var player = fp.getServer().getPlayer(id);
            if (player != null) fp.refreshPlayerAttachment(player);
        }
    }

    /**
     * Refreshes only the online members of a group (instead of every online
     * player), which is what changing a group's permissions actually affects.
     */
    private void refreshGroup(String group) {
        if (plugin instanceof FoliaPerms fp) {
            // The default group affects everyone, not only explicit members.
            if (isDefaultGroup(group)) {
                fp.refreshAllAttachments();
            } else {
                fp.refreshGroupMembers(group);
            }
        }
    }

    public void addUserPermission(UUID id, String node) {
        String normalized = node == null ? null : node.toLowerCase();
        if (normalized == null) return;
        getOrCreateUser(id).addPermission(normalized);
        registeredPermissions.add(normalized);
        cachedSortedPermissions = null; // Invalidate cache
        plugin.getLogger().info("Added permission '" + normalized + "' to user " + id.toString());
        refreshPlayer(id);
    }

    public void removeUserPermission(UUID id, String node) {
        UserData ud = getUser(id);
        if (ud != null) ud.removePermission(node);
        plugin.getLogger().info("Removed permission '" + node + "' from user " + id.toString());
        refreshPlayer(id);
    }

    public GroupData createGroup(String name) {
        String key = name.toLowerCase();
        return groups.computeIfAbsent(key, GroupData::new);
    }

    /** The name of the default group implicitly applied to every player. */
    public String getDefaultGroupName() {
        return defaultGroupName;
    }

    public void setDefaultGroupName(String name) {
        this.defaultGroupName = (name == null || name.isBlank()) ? "default" : name.toLowerCase();
    }

    public boolean isDefaultGroup(String name) {
        return name != null && name.equalsIgnoreCase(defaultGroupName);
    }

    /** Ensures the configured default group exists so it can be edited like any other. */
    public void ensureDefaultGroup() {
        createGroup(defaultGroupName);
    }

    // ---- Prefix / suffix / weight (meta) ----

    public void setUserPrefix(UUID id, String prefix) {
        getOrCreateUser(id).setPrefix(prefix);
        refreshPlayer(id);
    }

    public void setUserSuffix(UUID id, String suffix) {
        getOrCreateUser(id).setSuffix(suffix);
        refreshPlayer(id);
    }

    public void setGroupPrefix(String name, String prefix) {
        createGroup(name).setPrefix(prefix);
        refreshGroup(name);
    }

    public void setGroupSuffix(String name, String suffix) {
        createGroup(name).setSuffix(suffix);
        refreshGroup(name);
    }

    public void setGroupWeight(String name, int weight) {
        createGroup(name).setWeight(weight);
        refreshGroup(name);
    }

    /**
     * Resolves the prefix to display for a player: a user-level prefix takes
     * priority, otherwise the prefix of the highest-weight group the player
     * belongs to (the default group included). Returns "" if none is set.
     */
    public String resolvePrefix(UUID id) {
        return resolveMeta(id, true);
    }

    /** @see #resolvePrefix(UUID) */
    public String resolveSuffix(UUID id) {
        return resolveMeta(id, false);
    }

    private String resolveMeta(UUID id, boolean prefix) {
        UserData ud = users.get(id);
        if (ud != null) {
            String own = prefix ? ud.getPrefix() : ud.getSuffix();
            if (own != null) return own;
        }

        // Candidate groups: the player's groups and the default group, plus all
        // of their inherited parents, ranked by weight (highest wins).
        var chain = new java.util.HashSet<String>();
        if (ud != null) {
            for (String g : ud.getGroups()) collectGroupAndParents(g, chain);
        }
        collectGroupAndParents(defaultGroupName, chain);

        java.util.List<GroupData> candidates = new java.util.ArrayList<>();
        for (String g : chain) {
            GroupData gd = groups.get(g);
            if (gd != null) candidates.add(gd);
        }
        candidates.sort((a, b) -> Integer.compare(b.getWeight(), a.getWeight()));

        for (GroupData gd : candidates) {
            String v = prefix ? gd.getPrefix() : gd.getSuffix();
            if (v != null) return v;
        }
        return "";
    }

    /**
     * Collects a group together with all of its parent groups, transitively,
     * into {@code out} (lowercased names). Guards against inheritance cycles by
     * skipping already-visited groups.
     */
    private java.util.Set<String> collectGroupAndParents(String groupName, java.util.Set<String> out) {
        if (groupName == null) return out;
        String key = groupName.toLowerCase();
        if (!out.add(key)) return out; // already visited -> cycle guard
        GroupData gd = groups.get(key);
        if (gd != null) {
            for (String parent : gd.getParents()) {
                collectGroupAndParents(parent, out);
            }
        }
        return out;
    }

    /**
     * Returns true if {@code groupName} inherits from {@code ancestorName}
     * (directly or transitively). Used to reject inheritance cycles.
     */
    public boolean inheritsFrom(String groupName, String ancestorName) {
        if (groupName == null || ancestorName == null) return false;
        return collectGroupAndParents(groupName, new java.util.HashSet<>())
                .contains(ancestorName.toLowerCase());
    }

    /** Adds {@code parentName} as a parent of {@code groupName} (creating the child if needed). */
    public void addGroupParent(String groupName, String parentName) {
        if (groupName == null || parentName == null) return;
        createGroup(groupName).addParent(parentName);
        plugin.getLogger().info("Group '" + groupName + "' now inherits from '" + parentName + "'");
        refreshAfterInheritanceChange();
    }

    /** Removes {@code parentName} from {@code groupName}'s parents. */
    public void removeGroupParent(String groupName, String parentName) {
        if (groupName == null || parentName == null) return;
        GroupData gd = groups.get(groupName.toLowerCase());
        if (gd != null) gd.removeParent(parentName);
        plugin.getLogger().info("Group '" + groupName + "' no longer inherits from '" + parentName + "'");
        refreshAfterInheritanceChange();
    }

    /**
     * Inheritance changes can affect members of descendant groups too, so we
     * refresh every online player. This is a rare admin action, and each
     * per-player refresh is cheap, so a full refresh is acceptable here.
     */
    private void refreshAfterInheritanceChange() {
        if (plugin instanceof FoliaPerms fp) fp.refreshAllAttachments();
    }

    /**
     * Deletes a group and removes it from every user that belonged to it.
     * Online members of the group are refreshed so the change takes effect
     * immediately.
     *
     * @return true if the group existed and was removed, false otherwise.
     */
    public boolean deleteGroup(String name) {
        if (name == null) return false;
        if (isDefaultGroup(name)) {
            plugin.getLogger().warning("Refused to delete the default group '" + name + "'.");
            return false;
        }
        String key = name.toLowerCase();
        GroupData removed = groups.remove(key);
        if (removed == null) return false;

        java.util.Set<UUID> affected = new java.util.HashSet<>();
        for (Map.Entry<UUID, UserData> e : users.entrySet()) {
            if (e.getValue().getGroups().remove(key)) {
                affected.add(e.getKey());
            }
        }
        // Also drop the deleted group from any other group's parents.
        boolean wasParent = false;
        for (GroupData gd : groups.values()) {
            if (gd.getParents().remove(key)) wasParent = true;
        }
        plugin.getLogger().info("Deleted group '" + key + "' (removed from " + affected.size() + " users)");
        if (wasParent) {
            refreshAfterInheritanceChange();
        } else {
            for (UUID id : affected) refreshPlayer(id);
        }
        return true;
    }

    public GroupData getGroup(String name) {
        if (name == null) return null;
        return groups.get(name.toLowerCase());
    }

    /**
     * Returns the player's "primary" group: the highest-weight group they
     * explicitly belong to, falling back to the default group name when the
     * player has no explicit groups. Never null.
     */
    public String getPrimaryGroup(UUID id) {
        UserData ud = users.get(id);
        GroupData best = null;
        if (ud != null) {
            for (String g : ud.getGroups()) {
                GroupData gd = groups.get(g.toLowerCase());
                if (gd != null && (best == null || gd.getWeight() > best.getWeight())) {
                    best = gd;
                }
            }
        }
        return best != null ? best.getName() : defaultGroupName;
    }

    public void addGroupPermission(String name, String node) {
        if (node == null) return;
        String normalized = node.toLowerCase();
        GroupData gd = createGroup(name);
        gd.addPermission(normalized);
        registeredPermissions.add(normalized);
        cachedSortedPermissions = null; // Invalidate cache
        plugin.getLogger().info("Added group permission '" + normalized + "' to group " + name);
        refreshGroup(name);
    }


    public void addUserToGroup(UUID id, String group) {
        UserData ud = getOrCreateUser(id);
        ud.addGroup(group);
        GroupData gd = createGroup(group);
        gd.addMember(id.toString());
        refreshPlayer(id);
    }

    public void removeUserFromGroup(UUID id, String group) {
        if (group == null) return;
        String key = group.toLowerCase();
        UserData ud = getUser(id);
        if (ud != null) ud.removeGroup(group);
        GroupData gd = groups.get(key);
        if (gd != null) gd.removeMember(id.toString());
        plugin.getLogger().info("Removed user " + id + " from group " + group);
        refreshPlayer(id);
    }

    /**
     * Checks if a player has a permission.
     * Supports wildcard permissions (e.g., "plugin.*")
     */
    public boolean hasPermission(UUID id, String node) {
        if (node == null) return false;
        String normalized = node.toLowerCase();
        UserData ud = users.get(id);
        if (ud != null) {
            if (matches(ud.getPermissions(), normalized)) return true;

            // Check groups
            for (String g : ud.getGroups()) {
                if (checkGroupPermission(g, normalized)) return true;
            }
        }
        // The default group applies to everyone, even users with no stored record.
        return checkGroupPermission(defaultGroupName, normalized);
    }

    /**
     * Helper method to check group permissions, including all inherited parent
     * groups (transitively, cycle-safe).
     */
    private boolean checkGroupPermission(String groupName, String node) {
        for (String g : collectGroupAndParents(groupName, new java.util.HashSet<>())) {
            GroupData gd = groups.get(g);
            if (gd != null && matches(gd.getPermissions(), node)) return true;
        }
        return false;
    }

    /**
     * Returns true if {@code node} is granted by the given permission set, taking
     * into account the global wildcard {@code "*"} and parent wildcards
     * (e.g. {@code "a.b.c"} is granted by {@code "a.b.*"} or {@code "a.*"}).
     */
    private boolean matches(java.util.Set<String> perms, String node) {
        if (perms.isEmpty()) return false;
        if (perms.contains(node)) return true;
        if (perms.contains("*")) return true;
        int idx = node.length();
        while ((idx = node.lastIndexOf('.', idx - 1)) > 0) {
            if (perms.contains(node.substring(0, idx) + ".*")) return true;
        }
        return false;
    }

    public boolean groupHasDirectPermission(String name, String node) {
        if (name == null || node == null) return false;
        GroupData gd = groups.get(name.toLowerCase());
        return gd != null && gd.getPermissions().contains(node.toLowerCase());
    }

    public boolean userHasDirectPermission(UUID id, String node) {
        if (id == null || node == null) return false;
        UserData ud = users.get(id);
        return ud != null && ud.getPermissions().contains(node.toLowerCase());
    }

    public void removeGroupPermission(String name, String node) {
        if (name == null || node == null) return;
        GroupData gd = groups.get(name.toLowerCase());
        if (gd != null) gd.removePermission(node.toLowerCase());
        plugin.getLogger().info("Removed permission '" + node + "' from group " + name);
        refreshGroup(name);
    }

    public Map<UUID, UserData> getUsers() {
        return users;
    }

    public Map<String, GroupData> getGroups() {
        return groups;
    }
}