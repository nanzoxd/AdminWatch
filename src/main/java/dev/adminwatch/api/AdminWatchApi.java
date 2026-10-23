package dev.adminwatch.api;

import dev.adminwatch.AdminWatchPlugin;
import dev.adminwatch.integration.reroll.RerollAdapter;
import org.bukkit.Bukkit;

/** Public API for other plugins. */
public final class AdminWatchApi {
    private AdminWatchApi() {}

    /** Reports a reroll. Equivalent to firing {@link AdminRerollEvent}. */
    public static void reportReroll(RerollReport report) {
        Bukkit.getPluginManager().callEvent(new AdminRerollEvent(report, !Bukkit.isPrimaryThread()));
    }

    /**
     * Registers a custom adapter that detects rerolls of another restoration plugin.
     * The adapter is started immediately and restarted on /adminwatch reload.
     */
    public static void registerRerollAdapter(RerollAdapter adapter) {
        AdminWatchPlugin plugin = AdminWatchPlugin.get();
        if (plugin != null) plugin.integrations().registerExternalAdapter(adapter);
    }
}
