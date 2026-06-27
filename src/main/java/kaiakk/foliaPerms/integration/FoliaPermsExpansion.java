package kaiakk.foliaPerms.integration;

import kaiakk.foliaPerms.FoliaPerms;
import kaiakk.foliaPerms.permissions.GroupData;
import kaiakk.foliaPerms.permissions.PermissionService;
import kaiakk.foliaPerms.permissions.UserData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.UUID;

/**
 * PlaceholderAPI expansion exposing FoliaPerms meta to plugins like TAB.
 *
 * <p>Available placeholders (identifier {@code foliaperms}):
 * <ul>
 *   <li>{@code %foliaperms_prefix%} – resolved prefix</li>
 *   <li>{@code %foliaperms_suffix%} – resolved suffix</li>
 *   <li>{@code %foliaperms_group%} / {@code %foliaperms_primary_group%} – primary group</li>
 *   <li>{@code %foliaperms_weight%} – weight of the primary group</li>
 *   <li>{@code %foliaperms_groups%} – comma-separated list of groups</li>
 * </ul>
 */
public class FoliaPermsExpansion extends PlaceholderExpansion {
    private final FoliaPerms plugin;
    private final PermissionService service;

    public FoliaPermsExpansion(FoliaPerms plugin) {
        this.plugin = plugin;
        this.service = plugin.getPermissionService();
    }

    @Override
    public String getIdentifier() {
        return "foliaperms";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        // Keep the expansion registered across PlaceholderAPI reloads.
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (service == null || player == null || params == null) return "";
        UUID id = player.getUniqueId();

        switch (params.toLowerCase()) {
            case "prefix":
                return service.resolvePrefix(id);
            case "suffix":
                return service.resolveSuffix(id);
            case "group":
            case "primary_group":
                return service.getPrimaryGroup(id);
            case "weight": {
                GroupData gd = service.getGroup(service.getPrimaryGroup(id));
                return String.valueOf(gd != null ? gd.getWeight() : 0);
            }
            case "groups": {
                UserData ud = service.getUser(id);
                if (ud == null || ud.getGroups().isEmpty()) return service.getDefaultGroupName();
                return String.join(", ", ud.getGroups());
            }
            default:
                return null; // unknown placeholder
        }
    }
}
