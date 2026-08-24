package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.Arrays;
import java.util.Objects;

public record ArchiveProgress(ArchiveDatasetId dataset, ArchiveTrack track,
                              long coordinate, long slot, byte[] blockHash,
                              long backendGeneration) {
    public ArchiveProgress {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(track, "track");
        if (coordinate < -1 || slot < -1 || backendGeneration < 0) {
            throw new IllegalArgumentException("invalid archive progress");
        }
        blockHash = blockHash == null ? new byte[0] : Arrays.copyOf(blockHash, blockHash.length);
    }

    @Override public byte[] blockHash() { return Arrays.copyOf(blockHash, blockHash.length); }
}
