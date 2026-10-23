package dev.adminwatch.listener;

import dev.adminwatch.AdminWatchPlugin;
import dev.adminwatch.config.Settings;
import dev.adminwatch.integration.invsee.InvSeeSupport;
import dev.adminwatch.model.AuditEvent;
import dev.adminwatch.model.Category;
import dev.adminwatch.util.ItemFormatter;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Items obtained by monitored administrators while in Creative mode.
 * <p>
 * Created vs. moved: the Creative client reports every slot it writes as an {@link InventoryCreativeEvent}.
 * A write of an item that the same player had just lifted out of another slot (within {@link #MOVE_WINDOW_MS},
 * same item, not larger) is a rearrangement and is NOT reported as obtained. Anything else is new stock.
 */
public final class CreativeItemListener implements Listener {
    private static final long MOVE_WINDOW_MS = 5000;

    private record Carry(ItemStack item, long atMs, String source) {}

    private final AdminWatchPlugin plugin;
    private final Map<UUID, Carry> lifted = new HashMap<>();        // item lifted out of an own slot
    private final Map<UUID, Carry> containerOrigin = new HashMap<>(); // item picked from a container onto the cursor

    public CreativeItemListener(AdminWatchPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ creative menu writes

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !eligible(p)) return;
        Settings s = plugin.settings();
        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        if (ItemFormatter.isAir(cursor)) {
            // slot cleared: either the player lifted an item (maybe to move it) or deleted it
            if (!ItemFormatter.isAir(current)) lifted.put(id, new Carry(current.clone(), now, "slot " + e.getSlot()));
            return;
        }

        Carry carry = lifted.get(id);
        boolean moved = carry != null && now - carry.atMs() <= MOVE_WINDOW_MS
                && carry.item().isSimilar(cursor) && cursor.getAmount() <= carry.item().getAmount();
        if (moved) {
            lifted.remove(id);
            if (s.crMoves) log(p, "MOVED", "Moved (rearranged, not newly obtained)", cursor, cursor.getAmount(),
                    "Inventory slot " + carry.source() + " -> slot " + e.getSlot(), null);
            return;
        }
        if (!s.crCreated) return;

        boolean dropped = e.getSlot() < 0 || e.getSlotType() == InventoryType.SlotType.OUTSIDE;
        ItemStack replaced = (!dropped && !ItemFormatter.isAir(current) && !current.isSimilar(cursor)) ? current : null;
        log(p, dropped ? "CREATED_DROPPED" : "CREATED",
                dropped ? "Created (dropped into the world)" : "Created",
                cursor, cursor.getAmount(),
                "Creative menu / pick-block / clone (client-side item creation)", replaced);
    }

    // ------------------------------------------------------------------ clicks in real containers

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (e instanceof InventoryCreativeEvent) return;
        if (!(e.getWhoClicked() instanceof Player p) || !eligible(p)) return;
        Settings s = plugin.settings();
        UUID id = p.getUniqueId();
        Inventory clicked = e.getClickedInventory();
        if (clicked == null) return;

        // Middle-click clone in Creative produces a full new stack on the cursor.
        if (e.getAction() == InventoryAction.CLONE_STACK) {
            ItemStack src = e.getCurrentItem();
            if (!ItemFormatter.isAir(src) && s.crCreated) {
                log(p, "CREATED", "Created (middle-click clone)", src, src.getMaxStackSize(),
                        "Clone of an item in " + clicked.getType().name(), null);
            }
            return;
        }
        if (!s.crContainerTakes) return;

        Inventory top = e.getView().getTopInventory();
        if (!realContainer(top)) return;
        ItemStack current = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        InventoryAction act = e.getAction();

        if (clicked == top) {
            if (ItemFormatter.isAir(current)) return;
            switch (act) {
                case MOVE_TO_OTHER_INVENTORY -> log(p, "MOVED_INTO_INVENTORY", "Moved into inventory", current,
                        current.getAmount(), containerSource(top), null);
                case HOTBAR_SWAP -> log(p, "MOVED_INTO_INVENTORY", "Moved into inventory (hotbar swap)", current,
                        current.getAmount(), containerSource(top), null);
                case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME -> {
                    if (ItemFormatter.isAir(cursor)) {
                        containerOrigin.put(id, new Carry(current.clone(), System.currentTimeMillis(), containerSource(top)));
                    }
                }
                default -> { }
            }
        } else if (clicked.equals(p.getInventory())) {
            Carry origin = containerOrigin.get(id);
            if (origin == null || ItemFormatter.isAir(cursor) || !origin.item().isSimilar(cursor)) return;
            if (act == InventoryAction.PLACE_ALL || act == InventoryAction.PLACE_ONE || act == InventoryAction.PLACE_SOME) {
                int amount = act == InventoryAction.PLACE_ONE ? 1 : cursor.getAmount();
                log(p, "MOVED_INTO_INVENTORY", "Moved into inventory (via cursor)", cursor, amount, origin.source(), null);
                if (act == InventoryAction.PLACE_ALL) containerOrigin.remove(id);
            }
        }
    }

    private boolean realContainer(Inventory top) {
        InventoryType t = top.getType();
        if (t == InventoryType.CRAFTING || t == InventoryType.CREATIVE || t == InventoryType.PLAYER) return false;
        if (InvSeeSupport.isSpectator(top)) return false;      // covered by inventory_inspections / modifications
        return top.getLocation() != null;                         // virtual GUIs (menus) have no location
    }

    private String containerSource(Inventory top) {
        Location l = top.getLocation();
        String where = l == null || l.getWorld() == null ? "" : " at " + l.getWorld().getName() + " "
                + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
        return "Container " + top.getType().name() + where;
    }

    // ------------------------------------------------------------------ ground pickups

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !eligible(p) || !plugin.settings().crPickups) return;
        Item item = e.getItem();
        ItemStack stack = item.getItemStack();
        int picked = stack.getAmount() - e.getRemaining();
        if (ItemFormatter.isAir(stack) || picked <= 0) return;
        String source = "Item entity on the ground (origin unknown)";
        UUID thrower = item.getThrower();
        if (thrower != null) {
            String name = Bukkit.getOfflinePlayer(thrower).getName();
            source = "Dropped by " + (name != null ? name + " (" + thrower + ")" : thrower.toString());
        }
        log(p, "PICKED_UP", "Picked up from the ground", stack, picked, source, null);
    }

    // ------------------------------------------------------------------ cleanup

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        containerOrigin.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lifted.remove(e.getPlayer().getUniqueId());
        containerOrigin.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ output

    private boolean eligible(Player p) {
        return p.getGameMode() == GameMode.CREATIVE && plugin.audit().isMonitored(p)
                && !plugin.audit().worldIgnored(Category.CREATIVE_ITEMS, p.getWorld().getName());
    }

    private void log(Player p, String action, String actionLabel, ItemStack item, int amount, String source, ItemStack replaced) {
        Settings s = plugin.settings();
        if (!ItemFormatter.isMaterialAllowed(item.getType(), s.crIgnoredMaterials, s.crOnlyMaterials)) return;
        AuditEvent.Builder ev = AuditEvent.builder(Category.CREATIVE_ITEMS, action)
                .admin(p).location(p.getLocation())
                .summary("Administrator " + actionLabel.toLowerCase() + ": " + ItemFormatter.line(item, amount))
                .field("Action", actionLabel)
                .field("Item", ItemFormatter.line(item, amount))
                .field("Item Metadata", ItemFormatter.details(item))
                .field("Source", source)
                .field("Gamemode", "CREATIVE");
        ev.alert(s.valuableTakeReason(item.getType(), amount));
        if (replaced != null) ev.field("Replaced Item", ItemFormatter.describe(replaced));
        plugin.audit().record(ev.build());
    }
}
