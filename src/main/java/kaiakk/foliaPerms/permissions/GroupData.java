package kaiakk.foliaPerms.permissions;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GroupData {
    private final String name;
    private final Set<String> permissions = ConcurrentHashMap.newKeySet();
    private final Set<String> members = ConcurrentHashMap.newKeySet();

    // Chat/meta data (LuckPerms-style). Null means "not set".
    private volatile String prefix;
    private volatile String suffix;
    // Higher weight wins when resolving which group's prefix/suffix to display.
    private volatile int weight = 0;

    public GroupData(String name) {
        this.name = name.toLowerCase();
    }

    public String getName() {
        return name;
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

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public Set<String> getMembers() {
        return members;
    }

    public void addPermission(String node) {
        permissions.add(node.toLowerCase());
    }

    public void removePermission(String node) {
        permissions.remove(node.toLowerCase());
    }

    public void addMember(String uuid) {
        members.add(uuid);
    }

    public void removeMember(String uuid) {
        members.remove(uuid);
    }
}