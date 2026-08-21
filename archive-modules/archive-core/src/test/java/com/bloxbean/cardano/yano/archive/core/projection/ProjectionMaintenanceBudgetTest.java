package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.projection.ProjectionMaintenance;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Housekeeping reclaims disk; compaction is an optimisation. The budgets are separate so a
 * large rewrite can never consume the window and skip the cleanup — which is precisely how
 * the replay-worker path behaves today, and how a lagging archive becomes a full disk.
 */
class ProjectionMaintenanceBudgetTest {

    @Test
    void housekeepingAndCompactionAreBudgetedSeparately() {
        var budget = ProjectionMaintenance.Budget.full(
                Duration.ofSeconds(10), Duration.ofSeconds(30), 1L << 30);
        assertThat(budget.housekeepingTimeLimit()).isEqualTo(Duration.ofSeconds(10));
        assertThat(budget.compactionTimeLimit()).isEqualTo(Duration.ofSeconds(30));
        assertThat(budget.compactionAllowed()).isTrue();
    }

    @Test
    void housekeepingOnlyWithholdsCompactionButKeepsCleanupFunded() {
        var budget = ProjectionMaintenance.Budget.housekeepingOnly(Duration.ofSeconds(10));
        assertThat(budget.compactionAllowed()).isFalse();
        assertThat(budget.compactionTimeLimit()).isEqualTo(Duration.ZERO);
        assertThat(budget.maxBytesToRewrite()).isZero();
        assertThat(budget.housekeepingTimeLimit())
                .as("cleanup must still be funded when compaction is withheld")
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void compactingFewerThanTwoFilesIsRejectedAsMeaningless() {
        assertThatThrownBy(() -> new ProjectionMaintenance.Budget(Duration.ofSeconds(1),
                Duration.ofSeconds(1), 1024, 1024, 1, 1024, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fewer than two files");
    }

    @Test
    void unsupportedIsExplicitRatherThanASilentNoOp() {
        var result = ProjectionMaintenance.Result.unsupported("this backend stores no files");
        assertThat(result.outcome()).isEqualTo(ProjectionMaintenance.Outcome.UNSUPPORTED);
        assertThat(result.detail()).isPresent();
    }

    @Test
    void deferralCarriesItsReason() {
        var result = ProjectionMaintenance.Result.deferred("active reader snapshot");
        assertThat(result.outcome()).isEqualTo(ProjectionMaintenance.Outcome.DEFERRED);
        assertThat(result.detail()).get().asString().contains("reader");
    }

    @Test
    void unnecessaryReportsFileCountWithoutClaimingWork() {
        var result = ProjectionMaintenance.Result.unnecessary(42);
        assertThat(result.outcome()).isEqualTo(ProjectionMaintenance.Outcome.UNNECESSARY);
        assertThat(result.filesBefore()).hasValue(42);
        assertThat(result.filesReclaimed()).hasValue(0);
    }

    @Test
    void filesReclaimedNeverGoesNegative() {
        var grew = new ProjectionMaintenance.Result(ProjectionMaintenance.Outcome.COMPLETED,
                Duration.ZERO, java.util.OptionalLong.of(10), java.util.OptionalLong.of(12),
                java.util.OptionalLong.of(0), 0, 0, Duration.ZERO, java.util.Optional.empty());
        assertThat(grew.filesReclaimed()).hasValue(0);
    }
}
