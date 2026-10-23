package dev.adminwatch.listener;

import dev.adminwatch.AdminWatchPlugin;
import dev.adminwatch.config.Settings;
import dev.adminwatch.model.AuditEvent;
import dev.adminwatch.model.Category;
import dev.adminwatch.util.SensitiveFilter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CommandBlock;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Command block placement, destruction and command edits.
 * <p>
 * Bukkit has no event for the command-block GUI. Edits are therefore observed by "watching": when a monitored
 * player right-clicks a command block, the block's command is compared every half second for a configurable time
 * and any change is logged (old and new command).
 */
public final class CommandBlockListener implements Listener {
    private static final class Watch {
        final Location location;
        String lastCommand;
        long expiresMs;

        Watch(Location location, String lastCommand, long expiresMs) {
            this.location = location;
            this.lastCommand = lastCommand;
            this.expiresMs = expiresMs;
        }
    }

    private final AdminWatchPlugin plugin;
    private final Map<UUID, Watch> watches = new HashMap<>();
    private BukkitTask task;

    public CommandBlockListener(AdminWatchPlugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isCommandBlock(Material m) {
        return m == Material.COMMAND_BLOCK || m == Material.CHAIN_COMMAND_BLOCK || m == Material.REPEATING_COMMAND_BLOCK;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::poll, 10L, 10L);
    }

    public void stop() {
        if (task != null) task.cancel();
        watches.clear();
    }

    // ------------------------------------------------------------------ placement / destruction

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!isCommandBlock(b.getType()) || !eligible(e.getPlayer(), b.getWorld().getName())) return;
        AuditEvent.Builder ev = base("PLACED", e.getPlayer(), b.getLocation(), b.getType().name())
                .summary("Administrator placed a command block")
                .alert(alertReason("Command block placed"))
                .field("Action", "Placed")
                .field("Block State", b.getBlockData().getAsString());
        String cmd = commandOf(b.getState(false));
        if (cmd != null && !cmd.isEmpty() && plugin.settings().cbLogText) {
            ev.field("Command", SensitiveFilter.redactText(cmd));
        }
        plugin.audit().record(ev.build());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!isCommandBlock(b.getType()) || !eligible(e.getPlayer(), b.getWorld().getName())) return;
        AuditEvent.Builder ev = base("BROKEN", e.getPlayer(), b.getLocation(), b.getType().name())
                .summary("Administrator destroyed a command block")
                .field("Action", "Broken");
        String cmd = commandOf(b.getState(false)); // BlockBreakEvent fires before removal
        if (cmd != null && !cmd.isEmpty() && plugin.settings().cbLogText) {
            ev.field("Command at removal", SensitiveFilter.redactText(cmd));
        }
        plugin.audit().record(ev.build());
        watches.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMinecartPlace(EntityPlaceEvent e) {
        Player p = e.getPlayer();
        if (p == null || e.getEntityType() != EntityType.COMMAND_BLOCK_MINECART) return;
        if (!eligible(p, e.getEntity().getWorld().getName())) return;
        plugin.audit().record(base("PLACED", p, e.getEntity().getLocation(), "COMMAND_BLOCK_MINECART")
                .summary("Administrator placed a command block minecart")
                .alert(alertReason("Command block minecart placed"))
                .field("Action", "Placed").build());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMinecartDestroy(VehicleDestroyEvent e) {
        if (e.getVehicle().getType() != EntityType.COMMAND_BLOCK_MINECART) return;
        if (!(e.getAttacker() instanceof Player p) || !eligible(p, e.getVehicle().getWorld().getName())) return;
        plugin.audit().record(base("BROKEN", p, e.getVehicle().getLocation(), "COMMAND_BLOCK_MINECART")
                .summary("Administrator destroyed a command block minecart")
                .field("Action", "Broken").build());
    }

    // ------------------------------------------------------------------ command edits

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || !isCommandBlock(b.getType()) || !eligible(e.getPlayer(), b.getWorld().getName())) return;
        String cmd = commandOf(b.getState(false));
        if (cmd == null) return;
        long expires = System.currentTimeMillis() + plugin.settings().cbWatchSeconds * 1000L;
        watches.put(e.getPlayer().getUniqueId(), new Watch(b.getLocation(), cmd, expires));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        watches.remove(e.getPlayer().getUniqueId());
    }

    private void poll() {
        if (watches.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Watch>> it = watches.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Watch> en = it.next();
            Watch w = en.getValue();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || now > w.expiresMs || w.location.getWorld() == null) {
                it.remove();
                continue;
            }
            String cur = commandOf(w.location.getBlock().getState(false));
            if (cur == null) { // block no longer exists
                it.remove();
                continue;
            }
            if (!cur.equals(w.lastCommand)) {
                Settings s = plugin.settings();
                if (s.cbLogText) {
                    plugin.audit().record(base("COMMAND_CHANGED", p, w.location, w.location.getBlock().getType().name())
                            .summary("Administrator changed a command block's command")
                            .field("Action", "Command changed")
                            .field("Old command", w.lastCommand.isEmpty() ? "(empty)" : SensitiveFilter.redactText(w.lastCommand))
                            .field("New command", cur.isEmpty() ? "(empty)" : SensitiveFilter.redactText(cur))
                            .build());
                } else {
                    plugin.audit().record(base("COMMAND_CHANGED", p, w.location, w.location.getBlock().getType().name())
                            .summary("Administrator changed a command block's command (text logging disabled)")
                            .field("Action", "Command changed").build());
                }
                w.lastCommand = cur;
                w.expiresMs = now + s.cbWatchSeconds * 1000L;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean eligible(Player p, String world) {
        return plugin.audit().isMonitored(p) && !plugin.audit().worldIgnored(Category.COMMAND_BLOCKS, world);
    }

    private String alertReason(String reason) {
        Settings s = plugin.settings();
        return s.alertsEnabled && s.alertCommandBlocks ? reason : null;
    }

    private static String commandOf(BlockState state) {
        return state instanceof CommandBlock cb ? cb.getCommand() : null;
    }

    private AuditEvent.Builder base(String action, Player p, Location loc, String type) {
        return AuditEvent.builder(Category.COMMAND_BLOCKS, action)
                .admin(p).location(loc)
                .field("Command Block Type", type)
                .field("Gamemode", p.getGameMode().name());
    }
}
