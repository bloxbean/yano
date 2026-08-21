package com.bloxbean.cardano.yano.archive.core.projection;

/**
 * Disk thresholds governing whether canonical ingestion may keep running ahead of the sink.
 *
 * <p>Hysteresis is deliberate: ingestion pauses at {@code hardBytes} but only resumes once
 * cleanup has brought usage back under {@code lowWaterBytes}. Resuming at the same threshold
 * that triggered the pause would oscillate — each resumed block immediately re-crosses the
 * limit — which would look like a stall while thrashing.
 */
public record ArchiveDiskLimits(long softBytes, long hardBytes, long lowWaterBytes,
                                long freeSpaceReserveBytes) {

    public ArchiveDiskLimits {
        if (softBytes < 1) throw new IllegalArgumentException("softBytes must be positive");
        if (hardBytes < softBytes) throw new IllegalArgumentException("hardBytes must be at least softBytes");
        if (lowWaterBytes < 1 || lowWaterBytes > softBytes) {
            throw new IllegalArgumentException("lowWaterBytes must be positive and at or below softBytes");
        }
        if (freeSpaceReserveBytes < 0) throw new IllegalArgumentException("freeSpaceReserveBytes must not be negative");
    }

    /** 8 GiB soft, 32 GiB hard, resume under 4 GiB, keep 16 GiB of filesystem headroom. */
    public static ArchiveDiskLimits defaults() {
        return new ArchiveDiskLimits(8L << 30, 32L << 30, 4L << 30, 16L << 30);
    }
}
