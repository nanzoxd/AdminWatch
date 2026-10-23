package dev.adminwatch.config;

import dev.adminwatch.model.Category;
import dev.adminwatch.util.TimeFormats;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Immutable snapshot of config.yml. A new instance is created on every reload. */
public final class Settings {
    private static final Pattern WEBHOOK_URL = Pattern.compile(
            "^https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api(?:/v\\d+)?/webhooks/\\d+/[\\w-]+(?:\\?.*)?$");

    public record Webhook(boolean enabled, String url, boolean validUrl) {
        public boolean deliverable() { return enabled && validUrl; }
        public String statusText() {
            if (!enabled) return "disabled";
            if (url == null || url.isBlank()) return "no URL configured";
            return validUrl ? "active" : "invalid URL";
        }
    }

    // webhooks / discord
    private final Map<Category, Webhook> webhooks = new EnumMap<>(Category.class);
    public final String discordUsername, discordAvatar;
    public final int queueCapacity, minIntervalMs, maxRetries, retryBackoffMs, requestTimeoutSec;
    // embed
    private final Map<Category, Integer> colors = new EnumMap<>(Category.class);
    public final boolean embedTimestamp;
    public final String embedFooter;
    public final TimeFormats time;
    // monitoring
    public final String monitorPermission;
    public final Set<String> ignoredPlayers;
    public final Set<String> globalIgnoredWorlds;
    private final Map<Category, Set<String>> categoryIgnoredWorlds = new EnumMap<>(Category.class);
    // commands
    public final boolean cmdPlayers, cmdConsole, cmdRcon;
    public final Set<String> cmdIgnored, cmdSensitive;
    // creative
    public final boolean crCreated, crMoves, crPickups, crContainerTakes;
    public final Set<String> crIgnoredMaterials, crOnlyMaterials;
    // blocks
    public final boolean blPlaced, blBroken, blCreativeOnly, blPlaceHighValueOnly;
    public final Set<String> blHighValue;
    // alerts
    public final boolean alertsEnabled, alertCommandBlocks;
    public final String alertMention;
    public final String alertAllowedMentions;
    public final Map<String, Integer> alertItems;
    public final Set<String> alertPlaceMaterials;
    public final Set<String> blIgnoredMaterials, blOnlyMaterials;
    // command blocks
    public final boolean cbLogText;
    public final int cbWatchSeconds;
    // database
    public final String dbFile;
    public final int retentionDays, cleanupMinutes;
    // integrations
    public final boolean invseeEnabled, invseeIgnoreSelf, rerollApi, irpEnabled;
    public final String invseePlugin, irpPlugin;
    public final int irpSessionSeconds;

    public Settings(FileConfiguration c, Logger log) {
        for (Category cat : Category.values()) {
            String base = "webhooks." + cat.key();
            boolean enabled = c.getBoolean(base + ".enabled", true);
            String url = c.getString(base + ".url", "").trim();
            boolean valid = WEBHOOK_URL.matcher(url).matches();
            if (enabled && !url.isEmpty() && !valid) {
                log.warning("Webhook for '" + cat.key() + "' is not a valid Discord webhook URL; events are stored locally only.");
            }
            webhooks.put(cat, new Webhook(enabled, url, valid));
            colors.put(cat, parseColor(c.getString("embed.colors." + cat.key()), cat.defaultColor()));
            categoryIgnoredWorlds.put(cat, lower(c.getStringList(cat.key() + ".ignored-worlds")));
        }
        discordUsername = c.getString("discord.username", "AdminWatch");
        discordAvatar = c.getString("discord.avatar-url", "");
        queueCapacity = Math.max(10, c.getInt("discord.queue.capacity", 500));
        minIntervalMs = Math.max(0, c.getInt("discord.queue.min-interval-ms", 600));
        maxRetries = Math.max(0, c.getInt("discord.queue.max-retries", 5));
        retryBackoffMs = Math.max(100, c.getInt("discord.queue.retry-backoff-ms", 2000));
        requestTimeoutSec = Math.max(3, c.getInt("discord.queue.request-timeout-seconds", 10));

        embedTimestamp = c.getBoolean("embed.show-timestamp", true);
        embedFooter = c.getString("embed.footer", "AdminWatch");
        time = new TimeFormats(c.getString("time.timezone", "UTC"),
                c.getString("time.format", "yyyy-MM-dd HH:mm:ss z"),
                c.getString("time.short-format", "HH:mm:ss z"), log);

        monitorPermission = c.getString("monitoring.permission", "adminwatch.monitored");
        ignoredPlayers = lower(c.getStringList("monitoring.ignored-players"));
        globalIgnoredWorlds = lower(c.getStringList("monitoring.ignored-worlds"));

        cmdPlayers = c.getBoolean("commands.log-players", true);
        cmdConsole = c.getBoolean("commands.log-console", true);
        cmdRcon = c.getBoolean("commands.log-rcon", true);
        cmdIgnored = lower(c.getStringList("commands.ignored-commands"));
        cmdSensitive = lower(c.getStringList("commands.sensitive-commands"));

        crCreated = c.getBoolean("creative_items.log-created", true);
        crMoves = c.getBoolean("creative_items.log-moves", false);
        crPickups = c.getBoolean("creative_items.log-pickups", true);
        crContainerTakes = c.getBoolean("creative_items.log-container-takes", true);
        crIgnoredMaterials = upper(c.getStringList("creative_items.ignored-materials"));
        crOnlyMaterials = upper(c.getStringList("creative_items.only-materials"));

        blPlaced = c.getBoolean("block_changes.log-placed", true);
        blBroken = c.getBoolean("block_changes.log-broken", true);
        blCreativeOnly = c.getBoolean("block_changes.creative-only", false);
        blIgnoredMaterials = upper(c.getStringList("block_changes.ignored-materials"));
        blOnlyMaterials = upper(c.getStringList("block_changes.only-materials"));

        blPlaceHighValueOnly = c.getBoolean("block_changes.place-high-value-only", true);
        blHighValue = upper(c.getStringList("block_changes.high-value-blocks"));

        alertsEnabled = c.getBoolean("alerts.enabled", true);
        alertCommandBlocks = c.getBoolean("alerts.command-block-placed", true);
        String mention = c.getString("alerts.mention", "").trim();
        if (!mention.isEmpty() && !MENTION.matcher(mention).matches()) {
            log.warning("alerts.mention is invalid (use <@&ROLE_ID>, <@USER_ID>, @everyone or @here); alerts will not ping.");
            mention = "";
        }
        alertMention = mention;
        alertAllowedMentions = allowedMentions(mention);
        Map<String, Integer> items = new HashMap<>();
        ConfigurationSection vi = c.getConfigurationSection("alerts.valuable-items");
        if (vi != null) {
            for (String k : vi.getKeys(false)) {
                Object v = vi.get(k);
                int need = -1; // "full" = a full stack of that item
                if (v instanceof Number n) need = Math.max(1, n.intValue());
                else if (v != null && !v.toString().equalsIgnoreCase("full")) {
                    try { need = Math.max(1, Integer.parseInt(v.toString().trim())); } catch (NumberFormatException ignored) { }
                }
                items.put(k.toUpperCase(Locale.ROOT), need);
            }
        }
        alertItems = Collections.unmodifiableMap(items);
        alertPlaceMaterials = upper(c.getStringList("alerts.valuable-block-place"));

        cbLogText = c.getBoolean("command_blocks.log-command-text", true);
        cbWatchSeconds = Math.max(10, c.getInt("command_blocks.watch-seconds", 120));

        dbFile = c.getString("database.file", "adminwatch.db");
        retentionDays = Math.max(0, c.getInt("database.retention-days", 90));
        cleanupMinutes = Math.max(1, c.getInt("database.cleanup-interval-minutes", 60));

        invseeEnabled = c.getBoolean("integrations.invsee.enabled", true);
        invseePlugin = c.getString("integrations.invsee.plugin-name", "InvSeePlusPlus");
        invseeIgnoreSelf = c.getBoolean("integrations.invsee.ignore-self-inspection", true);
        rerollApi = c.getBoolean("integrations.reroll.api-enabled", true);
        ConfigurationSection irp = c.getConfigurationSection("integrations.reroll.inventoryrollbackplus");
        irpEnabled = irp == null || irp.getBoolean("enabled", true);
        irpPlugin = irp == null ? "InventoryRollbackPlus" : irp.getString("plugin-name", "InventoryRollbackPlus");
        irpSessionSeconds = irp == null ? 600 : Math.max(30, irp.getInt("session-seconds", 600));
    }

    private static final Pattern MENTION = Pattern.compile(
            "^(?:@everyone|@here|<@&?\\d{5,25}>)(?:\\s+(?:@everyone|@here|<@&?\\d{5,25}>))*$");

    private static String allowedMentions(String mention) {
        if (mention.isEmpty()) return "{\"parse\":[]}";
        java.util.List<String> roles = new java.util.ArrayList<>(), users = new java.util.ArrayList<>();
        boolean everyone = mention.contains("@everyone") || mention.contains("@here");
        java.util.regex.Matcher m = Pattern.compile("<@(&?)(\\d+)>").matcher(mention);
        while (m.find()) (m.group(1).isEmpty() ? users : roles).add("\"" + m.group(2) + "\"");
        StringBuilder sb = new StringBuilder("{\"parse\":").append(everyone ? "[\"everyone\"]" : "[]");
        if (!roles.isEmpty()) sb.append(",\"roles\":[").append(String.join(",", roles)).append(']');
        if (!users.isEmpty()) sb.append(",\"users\":[").append(String.join(",", users)).append(']');
        return sb.append('}').toString();
    }

    /** Block names in config: exact (BEACON), suffix (*_ORE, *SHULKER_BOX) or prefix (DEEPSLATE_*). */
    public boolean isHighValueBlock(org.bukkit.Material m) {
        String n = m.name();
        for (String e : blHighValue) {
            if (e.startsWith("*") ? n.endsWith(e.substring(1)) : e.endsWith("*") ? n.startsWith(e.substring(0, e.length() - 1)) : n.equals(e)) return true;
        }
        return false;
    }

    /** Alert reason if taking this much of this item is alert-worthy, else null. */
    public String valuableTakeReason(org.bukkit.Material m, int amount) {
        if (!alertsEnabled) return null;
        Integer need = alertItems.get(m.name());
        if (need == null) return null;
        int threshold = need < 0 ? m.getMaxStackSize() : need;
        return amount >= threshold ? "Valuable item: " + m.name() + " × " + amount : null;
    }

    /** inventory_modifications shares the inventory_inspections webhook. */
    public Webhook webhook(Category c) {
        return webhooks.get(c == Category.INVENTORY_MODIFICATIONS ? Category.INVENTORY_INSPECTIONS : c);
    }
    public int color(Category c) { return colors.get(c); }

    public boolean worldIgnored(Category c, String world) {
        if (world == null) return false;
        String w = world.toLowerCase(Locale.ROOT);
        return globalIgnoredWorlds.contains(w) || categoryIgnoredWorlds.get(c).contains(w);
    }

    private static Set<String> lower(List<String> in) {
        Set<String> s = new HashSet<>();
        for (String x : in) if (x != null && !x.isBlank()) s.add(x.trim().toLowerCase(Locale.ROOT));
        return Collections.unmodifiableSet(s);
    }

    private static Set<String> upper(List<String> in) {
        Set<String> s = new HashSet<>();
        for (String x : in) if (x != null && !x.isBlank()) s.add(x.trim().toUpperCase(Locale.ROOT));
        return Collections.unmodifiableSet(s);
    }

    private static int parseColor(String hex, int fallback) {
        if (hex == null) return fallback;
        try {
            return Integer.parseInt(hex.trim().replace("#", ""), 16) & 0xFFFFFF;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
