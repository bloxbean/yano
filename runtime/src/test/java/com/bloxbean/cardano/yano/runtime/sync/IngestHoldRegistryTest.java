package com.bloxbean.cardano.yano.runtime.sync;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-039 disk backpressure registration.
 *
 * <p>These cover the two failure modes that were both silent in production: the hold being
 * registered before any header sync manager exists, and the manager being replaced on
 * reconnect. The live preprod run logged "disk backpressure could not be installed" for
 * exactly the first reason, while {@code /status} still reported a healthy gate decision — so
 * the gate looked fine while nothing was actually attached.
 */
class IngestHoldRegistryTest {

    /** Records what was installed, standing in for a header sync manager. */
    private static final class RecordingTarget implements IngestHoldRegistry.HoldTarget {
        private final List<BooleanSupplier> holds = new ArrayList<>();
        private final List<String> reasons = new ArrayList<>();

        @Override
        public void setIngestHold(BooleanSupplier hold, String reason) {
            holds.add(hold);
            reasons.add(reason);
        }

        int installs() {
            return holds.size();
        }

        BooleanSupplier lastHold() {
            return holds.get(holds.size() - 1);
        }

        String lastReason() {
            return reasons.get(reasons.size() - 1);
        }
    }

    @Test
    void registrationBeforeAnyManagerExistsIsRemembered() {
        var registry = new IngestHoldRegistry();
        registry.register(() -> true, "disk limit");

        assertThat(registry.isRegistered())
                .as("archive init runs before the node starts; the hold must survive that")
                .isTrue();
        assertThat(registry.reason()).isEqualTo("disk limit");
    }

    @Test
    void applyingWithNoManagerIsSafeAndKeepsTheRegistration() {
        var registry = new IngestHoldRegistry();
        registry.register(() -> true, "disk limit");
        registry.applyTo(null);
        assertThat(registry.isRegistered()).isTrue();
    }

    @Test
    void theHoldIsAppliedWhenAManagerAppears() {
        var registry = new IngestHoldRegistry();
        BooleanSupplier hold = () -> true;
        registry.register(hold, "disk limit");

        var target = new RecordingTarget();
        registry.applyTo(target);
        assertThat(target.installs()).isEqualTo(1);
        assertThat(target.lastHold()).isSameAs(hold);
        assertThat(target.lastReason()).isEqualTo("disk limit");
    }

    @Test
    void theHoldIsReappliedAfterPeerSessionReplacement() {
        // A reconnect builds a new manager. Without re-application the hold would be lost
        // exactly when a backlog is most likely to have grown.
        var registry = new IngestHoldRegistry();
        BooleanSupplier hold = () -> true;
        registry.register(hold, "disk limit");

        var first = new RecordingTarget();
        var second = new RecordingTarget();
        registry.applyTo(first);
        registry.applyTo(second);

        assertThat(first.installs()).isEqualTo(1);
        assertThat(second.installs()).isEqualTo(1);
        assertThat(second.lastHold()).isSameAs(hold);
    }

    @Test
    void noHoldRegisteredMeansNothingIsInstalled() {
        var registry = new IngestHoldRegistry();
        var target = new RecordingTarget();
        registry.applyTo(target);
        assertThat(target.installs()).isZero();
        assertThat(registry.isRegistered()).isFalse();
    }

    @Test
    void theRegisteredSupplierIsEvaluatedLiveNotCaptured() {
        // The gate re-evaluates against current usage, so the supplier must be consulted each
        // time rather than its value snapshotted at registration.
        var registry = new IngestHoldRegistry();
        var paused = new AtomicBoolean(false);
        registry.register(paused::get, "disk limit");

        var target = new RecordingTarget();
        registry.applyTo(target);

        assertThat(target.lastHold().getAsBoolean()).isFalse();
        paused.set(true);
        assertThat(target.lastHold().getAsBoolean())
                .as("hard limit reached later must be observed by the installed hold")
                .isTrue();
    }

    @Test
    void reRegistrationReplacesTheHold() {
        var registry = new IngestHoldRegistry();
        registry.register(() -> false, "first");
        registry.register(() -> true, "second");
        assertThat(registry.reason()).isEqualTo("second");

        var target = new RecordingTarget();
        registry.applyTo(target);
        assertThat(target.lastReason()).isEqualTo("second");
        assertThat(target.lastHold().getAsBoolean()).isTrue();
    }
}
