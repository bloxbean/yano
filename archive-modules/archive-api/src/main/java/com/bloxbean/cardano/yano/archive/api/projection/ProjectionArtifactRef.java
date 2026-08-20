package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Reference to a large immutable epoch artifact streamed by the sink rather than
 * copied into the block envelope (ADR-039 §5).
 *
 * <p>{@code producingBlockNumber} is the transition that created the artifact and is
 * the coordinate compared against the rollback-safe cutoff; {@code semanticEpoch} is
 * the epoch the resulting rows are labelled with. They differ by design: the
 * artifact produced at transition {@code E-1 -> E} carries semantic epoch {@code E}
 * but only becomes eligible one safe boundary later.
 */
public record ProjectionArtifactRef(ArchiveDatasetId dataset, int semanticEpoch,
                                    long producingBlockNumber, long producingSlot,
                                    ProjectionArtifactRepresentation representation,
                                    String sourceGeneration, int sourceCodecVersion,
                                    String sourceStateVersion,
                                    OptionalLong expectedRowCount, String contentDigest,
                                    long oldestRequiredSlot) {
    public ProjectionArtifactRef {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(representation, "representation");
        sourceGeneration = Objects.requireNonNull(sourceGeneration, "sourceGeneration").trim();
        sourceStateVersion = Objects.requireNonNull(sourceStateVersion, "sourceStateVersion").trim();
        Objects.requireNonNull(expectedRowCount, "expectedRowCount");
        contentDigest = contentDigest == null ? "" : contentDigest.trim().toLowerCase();
        if (semanticEpoch < 0 || producingBlockNumber < 0 || producingSlot < 0) {
            throw new IllegalArgumentException("invalid artifact coordinate");
        }
        if (sourceGeneration.isEmpty() || sourceStateVersion.isEmpty()) {
            throw new IllegalArgumentException("sourceGeneration and sourceStateVersion are required");
        }
        if (sourceCodecVersion < 1) throw new IllegalArgumentException("sourceCodecVersion must be positive");
        if (oldestRequiredSlot < 0) throw new IllegalArgumentException("oldestRequiredSlot must not be negative");
    }
}
