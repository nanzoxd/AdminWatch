package dev.adminwatch;

import dev.adminwatch.audit.AuditService;
import dev.adminwatch.command.AdminWatchCommand;
import dev.adminwatch.config.Settings;
import dev.adminwatch.db.DatabaseManager;
import dev.adminwatch.discord.WebhookManager;
import dev.adminwatch.integration.IntegrationManager;
import dev.adminwatch.listener.BlockChangeListener;
import dev.adminwatch.listener.CommandBlockListener;
import dev.adminwatch.listener.CommandListener;
import dev.adminwatch.listener.CreativeItemListener;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;

public final class AdminWatchPlugin extends JavaPlugin {
    private static AdminWatchPlugin instance;

    private volatile Settings settings;
    private DatabaseManager database;
    private WebhookManager webhooks;
    private AuditService audit;
    private IntegrationManager integrations;
    private CommandBlockListener commandBlockListener;
    private BukkitTask cleanupTask;
    private final Instant startedAt = Instant.now();

    public static AdminWatchPlugin get() { return instance; }

    public Settings settings() { return settings; }
    public DatabaseManager database() { return database; }
    public WebhookManager webhooks() { return webhooks; }
    public AuditService audit() { return audit; }
    public IntegrationManager integrations() { return integrations; }
    public Instant startedAt() { return startedAt; }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        settings = new Settings(getConfig(), getLogger());

        database = new DatabaseManager(this);
        database.init();
        webhooks = new WebhookManager(this, settings);
        audit = new AuditService(this);

        Bukkit.getPluginManager().registerEvents(new CommandListener(this), this);
        Bukkit.getPluginManager().registerEvents(new CreativeItemListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BlockChangeListener(this), this);
        commandBlockListener = new CommandBlockListener(this);
        Bukkit.getPluginManager().registerEvents(commandBlockListener, this);
        commandBlockListener.start();

        integrations = new IntegrationManager(this);
        integrations.start();

        PluginCommand cmd = getCommand("adminwatch");
        if (cmd != null) {
            AdminWatchCommand handler = new AdminWatchCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
        scheduleCleanup();
        getLogger().info("AdminWatch enabled. Webhooks active: " + activeWebhookCount() + "/"
                + webhookSlots()
                + ", database " + (database.isAvailable() ? "ready" : "UNAVAILABLE"));
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) cleanupTask.cancel();
        if (commandBlockListener != null) commandBlockListener.stop();
        if (integrations != null) integrations.stop();   // finalises open inspection sessions
        if (database != null) database.flush(5000);      // make sure queued rows are written
        if (webhooks != null) webhooks.shutdown(5000);   // try to deliver what is still queued
        if (database != null) database.close();
        instance = null;
    }

    /** Re-reads config.yml. Database file changes need a restart. */
    public void reload() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        webhooks.reconfigure(settings);
        integrations.restart();
        scheduleCleanup();
    }

    public int activeWebhookCount() {
        int n = 0;
        for (var c : dev.adminwatch.model.Category.values()) {
            if (c != dev.adminwatch.model.Category.INVENTORY_MODIFICATIONS && settings.webhook(c).deliverable()) n++;
        }
        return n;
    }

    public int webhookSlots() { return dev.adminwatch.model.Category.values().length - 1; }

    private void scheduleCleanup() {
        if (cleanupTask != null) cleanupTask.cancel();
        long period = 20L * 60 * settings.cleanupMinutes;
        cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            int days = settings.retentionDays;
            if (days <= 0) return;
            database.cleanup(days).thenAccept(n -> {
                if (n != null && n > 0) getLogger().info("Retention cleanup removed " + n + " old audit record(s).");
            });
        }, 20L * 30, period);
    }
}
