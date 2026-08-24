package com.bloxbean.cardano.yano.api.archive;

import java.util.OptionalLong;

/**
 * Bounded, idempotent maintenance of the derived as-of pointer index.
 *
 * <p>Pointer addresses exist only pre-Conway, so once the chain is safely past that boundary
 * the index has no further readers and can be reclaimed. Reclaiming it early would silently
 * break resolution for any block still replayable, so the decision is gated elsewhere: this
 * interface only <em>executes</em> a cleanup that something else has already authorised.
 *
 * <p>Execution is deliberately bounded and resumable. Deleting an unbounded key range in one
 * batch would stall canonical apply and lose all progress on a crash; instead each call
 * removes at most {@code maxKeys} and returns how many, so a caller can spread the work and
 * a crash costs only the current chunk.
 */
public interface PointerIndexMaintenance {

    /**
     * @param entryCount   derived index entries currently retained
     * @param logicalBytes their logical size, for disk accounting
     * @param cleanedThroughSlot slot recorded by a completed cleanup, when one has run
     */
    record PointerIndexStatus(PointerCredentialSource.IndexCompleteness completeness,
                              long entryCount, long logicalBytes, OptionalLong cleanedThroughSlot) {
    }

    PointerIndexStatus pointerIndexStatus();

    /**
     * Remove at most {@code maxKeys} derived index entries at or before {@code throughSlot}.
     *
     * <p>Idempotent: re-running after a crash simply finds fewer entries. Does not write the
     * completion marker — a partial pass must not be mistaken for a finished one.
     *
     * @return the number of entries removed
     */
    long cleanupPointerIndex(long throughSlot, int maxKeys);

    /**
     * Durably record that cleanup finished through {@code throughSlot}.
     *
     * <p>Written only after the range is empty. Afterwards
     * {@link PointerCredentialSource#completeness()} reports {@code CLEANED}, so a later
     * attempt to resolve a pointer fails closed rather than silently returning "unresolved"
     * from an index that no longer exists.
     */
    void markPointerIndexCleaned(long throughSlot);
}
