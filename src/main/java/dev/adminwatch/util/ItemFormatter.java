package dev.adminwatch.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Item description + multiset helpers used for before/after diffing. */
public final class ItemFormatter {
    private ItemFormatter() {}

    public static boolean isAir(ItemStack i) {
        return i == null || i.getType().isAir() || i.getAmount() <= 0;
    }

    /** "DIAMOND × 32" */
    public static String line(ItemStack i, int amount) {
        return i.getType().name() + " × " + amount;
    }

    public static String line(ItemStack i) {
        return line(i, i.getAmount());
    }

    /** One-line description including name and enchantments. */
    public static String describe(ItemStack i, int amount) {
        String extra = metaSummary(i);
        return line(i, amount) + (extra.isEmpty() ? "" : " (" + extra + ")");
    }

    public static String describe(ItemStack i) {
        return describe(i, i.getAmount());
    }

    /** Custom name / enchantments / lore / shulker contents summary, or "" if plain. */
    public static String metaSummary(ItemStack i) {
        if (isAir(i) || !i.hasItemMeta()) return "";
        ItemMeta meta = i.getItemMeta();
        List<String> parts = new ArrayList<>();
        if (meta.hasDisplayName() && meta.displayName() != null) {
            parts.add("name '" + PlainTextComponentSerializer.plainText().serialize(meta.displayName()) + "'");
        }
        String ench = enchantments(meta);
        if (!ench.isEmpty()) parts.add(ench);
        if (meta.hasLore() && meta.lore() != null) parts.add("lore lines: " + meta.lore().size());
        String shulker = shulkerSummary(meta);
        if (!shulker.isEmpty()) parts.add(shulker);
        return String.join("; ", parts);
    }

    private static String shulkerSummary(ItemMeta meta) {
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof ShulkerBox box) {
            int n = 0;
            for (ItemStack s : box.getInventory().getContents()) if (!isAir(s)) n++;
            return "shulker contents: " + n + " stack(s)";
        }
        return "";
    }

    public static String enchantments(ItemMeta meta) {
        Map<Enchantment, Integer> all = new LinkedHashMap<>(meta.getEnchants());
        if (meta instanceof EnchantmentStorageMeta esm) all.putAll(esm.getStoredEnchants());
        if (all.isEmpty()) return "";
        List<String> out = new ArrayList<>();
        for (var e : all.entrySet()) {
            String s = e.getKey().getKey().getKey() + " " + e.getValue();
            if (e.getValue() > e.getKey().getMaxLevel()) s += " (above vanilla max)";
            out.add(s);
        }
        return "enchants: " + String.join(", ", out);
    }

    /** Multi-line item details for embed fields (empty for plain items). */
    public static String details(ItemStack i) {
        if (isAir(i) || !i.hasItemMeta()) return "";
        ItemMeta meta = i.getItemMeta();
        StringBuilder sb = new StringBuilder();
        if (meta.hasDisplayName() && meta.displayName() != null) {
            sb.append("Name: ").append(PlainTextComponentSerializer.plainText().serialize(meta.displayName())).append('\n');
        }
        String ench = enchantments(meta);
        if (!ench.isEmpty()) sb.append(ench).append('\n');
        if (meta.hasLore() && meta.lore() != null) sb.append("Lore lines: ").append(meta.lore().size()).append('\n');
        String shulker = shulkerSummary(meta);
        if (!shulker.isEmpty()) sb.append(shulker).append('\n');
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ multiset helpers

    /** Counts items by "type + meta" (amount ignored). Rearranging slots does not change the result. */
    public static Map<ItemStack, Integer> counts(ItemStack[] contents) {
        Map<ItemStack, Integer> map = new LinkedHashMap<>();
        if (contents == null) return map;
        for (ItemStack s : contents) {
            if (isAir(s)) continue;
            map.merge(s.asOne(), s.getAmount(), Integer::sum);
        }
        return map;
    }

    public static final class Diff {
        public final Map<ItemStack, Integer> added = new LinkedHashMap<>();
        public final Map<ItemStack, Integer> removed = new LinkedHashMap<>();

        public boolean isEmpty() { return added.isEmpty() && removed.isEmpty(); }
        public int addedTotal() { return added.values().stream().mapToInt(Integer::intValue).sum(); }
        public int removedTotal() { return removed.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    public static Diff diff(Map<ItemStack, Integer> before, Map<ItemStack, Integer> after) {
        Diff d = new Diff();
        for (var e : after.entrySet()) {
            int b = before.getOrDefault(e.getKey(), 0);
            if (e.getValue() > b) d.added.put(e.getKey(), e.getValue() - b);
        }
        for (var e : before.entrySet()) {
            int a = after.getOrDefault(e.getKey(), 0);
            if (e.getValue() > a) d.removed.put(e.getKey(), e.getValue() - a);
        }
        return d;
    }

    public static String describeMap(Map<ItemStack, Integer> m, int maxLines) {
        if (m.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (var e : m.entrySet()) {
            if (n++ >= maxLines) {
                sb.append("… and ").append(m.size() - maxLines).append(" more");
                break;
            }
            sb.append(describe(e.getKey(), e.getValue())).append('\n');
        }
        return sb.toString().trim();
    }

    public static boolean isMaterialAllowed(Material m, Set<String> ignored, Set<String> only) {
        String n = m.name().toUpperCase(Locale.ROOT);
        if (!only.isEmpty() && !only.contains(n)) return false;
        return !ignored.contains(n);
    }
}
