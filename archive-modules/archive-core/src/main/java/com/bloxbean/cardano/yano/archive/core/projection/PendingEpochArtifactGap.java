package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.time.Instant;
import java.util.Objects;

/** Durable failure intent awaiting the first canonical carrier in its semantic epoch. */
record PendingEpochArtifactGap(ArchiveDatasetId dataset, int semanticEpoch,
                               long intendedCarrierBlockNumber, String failureClass,
                               String detail, Instant recordedAt, boolean pausedContinuation) {
    PendingEpochArtifactGap {
        Objects.requireNonNull(dataset, "dataset");
        if (semanticEpoch < 0 || intendedCarrierBlockNumber < 0) {
            throw new IllegalArgumentException("pending gap epoch and carrier must be non-negative");
        }
        failureClass = bounded(Objects.requireNonNull(failureClass, "failureClass"), 80);
        detail = bounded(detail == null ? "" : detail, 1_024);
        Objects.requireNonNull(recordedAt, "recordedAt");
    }

    private static String bounded(String value, int max) {
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
