package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactContracts;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.Map;

/** Bounded ADR-045 epoch-artifact selection, coverage and degradation metrics. */
@ApplicationScoped
public class ProjectionHistoryMetrics {
    @Inject MeterRegistry registry;
    @Inject ProjectionHistoryService projection;

    private volatile Snapshot cached;

    void onStart(@Observes StartupEvent ignored) {
        Gauge.builder("yano.history.projection.drain.failures", projection,
                        service -> (double) service.drainFailureCount())
                .description("Asynchronous projection drain failures; L1 ingestion continues")
                .register(registry);
        Gauge.builder("yano.history.projection.capture.failures", projection,
                        service -> (double) service.captureFailureCount())
                .description("Projection capture failures isolated from canonical L1 ingestion")
                .register(registry);
        for (var contract : ProjectionArtifactContracts.shipped().contracts().values()) {
            String dataset = contract.dataset().logicalName();
            gauge("yano.history.epoch.artifact.selected", dataset, "selected");
            gauge("yano.history.epoch.artifact.paused", dataset, "paused");
            gauge("yano.history.epoch.artifact.projected.from", dataset, "projectedFrom");
            gauge("yano.history.epoch.artifact.last.complete", dataset, "lastComplete");
            gauge("yano.history.epoch.artifact.observed.through", dataset, "observedThrough");
            gauge("yano.history.epoch.artifact.gaps", dataset, "gaps");
            gauge("yano.history.epoch.artifact.gap.ranges", dataset, "gapRanges");
            for (String failure : java.util.List.of("io", "filesystem", "capacity", "capture")) {
                Gauge.builder("yano.history.epoch.artifact.gaps.by.failure", this,
                                metrics -> metrics.value(dataset, "gaps." + failure))
                        .tag("dataset", dataset).tag("failure", failure)
                        .description("Current durable epoch-artifact point gaps by bounded class")
                        .register(registry);
            }
        }
    }

    private void gauge(String name, String dataset, String value) {
        Gauge.builder(name, this, metrics -> metrics.value(dataset, value))
                .tag("dataset", dataset)
                .description("ADR-045 epoch-artifact coverage state")
                .register(registry);
    }

    private double value(String dataset, String key) {
        long now = System.nanoTime();
        Snapshot value = cached;
        if (value == null || now - value.createdAt() > 250_000_000L) {
            synchronized (this) {
                value = cached;
                if (value == null || now - value.createdAt() > 250_000_000L) {
                    value = cached = new Snapshot(now, projection.epochArtifactMetrics());
                }
            }
        }
        return value.values().getOrDefault(dataset, Map.of()).getOrDefault(key, Double.NaN);
    }

    private record Snapshot(long createdAt, Map<String, Map<String, Double>> values) { }
}
