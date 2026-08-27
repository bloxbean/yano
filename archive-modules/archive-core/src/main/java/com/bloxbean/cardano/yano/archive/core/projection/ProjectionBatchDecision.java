package com.bloxbean.cardano.yano.archive.core.projection;

import java.util.Objects;

/**
 * Whether an accumulated batch should be committed now, and what forced it.
 *
 * <p>The reason is carried rather than discarded because batch-size distribution is only
 * interpretable alongside <em>why</em> each batch flushed. A run dominated by
 * {@link Reason#MAX_ROWS} means something very different from one dominated by
 * {@link Reason#LINGER_EXPIRED}, and the difference decides whether the bounds are tuned
 * correctly.
 */
public record ProjectionBatchDecision(boolean flush, Reason reason, int blocks, long rows,
                                      long encodedBytes, long estimatedHeapBytes) {

    public enum Reason {
        /** Not enough work yet, and the linger window has not expired. */
        ACCUMULATING,
        /** The regime's minimum block count was reached. */
        MIN_BLOCKS,
        /** The linger window expired; commit what is available. */
        LINGER_EXPIRED,
        /** Hard block ceiling. */
        MAX_BLOCKS,
        /** Encoded outbox payload ceiling. */
        MAX_BYTES,
        /** Materialised row ceiling. */
        MAX_ROWS,
        /** Estimated materialised heap ceiling. */
        MAX_HEAP,
        /** Shutdown or an explicit flush request. */
        FORCED
    }

    public ProjectionBatchDecision {
        Objects.requireNonNull(reason, "reason");
    }

    static ProjectionBatchDecision accumulate(int blocks, long rows, long bytes, long heap) {
        return new ProjectionBatchDecision(false, Reason.ACCUMULATING, blocks, rows, bytes, heap);
    }

    static ProjectionBatchDecision flush(Reason reason, int blocks, long rows, long bytes, long heap) {
        return new ProjectionBatchDecision(true, reason, blocks, rows, bytes, heap);
    }
}
