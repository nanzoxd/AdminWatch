package dev.adminwatch.integration.reroll;

/**
 * Adapter for an inventory restoration / rollback plugin.
 * <p>
 * Implementations detect restores performed through that plugin (events, commands, GUI) and report them with
 * {@link dev.adminwatch.api.AdminWatchApi#reportReroll(dev.adminwatch.api.RerollReport)}.
 * Register custom adapters with {@link dev.adminwatch.api.AdminWatchApi#registerRerollAdapter(RerollAdapter)}.
 */
public interface RerollAdapter {
    /** Short identifier shown in /adminwatch status. */
    String id();

    /** Start observing (register listeners, tasks). Called on the main thread. */
    void start();

    /** Stop observing and release everything registered in {@link #start()}. */
    void stop();
}
