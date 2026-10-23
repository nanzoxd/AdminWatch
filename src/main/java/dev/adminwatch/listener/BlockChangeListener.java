package dev.adminwatch.listener;

import dev.adminwatch.AdminWatchPlugin;
import dev.adminwatch.config.Settings;
import dev.adminwatch.model.AuditEvent;
import dev.adminwatch.model.Category;
import dev.adminwatch.util.ItemFormatter;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

/** Logs block placement / destruction by monitored players (command blocks are handled separately). */
public final class BlockChangeListener implements Listener {
    private final AdminWatchPlugin plugin;

    public BlockChangeListener(AdminWatchPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Settings s = plugin.settings();
        if (!s.blPlaced) return;
        Block b = e.getBlockPlaced();
        if (!relevant(e.getPlayer(), b.getType())) return;
        boolean alertBlock = s.alertsEnabled && s.alertPlaceMaterials.contains(b.getType().name());
        if (s.blPlaceHighValueOnly && !alertBlock && !s.isHighValueBlock(b.getType())) return;

        AuditEvent.Builder ev = AuditEvent.builder(Category.BLOCK_CHANGES, "PLACED")
                .admin(e.getPlayer()).location(b.getLocation())
                .summary("Administrator placed a block")
                .field("Action", "Placed")
                .field("Block", b.getType().name())
                .field("Block State", b.getBlockData().getAsString())
                .field("Gamemode", e.getPlayer().getGameMode().name());
        Material replaced = e.getBlockReplacedState().getType();
        if (!replaced.isAir()) ev.field("Replaced", replaced.name());
        if (alertBlock) ev.alert("Valuable block placed: " + b.getType().name());
        plugin.audit().record(ev.build());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Settings s = plugin.settings();
        if (!s.blBroken) return;
        Block b = e.getBlock();
        if (!relevant(e.getPlayer(), b.getType())) return;

        ItemStack tool = e.getPlayer().getInventory().getItemInMainHand();
        AuditEvent.Builder ev = AuditEvent.builder(Category.BLOCK_CHANGES, "BROKEN")
                .admin(e.getPlayer()).location(b.getLocation())
                .summary("Administrator broke a block")
                .field("Action", "Broken")
                .field("Block", b.getType().name())
                .field("Block State", b.getBlockData().getAsString())
                .field("Gamemode", e.getPlayer().getGameMode().name())
                .field("Tool", ItemFormatter.isAir(tool) ? "Hand" : tool.getType().name());
        plugin.audit().record(ev.build());
    }

    private boolean relevant(Player p, Material m) {
        Settings s = plugin.settings();
        if (CommandBlockListener.isCommandBlock(m)) return false;
        if (!plugin.audit().isMonitored(p)) return false;
        if (s.blCreativeOnly && p.getGameMode() != GameMode.CREATIVE) return false;
        if (plugin.audit().worldIgnored(Category.BLOCK_CHANGES, p.getWorld().getName())) return false;
        return ItemFormatter.isMaterialAllowed(m, s.blIgnoredMaterials, s.blOnlyMaterials);
    }
}
