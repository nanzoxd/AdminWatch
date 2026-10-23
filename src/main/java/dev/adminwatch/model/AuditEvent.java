package dev.adminwatch.model;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable audit record. Stored in SQLite and rendered into a Discord embed. */
public final class AuditEvent {
    private final Category category;
    private final String action;
    private final Instant time;
    private final String adminName;
    private final UUID adminUuid;
    private final String targetName;
    private final UUID targetUuid;
    private final String world;
    private final Integer x, y, z;
    private final String title;
    private final String summary;
    private final String alertReason;
    private final Map<String, String> fields;

    private AuditEvent(Builder b) {
        this.category = b.category;
        this.action = b.action;
        this.time = b.time;
        this.adminName = b.adminName;
        this.adminUuid = b.adminUuid;
        this.targetName = b.targetName;
        this.targetUuid = b.targetUuid;
        this.world = b.world;
        this.x = b.x;
        this.y = b.y;
        this.z = b.z;
        this.title = b.title;
        this.summary = b.summary;
        this.alertReason = b.alertReason;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(b.fields));
    }

    public Category category() { return category; }
    public String action() { return action; }
    public Instant time() { return time; }
    public String adminName() { return adminName; }
    public UUID adminUuid() { return adminUuid; }
    public String targetName() { return targetName; }
    public UUID targetUuid() { return targetUuid; }
    public String world() { return world; }
    public Integer x() { return x; }
    public Integer y() { return y; }
    public Integer z() { return z; }
    /** Optional embed title override (null = category default). */
    public String title() { return title; }
    public String summary() { return summary; }
    /** Non-null = this event should ping the configured alert mention. */
    public String alertReason() { return alertReason; }
    public Map<String, String> fields() { return fields; }

    public static Builder builder(Category category, String action) {
        return new Builder(category, action);
    }

    public static final class Builder {
        private final Category category;
        private final String action;
        private Instant time = Instant.now();
        private String adminName = "CONSOLE";
        private UUID adminUuid;
        private String targetName, world, title, summary, alertReason;
        private UUID targetUuid;
        private Integer x, y, z;
        private final Map<String, String> fields = new LinkedHashMap<>();

        private Builder(Category category, String action) {
            this.category = category;
            this.action = action;
        }

        public Builder time(Instant t) { this.time = t; return this; }

        public Builder admin(Player p) {
            this.adminName = p.getName();
            this.adminUuid = p.getUniqueId();
            return this;
        }

        public Builder admin(String name, UUID uuid) {
            this.adminName = name;
            this.adminUuid = uuid;
            return this;
        }

        public Builder target(String name, UUID uuid) {
            this.targetName = name;
            this.targetUuid = uuid;
            return this;
        }

        public Builder location(Location l) {
            if (l == null) return this;
            if (l.getWorld() != null) this.world = l.getWorld().getName();
            this.x = l.getBlockX();
            this.y = l.getBlockY();
            this.z = l.getBlockZ();
            return this;
        }

        public Builder world(String w) { this.world = w; return this; }
        public Builder title(String t) { this.title = t; return this; }
        public Builder summary(String s) { this.summary = s; return this; }

        /** Marks the event as an alert (mention is sent only if alerts are enabled and a mention is configured). */
        public Builder alert(String reason) {
            if (reason == null) return this;
            this.alertReason = reason;
            return field("ALERT", reason);
        }

        public Builder field(String name, String value) {
            if (value != null && !value.isEmpty()) fields.put(name, value);
            return this;
        }

        public AuditEvent build() { return new AuditEvent(this); }
    }
}
