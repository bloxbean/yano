package com.bloxbean.cardano.yano.archive.core.projection;

import java.util.Objects;
import java.util.Optional;

/** Outcome of one consumer pass. */
public record ProjectionConsumerResult(Outcome outcome, long firstBlock, long lastBlock,
                                       Optional<String> detail, boolean workPending) {

    /** Legacy shape: work is pending exactly when the pass committed something. */
    public ProjectionConsumerResult(Outcome outcome, long firstBlock, long lastBlock, Optional<String> detail) {
        this(outcome, firstBlock, lastBlock, detail,
                outcome == Outcome.COMMITTED || outcome == Outcome.REPLAYED);
    }

    public enum Outcome {
        /** Nothing complete, eligible and unacknowledged was available. */
        IDLE,
        /** A batch was committed by the sink and acknowledged. */
        COMMITTED,
        /** A durable receipt already covered this batch; it was acknowledged without re-writing. */
        REPLAYED,
        /** Retention health failed; the consumer paused rather than narrowing eligibility. */
        PAUSED,
        /**
         * Envelopes were consumed into the pending batch but no ceiling, block target or
         * freshness deadline said commit yet. Nothing was written and nothing was
         * acknowledged; the envelopes remain durable in the outbox.
         */
        ACCUMULATING
    }

    public ProjectionConsumerResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(detail, "detail");
    }

    static ProjectionConsumerResult idle() {
        return new ProjectionConsumerResult(Outcome.IDLE, -1, -1, Optional.empty());
    }

    /**
     * @param workPending whether more eligible envelopes are already waiting, which tells the
     *                    coordinator to keep draining instead of backing off
     */
    static ProjectionConsumerResult accumulating(long firstBlock, long lastBlock, boolean workPending) {
        return new ProjectionConsumerResult(Outcome.ACCUMULATING, firstBlock, lastBlock,
                Optional.empty(), workPending);
    }

    static ProjectionConsumerResult paused(String reason) {
        return new ProjectionConsumerResult(Outcome.PAUSED, -1, -1, Optional.of(reason));
    }

    public boolean madeProgress() {
        return outcome == Outcome.COMMITTED || outcome == Outcome.REPLAYED;
    }
}
