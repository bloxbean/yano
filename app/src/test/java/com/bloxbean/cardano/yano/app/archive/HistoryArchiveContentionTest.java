package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveFatalException;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.ArchiveStuckOperationException;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveWorkerStatus;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-037 worker-state vocabulary and disabled-history absence. Contention must
 * never be reported as a failure, and a non-retryable error must not look like a
 * transient one.
 */
class HistoryArchiveContentionTest {

    @Test
    void aStuckResourceWaitIsRetryableAndNotAFatalError() {
        var stuck = new ArchiveStuckOperationException("ducklake-writer", "begin TRANSACTION",
                Duration.ofMinutes(5), "held by maintenance for 300s");

        assertThat(HistoryArchiveService.classifyFailure(stuck))
                .isEqualTo(ArchiveWorkerStatus.State.DEGRADED);
        assertThat(stuck.gate()).isEqualTo("ducklake-writer");
        assertThat(stuck.waited()).isEqualTo(Duration.ofMinutes(5));
        assertThat(stuck.getMessage()).contains("begin TRANSACTION").contains("held by maintenance");
    }

    @Test
    void configurationSchemaIdentityAndProjectionErrorsAreNonRetryable() {
        assertThat(HistoryArchiveService.classifyFailure(
                new ArchiveFatalException("archive job network identity does not match backend")))
                .isEqualTo(ArchiveWorkerStatus.State.FAILED);
        // Also when wrapped, since workers see causes through the commit path.
        assertThat(HistoryArchiveService.classifyFailure(new ArchiveStoreException("wrapped",
                new ArchiveFatalException("projection version mismatch"))))
                .isEqualTo(ArchiveWorkerStatus.State.FAILED);
    }

    @Test
    void anOrdinaryBackendFailureRemainsRetryable() {
        assertThat(HistoryArchiveService.classifyFailure(new ArchiveStoreException("disk hiccup")))
                .isEqualTo(ArchiveWorkerStatus.State.DEGRADED);
        assertThat(HistoryArchiveService.classifyFailure(new RuntimeException("boom")))
                .isEqualTo(ArchiveWorkerStatus.State.DEGRADED);
    }

    @Test
    void everyWorkerStateHasOneMeaningAndOnlyFailuresAreUnhealthy() {
        assertThat(ArchiveWorkerStatus.State.values()).containsExactly(
                ArchiveWorkerStatus.State.DISABLED,
                ArchiveWorkerStatus.State.IDLE,
                ArchiveWorkerStatus.State.RUNNING,
                ArchiveWorkerStatus.State.WAITING_FOR_WRITER,
                ArchiveWorkerStatus.State.PAUSED_CORE_LAG,
                ArchiveWorkerStatus.State.DEGRADED,
                ArchiveWorkerStatus.State.FAILED);

        assertThat(status(ArchiveWorkerStatus.State.WAITING_FOR_WRITER).healthyState()).isTrue();
        assertThat(status(ArchiveWorkerStatus.State.PAUSED_CORE_LAG).healthyState()).isTrue();
        assertThat(status(ArchiveWorkerStatus.State.DISABLED).healthyState()).isTrue();
        assertThat(status(ArchiveWorkerStatus.State.DEGRADED).healthyState()).isFalse();
        assertThat(status(ArchiveWorkerStatus.State.FAILED).healthyState()).isFalse();
    }

    @Test
    void aNonRetryableDatasetIsParkedInsteadOfReDerivingEveryPoll() {
        // FAILED means "retrying cannot help", so the scheduler must actually
        // stop re-running that dataset rather than reproducing the same error
        // and the same stack trace every poll interval.
        assertThat(HistoryArchiveService.nonRetryablyFailed(
                Map.of(ArchiveTrack.BACKFILL, status(ArchiveWorkerStatus.State.FAILED)),
                ArchiveTrack.BACKFILL)).isTrue();
        assertThat(HistoryArchiveService.nonRetryablyFailed(
                Map.of(ArchiveTrack.LIVE, status(ArchiveWorkerStatus.State.FAILED)),
                ArchiveTrack.LIVE)).isTrue();

        // Every other state stays on the normal retry path, contention included.
        for (var state : ArchiveWorkerStatus.State.values()) {
            if (state == ArchiveWorkerStatus.State.FAILED) continue;
            assertThat(HistoryArchiveService.nonRetryablyFailed(
                    Map.of(ArchiveTrack.BACKFILL, status(state)), ArchiveTrack.BACKFILL))
                    .as("state %s must remain retryable", state).isFalse();
        }
        // A failure on one track does not park the other.
        assertThat(HistoryArchiveService.nonRetryablyFailed(
                Map.of(ArchiveTrack.LIVE, status(ArchiveWorkerStatus.State.FAILED)),
                ArchiveTrack.BACKFILL)).isFalse();
        assertThat(HistoryArchiveService.nonRetryablyFailed(Map.of(), ArchiveTrack.BACKFILL)).isFalse();
    }

    @Test
    void disabledHistoryConstructsNoArchiveResourceAndStaysUnavailable() {
        var service = new HistoryArchiveService(new StaticConfig(Map.of("yano.history.enabled", "false")));

        assertThat(service.enabled()).isFalse();
        assertThat(service.available()).isFalse();
        assertThat(service.backend()).isEmpty();
        assertThat(service.enabledBlockDatasets()).isEmpty();
        Map<String, Object> status = service.status();
        assertThat(status).containsEntry("enabled", false);
        assertThat(status).doesNotContainKeys("resources", "generation", "maintenance");

        // Closing an uninitialized service must be a safe no-op, not a throw.
        service.close();
        service.close();
    }

    @Test
    void aCompletedCycleClearsAPreviousTransientFailure() {
        var service = new HistoryArchiveService(new StaticConfig(Map.of("yano.history.enabled", "false")));

        // A cycle that throws before any dataset could record status is reported
        // rather than swallowed.
        service.recordCycleFailure(new IllegalStateException("transient cycle blow-up"));
        assertThat(service.cycleError()).isEqualTo("transient cycle blow-up");

        // The next cycle completes, so the recovered failure must stop being
        // reported as an active one instead of pinning the console to
        // "attention required" forever.
        service.runBoundedWork();
        assertThat(service.cycleError()).isNull();

        // A fresh failure is reported again.
        service.recordCycleFailure(new IllegalStateException("second failure"));
        assertThat(service.cycleError()).isEqualTo("second failure");
        service.runBoundedWork();
        assertThat(service.cycleError()).isNull();
    }

    @Test
    void oneFailingCycleStageDoesNotCancelTheRemainingStages() {
        var service = new HistoryArchiveService(new StaticConfig(Map.of("yano.history.enabled", "false")));
        List<String> ran = new ArrayList<>();

        // An unguarded stage failure used to skip every later stage on every
        // cycle, silently stopping promotion, retention, and maintenance.
        service.runStage("block-projection", () -> ran.add("block-projection"));
        service.runStage("epoch-archive", () -> {
            throw new IllegalStateException("epoch commit blew up");
        });
        service.runStage("retention", () -> ran.add("retention"));

        assertThat(ran).containsExactly("block-projection", "retention");
        assertThat(service.stageErrors()).containsEntry("epoch-archive", "epoch commit blew up");
        assertThat(service.stageErrors()).doesNotContainKeys("block-projection", "retention");

        // A stage that recovers stops being reported.
        service.runStage("epoch-archive", () -> ran.add("epoch-archive"));
        assertThat(service.stageErrors()).isEmpty();
        assertThat(ran).containsExactly("block-projection", "retention", "epoch-archive");
    }

    @Test
    void aParkedDatasetStopsBeingServedInsteadOfReturningTruncatedHistory() {
        // The durable LIVE phase marker stays (it is crash-safe handoff state),
        // so readiness must reject a parked worker instead. Otherwise the API
        // keeps answering from a cursor that can never advance again, silently
        // truncating history rather than reporting it unavailable.
        var live = Map.of(ArchiveTrack.LIVE, status(ArchiveWorkerStatus.State.FAILED));
        assertThat(HistoryArchiveService.nonRetryablyFailed(live, ArchiveTrack.LIVE)).isTrue();

        // Epoch datasets are owned by their backfill track.
        var epoch = Map.of(ArchiveTrack.BACKFILL, status(ArchiveWorkerStatus.State.FAILED));
        assertThat(HistoryArchiveService.nonRetryablyFailed(epoch, ArchiveTrack.BACKFILL)).isTrue();

        // Contention and ordinary progress keep a dataset serving.
        for (var state : ArchiveWorkerStatus.State.values()) {
            if (state == ArchiveWorkerStatus.State.FAILED) continue;
            assertThat(HistoryArchiveService.nonRetryablyFailed(
                    Map.of(ArchiveTrack.LIVE, status(state)), ArchiveTrack.LIVE))
                    .as("state %s must keep the dataset serving", state).isFalse();
        }
    }

    @Test
    void aRollbackNeverUnparksAFailedDatasetButStillRollsBackItsHotState() {
        long activation = 100;

        // Deep rollback, or one whose cold coverage was invalidated: a healthy
        // dataset reactivates, a parked one must not -- reactivation re-arms
        // catch-up and the body-retention floor that parking released. The parked
        // dataset still clears its now-orphaned live cursor, because parking is
        // in-memory only and a surviving cursor would let a restarted worker
        // resume above an invalidated range.
        assertThat(HistoryArchiveService.liveRollbackAction(false, true, 150, activation))
                .isEqualTo(HistoryArchiveService.LiveRollbackAction.REACTIVATE);
        assertThat(HistoryArchiveService.liveRollbackAction(true, true, 150, activation))
                .isEqualTo(HistoryArchiveService.LiveRollbackAction.CLEAR_STALE_PARKED);
        assertThat(HistoryArchiveService.liveRollbackAction(false, false, activation - 5, activation))
                .isEqualTo(HistoryArchiveService.LiveRollbackAction.REACTIVATE);
        assertThat(HistoryArchiveService.liveRollbackAction(true, false, activation - 5, activation))
                .isEqualTo(HistoryArchiveService.LiveRollbackAction.CLEAR_STALE_PARKED);

        // Shallower rollbacks still apply exactly, parked or not, so private hot
        // state is never left orphaned above the common block.
        for (boolean parked : new boolean[] {false, true}) {
            assertThat(HistoryArchiveService.liveRollbackAction(parked, false, activation - 1, activation))
                    .as("parked=%s at the activation boundary", parked)
                    .isEqualTo(HistoryArchiveService.LiveRollbackAction.RESET_TO_ACTIVATION);
            assertThat(HistoryArchiveService.liveRollbackAction(parked, false, 150, activation))
                    .as("parked=%s inside the live window", parked)
                    .isEqualTo(HistoryArchiveService.LiveRollbackAction.EXACT_ROLLBACK);
        }
    }

    private static ArchiveWorkerStatus status(ArchiveWorkerStatus.State state) {
        return new ArchiveWorkerStatus(ArchiveDatasetId.TRANSACTION,
                ArchiveTrack.BACKFILL, state, 0, 0, "", Instant.EPOCH);
    }

    private record StaticConfig(Map<String, String> values) implements Config {
        @Override public <T> T getValue(String propertyName, Class<T> propertyType) {
            return getOptionalValue(propertyName, propertyType)
                    .orElseThrow(() -> new NoSuchElementException(propertyName));
        }

        @Override public ConfigValue getConfigValue(String propertyName) {
            throw new UnsupportedOperationException();
        }

        @Override public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
            String value = values.get(propertyName);
            if (value == null) return Optional.empty();
            if (propertyType == String.class) return Optional.of(propertyType.cast(value));
            if (propertyType == Boolean.class) return Optional.of(propertyType.cast(Boolean.valueOf(value)));
            if (propertyType == Long.class) return Optional.of(propertyType.cast(Long.valueOf(value)));
            if (propertyType == Integer.class) return Optional.of(propertyType.cast(Integer.valueOf(value)));
            throw new UnsupportedOperationException("Unsupported config type: " + propertyType.getName());
        }

        @Override public Iterable<String> getPropertyNames() { return values.keySet(); }

        @Override public Iterable<ConfigSource> getConfigSources() { return List.of(); }

        @Override public <T> Optional<Converter<T>> getConverter(Class<T> forType) { return Optional.empty(); }

        @Override public <T> T unwrap(Class<T> type) {
            if (type.isInstance(this)) return type.cast(this);
            throw new IllegalArgumentException("Cannot unwrap to " + type.getName());
        }
    }
}
