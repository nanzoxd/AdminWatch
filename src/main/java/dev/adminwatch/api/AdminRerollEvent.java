package dev.adminwatch.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Integration point for inventory restoration / reroll plugins.
 * <p>
 * A plugin that performs a reroll can fire this event (from any thread; set {@code async} accordingly)
 * and AdminWatch will store it and send it to the "inventory_rerolls" webhook:
 * <pre>
 * RerollReport r = RerollReport.builder(RerollType.ENDER_CHEST, RerollStatus.SUCCESSFUL)
 *         .admin(adminPlayer).target(target.getName(), target.getUniqueId())
 *         .previous(before).restored(after).snapshotId("2026-10-23-14-40").source("MyRollbackPlugin").build();
 * Bukkit.getPluginManager().callEvent(new AdminRerollEvent(r, !Bukkit.isPrimaryThread()));
 * </pre>
 * {@link AdminWatchApi#reportReroll(RerollReport)} does exactly that.
 */
public class AdminRerollEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final RerollReport report;

    public AdminRerollEvent(RerollReport report, boolean async) {
        super(async);
        this.report = report;
    }

    public RerollReport getReport() { return report; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
