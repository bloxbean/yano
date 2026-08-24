package com.bloxbean.cardano.yano.archive.api;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** A deterministic, retry-safe unit of archive work. */
public record ArchiveJob(
        UUID jobId,
        ArchiveNetworkIdentity networkIdentity,
        ArchiveDatasetId dataset,
        int projectionVersion,
        ArchiveRange range,
        ArchiveRangeAnchor anchors,
        String sourceStateVersion) {

    public ArchiveJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(networkIdentity, "networkIdentity");
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(anchors, "anchors");
        sourceStateVersion = Objects.requireNonNull(sourceStateVersion, "sourceStateVersion").trim();
        if (projectionVersion < 1) throw new IllegalArgumentException("projectionVersion must be positive");
        if (dataset.sourceKind() != range.sourceKind()) throw new IllegalArgumentException("dataset/range source mismatch");
        if (sourceStateVersion.isEmpty()) throw new IllegalArgumentException("sourceStateVersion is required");
    }

    public static ArchiveJob deterministic(ArchiveNetworkIdentity networkIdentity,
                                           ArchiveDatasetId dataset, int projectionVersion,
                                           ArchiveRange range, ArchiveRangeAnchor anchors,
                                           String sourceStateVersion) {
        Objects.requireNonNull(networkIdentity, "networkIdentity");
        Objects.requireNonNull(anchors, "anchors");
        String key = networkIdentity.canonicalForm() + '|' + dataset.logicalName() + '|'
                + projectionVersion + '|' + range.canonicalForm() + '|'
                + anchors.canonicalForm() + '|' + sourceStateVersion;
        return new ArchiveJob(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                networkIdentity, dataset, projectionVersion, range, anchors, sourceStateVersion);
    }

    /** Compatibility name for the end anchor used as the job's commit boundary. */
    public long anchorSlot() {
        return anchors.endSlot();
    }

    /** Compatibility name for the end anchor used as the job's commit boundary. */
    public byte[] anchorBlockHash() {
        return anchors.endHash();
    }
}
