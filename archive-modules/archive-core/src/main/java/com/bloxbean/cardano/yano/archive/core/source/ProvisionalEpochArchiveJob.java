package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.SourceKind;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable epoch evidence captured before a locally produced block has a canonical hash. */
public record ProvisionalEpochArchiveJob(
        UUID captureId,
        ArchiveNetworkIdentity networkIdentity,
        ArchiveDatasetId dataset,
        int projectionVersion,
        long epoch,
        long boundaryBlockNumber,
        long boundarySlot,
        String sourceStateVersion,
        String sourceReference,
        Instant createdAt) {

    public ProvisionalEpochArchiveJob {
        Objects.requireNonNull(captureId, "captureId");
        Objects.requireNonNull(networkIdentity, "networkIdentity");
        Objects.requireNonNull(dataset, "dataset");
        if (dataset.sourceKind() != SourceKind.EPOCH) {
            throw new IllegalArgumentException("provisional job requires an epoch dataset");
        }
        if (projectionVersion < 1 || epoch < 0 || boundaryBlockNumber < 0 || boundarySlot < 0) {
            throw new IllegalArgumentException("invalid provisional epoch coordinate");
        }
        Objects.requireNonNull(sourceStateVersion, "sourceStateVersion");
        Objects.requireNonNull(sourceReference, "sourceReference");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
