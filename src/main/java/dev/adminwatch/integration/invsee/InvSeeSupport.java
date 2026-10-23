package dev.adminwatch.integration.invsee;

import org.bukkit.inventory.Inventory;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection bridge to the InvSee++ API (com.janboerman.invsee.spigot.api).
 * No compile-time dependency: InvSee++ is optional, and its classes are loaded by its own class loader.
 * Verified against InvSee++ 0.31.22: SpectatorInventory#getSpectatedPlayerId()/getSpectatedPlayerName(),
 * MainSpectatorInventory, EnderSpectatorInventory.
 */
public final class InvSeeSupport {
    public static final String SPECTATOR = "com.janboerman.invsee.spigot.api.SpectatorInventory";
    public static final String MAIN = "com.janboerman.invsee.spigot.api.MainSpectatorInventory";
    public static final String ENDER = "com.janboerman.invsee.spigot.api.EnderSpectatorInventory";

    private static final Map<Class<?>, Optional<Class<?>>> SPECTATOR_CACHE = new ConcurrentHashMap<>();

    private InvSeeSupport() {}

    /** True if the inventory is an InvSee++ spectator inventory. */
    public static boolean isSpectator(Inventory inv) {
        return inv != null && spectatorType(inv.getClass()) != null;
    }

    private static Class<?> spectatorType(Class<?> c) {
        return SPECTATOR_CACHE.computeIfAbsent(c, k -> Optional.ofNullable(findType(k, SPECTATOR))).orElse(null);
    }

    /** Searches the class, its superclasses and all interfaces for a type with the given name. */
    public static Class<?> findType(Class<?> c, String name) {
        for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
            if (cur.getName().equals(name)) return cur;
            for (Class<?> i : cur.getInterfaces()) {
                Class<?> r = findType(i, name);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** "Player Inventory", "Ender Chest" or null if unknown. */
    public static String typeLabel(Inventory inv) {
        if (inv == null) return null;
        if (findType(inv.getClass(), MAIN) != null) return "Player Inventory";
        if (findType(inv.getClass(), ENDER) != null) return "Ender Chest";
        return null;
    }

    public static UUID targetId(Inventory inv) {
        Object o = call(inv, "getSpectatedPlayerId");
        return o instanceof UUID u ? u : null;
    }

    public static String targetName(Inventory inv) {
        Object o = call(inv, "getSpectatedPlayerName");
        return o instanceof String s ? s : null;
    }

    private static Object call(Inventory inv, String method) {
        if (inv == null) return null;
        try {
            Class<?> type = spectatorType(inv.getClass());
            if (type == null) return null;
            Method m = type.getMethod(method);
            return m.invoke(inv);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    /** Calls a no-arg getter on an arbitrary object via its public class hierarchy (used for InvSee++ events). */
    public static Object callGetter(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }
}
