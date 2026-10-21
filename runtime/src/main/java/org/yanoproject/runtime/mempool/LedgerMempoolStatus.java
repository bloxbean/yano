package org.yanoproject.runtime.mempool;

/**
 * Health and counters of the {@link LedgerMempool} (ADR-056 §6): exported on the transaction subsystem's health
 * details and as node metrics.
 *
 * @param state                {@code READY} or {@code CATCHING_UP}
 * @param mempoolGeneration    the published state's mempool generation
 * @param baseGeneration       the canonical generation the published state validates against
 * @param canonicalGeneration  the published canonical generation
 * @param lagging              true while the published state is behind the canonical mark (provisional admissions)
 * @param rebuildsPublished    rebuilds that published a state
 * @param rebuildsDiscarded    rebuild attempts discarded (descent or freshness failure, no canonical state)
 * @param synchronousFallbacks rebuild cycles that used the synchronous fallback
 * @param catchingUpEntered    times the mempool entered {@code CATCHING_UP}
 * @param catchingUpRejections admissions refused with the retryable {@code CATCHING_UP} status
 * @param reapplications       re-validations that re-applied (static checks skipped)
 * @param fullRevalidations    re-validations that validated in full
 * @param revalidationDrops    transactions a rebuild dropped
 * @param deferredRemovals     invalidation removals deferred to a pending rebuild
 * @param lastRebuildMillis    duration of the last rebuild cycle
 * @param lockOrderViolations  lock-order assertion failures (must stay 0)
 * @param blockSelections              completed block selections (ADR-056 §6, "Block production")
 * @param blockSelectionRedos          selections discarded and redone because the canonical generation moved
 * @param blockSelectionReapplications selected candidates that were re-applied
 * @param blockSelectionFullValidations selected candidates that were validated in full
 * @param blockSelectionRejected       candidates that failed a ledger rule during selection (removed)
 * @param blockSelectionSkipped        candidates skipped without removal (transient failures)
 * @param lastBlockSelectionMillis     duration of the last block selection
 */
public record LedgerMempoolStatus(String state, long mempoolGeneration, long baseGeneration, long canonicalGeneration,
                                  boolean lagging, long rebuildsPublished, long rebuildsDiscarded,
                                  long synchronousFallbacks, long catchingUpEntered, long catchingUpRejections,
                                  long reapplications, long fullRevalidations, long revalidationDrops,
                                  long deferredRemovals, long lastRebuildMillis, long lockOrderViolations,
                                  long blockSelections, long blockSelectionRedos, long blockSelectionReapplications,
                                  long blockSelectionFullValidations, long blockSelectionRejected,
                                  long blockSelectionSkipped, long lastBlockSelectionMillis) {

    public boolean catchingUp() {
        return LedgerMempool.Status.CATCHING_UP.name().equals(state);
    }
}
