package dev.adminwatch.integration.invsee;

import dev.adminwatch.AdminWatchPlugin;
import dev.adminwatch.config.Settings;
import dev.adminwatch.model.AuditEvent;
import dev.adminwatch.model.Category;
import dev.adminwatch.util.ItemFormatter;
import dev.adminwatch.util.TimeFormats;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * InvSee++ integration.
 * <ul>
 *   <li>Inspection sessions: opened/closed via Bukkit's InventoryOpen/Close events on InvSee++ spectator views.</li>
 *   <li>Modifications: before/after multisets of the inspected inventory (and the admin's own inventory), so slot
 *       rearrangements are never reported as additions or removals.</li>
 *   <li>InvSee++'s own SpectatorInventorySaveEvent is observed to count saves into the target's data.</li>
 * </ul>
 */
public final class InvSeeIntegration implements Listener {

    private static final class Session {
        final UUID adminId;
        final String adminName;
        final UUID targetId;
        final String targetName;
        final String type;
        final Inventory top;
        final Instant opened = Instant.now();
        final String world;
        int modifications, saves;
        final List<String> modLog = new ArrayList<>();
        // pending diff window
        boolean scheduled;
        Map<ItemStack, Integer> beforeTarget, beforeAdmin;

        Session(Player admin, UUID targetId, String targetName, String type, Inventory top) {
            this.adminId = admin.getUniqueId();
            this.adminName = admin.getName();
            this.targetId = targetId;
            this.targetName = targetName;
            this.type = type;
            this.top = top;
            this.world = admin.getWorld().getName();
        }
    }

    private final AdminWatchPlugin plugin;
    private final Plugin invsee;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public InvSeeIntegration(AdminWatchPlugin plugin, Plugin invsee) {
        this.plugin = plugin;
        this.invsee = invsee;
    }

    @SuppressWarnings("unchecked")
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        try {
            Class<?> saveEvent = Class.forName("com.janboerman.invsee.spigot.api.event.SpectatorInventorySaveEvent",
                    false, invsee.getClass().getClassLoader());
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) saveEvent, this, EventPriority.MONITOR,
                    (listener, event) -> onSave(event), plugin, true);
        } catch (ClassNotFoundException | ClassCastException ex) {
            plugin.getLogger().info("InvSee++ SpectatorInventorySaveEvent not found; save counting disabled.");
        }
        plugin.getLogger().info("InvSee++ integration active (" + invsee.getPluginMeta().getVersion() + ").");
    }

    public void stop() {
        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.adminId);
            if (s.scheduled && p != null) finishDiff(s, p);
            finalizeSession(s, "Server shutdown / reload");
        }
        sessions.clear();
        HandlerList.unregisterAll(this);
    }

    public int openSessions() { return sessions.size(); }

    // ------------------------------------------------------------------ open / close

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player admin)) return;
        Inventory top = e.getView().getTopInventory();
        if (!InvSeeSupport.isSpectator(top) || !plugin.audit().isMonitored(admin)) return;
        String type = InvSeeSupport.typeLabel(top);
        UUID targetId = InvSeeSupport.targetId(top);
        String targetName = InvSeeSupport.targetName(top);
        if (type == null || targetName == null) return;
        Settings s = plugin.settings();
        if (s.invseeIgnoreSelf && admin.getUniqueId().equals(targetId)) return;
        if (plugin.audit().worldIgnored(Category.INVENTORY_INSPECTIONS, admin.getWorld().getName())) return;
        Session old = sessions.put(admin.getUniqueId(), new Session(admin, targetId, targetName, type, top));
        if (old != null) finalizeSession(old, "Replaced by another inspection");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.top != e.getView().getTopInventory()) return;
        sessions.remove(p.getUniqueId());
        if (s.scheduled) finishDiff(s, p);
        finalizeSession(s, null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.remove(e.getPlayer().getUniqueId());
        if (s == null) return;
        if (s.scheduled) finishDiff(s, e.getPlayer());
        finalizeSession(s, "Administrator disconnected");
    }

    private void finalizeSession(Session s, String note) {
        Instant closed = Instant.now();
        TimeFormats t = plugin.settings().time;
        AuditEvent.Builder ev = AuditEvent.builder(Category.INVENTORY_INSPECTIONS, "INSPECTION")
                .time(closed)
                .admin(s.adminName, s.adminId).target(s.targetName, s.targetId).world(s.world)
                .summary("Administrator inspected another player's " + s.type.toLowerCase())
                .field("Inventory", s.type)
                .field("Opened", t.shortTime(s.opened))
                .field("Closed", t.shortTime(closed))
                .field("Duration", TimeFormats.duration(Duration.between(s.opened, closed)))
                .field("Modifications", String.valueOf(s.modifications))
                .field("Saved To Target", s.saves > 0 ? s.saves + " time(s)" : null)
                .field("Modification Summary", String.join("\n", s.modLog))
                .field("Note", note);
        plugin.audit().record(ev.build());
    }

    // ------------------------------------------------------------------ modification tracking

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        track(e.getWhoClicked(), e.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        track(e.getWhoClicked(), e.getView().getTopInventory());
    }

    /** Runs BEFORE the server applies the click, so the snapshot is the pre-click state. */
    private void track(Object who, Inventory top) {
        if (!(who instanceof Player p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.top != top || s.scheduled) return;
        s.beforeTarget = ItemFormatter.counts(top.getContents());
        s.beforeAdmin = ItemFormatter.counts(p.getInventory().getContents());
        s.scheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (s.scheduled) finishDiff(s, p);
        }, 2L);
    }

    private void finishDiff(Session s, Player admin) {
        s.scheduled = false;
        Map<ItemStack, Integer> afterTarget = ItemFormatter.counts(s.top.getContents());
        Map<ItemStack, Integer> afterAdmin = admin.isOnline()
                ? ItemFormatter.counts(admin.getInventory().getContents()) : s.beforeAdmin;
        ItemFormatter.Diff target = ItemFormatter.diff(s.beforeTarget, afterTarget);
        if (target.isEmpty()) return; // pure slot rearrangement or nothing happened
        ItemFormatter.Diff own = ItemFormatter.diff(s.beforeAdmin, afterAdmin);

        // exactly one stack type swapped for another, admin inventory untouched -> REPLACED
        if (target.removed.size() == 1 && target.added.size() == 1 && own.isEmpty()) {
            var oldE = target.removed.entrySet().iterator().next();
            var newE = target.added.entrySet().iterator().next();
            emit(s, "REPLACED", "Replaced", newE.getKey(), newE.getValue(), oldE.getKey(), oldE.getValue(), null);
            return;
        }
        for (var en : target.added.entrySet()) {
            int fromAdmin = Math.min(en.getValue(), own.removed.getOrDefault(en.getKey(), 0));
            if (fromAdmin > 0) emit(s, "TRANSFERRED", "Transferred", en.getKey(), fromAdmin, null, 0, "Admin inventory -> target");
            int rest = en.getValue() - fromAdmin;
            if (rest > 0) emit(s, "ADDED", "Added", en.getKey(), rest, null, 0, null);
        }
        for (var en : target.removed.entrySet()) {
            int toAdmin = Math.min(en.getValue(), own.added.getOrDefault(en.getKey(), 0));
            if (toAdmin > 0) emit(s, "TRANSFERRED", "Transferred", en.getKey(), toAdmin, null, 0, "Target -> admin inventory");
            int rest = en.getValue() - toAdmin;
            if (rest > 0) emit(s, "REMOVED", "Removed", en.getKey(), rest, null, 0, null);
        }
    }

    private void emit(Session s, String action, String label, ItemStack item, int amount,
                      ItemStack previous, int previousAmount, String direction) {
        s.modifications++;
        Instant now = Instant.now();
        TimeFormats t = plugin.settings().time;
        String line = ItemFormatter.line(item, amount);
        if (s.modLog.size() < 15) s.modLog.add(t.shortTime(now) + " " + label + ": " + line
                + (previous != null ? " (was " + ItemFormatter.line(previous, previousAmount) + ")" : ""));
        AuditEvent.Builder ev = AuditEvent.builder(Category.INVENTORY_MODIFICATIONS, action)
                .time(now)
                .admin(s.adminName, s.adminId).target(s.targetName, s.targetId).world(s.world)
                .summary("Administrator modified another player's " + s.type.toLowerCase())
                .field("Inventory", s.type)
                .field("Action", label)
                .field("Item", line)
                .field("Item Metadata", ItemFormatter.details(item))
                .field("Direction", direction);
        if (action.equals("REMOVED") || (direction != null && direction.startsWith("Target"))) {
            ev.alert(plugin.settings().valuableTakeReason(item.getType(), amount));
        }
        if (previous != null) {
            ev.field("Previous Item", ItemFormatter.describe(previous, previousAmount));
            ev.field("Resulting Item", ItemFormatter.describe(item, amount));
        }
        plugin.audit().record(ev.build());
    }

    // ------------------------------------------------------------------ InvSee++ save event (via reflection)

    private void onSave(Event event) {
        Object inv = InvSeeSupport.callGetter(event, "getInventory");
        if (!(inv instanceof Inventory spectator)) return;
        UUID target = InvSeeSupport.targetId(spectator);
        if (target == null) return;
        for (Session s : sessions.values()) {
            if (target.equals(s.targetId) && s.top == spectator) s.saves++;
        }
    }
}
