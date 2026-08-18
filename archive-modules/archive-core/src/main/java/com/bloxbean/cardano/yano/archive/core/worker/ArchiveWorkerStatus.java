package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.time.Instant;

public record ArchiveWorkerStatus(ArchiveDatasetId dataset, ArchiveTrack track,
                                  State state, long coordinate, long lag,
                                  String detail, Instant observedAt) {
    /**
     * Worker state vocabulary. Each value has one meaning; contention is
     * deliberately not reported as a failure.
     */
    public enum State {
        /** Configured but not enabled. The worker does not exist and no resources are held. */
        DISABLED,
        /** Enabled and idle: caught up to its current bound, or waiting for the next poll. */
        IDLE,
        /** Actively decoding, deriving, or committing. */
        RUNNING,
        /** Blocked on the single archive writer or bounded DuckDB capacity. Not an error. */
        WAITING_FOR_WRITER,
        /** Held back by the configured core-lag policy only. */
        PAUSED_CORE_LAG,
        /** A mutation actually failed for a retryable reason; the cursor did not advance. */
        DEGRADED,
        /** Non-retryable configuration, schema, identity, or projection error. Retrying cannot help. */
        FAILED
    }

    /** True when the state records contention or scheduling rather than a failure. */
    public boolean healthyState() {
        return state != State.DEGRADED && state != State.FAILED;
    }
}
