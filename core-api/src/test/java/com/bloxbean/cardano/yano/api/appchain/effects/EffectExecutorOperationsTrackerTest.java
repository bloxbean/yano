package com.bloxbean.cardano.yano.api.appchain.effects;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EffectExecutorOperationsTrackerTest {
    @Test
    void recordsOnlyBoundedOutcomeClassesAndNormalizedAges() {
        EffectExecutorOperationsTracker tracker = new EffectExecutorOperationsTracker();

        tracker.observe(() -> EffectExecution.confirmed(new byte[]{1}));
        tracker.observe(() -> EffectExecution.failed("RATE_LIMITED", true));
        tracker.observe(() -> EffectExecution.failed("secret vendor message", false));
        tracker.observe(() -> new EffectExecution.Retry(Duration.ofMillis(10)));

        EffectExecutorOperationalSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.readiness())
                .isEqualTo(EffectExecutorOperationalSnapshot.Readiness.DEGRADED);
        assertThat(snapshot.attempts()).isEqualTo(4);
        assertThat(snapshot.successes()).isEqualTo(1);
        assertThat(snapshot.retryableFailures()).isEqualTo(2);
        assertThat(snapshot.terminalFailures()).isEqualTo(1);
        assertThat(snapshot.inFlight()).isZero();
        assertThat(snapshot.lastSuccessAge())
                .isEqualTo(EffectExecutorOperationalSnapshot.AgeBucket.LESS_THAN_ONE_MINUTE);
        assertThat(snapshot.lastFailureAge())
                .isEqualTo(EffectExecutorOperationalSnapshot.AgeBucket.LESS_THAN_ONE_MINUTE);
        assertThat(snapshot.failureCode())
                .isEqualTo(EffectExecutorOperationalSnapshot.FailureCode.BUSY);
        assertThat(snapshot.toString()).doesNotContain("secret vendor message");
    }

    @Test
    void rejectsImpossibleOrNegativeSnapshots() {
        assertThatThrownBy(() -> new EffectExecutorOperationalSnapshot(
                EffectExecutorOperationalSnapshot.Readiness.READY,
                1, 1, 1, 0, 0,
                EffectExecutorOperationalSnapshot.AgeBucket.NEVER,
                EffectExecutorOperationalSnapshot.AgeBucket.NEVER,
                EffectExecutorOperationalSnapshot.FailureCode.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordsAndRethrowsCheckedCallbackFailuresWithoutExposingTheirMessage() {
        EffectExecutorOperationsTracker tracker = new EffectExecutorOperationsTracker();

        assertThatThrownBy(() -> tracker.observeChecked(() -> {
            throw new IOException("credential-bearing provider failure");
        })).isInstanceOf(IOException.class);

        EffectExecutorOperationalSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.attempts()).isOne();
        assertThat(snapshot.retryableFailures()).isOne();
        assertThat(snapshot.inFlight()).isZero();
        assertThat(snapshot.failureCode())
                .isEqualTo(EffectExecutorOperationalSnapshot.FailureCode.INTERNAL);
        assertThat(snapshot.toString()).doesNotContain("credential-bearing provider failure");
    }
}
