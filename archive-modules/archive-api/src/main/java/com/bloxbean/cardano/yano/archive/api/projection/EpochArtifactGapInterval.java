package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.Arrays;
import java.util.Objects;

/** Compact range of later boundaries missed while one dataset remained paused. */
public record EpochArtifactGapInterval(
        ArchiveDatasetId dataset,
        int fromEpoch,
        int throughEpoch,
        long fromBoundarySlot,
        byte[] fromBoundaryHash,
        long throughBoundarySlot,
        byte[] throughBoundaryHash,
        boolean open,
        int causedByEpoch,
        String failureClass) {
    public EpochArtifactGapInterval {
        Objects.requireNonNull(dataset, "dataset");
        if (fromEpoch < 0 || throughEpoch < fromEpoch || causedByEpoch < 0
                || fromBoundarySlot < 0 || throughBoundarySlot < fromBoundarySlot) {
            throw new IllegalArgumentException("invalid epoch gap interval");
        }
        Objects.requireNonNull(fromBoundaryHash, "fromBoundaryHash");
        Objects.requireNonNull(throughBoundaryHash, "throughBoundaryHash");
        if (fromBoundaryHash.length == 0 || throughBoundaryHash.length == 0) {
            throw new IllegalArgumentException("epoch gap interval boundary hashes are required");
        }
        fromBoundaryHash = Arrays.copyOf(fromBoundaryHash, fromBoundaryHash.length);
        throughBoundaryHash = Arrays.copyOf(throughBoundaryHash, throughBoundaryHash.length);
        failureClass = Objects.requireNonNull(failureClass, "failureClass");
    }
    @Override public byte[] fromBoundaryHash() { return fromBoundaryHash.clone(); }
    @Override public byte[] throughBoundaryHash() { return throughBoundaryHash.clone(); }
}
