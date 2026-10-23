package dev.adminwatch.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Description of one inventory / ender chest reroll (restore, rollback) performed by an administrator.
 * Build it with {@link #builder(RerollType, RerollStatus)} and pass it to
 * {@link AdminWatchApi#reportReroll(RerollReport)}.
 */
public final class RerollReport {
    private final String adminName;
    private final UUID adminUuid;
    private final String targetName;
    private final UUID targetUuid;
    private final RerollType type;
    private final RerollStatus status;
    private final ItemStack[] previous;
    private final ItemStack[] restored;
    private final String snapshotId;
    private final String source;
    private final String note;
    private final String world;

    private RerollReport(Builder b) {
        this.adminName = b.adminName;
        this.adminUuid = b.adminUuid;
        this.targetName = b.targetName;
        this.targetUuid = b.targetUuid;
        this.type = b.type;
        this.status = b.status;
        this.previous = b.previous;
        this.restored = b.restored;
        this.snapshotId = b.snapshotId;
        this.source = b.source;
        this.note = b.note;
        this.world = b.world;
    }

    public String adminName() { return adminName; }
    public UUID adminUuid() { return adminUuid; }
    public String targetName() { return targetName; }
    public UUID targetUuid() { return targetUuid; }
    public RerollType type() { return type; }
    public RerollStatus status() { return status; }
    /** Contents before the reroll (nullable). */
    public ItemStack[] previous() { return previous; }
    /** Contents after the reroll / restored snapshot (nullable). */
    public ItemStack[] restored() { return restored; }
    public String snapshotId() { return snapshotId; }
    public String source() { return source; }
    public String note() { return note; }
    public String world() { return world; }

    public static Builder builder(RerollType type, RerollStatus status) {
        return new Builder(type, status);
    }

    public static final class Builder {
        private final RerollType type;
        private final RerollStatus status;
        private String adminName = "CONSOLE", targetName = "unknown", snapshotId, source, note, world;
        private UUID adminUuid, targetUuid;
        private ItemStack[] previous, restored;

        private Builder(RerollType type, RerollStatus status) {
            this.type = type;
            this.status = status;
        }

        public Builder admin(Player p) {
            this.adminName = p.getName();
            this.adminUuid = p.getUniqueId();
            this.world = p.getWorld().getName();
            return this;
        }

        public Builder admin(String name, UUID uuid) { this.adminName = name; this.adminUuid = uuid; return this; }
        public Builder target(String name, UUID uuid) { this.targetName = name; this.targetUuid = uuid; return this; }
        public Builder previous(ItemStack[] contents) { this.previous = contents; return this; }
        public Builder restored(ItemStack[] contents) { this.restored = contents; return this; }
        public Builder snapshotId(String id) { this.snapshotId = id; return this; }
        public Builder source(String plugin) { this.source = plugin; return this; }
        public Builder note(String note) { this.note = note; return this; }
        public Builder world(String world) { this.world = world; return this; }
        public RerollReport build() { return new RerollReport(this); }
    }
}
