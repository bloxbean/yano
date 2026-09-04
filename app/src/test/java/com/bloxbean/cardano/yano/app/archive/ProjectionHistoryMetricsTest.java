package com.bloxbean.cardano.yano.app.archive;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectionHistoryMetricsTest {
    @Test
    void metricsUseOnlyBoundedDatasetAndFailureTags() {
        var projection = mock(ProjectionHistoryService.class);
        when(projection.drainFailureCount()).thenReturn(3L);
        when(projection.captureFailureCount()).thenReturn(2L);
        when(projection.durableCaptureFailureCount()).thenReturn(1L);
        when(projection.pendingEpochArtifactGapCount()).thenReturn(4L);
        when(projection.epochArtifactMetrics()).thenReturn(Map.of("reward", Map.ofEntries(
                Map.entry("selected", 1d), Map.entry("paused", 1d),
                Map.entry("projectedFrom", 400d), Map.entry("lastComplete", 449d),
                Map.entry("observedThrough", 500d), Map.entry("gaps", 1d),
                Map.entry("gapRanges", 1d), Map.entry("gaps.io", 1d),
                Map.entry("gaps.filesystem", 0d), Map.entry("gaps.capacity", 0d),
                Map.entry("gaps.capture", 0d))));
        var metrics = new ProjectionHistoryMetrics();
        metrics.registry = new SimpleMeterRegistry();
        metrics.projection = projection;
        metrics.onStart(null);

        assertThat(metrics.registry.find("yano.history.epoch.artifact.paused")
                .tag("dataset", "reward").gauge().value()).isEqualTo(1d);
        assertThat(metrics.registry.find("yano.history.epoch.artifact.gaps.by.failure")
                .tag("dataset", "reward").tag("failure", "io").gauge().value()).isEqualTo(1d);
        assertThat(metrics.registry.find("yano.history.projection.drain.failures")
                .gauge().value()).isEqualTo(3d);
        assertThat(metrics.registry.find("yano.history.projection.capture.failures")
                .gauge().value()).isEqualTo(2d);
        assertThat(metrics.registry.find("yano.history.projection.capture.durable.failures")
                .gauge().value()).isEqualTo(1d);
        assertThat(metrics.registry.find("yano.history.projection.capture.pending.epoch.gaps")
                .gauge().value()).isEqualTo(4d);
        assertThat(metrics.registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isIn("dataset", "failure")));
    }
}
