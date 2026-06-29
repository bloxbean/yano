package com.bloxbean.cardano.yano.runtime.sync.validation;

/**
 * Runtime counters for upstream header validation.
 */
public record HeaderValidationSnapshot(
        String level,
        long acceptedHeaders,
        long rejectedHeaders,
        String lastRejectedStage,
        String lastRejectedReason
) {
    public static HeaderValidationSnapshot none() {
        return new HeaderValidationSnapshot("none", 0, 0, null, null);
    }
}
