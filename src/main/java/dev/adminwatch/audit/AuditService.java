package dev.adminwatch.audit;

import dev.adminwatch.AdminWatchPlugin;
import dev.adminwatch.config.Settings;
import dev.adminwatch.model.AuditEvent;
import dev.adminwatch.model.Category;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Single entry point for every module: stores the event in SQLite (always) and then queues it on the
 * webhook of its own category (only if that webhook is active). Safe to call from any thread.
 */
public final class AuditService {
    private final AdminWatchPlugin plugin;

    public AuditService(AdminWatchPlugin plugin) {
        this.plugin = plugin;
    }

    /** True if this player is audited (holds the monitor permission and is not in ignored-players). */
    public boolean isMonitored(Player p) {
        Settings s = plugin.settings();
        if (!p.hasPermission(s.monitorPermission)) return false;
        return !s.ignoredPlayers.contains(p.getName().toLowerCase(Locale.ROOT))
                && !s.ignoredPlayers.contains(p.getUniqueId().toString());
    }

    public boolean worldIgnored(Category c, String world) {
        return plugin.settings().worldIgnored(c, world);
    }

    public void record(AuditEvent event) {
        String initial = plugin.webhooks().initialStatus(event.category());
        plugin.database().insert(event, initial).whenComplete((id, err) ->
                plugin.webhooks().submit(event, (err != null || id == null) ? -1L : id));
    }
}
