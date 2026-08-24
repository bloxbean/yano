package com.bloxbean.cardano.yano.archive.core.projection;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Housekeeping reclaims disk and must not wait for tip; compaction is an optimisation and must
 * wait. A mainnet bootstrap can outlast the snapshot and file retention windows, so an archive
 * that cleaned up only at tip would accumulate obsolete files for the whole sync.
 */
class ProjectionMaintenanceScheduleTest {

    private static final Instant T0 = Instant.EPOCH;

    private static ProjectionMaintenanceSchedule schedule() {
        return new ProjectionMaintenanceSchedule(Duration.ofMinutes(30), Duration.ofHours(6));
    }

    @Test
    void housekeepingRunsDuringBootstrapWithoutWaitingForTip() {
        var s = schedule();
        var action = s.decide(false, false, true, T0);
        assertThat(action)
                .as("bootstrap with a backlog must still clean up")
                .isEqualTo(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING);
    }

    @Test
    void compactionIsWithheldDuringBootstrapEvenWhenDue() {
        var s = schedule();
        s.recordRun(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING, T0);
        // long past the compaction interval, but still bootstrapping
        assertThat(s.decide(false, false, true, T0.plus(Duration.ofDays(2))))
                .isEqualTo(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING);
    }

    @Test
    void compactionIsWithheldAtTipWhileABacklogRemains() {
        var s = schedule();
        s.recordRun(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING, T0);
        assertThat(s.decide(false, true, true, T0.plus(Duration.ofDays(1))))
                .isEqualTo(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING);
    }

    @Test
    void compactionRunsOnlyAtTipWithNoBacklog() {
        var s = schedule();
        assertThat(s.decide(false, true, false, T0))
                .isEqualTo(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING_AND_COMPACTION);
    }

    @Test
    void nothingRunsWhileAnAppendIsActive() {
        var s = schedule();
        assertThat(s.decide(true, true, false, T0.plus(Duration.ofDays(1))))
                .as("maintenance must never compete with an in-flight sink transaction")
                .isEqualTo(ProjectionMaintenanceSchedule.Action.NONE);
    }

    @Test
    void housekeepingBecomesDueAgainOnItsOwnInterval() {
        var s = schedule();
        s.recordRun(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING, T0);
        assertThat(s.decide(false, false, true, T0.plus(Duration.ofMinutes(29))))
                .isEqualTo(ProjectionMaintenanceSchedule.Action.NONE);
        assertThat(s.decide(false, false, true, T0.plus(Duration.ofMinutes(30))))
                .isEqualTo(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING);
    }

    @Test
    void compactionHasItsOwnLongerIntervalAndDoesNotStarveHousekeeping() {
        var s = schedule();
        s.recordRun(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING_AND_COMPACTION, T0);

        // 30 minutes later housekeeping is due again; compaction is not.
        assertThat(s.decide(false, true, false, T0.plus(Duration.ofMinutes(30))))
                .isEqualTo(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING);

        // after the compaction interval, both.
        assertThat(s.decide(false, true, false, T0.plus(Duration.ofHours(6))))
                .isEqualTo(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING_AND_COMPACTION);
    }

    @Test
    void aCompactionRunAlsoSatisfiesHousekeeping() {
        var s = schedule();
        s.recordRun(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING_AND_COMPACTION, T0);
        assertThat(s.decide(false, true, false, T0.plus(Duration.ofMinutes(5))))
                .isEqualTo(ProjectionMaintenanceSchedule.Action.NONE);
    }

    @Test
    void aWithheldCompactionDoesNotStayPermanentlyDueAndRetryOnEveryTick() {
        // Compaction is due, but the executor withholds it because the sink is busy. Nothing
        // records a compaction as having run, so without a backstop it stays due forever and a
        // maintenance pass — which acquires a bulk lease — is attempted on every drain tick.
        var schedule = new ProjectionMaintenanceSchedule(Duration.ofMinutes(30), Duration.ofHours(6));
        var t0 = Instant.parse("2026-08-20T00:00:00Z");

        assertThat(schedule.decide(false, true, false, t0))
                .isEqualTo(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING_AND_COMPACTION);
        schedule.recordRun(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING, t0);
        schedule.recordWithheldCompaction(t0);

        // A quarter of a second later — the drain loop's idle interval — nothing is due.
        assertThat(schedule.decide(false, true, false, t0.plusMillis(250)))
                .isEqualTo(ProjectionMaintenanceSchedule.Action.NONE);
        assertThat(schedule.decide(false, true, false, t0.plusSeconds(60)))
                .isEqualTo(ProjectionMaintenanceSchedule.Action.NONE);

        // It retries one housekeeping interval later, not one tick later.
        assertThat(schedule.decide(false, true, false, t0.plus(Duration.ofMinutes(30))))
                .isEqualTo(ProjectionMaintenanceSchedule.Action.HOUSEKEEPING_AND_COMPACTION);
    }
}
