package dev.adminwatch.integration.reroll;

import dev.adminwatch.AdminWatchPlugin;
import dev.adminwatch.api.RerollReport;
import dev.adminwatch.api.RerollStatus;
import dev.adminwatch.api.RerollType;
import dev.adminwatch.integration.invsee.InvSeeSupport;
import dev.adminwatch.util.ItemFormatter;
import dev.adminwatch.util.SensitiveFilter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter for InventoryRollbackPlus (verified against version 1.8.5).
 * <p>
 * IMPORTANT: InventoryRollbackPlus 1.8.5 exposes NO Bukkit events and NO public API for restores, so a restore
 * cannot be observed directly. This adapter therefore uses an observation strategy and reports only what it can
 * verify:
 * <ol>
 *   <li>A monitored admin runs {@code /ir restore [player]} (aliases ir, irp, inventoryrollback,
 *       inventoryrollbackplus). That opens an observation session.</li>
 *   <li>While the session is open, every click inside a chest-type GUI that belongs to neither InvSee++ nor
 *       a real container takes a snapshot of the target's inventory and ender chest (the named player, or all
 *       online players when the admin used the player-picker GUI).</li>
 *   <li>Shortly after the click the snapshots are compared. If the contents of an online player's inventory or
 *       ender chest changed, a reroll is reported with exact before/after contents. Restores that change nothing
 *       are not reported.</li>
 * </ol>
 * Limitations: restores for OFFLINE players change files only and cannot be detected; the source snapshot id is
 * not readable; changes by other plugins in the same 2-20 tick window could be attributed to the click.
 */
public final class IrpAdapter implements RerollAdapter, Listener {
    private static final Set<String> LABELS = Set.of("ir", "irp", "inventoryrollback", "inventoryrollbackplus");

    private record Session(String targetName, long expiresMs) {}

    private record Snap(Player player, ItemStack[] inv, ItemStack[] ender) {}

    private final AdminWatchPlugin plugin;
    private final RerollService service;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public IrpAdapter(AdminWatchPlugin plugin, RerollService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public String id() { return "InventoryRollbackPlus"; }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        sessions.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String line = e.getMessage().substring(1).trim();
        if (line.isEmpty() || !LABELS.contains(SensitiveFilter.labelOf(line))) return;
        String[] a = line.split("\\s+");
        if (a.length < 2 || !a[1].equalsIgnoreCase("restore")) return;
        Player admin = e.getPlayer();
        if (!plugin.audit().isMonitored(admin)) return;
        long expires = System.currentTimeMillis() + plugin.settings().irpSessionSeconds * 1000L;
        sessions.put(admin.getUniqueId(), new Session(a.length >= 3 ? a[2] : null, expires));
    }

    /** LOWEST: runs before InventoryRollbackPlus handles the click, so the snapshot is the "before" state. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player admin)) return;
        Session session = sessions.get(admin.getUniqueId());
        if (session == null) return;
        if (System.currentTimeMillis() > session.expiresMs()) {
            sessions.remove(admin.getUniqueId());
            return;
        }
        Inventory top = e.getView().getTopInventory();
        if (e.getClickedInventory() != top || top.getType() != InventoryType.CHEST) return;
        if (InvSeeSupport.isSpectator(top) || top.getLocation() != null) return; // only virtual GUIs

        List<Snap> snaps = new ArrayList<>();
        if (session.targetName() != null) {
            Player t = Bukkit.getPlayerExact(session.targetName());
            if (t != null) snaps.add(snapshot(t));
        } else {
            for (Player t : Bukkit.getOnlinePlayers()) snaps.add(snapshot(t));
        }
        if (snaps.isEmpty()) return;
        String world = admin.getWorld().getName();
        UUID adminId = admin.getUniqueId();
        String adminName = admin.getName();
        Bukkit.getScheduler().runTaskLater(plugin, () -> check(snaps, adminId, adminName, world, true), 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
    }

    private static Snap snapshot(Player p) {
        return new Snap(p, clone(p.getInventory().getContents()), clone(p.getEnderChest().getContents()));
    }

    private static ItemStack[] clone(ItemStack[] in) {
        ItemStack[] out = new ItemStack[in.length];
        for (int i = 0; i < in.length; i++) out[i] = in[i] == null ? null : in[i].clone();
        return out;
    }

    private void check(List<Snap> snaps, UUID adminId, String adminName, String world, boolean retry) {
        boolean found = false;
        for (Snap s : snaps) {
            Player t = s.player();
            if (!t.isOnline()) continue;
            ItemStack[] invNow = t.getInventory().getContents();
            ItemStack[] enderNow = t.getEnderChest().getContents();
            found |= report(RerollType.INVENTORY, s.inv(), invNow, t, adminId, adminName, world);
            found |= report(RerollType.ENDER_CHEST, s.ender(), enderNow, t, adminId, adminName, world);
        }
        if (!found && retry) { // restore may complete a little later (async data loading)
            Bukkit.getScheduler().runTaskLater(plugin, () -> check(snaps, adminId, adminName, world, false), 18L);
        }
    }

    private boolean report(RerollType type, ItemStack[] before, ItemStack[] after, Player target,
                           UUID adminId, String adminName, String world) {
        ItemFormatter.Diff d = ItemFormatter.diff(ItemFormatter.counts(before), ItemFormatter.counts(after));
        if (d.isEmpty()) return false;
        service.record(RerollReport.builder(type, RerollStatus.SUCCESSFUL)
                .admin(adminName, adminId)
                .target(target.getName(), target.getUniqueId())
                .world(world)
                .previous(before).restored(after)
                .source("InventoryRollbackPlus (detected via GUI click + before/after snapshot)")
                .note("Detected by comparing the target's contents before and after the admin's click in the restore GUI.")
                .build());
        return true;
    }
}
