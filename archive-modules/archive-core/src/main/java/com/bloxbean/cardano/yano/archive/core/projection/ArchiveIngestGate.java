package com.bloxbean.cardano.yano.archive.core.projection;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Decides whether canonical ingestion may continue while the sink drains behind it
 * (ADR-039 disk backpressure).
 *
 * <p>The default is emphatically <em>not</em> sink-paced sync. Core runs ahead freely, and
 * the outbox absorbs the difference; asynchrony is the point. Ingestion pauses only when the
 * node would otherwise run out of the disk it was given — either the configured aggregate
 * archive budget or the filesystem's own free-space reserve.
 *
 * <p>Nothing here can discard data to recover space. Envelopes, artifact evidence, receipts
 * and protected source generations are all off limits: the only way out of a pause is the
 * sink acknowledging work and cleanup releasing it. A pause is therefore always visible and
 * always attributable, never a silent slowdown.
 */
public final class ArchiveIngestGate {

    /** Why ingestion is paused, or that it is not. */
    public record Decision(State state, Optional<String> reason, long observedBytes, long limitBytes,
                           long freeBytes) {
        public enum State {
            /** Running normally; the outbox may keep growing. */
            RUNNING,
            /** Past the soft bound: health is degraded and metrics should show it, but ingestion continues. */
            DEGRADED,
            /** Paused: the aggregate budget or the free-space reserve was reached. */
            PAUSED
        }

        public Decision {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
        }

        public boolean pausesIngest() {
            return state == State.PAUSED;
        }
    }

    private final ArchiveDiskLimits limits;
    private final AtomicReference<Decision> current = new AtomicReference<>(
            new Decision(Decision.State.RUNNING, Optional.empty(), 0, 0, 0));

    public ArchiveIngestGate(ArchiveDiskLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Re-evaluate against the current footprint.
     *
     * <p>Once paused, the gate stays paused until usage falls below the low-water mark and
     * free space recovers, rather than releasing the moment it dips under the hard limit.
     */
    public Decision evaluate(ArchiveRetainedFootprint footprint) {
        Objects.requireNonNull(footprint, "footprint");
        long used = footprint.estimatedPhysicalBytes();
        long free = footprint.filesystemFreeBytes();
        boolean wasPaused = current.get().pausesIngest();

        if (wasPaused) {
            boolean recovered = used <= limits.lowWaterBytes() && free > limits.freeSpaceReserveBytes();
            if (!recovered) {
                return set(new Decision(Decision.State.PAUSED, Optional.of(
                        "waiting for archive cleanup: " + used + " bytes retained, resuming below "
                                + limits.lowWaterBytes() + " bytes with more than "
                                + limits.freeSpaceReserveBytes() + " bytes free (currently " + free + ")"),
                        used, limits.lowWaterBytes(), free));
            }
            return set(new Decision(Decision.State.RUNNING, Optional.empty(), used, limits.hardBytes(), free));
        }

        if (free <= limits.freeSpaceReserveBytes()) {
            return set(new Decision(Decision.State.PAUSED, Optional.of(
                    "filesystem free space " + free + " reached the configured safety reserve "
                            + limits.freeSpaceReserveBytes() + "; canonical ingestion paused rather than"
                            + " discarding projection data"),
                    used, limits.hardBytes(), free));
        }
        if (used >= limits.hardBytes()) {
            return set(new Decision(Decision.State.PAUSED, Optional.of(
                    "archive-retained disk usage " + used + " reached the configured hard limit "
                            + limits.hardBytes() + "; canonical ingestion paused until the sink drains"),
                    used, limits.hardBytes(), free));
        }
        if (used >= limits.softBytes()) {
            return set(new Decision(Decision.State.DEGRADED, Optional.of(
                    "archive-retained disk usage " + used + " crossed the soft bound " + limits.softBytes()),
                    used, limits.softBytes(), free));
        }
        return set(new Decision(Decision.State.RUNNING, Optional.empty(), used, limits.softBytes(), free));
    }

    private Decision set(Decision decision) {
        current.set(decision);
        return decision;
    }

    public Decision current() {
        return current.get();
    }

    public ArchiveDiskLimits limits() {
        return limits;
    }
}
