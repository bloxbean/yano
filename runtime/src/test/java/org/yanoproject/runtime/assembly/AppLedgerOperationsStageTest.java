package org.yanoproject.runtime.assembly;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The app-ledger {@code app-operations} stage seals on a failed start and attempts every seal on stop. */
class AppLedgerOperationsStageTest {
    private final List<String> calls = new ArrayList<>();

    private Runnable step(String name, RuntimeException failure) {
        return () -> {
            calls.add(name);
            if (failure != null) {
                throw failure;
            }
        };
    }

    @Test
    void partialResumeFailureSealsTelemetryAndProductsBeforeRethrowing() {
        var failure = new IllegalStateException("resume failed after one owner");
        var stage = new AppLedgerAssembly.OperationsStage(step("resume", failure), step("telemetry", null),
                step("seal-telemetry", null), step("seal-products", null));

        assertThatThrownBy(stage::start).isSameAs(failure);
        assertThat(calls).containsExactly("resume", "seal-telemetry", "seal-products");
    }

    @Test
    void telemetryActivationFailureSealsTelemetryAndProductsBeforeRethrowing() {
        var failure = new IllegalStateException("telemetry failed");
        var sealFailure = new IllegalStateException("product seal failed");
        var stage = new AppLedgerAssembly.OperationsStage(step("resume", null), step("telemetry", failure),
                step("seal-telemetry", null), step("seal-products", sealFailure));

        assertThatThrownBy(stage::start).isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(sealFailure);
        assertThat(calls).containsExactly("resume", "telemetry", "seal-telemetry", "seal-products");
    }

    @Test
    void stopAttemptsBothSealsAndAggregatesTheirFailures() {
        var telemetryFailure = new IllegalStateException("telemetry seal failed");
        var productFailure = new IllegalStateException("product seal failed");
        var stage = new AppLedgerAssembly.OperationsStage(step("resume", null), step("telemetry", null),
                step("seal-telemetry", telemetryFailure), step("seal-products", productFailure));

        stage.start();
        assertThatThrownBy(stage::stop).isSameAs(telemetryFailure);
        assertThat(telemetryFailure.getSuppressed()).containsExactly(productFailure);
        assertThat(calls).containsExactly("resume", "telemetry", "seal-telemetry", "seal-products");
    }
}
