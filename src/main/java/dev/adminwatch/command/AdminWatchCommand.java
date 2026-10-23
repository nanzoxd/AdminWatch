package dev.adminwatch.command;

import dev.adminwatch.AdminWatchPlugin;
import dev.adminwatch.db.DatabaseManager;
import dev.adminwatch.discord.WebhookManager;
import dev.adminwatch.model.Category;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AdminWatchCommand implements TabExecutor {
    private static final int PAGE_SIZE = 8;
    private final AdminWatchPlugin plugin;

    public AdminWatchCommand(AdminWatchPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "lookup" -> {
                if (!need(sender, "adminwatch.lookup")) return true;
                if (args.length < 2) { msg(sender, "Usage: /" + label + " lookup <player>", NamedTextColor.RED); return true; }
                lookup(sender, args[1]);
            }
            case "history" -> {
                if (!need(sender, "adminwatch.history")) return true;
                if (args.length < 2) { msg(sender, "Usage: /" + label + " history <player> [page]", NamedTextColor.RED); return true; }
                int page = 1;
                if (args.length >= 3) {
                    try { page = Math.max(1, Integer.parseInt(args[2])); }
                    catch (NumberFormatException ex) { msg(sender, "Page must be a number.", NamedTextColor.RED); return true; }
                }
                history(sender, args[1], page);
            }
            case "reload" -> {
                if (!need(sender, "adminwatch.reload")) return true;
                try {
                    plugin.reload();
                    msg(sender, "Configuration reloaded. Active webhooks: " + plugin.activeWebhookCount() + "/" + plugin.webhookSlots(), NamedTextColor.GREEN);
                } catch (Exception ex) {
                    msg(sender, "Reload failed: " + ex.getClass().getSimpleName() + " (see console)", NamedTextColor.RED);
                    plugin.getLogger().warning("Reload failed: " + ex);
                }
            }
            case "status" -> {
                if (!need(sender, "adminwatch.admin")) return true;
                status(sender);
            }
            default -> usage(sender);
        }
        return true;
    }

    private boolean need(CommandSender s, String perm) {
        if (s.hasPermission(perm) || s.hasPermission("adminwatch.admin")) return true;
        msg(s, "You do not have permission to do that.", NamedTextColor.RED);
        return false;
    }

    private void usage(CommandSender s) {
        if (!(s.hasPermission("adminwatch.admin") || s.hasPermission("adminwatch.lookup")
                || s.hasPermission("adminwatch.history") || s.hasPermission("adminwatch.reload"))) {
            msg(s, "You do not have permission to do that.", NamedTextColor.RED);
            return;
        }
        msg(s, "AdminWatch: /adminwatch <lookup <player> | history <player> [page] | reload | status>", NamedTextColor.GOLD);
    }

    private void lookup(CommandSender sender, String player) {
        plugin.database().summary(player, 5).thenAccept(sum -> sync(() -> {
            long total = sum.asAdminByCategory().values().stream().mapToLong(Long::longValue).sum() + sum.asTarget();
            if (total == 0) {
                msg(sender, "No audit records found for '" + player + "'.", NamedTextColor.YELLOW);
                return;
            }
            msg(sender, "=== AdminWatch lookup: " + player + " ===", NamedTextColor.GOLD);
            for (Map.Entry<String, Long> e : sum.asAdminByCategory().entrySet()) {
                msg(sender, " " + e.getKey() + ": " + e.getValue(), NamedTextColor.GRAY);
            }
            msg(sender, " As target of admin actions: " + sum.asTarget(), NamedTextColor.GRAY);
            msg(sender, " First record: " + plugin.settings().time.full(Instant.ofEpochMilli(sum.firstMs())), NamedTextColor.GRAY);
            msg(sender, " Latest record: " + plugin.settings().time.full(Instant.ofEpochMilli(sum.lastMs())), NamedTextColor.GRAY);
            msg(sender, "Most recent entries:", NamedTextColor.YELLOW);
            for (DatabaseManager.Row r : sum.recent()) msg(sender, row(r), NamedTextColor.WHITE);
            msg(sender, "Use /adminwatch history " + player + " for the full list.", NamedTextColor.DARK_GRAY);
        }));
    }

    private void history(CommandSender sender, String player, int page) {
        plugin.database().history(player, (page - 1) * PAGE_SIZE, PAGE_SIZE).thenAccept(h -> sync(() -> {
            if (h.total() == 0) {
                msg(sender, "No audit records found for '" + player + "'.", NamedTextColor.YELLOW);
                return;
            }
            long pages = (h.total() + PAGE_SIZE - 1) / PAGE_SIZE;
            msg(sender, "=== AdminWatch history: " + player + " (page " + page + "/" + pages + ", " + h.total() + " records) ===", NamedTextColor.GOLD);
            if (h.rows().isEmpty()) msg(sender, "That page is empty.", NamedTextColor.YELLOW);
            for (DatabaseManager.Row r : h.rows()) msg(sender, row(r), NamedTextColor.WHITE);
            if (page < pages) msg(sender, "Next: /adminwatch history " + player + " " + (page + 1), NamedTextColor.DARK_GRAY);
        }));
    }

    private String row(DatabaseManager.Row r) {
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(r.id()).append(' ')
                .append(plugin.settings().time.full(Instant.ofEpochMilli(r.epochMs()))).append(" | ")
                .append(r.category()).append('/').append(r.action()).append(" | ").append(r.admin());
        if (r.target() != null) sb.append(" -> ").append(r.target());
        if (r.world() != null) sb.append(" | ").append(r.world());
        if (r.x() != null) sb.append(' ').append(r.x()).append(',').append(r.y()).append(',').append(r.z());
        if (r.description() != null && !r.description().isBlank()) {
            String d = r.description().replace('\n', ' ');
            sb.append(" | ").append(d.length() > 90 ? d.substring(0, 89) + "…" : d);
        }
        return sb.toString();
    }

    private void status(CommandSender sender) {
        var db = plugin.database();
        db.count().thenAccept(count -> sync(() -> {
            msg(sender, "=== AdminWatch status ===", NamedTextColor.GOLD);
            msg(sender, " Uptime: " + dev.adminwatch.util.TimeFormats.duration(Duration.between(plugin.startedAt(), Instant.now())), NamedTextColor.GRAY);
            msg(sender, " Database: " + (db.isAvailable() ? "OK" : "UNAVAILABLE") + ", " + count + " records, "
                    + (db.fileSizeBytes() / 1024) + " KiB", NamedTextColor.GRAY);
            msg(sender, " Retention: " + (plugin.settings().retentionDays == 0 ? "forever" : plugin.settings().retentionDays + " days"), NamedTextColor.GRAY);
            var stats = plugin.webhooks().stats();
            for (Category c : Category.values()) {
                WebhookManager.Stats st = stats.get(c);
                msg(sender, " " + c.key() + ": " + plugin.settings().webhook(c).statusText()
                        + " | queued " + st.queued + ", sent " + st.sent.get() + ", failed " + st.failed.get() + ", dropped " + st.dropped.get(),
                        NamedTextColor.GRAY);
            }
            msg(sender, " Integrations: " + plugin.integrations().describe(), NamedTextColor.GRAY);
        }));
    }

    private void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
    }

    private static void msg(CommandSender s, String text, NamedTextColor color) {
        if (s instanceof Player p && !p.isOnline()) return;
        s.sendMessage(Component.text(text, color));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("adminwatch.lookup")) out.add("lookup");
            if (sender.hasPermission("adminwatch.history")) out.add("history");
            if (sender.hasPermission("adminwatch.reload")) out.add("reload");
            if (sender.hasPermission("adminwatch.admin")) out.add("status");
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("lookup") || args[0].equalsIgnoreCase("history"))
                && (sender.hasPermission("adminwatch.lookup") || sender.hasPermission("adminwatch.history"))) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
