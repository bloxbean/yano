package com.bloxbean.cardano.yano.archive.api;

import java.time.Duration;

/**
 * A resource wait exceeded its configured stuck-operation threshold.
 *
 * <p>This is deliberately distinct from ordinary contention, which never fails.
 * It is retryable: the mutation is abandoned before any receipt, coverage row,
 * or cursor advances, so the caller re-derives and retries the same
 * deterministic job.
 */
public final class ArchiveStuckOperationException extends ArchiveStoreException {
    private final transient String gate;
    private final transient String operation;
    private final transient Duration waited;
    private final transient String holderDetail;

    public ArchiveStuckOperationException(String gate, String operation, Duration waited,
                                          String holderDetail) {
        super("archive " + gate + " wait exceeded the stuck-operation threshold after "
                + waited.toSeconds() + "s while waiting for " + operation
                + (holderDetail == null || holderDetail.isBlank() ? "" : "; " + holderDetail));
        this.gate = gate;
        this.operation = operation;
        this.waited = waited;
        this.holderDetail = holderDetail == null ? "" : holderDetail;
    }

    public String gate() {
        return gate;
    }

    public String operation() {
        return operation;
    }

    public Duration waited() {
        return waited;
    }

    public String holderDetail() {
        return holderDetail;
    }
}
