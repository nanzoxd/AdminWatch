package dev.adminwatch.listener;

import dev.adminwatch.AdminWatchPlugin;
import dev.adminwatch.config.Settings;
import dev.adminwatch.model.AuditEvent;
import dev.adminwatch.model.Category;
import dev.adminwatch.util.SensitiveFilter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;

/**
 * Logs commands from monitored players, the console and RCON.
 * <p>
 * "Result" is the dispatch outcome that Bukkit can report reliably (dispatched / unknown / no permission /
 * cancelled by a plugin). The text a command prints back to the sender is not observable and is not recorded.
 */
public final class CommandListener implements Listener {
    private final AdminWatchPlugin plugin;

    public CommandListener(AdminWatchPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        Settings s = plugin.settings();
        Player p = e.getPlayer();
        if (!s.cmdPlayers || !plugin.audit().isMonitored(p)) return;
        if (plugin.audit().worldIgnored(Category.COMMANDS, p.getWorld().getName())) return;

        String raw = e.getMessage();
        String line = raw.startsWith("/") ? raw.substring(1) : raw;
        if (line.isBlank()) return;
        String label = SensitiveFilter.labelOf(line);
        if (s.cmdIgnored.contains(label)) return;

        String result;
        if (e.isCancelled()) {
            result = "Blocked (cancelled by a plugin)";
        } else {
            Command command = Bukkit.getCommandMap().getCommand(label);
            if (command == null) result = "Unknown command";
            else if (!command.testPermissionSilent(p)) result = "Denied (no permission)";
            else result = "Dispatched";
        }
        emit(AuditEvent.builder(Category.COMMANDS, "PLAYER_COMMAND")
                .admin(p).location(p.getLocation())
                .summary("Administrator executed a command")
                .field("Command", "/" + SensitiveFilter.redactCommand(line, s.cmdSensitive))
                .field("Executed By", "Player")
                .field("Gamemode", p.getGameMode().name())
                .field("Result", result)
                .build());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsoleCommand(ServerCommandEvent e) {
        Settings s = plugin.settings();
        if (!s.cmdConsole || !(e.getSender() instanceof ConsoleCommandSender)) return;
        String line = e.getCommand();
        if (line == null || line.isBlank()) return;
        if (s.cmdIgnored.contains(SensitiveFilter.labelOf(line))) return;
        emit(AuditEvent.builder(Category.COMMANDS, "CONSOLE_COMMAND")
                .admin("CONSOLE", null)
                .summary("Console executed a command")
                .field("Command", "/" + SensitiveFilter.redactCommand(line, s.cmdSensitive))
                .field("Executed By", "Console")
                .field("Result", e.isCancelled() ? "Blocked (cancelled by a plugin)" : "Dispatched")
                .build());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRcon(RemoteServerCommandEvent e) {
        Settings s = plugin.settings();
        if (!s.cmdRcon) return;
        String line = e.getCommand();
        if (line == null || line.isBlank()) return;
        if (s.cmdIgnored.contains(SensitiveFilter.labelOf(line))) return;
        emit(AuditEvent.builder(Category.COMMANDS, "RCON_COMMAND")
                .admin("RCON", null)
                .summary("RCON executed a command")
                .field("Command", "/" + SensitiveFilter.redactCommand(line, s.cmdSensitive))
                .field("Executed By", "RCON")
                .field("Result", e.isCancelled() ? "Blocked (cancelled by a plugin)" : "Dispatched")
                .build());
    }

    private void emit(AuditEvent event) {
        plugin.audit().record(event);
    }
}
