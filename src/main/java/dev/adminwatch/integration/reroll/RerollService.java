package dev.adminwatch.integration.reroll;

import dev.adminwatch.AdminWatchPlugin;
import dev.adminwatch.api.AdminRerollEvent;
import dev.adminwatch.api.RerollReport;
import dev.adminwatch.model.AuditEvent;
import dev.adminwatch.model.Category;
import dev.adminwatch.util.ItemFormatter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/** Converts {@link RerollReport}s (from adapters or {@link AdminRerollEvent}) into audit events. */
public final class RerollService implements Listener {
    private final AdminWatchPlugin plugin;

    public RerollService(AdminWatchPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRerollEvent(AdminRerollEvent e) {
        record(e.getReport());
    }

    public void record(RerollReport r) {
        if (plugin.audit().worldIgnored(Category.INVENTORY_REROLLS, r.world())) return;
        AuditEvent.Builder ev = AuditEvent.builder(Category.INVENTORY_REROLLS, r.type().name())
                .admin(r.adminName(), r.adminUuid())
                .target(r.targetName(), r.targetUuid())
                .world(r.world())
                .summary("Administrator restored a player's " + r.type().label().toLowerCase())
                .field("Type", r.type().label())
                .field("Status", r.status().label());

        Map<ItemStack, Integer> before = r.previous() == null ? null : ItemFormatter.counts(r.previous());
        Map<ItemStack, Integer> after = r.restored() == null ? null : ItemFormatter.counts(r.restored());
        if (after != null) {
            ev.field("Items Restored", String.valueOf(after.values().stream().mapToInt(Integer::intValue).sum()));
        }
        if (before != null && after != null) {
            ItemFormatter.Diff d = ItemFormatter.diff(before, after);
            ev.field("Items Added", d.added.isEmpty() ? null : ItemFormatter.describeMap(d.added, 12));
            ev.field("Items Removed", d.removed.isEmpty() ? null : ItemFormatter.describeMap(d.removed, 12));
            if (!d.added.isEmpty() && !d.removed.isEmpty()) {
                ev.field("Items Replaced / Changed", "Added " + d.addedTotal() + " item(s), removed " + d.removedTotal() + " item(s)");
            }
            if (d.isEmpty()) ev.field("Items Added", "No difference between previous and restored contents");
        }
        if (before != null) {
            ev.field("Previous Snapshot", snapshotText(before));
        }
        if (after != null) {
            ev.field("Restored Snapshot", snapshotText(after));
        }
        ev.field("Source Snapshot ID", r.snapshotId() == null ? "not provided by the restoration plugin" : r.snapshotId());
        ev.field("Source", r.source());
        ev.field("Note", r.note());
        plugin.audit().record(ev.build());
    }

    private static String snapshotText(Map<ItemStack, Integer> m) {
        int total = m.values().stream().mapToInt(Integer::intValue).sum();
        String list = ItemFormatter.describeMap(m, 10);
        return total + " item(s) in " + m.size() + " type(s)" + (list.isEmpty() ? "" : "\n" + list);
    }
}
