package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Restart-discoverable reference to an immutable epoch-derived source. */
public record EpochArchiveJob(UUID jobId, ArchiveNetworkIdentity networkIdentity,
                              ArchiveDatasetId dataset, int projectionVersion,
                              long epoch, long boundarySlot, byte[] boundaryBlockHash,
                              String sourceStateVersion, String sourceReference,
                              Instant createdAt) {
    public EpochArchiveJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(networkIdentity, "networkIdentity");
        Objects.requireNonNull(dataset, "dataset");
        if (dataset.sourceKind() != com.bloxbean.cardano.yano.archive.api.SourceKind.EPOCH) {
            throw new IllegalArgumentException("epoch job requires an epoch dataset");
        }
        if (projectionVersion < 1 || epoch < 0 || boundarySlot < 0) {
            throw new IllegalArgumentException("invalid epoch job coordinate");
        }
        if (boundaryBlockHash == null || boundaryBlockHash.length == 0) {
            throw new IllegalArgumentException("boundaryBlockHash is required");
        }
        boundaryBlockHash = Arrays.copyOf(boundaryBlockHash, boundaryBlockHash.length);
        Objects.requireNonNull(sourceStateVersion, "sourceStateVersion");
        Objects.requireNonNull(sourceReference, "sourceReference");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override public byte[] boundaryBlockHash() {
        return Arrays.copyOf(boundaryBlockHash, boundaryBlockHash.length);
    }
}
