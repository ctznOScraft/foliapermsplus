package kaiakk.foliaPerms.permissions;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class UserData {
    private final UUID id;
    private final Set<String> permissions = ConcurrentHashMap.newKeySet();
    private final Set<String> groups = ConcurrentHashMap.newKeySet();

    // Per-user prefix/suffix overrides (take priority over group meta). Null = not set.
    private volatile String prefix;
    private volatile String suffix;

    public UserData(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = (prefix == null || prefix.isEmpty()) ? null : prefix;
    }

    public String getSuffix() {
        return suffix;
    }

    public void setSuffix(String suffix) {
        this.suffix = (suffix == null || suffix.isEmpty()) ? null : suffix;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public Set<String> getGroups() {
        return groups;
    }

    public void addPermission(String node) {
        permissions.add(node.toLowerCase());
    }

    public void removePermission(String node) {
        permissions.remove(node.toLowerCase());
    }

    public void addGroup(String group) {
        groups.add(group.toLowerCase());
    }

    public void removeGroup(String group) {
        groups.remove(group.toLowerCase());
    }
}