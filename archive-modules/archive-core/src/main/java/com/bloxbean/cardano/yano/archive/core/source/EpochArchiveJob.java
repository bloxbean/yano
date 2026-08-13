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
                              long epoch, long boundaryBlockNumber, long boundarySlot, long boundaryBlockTime,
                              byte[] boundaryBlockHash,
                              String sourceStateVersion, String sourceReference,
                              Instant createdAt) {
    public EpochArchiveJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(networkIdentity, "networkIdentity");
        Objects.requireNonNull(dataset, "dataset");
        if (dataset.sourceKind() != com.bloxbean.cardano.yano.archive.api.SourceKind.EPOCH) {
            throw new IllegalArgumentException("epoch job requires an epoch dataset");
        }
        if (projectionVersion < 1 || epoch < 0 || boundaryBlockNumber < 0
                || boundarySlot < 0 || boundaryBlockTime < 0) {
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

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EpochArchiveJob that)) return false;
        return projectionVersion == that.projectionVersion && epoch == that.epoch
                && boundaryBlockNumber == that.boundaryBlockNumber && boundarySlot == that.boundarySlot
                && boundaryBlockTime == that.boundaryBlockTime
                && jobId.equals(that.jobId) && networkIdentity.equals(that.networkIdentity)
                && dataset == that.dataset && Arrays.equals(boundaryBlockHash, that.boundaryBlockHash)
                && sourceStateVersion.equals(that.sourceStateVersion)
                && sourceReference.equals(that.sourceReference) && createdAt.equals(that.createdAt);
    }

    @Override public int hashCode() {
        int result = Objects.hash(jobId, networkIdentity, dataset, projectionVersion, epoch,
                boundaryBlockNumber, boundarySlot, boundaryBlockTime,
                sourceStateVersion, sourceReference, createdAt);
        return 31 * result + Arrays.hashCode(boundaryBlockHash);
    }
}
