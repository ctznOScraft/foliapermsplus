package kaiakk.foliaPerms.events;

import io.papermc.paper.event.player.AsyncChatEvent;
import kaiakk.foliaPerms.FoliaPerms;
import kaiakk.foliaPerms.permissions.PermissionService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Renders chat messages as {@code prefix + name + suffix: message}, using the
 * prefix/suffix resolved by {@link PermissionService}. The renderer runs off the
 * main thread (AsyncChatEvent), which is fine since the permission data is held
 * in concurrent maps.
 */
public class ChatListener implements Listener {
    private final FoliaPerms plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();

    public ChatListener(FoliaPerms plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        PermissionService service = plugin.getPermissionService();
        if (service == null) return;

        event.renderer((source, sourceDisplayName, message, viewer) -> {
            String prefix = service.resolvePrefix(source.getUniqueId());
            String suffix = service.resolveSuffix(source.getUniqueId());
            return Component.empty()
                    .append(legacy.deserialize(prefix))
                    .append(sourceDisplayName)
                    .append(legacy.deserialize(suffix))
                    .append(Component.text(": "))
                    .append(message);
        });
    }
}
