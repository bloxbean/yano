package com.bloxbean.cardano.yano.archive.core.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-039 disk backpressure: canonical sync stays asynchronous until the node would run out
 * of the disk it was given.
 */
class ArchiveIngestGateTest {

    private static final long GB = 1L << 30;

    private static ArchiveDiskLimits limits() {
        // soft 8 GiB, hard 32 GiB, resume under 4 GiB, keep 16 GiB free
        return new ArchiveDiskLimits(8 * GB, 32 * GB, 4 * GB, 16 * GB);
    }

    private static ArchiveRetainedFootprint used(long logicalOutbox, long staged, long pinned, long free) {
        return new ArchiveRetainedFootprint(logicalOutbox, staged, pinned, 1.4, free);
    }

    @Test
    void coreRunsAheadOfTheSinkByDefault() {
        var gate = new ArchiveIngestGate(limits());
        // A large backlog is normal during bootstrap and must not pause anything.
        var decision = gate.evaluate(used(3 * GB, 0, 0, 500 * GB));
        assertThat(decision.state()).isEqualTo(ArchiveIngestGate.Decision.State.RUNNING);
        assertThat(decision.pausesIngest()).isFalse();
    }

    @Test
    void theSoftBoundDegradesHealthWithoutPausing() {
        var gate = new ArchiveIngestGate(limits());
        var decision = gate.evaluate(used(7 * GB, 0, 0, 500 * GB)); // 7 * 1.4 = 9.8 GiB
        assertThat(decision.state()).isEqualTo(ArchiveIngestGate.Decision.State.DEGRADED);
        assertThat(decision.pausesIngest()).isFalse();
        assertThat(decision.reason()).isPresent();
    }

    @Test
    void theAggregateBudgetCountsStagedFilesAndPinnedGenerationsNotJustTheOutbox() {
        var gate = new ArchiveIngestGate(limits());
        // Outbox alone is well under the hard limit; the obligation is not.
        var outboxOnly = gate.evaluate(used(2 * GB, 0, 0, 500 * GB));
        assertThat(outboxOnly.pausesIngest()).isFalse();

        var withArtifacts = gate.evaluate(used(2 * GB, 12 * GB, 18 * GB, 500 * GB));
        assertThat(withArtifacts.state()).isEqualTo(ArchiveIngestGate.Decision.State.PAUSED);
        assertThat(withArtifacts.reason()).get().asString().contains("hard limit");
    }

    @Test
    void amplificationIsCountedSoTheBudgetReflectsRealDisk() {
        var gate = new ArchiveIngestGate(limits());
        // 24 GiB logical is under the 32 GiB hard limit, but 1.4x amplification is not.
        assertThat(gate.evaluate(used(24 * GB, 0, 0, 500 * GB)).pausesIngest()).isTrue();
    }

    @Test
    void freeSpaceReservePausesEvenWhenTheBudgetIsUntouched() {
        var gate = new ArchiveIngestGate(limits());
        var decision = gate.evaluate(used(1 * GB, 0, 0, 8 * GB)); // plenty of budget, no disk
        assertThat(decision.state()).isEqualTo(ArchiveIngestGate.Decision.State.PAUSED);
        assertThat(decision.reason()).get().asString().contains("safety reserve");
    }

    // ------------------------------------------------------ hysteresis / resume

    @Test
    void aPauseHoldsUntilCleanupReachesTheLowWaterMarkRatherThanOscillating() {
        var gate = new ArchiveIngestGate(limits());
        assertThat(gate.evaluate(used(24 * GB, 0, 0, 500 * GB)).pausesIngest()).isTrue();

        // Dipping just under the hard limit is not enough: resuming here would immediately
        // re-cross it and thrash.
        assertThat(gate.evaluate(used(22 * GB, 0, 0, 500 * GB)).pausesIngest()).isTrue();
        assertThat(gate.evaluate(used(7 * GB, 0, 0, 500 * GB)).pausesIngest()).isTrue();

        // Below the low-water mark, ingestion resumes automatically.
        var resumed = gate.evaluate(used(2 * GB, 0, 0, 500 * GB)); // 2 * 1.4 = 2.8 GiB
        assertThat(resumed.state()).isEqualTo(ArchiveIngestGate.Decision.State.RUNNING);
    }

    @Test
    void resumeAlsoRequiresFreeSpaceToHaveRecovered() {
        var gate = new ArchiveIngestGate(limits());
        assertThat(gate.evaluate(used(1 * GB, 0, 0, 8 * GB)).pausesIngest()).isTrue();
        // Usage is tiny, but the filesystem is still under its reserve.
        assertThat(gate.evaluate(used(1 * GB, 0, 0, 10 * GB)).pausesIngest()).isTrue();
        assertThat(gate.evaluate(used(1 * GB, 0, 0, 500 * GB)).pausesIngest()).isFalse();
    }

    @Test
    void thePauseReasonNamesUsageThresholdAndRequiredProgress() {
        var gate = new ArchiveIngestGate(limits());
        gate.evaluate(used(24 * GB, 0, 0, 500 * GB));
        var stillPaused = gate.evaluate(used(20 * GB, 0, 0, 500 * GB));
        assertThat(stillPaused.reason()).get().asString()
                .contains("waiting for archive cleanup")
                .contains("resuming below");
        assertThat(stillPaused.observedBytes()).isPositive();
        assertThat(stillPaused.limitBytes()).isEqualTo(4 * GB);
    }

    // ----------------------------------------------------------- configuration

    @Test
    void limitsMustBeCoherent() {
        assertThatThrownBy(() -> new ArchiveDiskLimits(8 * GB, 4 * GB, 2 * GB, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArchiveDiskLimits(8 * GB, 32 * GB, 9 * GB, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowWaterBytes");
        assertThatThrownBy(() -> new ArchiveRetainedFootprint(0, 0, 0, 0.5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amplification");
    }

    @Test
    void defaultsAreCoherent() {
        var defaults = ArchiveDiskLimits.defaults();
        assertThat(defaults.lowWaterBytes()).isLessThanOrEqualTo(defaults.softBytes());
        assertThat(defaults.hardBytes()).isGreaterThanOrEqualTo(defaults.softBytes());
    }
}
