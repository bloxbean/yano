package com.bloxbean.cardano.yano.archive.core.projection;

import java.util.Objects;

/**
 * Aggregate disk obligation of projection history at a point in time (ADR-039 operational
 * model).
 *
 * <p>Counting only the outbox column families would understate the obligation badly. The
 * node is also holding staged artifact files and chainstate generations pinned beyond their
 * normal retention window by artifact leases, and RocksDB stores none of it at its logical
 * size. So the budget is expressed over everything the archive is keeping alive.
 *
 * <p>{@code amplificationFactor} converts logical outbox bytes into expected physical cost.
 * Exact attribution is impossible because RocksDB shares SST files across column families,
 * so this is a measured estimate rather than an accounting identity: the isolated benchmark
 * observed roughly 1.4x (3,419 bytes on disk per ~2,400 logical) for the first slice.
 */
public record ArchiveRetainedFootprint(long logicalOutboxBytes,
                                       long stagedArtifactBytes,
                                       long pinnedGenerationBytes,
                                       double amplificationFactor,
                                       long filesystemFreeBytes,
                                       boolean pinnedGenerationsMeasured) {

    /** Source-compatible constructor for callers that provide an actual pinned byte count. */
    public ArchiveRetainedFootprint(long logicalOutboxBytes, long stagedArtifactBytes,
                                    long pinnedGenerationBytes, double amplificationFactor,
                                    long filesystemFreeBytes) {
        this(logicalOutboxBytes, stagedArtifactBytes, pinnedGenerationBytes, amplificationFactor,
                filesystemFreeBytes, true);
    }

    public ArchiveRetainedFootprint {
        if (logicalOutboxBytes < 0 || stagedArtifactBytes < 0 || pinnedGenerationBytes < 0) {
            throw new IllegalArgumentException("retained byte counts must not be negative");
        }
        if (amplificationFactor < 1.0) {
            throw new IllegalArgumentException("amplification factor cannot be below 1.0");
        }
        if (filesystemFreeBytes < 0) throw new IllegalArgumentException("free space must not be negative");
        if (!pinnedGenerationsMeasured && pinnedGenerationBytes != 0) {
            throw new IllegalArgumentException("unmeasured pinned generations cannot carry a byte count");
        }
    }

    /**
     * Physical bytes the archive is responsible for keeping on disk.
     *
     * <p>Under-counts by whatever pinned generations hold while
     * {@link #pinnedGenerationsMeasured()} is false.
     */
    public long estimatedPhysicalBytes() {
        return Math.round(logicalOutboxBytes * amplificationFactor) + stagedArtifactBytes + pinnedGenerationBytes;
    }

    public static ArchiveRetainedFootprint ofOutboxOnly(long logicalOutboxBytes, double amplification,
                                                        long freeBytes) {
        return new ArchiveRetainedFootprint(logicalOutboxBytes, 0, 0, amplification, freeBytes);
    }
}
