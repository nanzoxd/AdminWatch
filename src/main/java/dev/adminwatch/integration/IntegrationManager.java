package dev.adminwatch.integration;

import dev.adminwatch.AdminWatchPlugin;
import dev.adminwatch.config.Settings;
import dev.adminwatch.integration.invsee.InvSeeIntegration;
import dev.adminwatch.integration.reroll.IrpAdapter;
import dev.adminwatch.integration.reroll.RerollAdapter;
import dev.adminwatch.integration.reroll.RerollService;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/** Starts / stops all optional plugin integrations. */
public final class IntegrationManager {
    private final AdminWatchPlugin plugin;
    private InvSeeIntegration invsee;
    private RerollService rerollService;
    private final List<RerollAdapter> builtIn = new ArrayList<>();
    private final List<RerollAdapter> external = new ArrayList<>();

    public IntegrationManager(AdminWatchPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Settings s = plugin.settings();

        if (s.invseeEnabled) {
            Plugin p = Bukkit.getPluginManager().getPlugin(s.invseePlugin);
            if (p != null && p.isEnabled()) {
                invsee = new InvSeeIntegration(plugin, p);
                invsee.start();
            } else {
                plugin.getLogger().info("InvSee++ ('" + s.invseePlugin + "') not found - inventory inspection logging inactive.");
            }
        }

        rerollService = new RerollService(plugin);
        if (s.rerollApi) Bukkit.getPluginManager().registerEvents(rerollService, plugin);

        if (s.irpEnabled) {
            Plugin p = Bukkit.getPluginManager().getPlugin(s.irpPlugin);
            if (p != null && p.isEnabled()) {
                IrpAdapter adapter = new IrpAdapter(plugin, rerollService);
                adapter.start();
                builtIn.add(adapter);
                plugin.getLogger().info("InventoryRollbackPlus adapter active (snapshot-comparison mode; see README).");
            } else {
                plugin.getLogger().info("InventoryRollbackPlus ('" + s.irpPlugin + "') not found - adapter inactive.");
            }
        }
        for (RerollAdapter a : external) a.start();
    }

    public void stop() {
        for (RerollAdapter a : builtIn) a.stop();
        builtIn.clear();
        for (RerollAdapter a : external) a.stop();
        if (invsee != null) invsee.stop();
        invsee = null;
        if (rerollService != null) HandlerList.unregisterAll(rerollService);
        rerollService = null;
    }

    public void restart() {
        stop();
        start();
    }

    /** Called by AdminWatchApi.registerRerollAdapter. */
    public void registerExternalAdapter(RerollAdapter adapter) {
        external.add(adapter);
        adapter.start();
    }

    public RerollService rerollService() { return rerollService; }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("InvSee++ ").append(invsee == null ? "inactive" : "active (" + invsee.openSessions() + " open session(s))");
        sb.append(", reroll API ").append(plugin.settings().rerollApi ? "on" : "off");
        for (RerollAdapter a : builtIn) sb.append(", ").append(a.id()).append(" adapter active");
        for (RerollAdapter a : external) sb.append(", ").append(a.id()).append(" (external) active");
        return sb.toString();
    }
}
