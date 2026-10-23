package dev.adminwatch.db;

import dev.adminwatch.AdminWatchPlugin;
import dev.adminwatch.model.AuditEvent;
import dev.adminwatch.util.Json;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * SQLite persistence. All JDBC access happens on ONE dedicated thread, so the main thread never blocks
 * on disk I/O and the single connection is never used concurrently.
 */
public final class DatabaseManager {

    public record Row(long id, String category, String action, long epochMs, String admin, String adminUuid,
                      String target, String world, Integer x, Integer y, Integer z, String description,
                      String discordStatus) {}

    public record Summary(Map<String, Long> asAdminByCategory, long asTarget, long firstMs, long lastMs,
                          List<Row> recent) {}

    public record HistoryPage(List<Row> rows, long total) {}

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS audit_events (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                category       TEXT    NOT NULL,
                action         TEXT    NOT NULL,
                timestamp_utc  TEXT    NOT NULL,
                epoch_ms       INTEGER NOT NULL,
                admin_name     TEXT,
                admin_uuid     TEXT,
                target_name    TEXT,
                target_uuid    TEXT,
                world          TEXT,
                x              INTEGER,
                y              INTEGER,
                z              INTEGER,
                description    TEXT,
                details        TEXT,
                discord_status TEXT    NOT NULL DEFAULT 'PENDING'
            )""";

    private static final String[] INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_ev_admin_name ON audit_events(admin_name COLLATE NOCASE)",
            "CREATE INDEX IF NOT EXISTS idx_ev_admin_uuid ON audit_events(admin_uuid)",
            "CREATE INDEX IF NOT EXISTS idx_ev_target_name ON audit_events(target_name COLLATE NOCASE)",
            "CREATE INDEX IF NOT EXISTS idx_ev_target_uuid ON audit_events(target_uuid)",
            "CREATE INDEX IF NOT EXISTS idx_ev_epoch ON audit_events(epoch_ms)",
            "CREATE INDEX IF NOT EXISTS idx_ev_category ON audit_events(category)"
    };

    private static final String MATCH =
            "(admin_name = ? COLLATE NOCASE OR target_name = ? COLLATE NOCASE OR admin_uuid = ? OR target_uuid = ?)";

    private final AdminWatchPlugin plugin;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AdminWatch-DB");
        t.setDaemon(true);
        return t;
    });
    private Connection conn;
    private volatile boolean available;
    private volatile boolean closed;
    private File file;

    public DatabaseManager(AdminWatchPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() { return available && !closed; }

    /** Opens the database and creates the schema. Never throws; on failure the plugin keeps running without SQLite. */
    public void init() {
        try {
            plugin.getDataFolder().mkdirs();
            file = new File(plugin.getDataFolder(), plugin.settings().dbFile);
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute(SCHEMA);
                for (String idx : INDEXES) st.execute(idx);
            }
            available = true;
        } catch (Exception ex) {
            available = false;
            plugin.getLogger().log(Level.SEVERE,
                    "Could not open SQLite database - events will NOT be stored locally until this is fixed.", ex);
        }
    }

    private <T> CompletableFuture<T> run(java.util.concurrent.Callable<T> job, T fallback) {
        if (!isAvailable()) return CompletableFuture.completedFuture(fallback);
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return job.call();
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "Database operation failed: " + ex.getMessage());
                    return fallback;
                }
            }, exec);
        } catch (RejectedExecutionException ex) {
            return CompletableFuture.completedFuture(fallback);
        }
    }

    /** Inserts an event. Completes with the row id, or -1 if it could not be stored. */
    public CompletableFuture<Long> insert(AuditEvent e, String discordStatus) {
        return run(() -> {
            String sql = "INSERT INTO audit_events(category,action,timestamp_utc,epoch_ms,admin_name,admin_uuid,"
                    + "target_name,target_uuid,world,x,y,z,description,details,discord_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                int i = 1;
                ps.setString(i++, e.category().key());
                ps.setString(i++, e.action());
                ps.setString(i++, e.time().toString()); // ISO-8601 UTC
                ps.setLong(i++, e.time().toEpochMilli());
                ps.setString(i++, e.adminName());
                ps.setString(i++, e.adminUuid() == null ? null : e.adminUuid().toString());
                ps.setString(i++, e.targetName());
                ps.setString(i++, e.targetUuid() == null ? null : e.targetUuid().toString());
                ps.setString(i++, e.world());
                setInt(ps, i++, e.x());
                setInt(ps, i++, e.y());
                setInt(ps, i++, e.z());
                ps.setString(i++, e.summary());
                ps.setString(i++, Json.object(e.fields()));
                ps.setString(i, discordStatus);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        }, -1L);
    }

    private static void setInt(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.INTEGER);
        else ps.setInt(idx, v);
    }

    public void updateDiscordStatus(long id, String status) {
        if (id < 0) return;
        run(() -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE audit_events SET discord_status=? WHERE id=?")) {
                ps.setString(1, status);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        }, null);
    }

    private static String uuidOrRaw(String q) {
        try {
            return UUID.fromString(q).toString();
        } catch (IllegalArgumentException ex) {
            return q;
        }
    }

    private static void bindMatch(PreparedStatement ps, String q) throws SQLException {
        String u = uuidOrRaw(q);
        ps.setString(1, q);
        ps.setString(2, q);
        ps.setString(3, u);
        ps.setString(4, u);
    }

    public CompletableFuture<Summary> summary(String query, int recent) {
        return run(() -> {
            Map<String, Long> byCat = new LinkedHashMap<>();
            long asTarget = 0, first = 0, last = 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT category, COUNT(*) FROM audit_events WHERE admin_name = ? COLLATE NOCASE OR admin_uuid = ? GROUP BY category")) {
                ps.setString(1, query);
                ps.setString(2, uuidOrRaw(query));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) byCat.put(rs.getString(1), rs.getLong(2));
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM audit_events WHERE target_name = ? COLLATE NOCASE OR target_uuid = ?")) {
                ps.setString(1, query);
                ps.setString(2, uuidOrRaw(query));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) asTarget = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT MIN(epoch_ms), MAX(epoch_ms) FROM audit_events WHERE " + MATCH)) {
                bindMatch(ps, query);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        first = rs.getLong(1);
                        last = rs.getLong(2);
                    }
                }
            }
            HistoryPage page = historyBlocking(query, 0, recent);
            return new Summary(byCat, asTarget, first, last, page.rows());
        }, new Summary(Map.of(), 0, 0, 0, List.of()));
    }

    public CompletableFuture<HistoryPage> history(String query, int offset, int limit) {
        return run(() -> historyBlocking(query, offset, limit), new HistoryPage(List.of(), 0));
    }

    private HistoryPage historyBlocking(String query, int offset, int limit) throws SQLException {
        long total = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM audit_events WHERE " + MATCH)) {
            bindMatch(ps, query);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) total = rs.getLong(1);
            }
        }
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id,category,action,epoch_ms,admin_name,admin_uuid,target_name,world,x,y,z,description,discord_status "
                        + "FROM audit_events WHERE " + MATCH + " ORDER BY epoch_ms DESC, id DESC LIMIT ? OFFSET ?")) {
            bindMatch(ps, query);
            ps.setInt(5, limit);
            ps.setInt(6, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                            rs.getString(6), rs.getString(7), rs.getString(8), nullableInt(rs, 9), nullableInt(rs, 10),
                            nullableInt(rs, 11), rs.getString(12), rs.getString(13)));
                }
            }
        }
        return new HistoryPage(rows, total);
    }

    private static Integer nullableInt(ResultSet rs, int col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    /** Deletes records older than the retention period. Completes with the number of deleted rows. */
    public CompletableFuture<Integer> cleanup(int retentionDays) {
        if (retentionDays <= 0) return CompletableFuture.completedFuture(0);
        return run(() -> {
            long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM audit_events WHERE epoch_ms < ?")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            }
        }, 0);
    }

    public CompletableFuture<Long> count() {
        return run(() -> {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM audit_events")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }, -1L);
    }

    public long fileSizeBytes() { return file == null ? 0 : file.length(); }

    /** Waits until everything queued so far has been written (used before shutdown). */
    public void flush(long timeoutMs) {
        if (!isAvailable()) return;
        try {
            CompletableFuture.supplyAsync(() -> null, exec).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // best effort
        }
    }

    /** Finishes queued writes and closes the connection. */
    public void close() {
        if (closed) return;
        exec.shutdown();
        try {
            if (!exec.awaitTermination(10, TimeUnit.SECONDS)) exec.shutdownNow();
        } catch (InterruptedException ex) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
        closed = true;
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ex) {
                plugin.getLogger().warning("Error closing database: " + ex.getMessage());
            }
        }
    }
}
