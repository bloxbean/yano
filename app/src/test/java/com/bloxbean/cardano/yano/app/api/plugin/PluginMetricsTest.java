package com.bloxbean.cardano.yano.app.api.plugin;

import com.bloxbean.cardano.yano.api.plugin.PluginTrustTier;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginBundleRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginContributionRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginCounterValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginFailure;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginGaugeValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthStatus;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginLifecycleState;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricSeries;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricType;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperation;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationCount;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationOutcome;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsTotals;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginTimerValue;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

class PluginMetricsTest {

    private static final String BUNDLE = "com.example.metrics";
    private static final String FINGERPRINT = "sha256:" + "e".repeat(64);

    @Test
    void exportedMeterCardinalityHasAnExplicitHostCap() {
        assertEquals(16, PluginMetrics.STANDARD_METER_COUNT);
        assertEquals(4_096, PluginMetricDescriptor.MAX_SERIES_HOST_WIDE);
        assertEquals(4_096, PluginMetrics.MAX_OPERATION_METERS);
        assertEquals(8_208, PluginMetrics.MAX_EXPORTED_METERS);
    }

    @Test
    void unavailableOperationsCacheCannotFailApplicationStartup() {
        PluginMetrics metrics = metrics(new SimpleMeterRegistry(), () -> {
            throw new IllegalStateException("secret-plugin-snapshot-error");
        });

        try {
            assertDoesNotThrow(() -> metrics.onStart(null));
        } finally {
            metrics.onStop(null);
        }
    }

    @Test
    void bridgeUsesOneCachedReadAndHostOwnedBoundedTags() {
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<PluginOperationsSnapshot> snapshot = new AtomicReference<>(snapshot(
                1, false,
                List.of(
                        gauge("queue.depth", "queue.depth", "plugin gauge A", "items", 7.5),
                        gauge("queue_depth", "queue_depth", "plugin gauge B", "bytes", 8.5),
                        counter(11), timer(3, 1_500_000))));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PluginMetrics metrics = metrics(registry, () -> {
            reads.incrementAndGet();
            return snapshot.get();
        });

        metrics.registerStandardMeters();
        metrics.refresh();

        assertEquals(1, reads.get());
        assertEquals(1d, registry.get(PluginMetrics.BUNDLES)
                .tag("state", "active").gauge().value());
        assertEquals(1d, registry.get(PluginMetrics.BUNDLE_HEALTH)
                .tag("status", "up").gauge().value());
        assertEquals(2d, registry.get(PluginMetrics.ACTIVE_CALLBACKS).gauge().value());
        assertEquals(3d, registry.get(PluginMetrics.QUEUED_CALLBACKS).gauge().value());
        assertEquals(7.5d, registry.get(PluginMetrics.CUSTOM_GAUGE)
                .tags("plugin", BUNDLE, "metric", "queue.depth").gauge().value());
        assertEquals(8.5d, registry.get(PluginMetrics.CUSTOM_GAUGE)
                .tags("plugin", BUNDLE, "metric", "queue_depth").gauge().value());
        assertEquals(11d, registry.get(PluginMetrics.CUSTOM_COUNTER)
                .tags("plugin", BUNDLE, "metric", "requests").functionCounter().count());
        assertEquals(3L, registry.get(PluginMetrics.CUSTOM_TIMER)
                .tags("plugin", BUNDLE, "metric", "latency").functionTimer().count());
        assertEquals(1_500_000d, registry.get(PluginMetrics.CUSTOM_TIMER)
                .tags("plugin", BUNDLE, "metric", "latency")
                .functionTimer().totalTime(TimeUnit.NANOSECONDS));
        assertEquals(7d, registry.get(PluginMetrics.OPERATIONS)
                .tags("plugin", BUNDLE, "operation", "domain_get", "outcome", "succeeded")
                .functionCounter().count());
        assertEquals(1, reads.get(), "meter reads must not call PluginOperationsView");

        Collection<Meter> customGauges = registry.find(PluginMetrics.CUSTOM_GAUGE).meters();
        assertEquals(2, customGauges.size(), "descriptor ids avoid name-normalization collisions");
        for (Meter meter : customGauges) {
            Set<String> tagKeys = meter.getId().getTags().stream()
                    .map(tag -> tag.getKey()).collect(Collectors.toSet());
            assertEquals(Set.of("plugin", "metric"), tagKeys);
            assertEquals("Plugin-provided cached gauge", meter.getId().getDescription());
            assertNull(meter.getId().getBaseUnit());
        }
    }

    @Test
    void repeatedRefreshesDoNotGrowMetersAndExplicitlyStaleSeriesRetainLastValue() {
        AtomicReference<PluginOperationsSnapshot> snapshot = new AtomicReference<>(snapshot(
                1, false, List.of(counter(5))));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PluginMetrics metrics = metrics(registry, snapshot::get);
        metrics.registerStandardMeters();
        metrics.refresh();
        int meters = registry.getMeters().size();

        for (int i = 0; i < 20; i++) {
            metrics.refresh();
        }

        assertEquals(meters, registry.getMeters().size());
        snapshot.set(snapshot(2, true, List.of(counter(5, true))));
        metrics.refresh();

        assertEquals(meters, registry.getMeters().size());
        assertEquals(1d, registry.get(PluginMetrics.STALE_SOURCES).gauge().value());
        assertEquals(5d, registry.get(PluginMetrics.CUSTOM_COUNTER)
                .tags("plugin", BUNDLE, "metric", "requests").functionCounter().count());
    }

    @Test
    void countersAndTimersStayMonotonicAcrossGenerationResetAndRemoval() {
        AtomicReference<PluginOperationsSnapshot> snapshot = new AtomicReference<>(snapshot(
                1, false, List.of(counter(100), timer(100, 1_000)), 100, true));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PluginMetrics metrics = metrics(registry, snapshot::get);
        metrics.registerStandardMeters();
        metrics.refresh();
        int meterCount = registry.getMeters().size();

        snapshot.set(snapshot(2, false, List.of(), 0, false));
        metrics.refresh();
        assertEquals(0d, registry.get(PluginMetrics.STALE_SOURCES).gauge().value());
        assertNull(registry.find(PluginMetrics.CUSTOM_COUNTER)
                .tags("plugin", BUNDLE, "metric", "requests").functionCounter());
        assertNull(registry.find(PluginMetrics.CUSTOM_TIMER)
                .tags("plugin", BUNDLE, "metric", "latency").functionTimer());
        assertEquals(meterCount - 2, registry.getMeters().size());
        assertEquals(100d, operationCounter(registry).count());

        snapshot.set(snapshot(3, false, List.of(counter(1), timer(1, 10)), 1, true));
        metrics.refresh();

        assertEquals(meterCount, registry.getMeters().size());
        assertEquals(0d, registry.get(PluginMetrics.STALE_SOURCES).gauge().value());
        assertEquals(101d, customCounter(registry).count());
        assertEquals(101L, customTimer(registry).count());
        assertEquals(1_010d, customTimer(registry).totalTime(TimeUnit.NANOSECONDS), 0.000_001d);
        assertEquals(101d, operationCounter(registry).count());
    }

    @Test
    void descriptorTypeChangeReplacesTheMeterAndResetsTypeSpecificContinuity() {
        AtomicReference<PluginOperationsSnapshot> snapshot = new AtomicReference<>(snapshot(
                1, false, List.of(counter(100))));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PluginMetrics metrics = metrics(registry, snapshot::get);
        metrics.registerStandardMeters();
        metrics.refresh();
        int meterCount = registry.getMeters().size();

        snapshot.set(snapshot(2, false, List.of(gauge(
                "requests", "renamed.requests", "Generation two gauge", "items", 7.5))));
        metrics.refresh();

        assertNull(registry.find(PluginMetrics.CUSTOM_COUNTER)
                .tags("plugin", BUNDLE, "metric", "requests").functionCounter());
        assertEquals(7.5d, registry.get(PluginMetrics.CUSTOM_GAUGE)
                .tags("plugin", BUNDLE, "metric", "requests").gauge().value());
        assertEquals(PluginMetricType.GAUGE,
                snapshot.get().metrics().getFirst().descriptor().type(),
                "the exported Micrometer shape must match the REST snapshot shape");
        assertEquals(meterCount, registry.getMeters().size());

        snapshot.set(snapshot(3, false, List.of(counter(1))));
        metrics.refresh();

        assertNull(registry.find(PluginMetrics.CUSTOM_GAUGE)
                .tags("plugin", BUNDLE, "metric", "requests").gauge());
        assertEquals(1d, registry.get(PluginMetrics.CUSTOM_COUNTER)
                .tags("plugin", BUNDLE, "metric", "requests").functionCounter().count(),
                "a type change starts a new type-specific cumulative series");
        assertEquals(meterCount, registry.getMeters().size());
    }

    @Test
    void healthMetersExcludeDeniedInventoryBundles() {
        PluginContributionRuntimeInfo activeContribution =
                new PluginContributionRuntimeInfo(
                        "node-plugin", BUNDLE, PluginTrustTier.REQUIRED,
                        true, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                        PluginFailure.none(), false, List.of());
        PluginBundleRuntimeInfo selected = new PluginBundleRuntimeInfo(
                BUNDLE, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                PluginFailure.none(), false, 10, 0, 0, List.of(),
                List.of(activeContribution));
        PluginBundleRuntimeInfo denied = new PluginBundleRuntimeInfo(
                "com.example.denied", PluginLifecycleState.NOT_SELECTED,
                PluginHealthStatus.UNKNOWN, PluginFailure.none(), false,
                0, 0, 0, List.of(), List.of());
        PluginOperationsSnapshot snapshot = new PluginOperationsSnapshot(
                FINGERPRINT, 1, 20,
                new PluginOperationsTotals(2, 1, 1, 0, 0, 1, 1, 1, 0, 0),
                List.of(selected, denied), List.of());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PluginMetrics metrics = metrics(registry, () -> snapshot);

        metrics.registerStandardMeters();
        metrics.refresh();

        assertEquals(1d, registry.get(PluginMetrics.BUNDLE_HEALTH)
                .tag("status", "up").gauge().value());
        assertEquals(0d, registry.get(PluginMetrics.BUNDLE_HEALTH)
                .tag("status", "unknown").gauge().value());
    }

    private static PluginMetrics metrics(
            SimpleMeterRegistry registry,
            com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsView view
    ) {
        PluginMetrics metrics = new PluginMetrics();
        metrics.registry = registry;
        metrics.operations = view;
        return metrics;
    }

    private static PluginOperationsSnapshot snapshot(long generation,
                                                       boolean metricsStale,
                                                       List<PluginMetricSeries> series) {
        return snapshot(generation, metricsStale, series, 7, true);
    }

    private static PluginOperationsSnapshot snapshot(long generation,
                                                       boolean metricsStale,
                                                       List<PluginMetricSeries> series,
                                                       long operationTotal,
                                                       boolean includeOperation) {
        List<PluginContributionRuntimeInfo> contributions = List.of(
                new PluginContributionRuntimeInfo(
                        "metrics", BUNDLE, PluginTrustTier.AUXILIARY_LOCAL,
                        true, PluginLifecycleState.ACTIVE, PluginHealthStatus.UP,
                        PluginFailure.none(), metricsStale, List.of()));
        PluginBundleRuntimeInfo bundle = new PluginBundleRuntimeInfo(
                BUNDLE, PluginLifecycleState.ACTIVE,
                metricsStale ? PluginHealthStatus.DEGRADED : PluginHealthStatus.UP,
                PluginFailure.none(), metricsStale, 10, 2, 3,
                includeOperation ? List.of(new PluginOperationCount(
                        PluginOperation.DOMAIN_GET, PluginOperationOutcome.SUCCEEDED,
                        operationTotal)) : List.of(),
                contributions);
        return new PluginOperationsSnapshot(
                FINGERPRINT, generation, 20,
                new PluginOperationsTotals(1, 1, 1, metricsStale ? 1 : 0, 0,
                        contributions.size(), contributions.size(), contributions.size(),
                        metricsStale ? 1 : 0, 0),
                List.of(bundle), series);
    }

    private static io.micrometer.core.instrument.FunctionCounter customCounter(
            SimpleMeterRegistry registry
    ) {
        return registry.get(PluginMetrics.CUSTOM_COUNTER)
                .tags("plugin", BUNDLE, "metric", "requests").functionCounter();
    }

    private static io.micrometer.core.instrument.FunctionTimer customTimer(
            SimpleMeterRegistry registry
    ) {
        return registry.get(PluginMetrics.CUSTOM_TIMER)
                .tags("plugin", BUNDLE, "metric", "latency").functionTimer();
    }

    private static io.micrometer.core.instrument.FunctionCounter operationCounter(
            SimpleMeterRegistry registry
    ) {
        return registry.get(PluginMetrics.OPERATIONS)
                .tags("plugin", BUNDLE, "operation", "domain_get", "outcome", "succeeded")
                .functionCounter();
    }

    private static PluginMetricSeries gauge(String id,
                                             String name,
                                             String description,
                                             String baseUnit,
                                             double value) {
        return new PluginMetricSeries(
                BUNDLE,
                new PluginMetricDescriptor(
                        id, name, PluginMetricType.GAUGE, description, baseUnit),
                new PluginGaugeValue(value), false);
    }

    private static PluginMetricSeries counter(double total) {
        return counter(total, false);
    }

    private static PluginMetricSeries counter(double total, boolean stale) {
        return new PluginMetricSeries(
                BUNDLE,
                new PluginMetricDescriptor(
                        "requests", "requests", PluginMetricType.COUNTER,
                        "Plugin requests", "requests"),
                new PluginCounterValue(total), stale);
    }

    private static PluginMetricSeries timer(long count, long totalNanos) {
        return new PluginMetricSeries(
                BUNDLE,
                new PluginMetricDescriptor(
                        "latency", "latency", PluginMetricType.TIMER,
                        "Plugin latency", "nanoseconds"),
                new PluginTimerValue(count, totalNanos), false);
    }
}
