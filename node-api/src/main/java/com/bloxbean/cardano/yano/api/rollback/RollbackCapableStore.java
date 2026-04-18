package com.bloxbean.cardano.yano.api.rollback;

/**
 * Interface for stores that support adhoc (startup) rollback.
 * <p>
 * This is separate from the sync-time {@code RollbackEvent} which handles chain reorgs
 * during normal sync. Adhoc rollback is a one-shot DB truncation for debugging,
 * called directly without the event bus.
 */
public interface RollbackCapableStore {

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
