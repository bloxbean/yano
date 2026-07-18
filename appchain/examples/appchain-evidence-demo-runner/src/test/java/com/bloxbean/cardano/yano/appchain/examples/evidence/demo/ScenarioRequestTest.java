package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRequestTest {
    private static final Path SAMPLE = Path.of("samples/evidence.json");

    @Test
    void acceptsTheExplicitBusinessOperationShapes() {
        assertThat(new ScenarioRequest(ScenarioRequest.Operation.PUBLISH,
                "inspection-a", 1, SAMPLE).businessVersion()).isEqualTo(1);
        assertThat(new ScenarioRequest(ScenarioRequest.Operation.REPUBLISH,
                "inspection-a", 2, SAMPLE).businessVersion()).isEqualTo(2);
        assertThat(new ScenarioRequest(ScenarioRequest.Operation.VERIFY,
                "inspection-a", 0, null).businessVersion()).isZero();
        assertThat(new ScenarioRequest(ScenarioRequest.Operation.REPLAY,
                "inspection-a", 1, SAMPLE).sampleFile()).isAbsolute();
    }

    @Test
    void rejectsAmbiguousVersionsAndSampleCombinations() {
        assertInvalid(() -> new ScenarioRequest(
                ScenarioRequest.Operation.PUBLISH, "inspection-a", 2, SAMPLE));
        assertInvalid(() -> new ScenarioRequest(
                ScenarioRequest.Operation.REPUBLISH, "inspection-a", 1, SAMPLE));
        assertInvalid(() -> new ScenarioRequest(
                ScenarioRequest.Operation.REPLAY, "inspection-a", 0, SAMPLE));
        assertInvalid(() -> new ScenarioRequest(
                ScenarioRequest.Operation.VERIFY, "inspection-a", 1, SAMPLE));
        assertInvalid(() -> new ScenarioRequest(
                ScenarioRequest.Operation.PUBLISH, "Inspection_A", 1, SAMPLE));
    }

    private static void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(DemoException.class, failure ->
                        assertThat(failure.error()).isEqualTo(DemoError.INVALID_ARGUMENT));
    }
}
