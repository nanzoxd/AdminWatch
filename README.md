# AdminWatch

Administrator audit plugin for **Paper 1.21.11** (Java 21). Every audit category has its **own Discord webhook**;
every event is also stored in a local **SQLite** database, even if Discord is disabled or unreachable.

## Build

Requires JDK 21 and Maven 3.8+ (network access to `repo.papermc.io` for the Paper API).

```bash
mvn clean package
# result: target/AdminWatch-1.0.0.jar
```

## Install

1. Copy `AdminWatch-1.0.0.jar` into `plugins/`.
2. Start the server once; `plugins/AdminWatch/config.yml` is created.
3. In Discord: *Channel settings → Integrations → Webhooks → New Webhook → Copy URL*. Create one webhook per category.
4. Paste each URL into its own `webhooks.<category>.url` and keep `enabled: true`.
5. `/adminwatch reload` (or restart). `/adminwatch status` shows which webhooks are active (URLs are never displayed).
6. Optional: install **InvSee++** and/or **InventoryRollbackPlus** (see below); AdminWatch detects them automatically.

SQLite: Paper bundles `sqlite-jdbc`; `plugin.yml` also lists it under `libraries` as a fallback (needs internet on first start in that case).

## Who is audited

Players holding **`adminwatch.monitored`** (default: op). Grant it to staff groups with your permission plugin, or negate it
for people who must not be logged. `monitoring.ignored-players` / `ignored-worlds` add exclusions.

## Commands and permissions

| Command | Permission |
|---|---|
| `/adminwatch lookup <player>` – summary (counts per category, first/last record, 5 latest) | `adminwatch.lookup` |
| `/adminwatch history <player> [page]` – paged records (admin **or** target matches name/UUID) | `adminwatch.history` |
| `/adminwatch reload` | `adminwatch.reload` |
| `/adminwatch status` – DB, queues, webhook state, integrations | `adminwatch.admin` |

`adminwatch.admin` (default op) includes lookup, history and reload. Aliases: `/aw`, `/awatch`.

## Webhooks (7 in config.yml)

| Config key | Content |
|---|---|
| `commands` | player / console / RCON commands |
| `creative_items` | items created or obtained in Creative |
| `block_changes` | block place / break |
| `command_blocks` | command block (and command block minecart) place / break / command edits |
| `inventory_inspections` | InvSee++ inspection sessions **and** the item modifications made during them (one shared webhook) |
| `inventory_rerolls` | inventory / ender chest restores |
| `alerts` | alerts only: command block placed, valuable items taken, valuable blocks placed - with mention, admin, world and coordinates |

Each category has an independent queue and worker thread; HTTP never runs on the main thread. 429 responses honour
Discord's `retry_after`; 5xx/network errors retry with exponential back-off; a full queue drops only the *Discord* message
(the row stays in SQLite, `discord_status = DROPPED`). Embeds are split to respect Discord limits (field 1024, 25 fields,
5500 chars per embed). `allowed_mentions` is empty, so logged text can never ping anyone.

## Compatibility guide

### Directly supported (Paper events only, no other plugin needed)

| Feature | How | Notes |
|---|---|---|
| Player commands | `PlayerCommandPreprocessEvent` | Result = dispatch status (Dispatched / Unknown / Denied / Blocked by plugin). The text a command prints back is **not observable** and is not recorded. |
| Console + RCON commands | `ServerCommandEvent`, `RemoteServerCommandEvent` | Command-block executed commands are not logged here. |
| Block place / break | `BlockPlaceEvent`, `BlockBreakEvent` (cancelled events ignored) | Includes Creative. Changes by WorldEdit, explosions, pistons, etc. are not "administrator placed/broke" events and are not logged. |
| Creative items | `InventoryCreativeEvent`, `InventoryClickEvent` (middle-click clone, container takes), `EntityPickupItemEvent` | See "Created vs moved" below. |
| Command blocks | place / break events, `EntityPlaceEvent`, `VehicleDestroyEvent` | Command edits: see limitation below. |
| Webhooks / SQLite / lookup / history | built in | – |

**Created vs. moved (Creative).** The Creative client reports each slot write as an event. A write of an item that the same
player lifted out of another slot within 5 seconds (same item, not a larger stack) is a *rearrangement* and is not reported
(`creative_items.log-moves` can show it as `MOVED`). Everything else is reported as `CREATED`; this includes menu
picks, pick-block and middle-click clones. Heuristic limit: Bukkit cannot tell *which* creative tab was used.
Items from `/give` / `/item` appear in the command log, not in `creative_items`.

**Command-block edits.** Bukkit has no event for the command block GUI. When a monitored player right-clicks a command
block, AdminWatch compares its command every 0.5 s for `command_blocks.watch-seconds` and logs old → new. Edits made
through other means (e.g. `/data merge`) show up in the command log instead.

### Requires an external plugin

| Feature | Plugin | Integration method |
|---|---|---|
| Inventory / Ender Chest inspections | **InvSee++ 0.31.22** (verified from the supplied jar) | Reflection on `com.janboerman.invsee.spigot.api.SpectatorInventory`, `MainSpectatorInventory`, `EnderSpectatorInventory` (`getSpectatedPlayerId()`, `getSpectatedPlayerName()`). InvSee++ publishes no open/close events, so sessions come from Bukkit's `InventoryOpenEvent` / `InventoryCloseEvent` on those inventories. `SpectatorInventorySaveEvent` is observed to count saves. No compile-time dependency. |
| Inventory modifications | InvSee++ | Before/after item *multisets* (type+meta → total amount) of the inspected inventory and the admin's own inventory, taken around every click/drag. Re-arranging slots never changes the multiset and is never reported. Admin-inventory comparison distinguishes `TRANSFERRED` from `ADDED` / `REMOVED`; one stack swapped for another is `REPLACED`. |
| Inventory / Ender Chest rerolls | **InventoryRollbackPlus 1.8.5** (verified from the supplied jar) | **No events or API exist in that plugin**, so this is *observation*, not a hook. See below. |
| Rerolls from any other plugin | any | Documented API: `AdminRerollEvent` / `AdminWatchApi.reportReroll` / custom `RerollAdapter`. |

### Reroll integration

**InventoryRollbackPlus adapter (observation mode).** IRP restores through a chest GUI opened by `/ir restore [player]`
and exposes nothing for other plugins. AdminWatch therefore:

1. notices `/ir|irp|inventoryrollback|inventoryrollbackplus restore [player]` from a monitored admin and opens a session
   (`integrations.reroll.inventoryrollbackplus.session-seconds`);
2. on each click inside a virtual chest GUI during that session, snapshots the target's inventory and ender chest
   (all online players if the admin used the player picker) *before* IRP handles the click;
3. compares 2 and 20 ticks later. A changed inventory and/or ender chest is logged as a reroll with exact
   previous/restored contents and the added/removed items. If nothing changed, nothing is logged.

Known limits: restores of **offline** players only change files and **cannot** be detected; the IRP backup/snapshot id is
not readable (the embed says so); the check window is a few ticks, so an unrelated change to the same player in that window
could be attributed to the click.

**Integration interface for other restoration plugins.**

```java
RerollReport report = RerollReport.builder(RerollType.ENDER_CHEST, RerollStatus.SUCCESSFUL)
        .admin(adminPlayer)
        .target(target.getName(), target.getUniqueId())
        .previous(contentsBefore)       // ItemStack[] (optional)
        .restored(contentsAfter)        // ItemStack[] (optional)
        .snapshotId("backup-2026-10-23-14-40")
        .source("MyRollbackPlugin")
        .build();
AdminWatchApi.reportReroll(report);     // or: Bukkit.getPluginManager().callEvent(new AdminRerollEvent(report, async));
```

For a plugin without any hook point, implement `dev.adminwatch.integration.reroll.RerollAdapter` (`id()`, `start()`, `stop()`)
and register it with `AdminWatchApi.registerRerollAdapter(adapter)`; the adapter calls `reportReroll` when it detects a restore.

## Database

`plugins/AdminWatch/adminwatch.db`, table `audit_events`:
`id, category, action, timestamp_utc (ISO-8601 UTC), epoch_ms, admin_name, admin_uuid, target_name, target_uuid, world, x, y, z, description, details (JSON), discord_status (PENDING/SENT/FAILED/DROPPED/SKIPPED)`.
All JDBC work runs on one dedicated thread. `database.retention-days` and `cleanup-interval-minutes` control automatic cleanup
(0 = keep forever). On shutdown the queue is flushed before the connection is closed. If SQLite cannot be opened the plugin
logs a severe error and keeps running (Discord only).

## Security

* Webhook URLs are validated (`discord.com` / `discordapp.com` / `ptb.` / `canary.` only), never printed by commands or logs.
* Commands listed in `commands.sensitive-commands` (login, register, password commands, …) keep only their label; everything
  else is scrubbed for `password=`, `token:`, `secret`, bearer tokens and Discord webhook URLs. Command-block commands get the same scrub.
* History/lookup are permission-gated; nothing is exposed to non-staff.

## Verification status

The source was written against the Paper 1.21 API and the *actual* InvSee++ 0.31.22 and InventoryRollbackPlus 1.8.5 jars
(class/method names were read from them). It was syntax-checked with `javac`, but **it has not been compiled against the
Paper API or run on a server** (no Paper dependency or network was available when it was written). Run `mvn clean package`
and test on a staging server before production use; report compiler errors, if any, and they are quick to fix.

## Block filter and alerts

* `block_changes.place-high-value-only: true` logs placement only for `block_changes.high-value-blocks`
  (default: ores, ancient debris, shulker boxes, netherite/diamond/emerald/gold blocks, beacon, spawners, dragon egg).
  Command blocks are always reported in the `command_blocks` webhook. Breaking is unaffected.
* `alerts.mention` (role `<@&id>`, user `<@id>`, `@everyone`, `@here`) is pinged in the event's own webhook when:
  a command block is placed; an admin obtains/takes at least the configured amount of a `valuable-items` entry
  (`full` = full stack) via Creative, containers or an InvSee++ inspected inventory; or a `valuable-block-place` block is placed.
  Only the configured mention can ping; logged text never can.


Alerts are posted to the `alerts` webhook (with the `alerts.mention` ping); the normal event still goes to its own webhook without a ping.
