package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadRequestTest {
    @Test
    void createsStableBoundedEvidenceIds() {
        LoadRequest request = new LoadRequest(50_000, 16, "shipment", Path.of("sample.json"));

        assertThat(request.evidenceId(1)).isEqualTo("shipment-000001");
        assertThat(request.evidenceId(50_000)).isEqualTo("shipment-050000");
        assertThat(request.sampleFile()).isAbsolute();
        assertThat(request.mode()).isEqualTo(LoadRequest.Mode.LIFECYCLE);
        assertThat(request.maxInFlight()).isEqualTo(16);

        LoadRequest pipeline = new LoadRequest(100, 4, LoadRequest.Mode.PIPELINE,
                32, "pipeline", Path.of("sample.json"));
        assertThat(pipeline.mode()).isEqualTo(LoadRequest.Mode.PIPELINE);
        assertThat(pipeline.maxInFlight()).isEqualTo(32);

        LoadRequest maximumPipeline = new LoadRequest(50_000, 16,
                LoadRequest.Mode.PIPELINE, 5_000, "maximum", Path.of("sample.json"));
        assertThat(maximumPipeline.maxInFlight()).isEqualTo(5_000);
    }

    @Test
    void rejectsUnsafeOrUnboundedLoadShapes() {
        assertInvalid(() -> new LoadRequest(0, 1, "load", Path.of("sample")));
        assertInvalid(() -> new LoadRequest(50_001, 1, "load", Path.of("sample")));
        assertInvalid(() -> new LoadRequest(2, 3, "load", Path.of("sample")));
        assertInvalid(() -> new LoadRequest(2, 1, "Load_A", Path.of("sample")));
        assertInvalid(() -> new LoadRequest(2, 1, "a".repeat(57), Path.of("sample")));
        assertInvalid(() -> new LoadRequest(10, 4, LoadRequest.Mode.PIPELINE,
                3, "load", Path.of("sample")));
        assertInvalid(() -> new LoadRequest(5_001, 4, LoadRequest.Mode.PIPELINE,
                5_001, "load", Path.of("sample")));
        assertThatThrownBy(() -> LoadRequest.Mode.parse("unknown"))
                .isInstanceOf(DemoException.class);
        assertThatThrownBy(() -> new LoadRequest(1, 1, "load", Path.of("sample"))
                .evidenceId(2)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(DemoException.class, failure ->
                        assertThat(failure.error()).isEqualTo(DemoError.INVALID_ARGUMENT));
    }
}
