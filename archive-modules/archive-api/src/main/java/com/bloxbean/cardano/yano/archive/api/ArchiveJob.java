package com.bloxbean.cardano.yano.archive.api;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** A deterministic, retry-safe unit of archive work. */
public record ArchiveJob(
        UUID jobId,
        ArchiveDatasetId dataset,
        int projectionVersion,
        ArchiveRange range,
        long anchorSlot,
        byte[] anchorBlockHash,
        String sourceStateVersion) {

    public ArchiveJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(sourceStateVersion, "sourceStateVersion");
        if (projectionVersion < 1) throw new IllegalArgumentException("projectionVersion must be positive");
        if (dataset.sourceKind() != range.sourceKind()) throw new IllegalArgumentException("dataset/range source mismatch");
        if (anchorSlot < 0) throw new IllegalArgumentException("anchorSlot must be non-negative");
        if (anchorBlockHash == null || anchorBlockHash.length == 0) throw new IllegalArgumentException("anchorBlockHash is required");
        anchorBlockHash = Arrays.copyOf(anchorBlockHash, anchorBlockHash.length);
    }

    public static ArchiveJob deterministic(ArchiveDatasetId dataset, int projectionVersion,
                                           ArchiveRange range, long anchorSlot,
                                           byte[] anchorBlockHash, String sourceStateVersion) {
        Objects.requireNonNull(anchorBlockHash, "anchorBlockHash");
        String key = dataset.logicalName() + '|' + projectionVersion + '|' + range.canonicalForm()
                + '|' + anchorSlot + '|' + hex(anchorBlockHash) + '|' + sourceStateVersion;
        return new ArchiveJob(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), dataset,
                projectionVersion, range, anchorSlot, anchorBlockHash, sourceStateVersion);
    }

    @Override
    public byte[] anchorBlockHash() {
        return Arrays.copyOf(anchorBlockHash, anchorBlockHash.length);
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }
}
