package com.bloxbean.cardano.yano.api.rollback;

import java.util.function.Function;

/**
 * Interface for stores that support adhoc (startup) rollback.
 * <p>
 * This is separate from the sync-time {@code RollbackEvent} which handles chain reorgs
 * during normal sync. Adhoc rollback is a one-shot DB truncation for debugging,
 * called directly without the event bus.
 */
public interface RollbackCapableStore {

    /**
     * Exact persisted chain point represented by a derived store.
     *
     * <p>The block hash is optional for stores that only retain a numeric
     * cursor. Consumers that need fork identity (rather than lag reporting)
     * must fail closed when it is absent.</p>
     */
    record AppliedPoint(long slot, String blockHash) {
    }

    /** Human-readable store name for logging. */
    String storeName();

    /**
     * Latest committed slot in this store.
     * Must come from persisted state, not transient in-memory cursors.
     *
     * @return latest applied slot, or -1 if store is empty
     */
    long getLatestAppliedSlot();

    /**
     * Latest atomically committed point, including fork identity when the
     * store retains it. The default preserves compatibility with stores that
     * expose only a slot cursor.
     */
    default AppliedPoint getLatestAppliedPoint() {
        return new AppliedPoint(getLatestAppliedSlot(), null);
    }

    /**
     * Run a derived-state read against one committed point while excluding
     * apply/rollback mutations. Implementations must use the same exclusion
     * mechanism here as their mutating operations.
     *
     * <p>The default synchronizes on the store instance, matching the
     * built-in stores. Implementations with a different locking model must
     * override this method.</p>
     */
    default <T> T readAtLatestAppliedPoint(Function<AppliedPoint, T> reader) {
        synchronized (this) {
            return reader.apply(getLatestAppliedPoint());
        }
    }

    /**
     * Earliest slot this store can safely rollback to with currently retained data.
     * 0 means the store can rollback to any point (no pruning / unbounded retention).
     *
     * @return rollback floor slot
     */
    long getRollbackFloorSlot();

    /**
     * Rollback this store's persisted state to the given target slot.
     * Must be idempotent — safe to call if already at or before target.
     * <p>
     * Does NOT publish any events. The orchestrator handles event publication
     * after all stores have rolled back successfully.
     *
     * @param targetSlot the slot to rollback to
     */
    void rollbackToSlot(long targetSlot);
}
