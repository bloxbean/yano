package com.bloxbean.cardano.yano.archive.core.projection;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Decides when the coordinator should run maintenance, and which kind.
 *
 * <p>Housekeeping and compaction are scheduled on <strong>different</strong> conditions, not
 * merely budgeted separately:
 *
 * <ul>
 *   <li><strong>Housekeeping</strong> — expiring snapshots and deleting obsolete and orphaned
 *       files — becomes due on a wall-clock interval and may run during bootstrap. A mainnet
 *       bootstrap or a sink recovery can easily outlast the snapshot and file retention
 *       windows, and an archive that waits for tip before ever cleaning up would accumulate
 *       obsolete files for the entire sync.</li>
 *   <li><strong>Compaction</strong> — only once the producer is at tip, the sink is caught up
 *       to the finality boundary, and no eligible drain backlog remains. During bootstrap it
 *       would contend with the sink for the same bounded bulk pool and re-merge the same
 *       neighbourhood as the sink keeps appending.</li>
 * </ul>
 *
 * <p>Housekeeping therefore never waits for tip, and compaction never pre-empts it.
 */
public final class ProjectionMaintenanceSchedule {

    private final Duration housekeepingInterval;
    private final Duration compactionInterval;

    private Instant lastHousekeeping;
    private Instant lastCompaction;

    public ProjectionMaintenanceSchedule(Duration housekeepingInterval, Duration compactionInterval) {
        this.housekeepingInterval = Objects.requireNonNull(housekeepingInterval, "housekeepingInterval");
        this.compactionInterval = Objects.requireNonNull(compactionInterval, "compactionInterval");
    }

    public static ProjectionMaintenanceSchedule defaults() {
        return new ProjectionMaintenanceSchedule(Duration.ofMinutes(30), Duration.ofHours(6));
    }

    /** What, if anything, should run now. */
    public enum Action {
        /** Nothing is due. */
        NONE,
        /** Housekeeping only: due on the interval, or due while bootstrap continues. */
        HOUSEKEEPING,
        /** Housekeeping followed by bounded compaction. */
        HOUSEKEEPING_AND_COMPACTION
    }

    /**
     * @param appendActive   whether the coordinator is mid-append; maintenance never competes
     *                       with an in-flight sink transaction
     * @param nearTip        whether the producer has reached the chain tip
     * @param drainBacklog   whether eligible envelopes are still waiting to be drained
     */
    public Action decide(boolean appendActive, boolean nearTip, boolean drainBacklog, Instant now) {
        if (appendActive) return Action.NONE;

        boolean housekeepingDue = lastHousekeeping == null
                || Duration.between(lastHousekeeping, now).compareTo(housekeepingInterval) >= 0;

        boolean compactionEligible = nearTip && !drainBacklog;
        boolean compactionDue = compactionEligible && (lastCompaction == null
                || Duration.between(lastCompaction, now).compareTo(compactionInterval) >= 0);

        if (compactionDue) return Action.HOUSEKEEPING_AND_COMPACTION;
        if (housekeepingDue) return Action.HOUSEKEEPING;
        return Action.NONE;
    }

    /** Record that a pass ran, so the next becomes due on schedule. */
    public void recordRun(Action action, Instant now) {
        if (action == Action.NONE) return;
        lastHousekeeping = now;
        if (action == Action.HOUSEKEEPING_AND_COMPACTION) lastCompaction = now;
    }

    /**
     * Record that compaction was due but the executor withheld it.
     *
     * <p>A backstop against the executor and this schedule disagreeing about eligibility.
     * Without it, a withheld compaction never advances {@code lastCompaction}, so compaction
     * stays permanently due and a maintenance pass is attempted on every tick. Spacing the
     * retry by the housekeeping interval bounds the cost of any such disagreement to one pass
     * per interval instead of one per tick.
     */
    public void recordWithheldCompaction(Instant now) {
        Instant retryFloor = now.minus(compactionInterval).plus(housekeepingInterval);
        // Never stamp into the future: with a housekeeping interval longer than the compaction
        // interval that would defer compaction by more than its own interval.
        lastCompaction = retryFloor.isAfter(now) ? now : retryFloor;
    }
}
