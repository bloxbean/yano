package com.bloxbean.cardano.yano.app.api.plugin;

import com.bloxbean.cardano.yano.api.plugin.operations.PluginCounterValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginGaugeValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthStatus;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginLifecycleState;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricSeries;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricType;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsTotals;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsView;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginTimerValue;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Micrometer projection of the host-cached ADR-011.4 operations snapshot. */
@ApplicationScoped
public class PluginMetrics {

    static final String BUNDLES = "yano.plugin.bundles";
    static final String CONTRIBUTIONS = "yano.plugin.contributions";
    static final String BUNDLE_HEALTH = "yano.plugin.bundle.health";
    static final String STALE_SOURCES = "yano.plugin.sources.stale";
    static final String ACTIVE_SAMPLES = "yano.plugin.samples.active";
    static final String ACTIVE_CALLBACKS = "yano.plugin.callbacks.active";
    static final String QUEUED_CALLBACKS = "yano.plugin.callbacks.queued";
    static final String OPERATIONS = "yano.plugin.operations";
    static final String CUSTOM_GAUGE = "yano.plugin.custom.gauge";
    static final String CUSTOM_COUNTER = "yano.plugin.custom.counter";
    static final String CUSTOM_TIMER = "yano.plugin.custom.timer";
    static final int STANDARD_METER_COUNT = 16;
    static final int MAX_OPERATION_METERS = 4_096;
    static final int MAX_EXPORTED_METERS = STANDARD_METER_COUNT
            + PluginMetricDescriptor.MAX_SERIES_HOST_WIDE + MAX_OPERATION_METERS;

    private static final Logger log = Logger.getLogger(PluginMetrics.class);

    @Inject
    MeterRegistry registry;

    @Inject
    PluginOperationsView operations;

    private final AtomicReference<PluginOperationsSnapshot> latest = new AtomicReference<>();
    private final Map<MetricKey, CustomMetricState> customMetrics = new ConcurrentHashMap<>();
    private final Map<OperationKey, OperationCounterState> operationCounters =
            new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean();
    private volatile ScheduledExecutorService refresher;

    void onStart(@Observes StartupEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            registerStandardMeters();
        } catch (RuntimeException registrationFailed) {
            log.warn("Plugin metrics registration failed");
        }
        refreshSafely();
        try {
            refresher = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name("plugin-metrics-cache").factory());
            refresher.scheduleWithFixedDelay(this::refreshSafely, 1, 1, TimeUnit.SECONDS);
        } catch (RuntimeException schedulingFailed) {
            refresher = null;
            log.warn("Plugin metrics cache scheduling failed");
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        ScheduledExecutorService current = refresher;
        refresher = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    void registerStandardMeters() {
        registerBundleGauge("discovered", PluginOperationsTotals::discoveredBundles);
        registerBundleGauge("selected", PluginOperationsTotals::selectedBundles);
        registerBundleGauge("active", PluginOperationsTotals::activeBundles);
        registerBundleGauge("degraded", PluginOperationsTotals::degradedBundles);
        registerBundleGauge("failed", PluginOperationsTotals::failedBundles);
        registerContributionGauge("total", PluginOperationsTotals::contributions);
        registerContributionGauge("observed", PluginOperationsTotals::observedContributions);
        registerContributionGauge(
                "active", PluginOperationsTotals::observedActiveContributions);
        for (PluginHealthStatus status : PluginHealthStatus.values()) {
            Gauge.builder(BUNDLE_HEALTH, this, metrics -> metrics.healthCount(status))
                    .tag("status", status.name().toLowerCase(Locale.ROOT))
                    .description("Plugin bundles by cached health status")
                    .register(registry);
        }
        Gauge.builder(STALE_SOURCES, this, metrics -> metrics.total(
                        PluginOperationsTotals::staleSources))
                .description("Stale plugin health or metric sources")
                .register(registry);
        Gauge.builder(ACTIVE_SAMPLES, this, metrics -> metrics.total(
                        PluginOperationsTotals::activeSamples))
                .description("Plugin telemetry callbacks currently executing")
                .register(registry);
        Gauge.builder(ACTIVE_CALLBACKS, this, PluginMetrics::activeCallbacks)
                .description("Plugin callbacks currently executing")
                .register(registry);
        Gauge.builder(QUEUED_CALLBACKS, this, PluginMetrics::queuedCallbacks)
                .description("Plugin callbacks currently queued")
                .register(registry);
    }

    synchronized void refresh() {
        PluginOperationsSnapshot snapshot = operations.snapshot();
        Set<MetricKey> present = new HashSet<>();
        for (PluginMetricSeries series : snapshot.metrics()) {
            MetricKey key = new MetricKey(series.bundleId(), series.descriptor().id());
            CustomMetricState state = customMetrics.get(key);
            if (state != null && state.type != series.descriptor().type()) {
                Meter removed = state.detach();
                if (removed != null) {
                    registry.remove(removed);
                }
                state = new CustomMetricState(series.descriptor().type());
                customMetrics.put(key, state);
            }
            if (state == null
                    && customMetrics.size() < PluginMetricDescriptor.MAX_SERIES_HOST_WIDE) {
                state = customMetrics.computeIfAbsent(
                        key, ignored -> new CustomMetricState(series.descriptor().type()));
            }
            if (state != null) {
                state.update(snapshot.generation(), series);
                if (!state.registered()) {
                    state.attach(registerCustomMetric(key, state));
                }
                present.add(key);
            }
        }
        customMetrics.forEach((key, state) -> {
            if (!present.contains(key)) {
                Meter removed = state.detach();
                if (removed != null) {
                    registry.remove(removed);
                }
            }
        });
        snapshot.bundles().forEach(bundle -> bundle.operationCounts().forEach(count -> {
            OperationKey key = new OperationKey(
                    bundle.id(), count.operation().name(), count.outcome().name());
            OperationCounterState state = operationCounters.get(key);
            if (state == null && operationCounters.size() < MAX_OPERATION_METERS) {
                state = operationCounters.computeIfAbsent(
                        key, this::registerOperationCounter);
            }
            if (state != null) {
                state.update(snapshot.generation(), count.total());
            }
        }));
        latest.set(snapshot);
    }

    private void refreshSafely() {
        try {
            refresh();
            refreshFailureLogged.set(false);
        } catch (RuntimeException unavailable) {
            // Public/log diagnostics intentionally exclude plugin-controlled exception text.
            if (refreshFailureLogged.compareAndSet(false, true)) {
                log.warn("Plugin metrics cache refresh failed");
            }
        }
    }

    private void registerBundleGauge(String state,
                                     java.util.function.ToIntFunction<PluginOperationsTotals> value) {
        Gauge.builder(BUNDLES, this, metrics -> metrics.total(value))
                .tag("state", state)
                .description("Plugin bundles by bounded lifecycle category")
                .register(registry);
    }

    private void registerContributionGauge(
            String state,
            java.util.function.ToIntFunction<PluginOperationsTotals> value
    ) {
        Gauge.builder(CONTRIBUTIONS, this, metrics -> metrics.total(value))
                .tag("state", state)
                .description("Plugin contributions by bounded lifecycle category")
                .register(registry);
    }

    private double total(java.util.function.ToIntFunction<PluginOperationsTotals> value) {
        PluginOperationsSnapshot snapshot = latest.get();
        return snapshot == null ? 0 : value.applyAsInt(snapshot.totals());
    }

    private double activeCallbacks() {
        PluginOperationsSnapshot snapshot = latest.get();
        return snapshot == null ? 0 : snapshot.bundles().stream()
                .mapToInt(bundle -> bundle.activeCallbacks()).sum();
    }

    private double healthCount(PluginHealthStatus status) {
        PluginOperationsSnapshot snapshot = latest.get();
        return snapshot == null ? 0 : snapshot.bundles().stream()
                .filter(bundle -> bundle.lifecycle() != PluginLifecycleState.NOT_SELECTED)
                .filter(bundle -> bundle.health() == status)
                .count();
    }

    private double queuedCallbacks() {
        PluginOperationsSnapshot snapshot = latest.get();
        return snapshot == null ? 0 : snapshot.bundles().stream()
                .mapToInt(bundle -> bundle.queuedCallbacks()).sum();
    }

    private Meter registerCustomMetric(MetricKey key, CustomMetricState state) {
        return switch (state.type) {
            case GAUGE -> Gauge.builder(CUSTOM_GAUGE, state, CustomMetricState::gauge)
                        .tags("plugin", key.bundle(), "metric", key.metric())
                        .description("Plugin-provided cached gauge")
                        .register(registry);
            case COUNTER -> FunctionCounter.builder(
                                CUSTOM_COUNTER, state, CustomMetricState::counter)
                        .tags("plugin", key.bundle(), "metric", key.metric())
                        .description("Plugin-provided cached counter")
                        .register(registry);
            case TIMER -> FunctionTimer.builder(
                            CUSTOM_TIMER,
                            state,
                            CustomMetricState::timerCount,
                            CustomMetricState::timerTotalNanos,
                            TimeUnit.NANOSECONDS)
                    .tags("plugin", key.bundle(), "metric", key.metric())
                    .description("Plugin-provided cached timer")
                    .register(registry);
        };
    }

    private OperationCounterState registerOperationCounter(OperationKey key) {
        OperationCounterState state = new OperationCounterState();
        FunctionCounter.builder(OPERATIONS, state, OperationCounterState::total)
                .tags("plugin", key.bundle(),
                        "operation", key.operation().toLowerCase(Locale.ROOT),
                        "outcome", key.outcome().toLowerCase(Locale.ROOT))
                .description("Host-observed plugin operation outcomes")
                .register(registry);
        return state;
    }

    private record MetricKey(String bundle, String metric) {
    }

    private record OperationKey(String bundle, String operation, String outcome) {
    }

    private static final class CustomMetricState {
        private final PluginMetricType type;
        private volatile double gauge;
        private volatile Meter meter;
        private final MonotonicDouble counter = new MonotonicDouble();
        private final MonotonicTimer timer = new MonotonicTimer();

        private CustomMetricState(PluginMetricType type) {
            this.type = type;
        }

        private void update(long generation, PluginMetricSeries series) {
            if (series.descriptor().type() != type) {
                return;
            }
            if (series.value() instanceof PluginGaugeValue value) {
                gauge = value.value();
            } else if (series.value() instanceof PluginCounterValue value) {
                counter.update(generation, value.total());
            } else if (series.value() instanceof PluginTimerValue value) {
                timer.update(generation, value.count(), value.totalNanos());
            }
        }

        private double gauge() {
            return gauge;
        }

        private double counter() {
            return counter.value();
        }

        private long timerCount() {
            return timer.value().count();
        }

        private double timerTotalNanos() {
            return timer.value().totalNanos();
        }

        private boolean registered() {
            return meter != null;
        }

        private void attach(Meter registered) {
            meter = registered;
        }

        private Meter detach() {
            Meter registered = meter;
            meter = null;
            return registered;
        }

    }

    private static final class OperationCounterState {
        private final MonotonicLong total = new MonotonicLong();

        private void update(long generation, long value) {
            total.update(generation, value);
        }

        private double total() {
            return total.value();
        }
    }

    /** Preserves a finite cumulative value when a new runtime generation resets its raw value. */
    private static final class MonotonicDouble {
        private long generation = Long.MIN_VALUE;
        private double offset;
        private double rawHighWatermark;
        private volatile double value;

        private synchronized void update(long nextGeneration, double raw) {
            if (generation == Long.MIN_VALUE) {
                generation = nextGeneration;
                rawHighWatermark = raw;
                value = raw;
                return;
            }
            if (nextGeneration < generation) {
                return;
            }
            if (nextGeneration > generation) {
                if (raw < rawHighWatermark) {
                    offset = value;
                }
                generation = nextGeneration;
                rawHighWatermark = raw;
            } else {
                rawHighWatermark = Math.max(rawHighWatermark, raw);
            }
            value = Math.max(value, finiteAdd(offset, raw));
        }

        private double value() {
            return value;
        }

        private static double finiteAdd(double left, double right) {
            return right > Double.MAX_VALUE - left ? Double.MAX_VALUE : left + right;
        }
    }

    /** Long-valued counterpart used by timers and host operation counters. */
    private static final class MonotonicLong {
        private long generation = Long.MIN_VALUE;
        private long offset;
        private long rawHighWatermark;
        private volatile long value;

        private synchronized long update(long nextGeneration, long raw) {
            if (generation == Long.MIN_VALUE) {
                generation = nextGeneration;
                rawHighWatermark = raw;
                value = raw;
                return value;
            }
            if (nextGeneration < generation) {
                return value;
            }
            if (nextGeneration > generation) {
                if (raw < rawHighWatermark) {
                    offset = value;
                }
                generation = nextGeneration;
                rawHighWatermark = raw;
            } else {
                rawHighWatermark = Math.max(rawHighWatermark, raw);
            }
            value = Math.max(value, saturatedAdd(offset, raw));
            return value;
        }

        private long value() {
            return value;
        }

        private static long saturatedAdd(long left, long right) {
            return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
        }
    }

    /** Publishes timer count and total time together from one immutable snapshot. */
    private static final class MonotonicTimer {
        private final MonotonicLong count = new MonotonicLong();
        private final MonotonicLong totalNanos = new MonotonicLong();
        private volatile TimerExport value = new TimerExport(0, 0);

        private synchronized void update(long generation, long rawCount, long rawTotalNanos) {
            long nextCount = count.update(generation, rawCount);
            long nextTotalNanos = totalNanos.update(generation, rawTotalNanos);
            value = new TimerExport(nextCount, nextTotalNanos);
        }

        private TimerExport value() {
            return value;
        }
    }

    private record TimerExport(long count, long totalNanos) {
    }
}
