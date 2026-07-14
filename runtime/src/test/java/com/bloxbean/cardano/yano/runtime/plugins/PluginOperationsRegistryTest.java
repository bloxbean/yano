package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.plugin.PluginApiVersion;
import com.bloxbean.cardano.yano.api.plugin.PluginBundleInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginCatalogView;
import com.bloxbean.cardano.yano.api.plugin.PluginContributionInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import com.bloxbean.cardano.yano.api.plugin.PluginSelectionStatus;
import com.bloxbean.cardano.yano.api.plugin.PluginSourceCategory;
import com.bloxbean.cardano.yano.api.plugin.PluginTrustTier;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiMediaType;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRoute;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginBundleRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginContributionRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginCounterValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginFailureCode;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthCheckDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthCheckRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthReport;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSource;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthStatus;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginLifecycleState;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricSeries;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricType;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsSource;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperation;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationOutcome;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsSnapshot;
import com.bloxbean.cardano.yano.catalog.ContributionKind;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginOperationsRegistryTest {
    private static final String FINGERPRINT = "sha256:" + "0".repeat(64);

    @Test
    void keepsUnobservedContributionsValidatedAndAggregatesUnknownHealthReport() {
        String emptyId = "com.example.empty";
        String machineId = "com.example.machine";
        String healthId = "com.example.health";
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginHealthProvider.class, healthId,
                healthProvider(healthId, () -> healthSource(
                        List.of(check("database"), check("remote")),
                        () -> new PluginHealthSnapshot(List.of(
                                report("database", PluginHealthStatus.UP),
                                report("remote", PluginHealthStatus.UNKNOWN))),
                        () -> { })));
        PluginCatalogView catalog = catalog(List.of(
                bundle(emptyId, List.of()),
                bundle(machineId, List.of(contribution(
                        "app-state-machine", machineId, PluginTrustTier.CONSENSUS))),
                bundle(healthId, List.of(contribution(
                        "health", healthId, PluginTrustTier.AUXILIARY_LOCAL)))));

        try (PluginOperationsRegistry registry = registry(
                catalog, providers, Duration.ofMillis(20), Duration.ofMillis(200))) {
            registry.activateTelemetry();
            registry.startSampling();
            await(() -> contribution(registry.snapshot(), healthId, "health").health()
                    == PluginHealthStatus.UNKNOWN);

            PluginOperationsSnapshot snapshot = registry.snapshot();
            assertThat(bundle(snapshot, emptyId).lifecycle())
                    .isEqualTo(PluginLifecycleState.VALIDATED);
            PluginContributionRuntimeInfo machine = contribution(
                    snapshot, machineId, "app-state-machine");
            assertThat(machine.lifecycle()).isEqualTo(PluginLifecycleState.VALIDATED);
            assertThat(machine.instances()).isEmpty();
            assertThat(contribution(snapshot, healthId, "health").instances())
                    .singleElement()
                    .satisfies(instance -> assertThat(instance.scope()).isEqualTo("node"));
            assertThat(bundle(snapshot, healthId).health())
                    .isEqualTo(PluginHealthStatus.UNKNOWN);
            assertThatThrownBy(() -> snapshot.bundles().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void healthSourceUnknownOverridesOrdinaryUpButValidatedUnknownDoesNot() {
        String healthId = "com.example.mixed-health";
        String ordinaryId = "com.example.mixed-ordinary";
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginHealthProvider.class, healthId,
                healthProvider(healthId, () -> healthSource(
                        List.of(check("remote")),
                        () -> new PluginHealthSnapshot(List.of(
                                report("remote", PluginHealthStatus.UNKNOWN))),
                        () -> { })));
        PluginCatalogView catalog = catalog(List.of(
                bundle(healthId, List.of(
                        contribution("domain-api", healthId,
                                PluginTrustTier.PRIVILEGED_LOCAL),
                        contribution("health", healthId,
                                PluginTrustTier.AUXILIARY_LOCAL))),
                bundle(ordinaryId, List.of(
                        contribution("domain-api", ordinaryId,
                                PluginTrustTier.PRIVILEGED_LOCAL),
                        contribution("app-state-machine", ordinaryId,
                                PluginTrustTier.CONSENSUS)))));

        try (PluginOperationsRegistry registry = registry(
                catalog, providers, Duration.ofMillis(20), Duration.ofMillis(200))) {
            registry.activateTelemetry();
            registry.domainActive(healthId);
            registry.domainActive(ordinaryId);
            registry.startSampling();
            await(() -> operationTotal(registry.snapshot(), healthId,
                    PluginOperation.HEALTH_SAMPLE,
                    PluginOperationOutcome.SUCCEEDED) > 0);

            assertThat(bundle(registry.snapshot(), healthId).health())
                    .isEqualTo(PluginHealthStatus.UNKNOWN);
            assertThat(bundle(registry.snapshot(), ordinaryId).health())
                    .isEqualTo(PluginHealthStatus.UP);
        }
    }

    @Test
    void publishesCanonicalPerCheckHealthAndRetainsLastGoodAcrossEveryFailureMode()
            throws Exception {
        String id = "com.example.check-detail";
        AtomicReference<String> mode = new AtomicReference<>("valid");
        CountDownLatch timeoutEntered = new CountDownLatch(1);
        CountDownLatch releaseTimeout = new CountDownLatch(1);
        PluginHealthSnapshot lastGood = new PluginHealthSnapshot(List.of(
                report("zeta", PluginHealthStatus.DOWN),
                report("alpha", PluginHealthStatus.UP)));
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginHealthProvider.class, id,
                healthProvider(id, () -> healthSource(
                        List.of(check("zeta"), check("alpha")), () -> {
                            return switch (mode.get()) {
                                case "valid" -> lastGood;
                                case "failure" -> throw new IllegalStateException(
                                        "sample failed");
                                case "invalid" -> new PluginHealthSnapshot(List.of(
                                        report("alpha", PluginHealthStatus.UP)));
                                case "timeout" -> {
                                    timeoutEntered.countDown();
                                    awaitIgnoringInterrupt(releaseTimeout);
                                    yield lastGood;
                                }
                                case "recovered" -> new PluginHealthSnapshot(List.of(
                                        report("alpha", PluginHealthStatus.UP),
                                        report("zeta", PluginHealthStatus.UP)));
                                default -> throw new AssertionError("unexpected mode");
                            };
                        }, () -> { })));

        try (PluginOperationsRegistry registry = registry(
                catalog(List.of(healthBundle(id))), providers,
                Duration.ofMillis(20), Duration.ofMillis(80), 1, 8)) {
            registry.activateTelemetry();
            await(() -> contribution(registry.snapshot(), id, "health").lifecycle()
                    == PluginLifecycleState.ACTIVE);

            PluginOperationsSnapshot initial = registry.snapshot();
            assertThat(initial.healthChecks())
                    .extracting(check -> check.descriptor().id())
                    .containsExactly("alpha", "zeta");
            assertThat(initial.healthChecks())
                    .allSatisfy(check -> {
                        assertThat(check.status()).isEqualTo(PluginHealthStatus.UNKNOWN);
                        assertThat(check.stale()).isFalse();
                    });

            registry.startSampling();
            await(() -> runtimeCheck(registry.snapshot(), id, "zeta").status()
                    == PluginHealthStatus.DOWN);

            mode.set("failure");
            await(() -> contribution(registry.snapshot(), id, "health").failure().code()
                    == PluginFailureCode.CHECK_FAILED);
            assertLastGoodChecksStale(registry.snapshot(), id);

            mode.set("valid");
            await(() -> !contribution(registry.snapshot(), id, "health").stale());
            mode.set("invalid");
            await(() -> contribution(registry.snapshot(), id, "health").failure().code()
                    == PluginFailureCode.INVALID_HEALTH_SNAPSHOT);
            assertLastGoodChecksStale(registry.snapshot(), id);

            mode.set("valid");
            await(() -> !contribution(registry.snapshot(), id, "health").stale());
            mode.set("timeout");
            assertThat(timeoutEntered.await(1, TimeUnit.SECONDS)).isTrue();
            await(() -> contribution(registry.snapshot(), id, "health").failure().code()
                    == PluginFailureCode.CHECK_TIMEOUT);
            assertLastGoodChecksStale(registry.snapshot(), id);

            mode.set("recovered");
            releaseTimeout.countDown();
            await(() -> runtimeCheck(registry.snapshot(), id, "zeta").status()
                    == PluginHealthStatus.UP
                    && !runtimeCheck(registry.snapshot(), id, "zeta").stale());
            assertThat(registry.snapshot().healthChecks())
                    .allSatisfy(check -> assertThat(check.status())
                            .isEqualTo(PluginHealthStatus.UP));
        } finally {
            releaseTimeout.countDown();
        }
    }

    @Test
    void recordsActualNodePluginLifecycleWithoutSynthesizingTypedSpiState() {
        String id = "com.example.node-lifecycle";
        PluginCatalogView catalog = catalog(List.of(bundle(id, List.of(
                contribution("node-plugin", id, PluginTrustTier.PRIVILEGED_LOCAL),
                contribution("app-effect-executor", id,
                        PluginTrustTier.PRIVILEGED_LOCAL)))));

        try (PluginOperationsRegistry registry = registry(
                catalog, new RecordingProviders(),
                Duration.ofSeconds(1), Duration.ofMillis(200))) {
            registry.nodePluginStarting(id);
            assertThat(contribution(registry.snapshot(), id, "node-plugin").lifecycle())
                    .isEqualTo(PluginLifecycleState.ACTIVATING);
            registry.nodePluginStarted(id);
            assertThat(contribution(registry.snapshot(), id, "node-plugin").lifecycle())
                    .isEqualTo(PluginLifecycleState.ACTIVE);
            registry.nodePluginStopped(id, true);
            registry.nodePluginClosed(id, true);

            PluginContributionRuntimeInfo node =
                    contribution(registry.snapshot(), id, "node-plugin");
            PluginContributionRuntimeInfo effect =
                    contribution(registry.snapshot(), id, "app-effect-executor");
            assertThat(node.lifecycleObserved()).isTrue();
            assertThat(node.lifecycle()).isEqualTo(PluginLifecycleState.CLOSED);
            assertThat(effect.lifecycleObserved()).isFalse();
            assertThat(effect.lifecycle()).isEqualTo(PluginLifecycleState.VALIDATED);
            assertThat(registry.snapshot().totals().observedContributions())
                    .isEqualTo(1);
            assertThat(registry.snapshot().totals().observedActiveContributions())
                    .isZero();
        }
    }

    @Test
    void retainsLastGoodMetricsMarksRegressionStaleAndRecoversAtomically() {
        String id = "com.example.metrics";
        PluginMetricDescriptor counter = new PluginMetricDescriptor(
                "requests", "requests", PluginMetricType.COUNTER,
                "Completed requests", "items");
        AtomicReference<PluginMetricSnapshot> value = new AtomicReference<>(
                metricSnapshot("requests", 5));
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginMetricsProvider.class, id,
                metricsProvider(id, () -> metricsSource(
                        List.of(counter), value::get, () -> { })));
        PluginCatalogView catalog = catalog(List.of(bundle(id, List.of(
                contribution("metrics", id, PluginTrustTier.AUXILIARY_LOCAL)))));

        try (PluginOperationsRegistry registry = registry(
                catalog, providers, Duration.ofMillis(20), Duration.ofMillis(200))) {
            registry.activateTelemetry();
            registry.startSampling();
            await(() -> metricTotal(registry.snapshot(), id) == 5);

            value.set(metricSnapshot("requests", 4));
            await(() -> contribution(registry.snapshot(), id, "metrics").stale());
            PluginOperationsSnapshot stale = registry.snapshot();
            assertThat(metricTotal(stale, id)).isEqualTo(5);
            assertThat(stale.metrics()).singleElement()
                    .satisfies(series -> assertThat(series.stale()).isTrue());
            assertThat(contribution(stale, id, "metrics").failure().code())
                    .isEqualTo(PluginFailureCode.INVALID_METRIC_SNAPSHOT);

            value.set(metricSnapshot("requests", 6));
            await(() -> metricTotal(registry.snapshot(), id) == 6
                    && !contribution(registry.snapshot(), id, "metrics").stale());
            PluginOperationsSnapshot recovered = registry.snapshot();
            assertThat(contribution(recovered, id, "metrics").failure().code())
                    .isEqualTo(PluginFailureCode.NONE);
            assertThat(operationTotal(recovered, id,
                    PluginOperation.METRICS_SAMPLE,
                    PluginOperationOutcome.SUCCEEDED)).isPositive();
            assertThat(operationTotal(recovered, id,
                    PluginOperation.METRICS_SAMPLE,
                    PluginOperationOutcome.FAILED)).isPositive();
        }
    }

    @Test
    void freezesSchemaAcrossRestartAndIsolatesDriftToAuxiliaryContribution() {
        String id = "com.example.schema";
        AtomicInteger generation = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginHealthProvider.class, id,
                healthProvider(id, () -> {
                    String checkId = generation.getAndIncrement() == 0 ? "first" : "second";
                    return healthSource(
                            List.of(check(checkId)),
                            () -> new PluginHealthSnapshot(List.of(
                                    report(checkId, PluginHealthStatus.UP))),
                            closes::incrementAndGet);
                }));
        PluginCatalogView catalog = catalog(List.of(bundle(id, List.of(
                contribution("health", id, PluginTrustTier.AUXILIARY_LOCAL)))));

        try (PluginOperationsRegistry registry = registry(
                catalog, providers, Duration.ofSeconds(1), Duration.ofMillis(200))) {
            registry.activateTelemetry();
            await(() -> contribution(registry.snapshot(), id, "health").lifecycle()
                    == PluginLifecycleState.ACTIVE);
            registry.sealAndAwait();
            assertThat(closes).hasValue(1);

            registry.activateTelemetry();
            await(() -> contribution(registry.snapshot(), id, "health").lifecycle()
                    == PluginLifecycleState.FAILED);
            PluginContributionRuntimeInfo failed = contribution(
                    registry.snapshot(), id, "health");
            assertThat(failed.lifecycle()).isEqualTo(PluginLifecycleState.FAILED);
            assertThat(failed.failure().code())
                    .isEqualTo(PluginFailureCode.ACTIVATION_FAILED);
            assertThat(bundle(registry.snapshot(), id).health())
                    .isEqualTo(PluginHealthStatus.DEGRADED);
            assertThat(closes).hasValue(2);
            registry.sealAndAwait();
        }
        assertThat(providers.cleanupSignals).hasSize(2)
                .allMatch(CompletableFuture::isDone);
    }

    @Test
    void timeoutRetainsSingleFlightProductLeaseUntilCallbackActuallyExits()
            throws Exception {
        String id = "com.example.hanging";
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginHealthProvider.class, id,
                healthProvider(id, () -> healthSource(
                        List.of(check("hang")), () -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            while (release.getCount() != 0) {
                                try {
                                    release.await(1, TimeUnit.DAYS);
                                } catch (InterruptedException ignored) {
                                    // Deliberately ignore the host timeout.
                                }
                            }
                            return new PluginHealthSnapshot(List.of(
                                    report("hang", PluginHealthStatus.UP)));
                        }, closes::incrementAndGet)));
        PluginCatalogView catalog = catalog(List.of(bundle(id, List.of(
                contribution("health", id, PluginTrustTier.AUXILIARY_LOCAL)))));
        ExecutorService stopper = Executors.newSingleThreadExecutor();

        try (PluginOperationsRegistry registry = registry(
                catalog, providers, Duration.ofMillis(15), Duration.ofMillis(30))) {
            registry.activateTelemetry();
            registry.startSampling();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            await(() -> contribution(registry.snapshot(), id, "health")
                    .failure().code() == PluginFailureCode.CHECK_TIMEOUT);
            assertThat(registry.snapshot().totals().activeSamples()).isEqualTo(1);
            Thread.sleep(60);
            assertThat(calls).hasValue(1);

            Future<?> sealing = stopper.submit(registry::sealAndAwait);
            Thread.sleep(30);
            assertThat(sealing.isDone()).isFalse();
            assertThat(closes).hasValue(0);
            assertThat(providers.cleanupSignals).singleElement()
                    .satisfies(signal -> assertThat(signal).isNotDone());

            release.countDown();
            sealing.get(1, TimeUnit.SECONDS);
            assertThat(closes).hasValue(1);
            assertThat(providers.cleanupSignals).singleElement()
                    .satisfies(signal -> assertThat(signal).isDone());
            assertNoCallbacks(registry.snapshot());
        } finally {
            release.countDown();
            stopper.shutdownNow();
        }
    }

    @Test
    void timeoutInterruptCannotLeakIntoNextSourceCallback() throws Exception {
        String firstId = "com.example.a-timeout";
        String secondId = "com.example.b-next";
        CountDownLatch firstInterrupted = new CountDownLatch(1);
        CountDownLatch secondSampled = new CountDownLatch(1);
        AtomicBoolean secondSawInterrupt = new AtomicBoolean();
        AtomicBoolean firstCall = new AtomicBoolean(true);
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginHealthProvider.class, firstId,
                healthProvider(firstId, () -> healthSource(
                        List.of(check("first")), () -> {
                            if (firstCall.compareAndSet(true, false)) {
                                try {
                                    Thread.sleep(TimeUnit.SECONDS.toMillis(5));
                                } catch (InterruptedException expected) {
                                    firstInterrupted.countDown();
                                }
                            }
                            return new PluginHealthSnapshot(List.of(
                                    report("first", PluginHealthStatus.UP)));
                        }, () -> { })));
        providers.add(PluginHealthProvider.class, secondId,
                healthProvider(secondId, () -> healthSource(
                        List.of(check("second")), () -> {
                            secondSawInterrupt.set(Thread.currentThread().isInterrupted());
                            secondSampled.countDown();
                            return new PluginHealthSnapshot(List.of(
                                    report("second", PluginHealthStatus.UP)));
                        }, () -> { })));
        PluginCatalogView catalog = catalog(List.of(
                bundle(firstId, List.of(contribution(
                        "health", firstId, PluginTrustTier.AUXILIARY_LOCAL))),
                bundle(secondId, List.of(contribution(
                        "health", secondId, PluginTrustTier.AUXILIARY_LOCAL)))));

        try (PluginOperationsRegistry registry = registry(
                catalog, providers, Duration.ofMillis(100), Duration.ofMillis(20),
                1, 8)) {
            registry.activateTelemetry();
            registry.startSampling();
            assertThat(firstInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(secondSampled.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(secondSawInterrupt).isFalse();
        }
    }

    @Test
    void isolatesActivationFailureAndStillSamplesHealthyBundle() {
        String failedId = "com.example.failed";
        String healthyId = "com.example.healthy";
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginHealthProvider.class, failedId,
                healthProvider(failedId, () -> {
                    throw new IllegalStateException("activation secret");
                }));
        providers.add(PluginHealthProvider.class, healthyId,
                healthProvider(healthyId, () -> healthSource(
                        List.of(check("ready")),
                        () -> new PluginHealthSnapshot(List.of(
                                report("ready", PluginHealthStatus.UP))),
                        () -> { })));
        PluginCatalogView catalog = catalog(List.of(
                bundle(failedId, List.of(contribution(
                        "health", failedId, PluginTrustTier.AUXILIARY_LOCAL))),
                bundle(healthyId, List.of(contribution(
                        "health", healthyId, PluginTrustTier.AUXILIARY_LOCAL)))));

        try (PluginOperationsRegistry registry = registry(
                catalog, providers, Duration.ofMillis(20), Duration.ofMillis(200))) {
            registry.activateTelemetry();
            registry.startSampling();
            await(() -> contribution(registry.snapshot(), healthyId, "health").health()
                    == PluginHealthStatus.UP);
            assertThat(contribution(registry.snapshot(), failedId, "health").lifecycle())
                    .isEqualTo(PluginLifecycleState.FAILED);
            assertThat(contribution(registry.snapshot(), healthyId, "health").lifecycle())
                    .isEqualTo(PluginLifecycleState.ACTIVE);
            assertThat(bundle(registry.snapshot(), failedId).lifecycle())
                    .isEqualTo(PluginLifecycleState.FAILED);
            assertThat(bundle(registry.snapshot(), healthyId).lifecycle())
                    .isEqualTo(PluginLifecycleState.ACTIVE);
            assertThat(registry.snapshot().totals().failedBundles()).isEqualTo(1);
        }
    }

    @Test
    void unobservedContributionKeepsAuxiliaryFailureLifecycleIndeterminate() {
        String id = "com.example.partially-observed";
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginHealthProvider.class, id,
                healthProvider(id, () -> {
                    throw new IllegalStateException("health activation failed");
                }));
        PluginCatalogView catalog = catalog(List.of(bundle(id, List.of(
                contribution("app-state-machine", id, PluginTrustTier.CONSENSUS),
                contribution("health", id, PluginTrustTier.AUXILIARY_LOCAL)))));

        try (PluginOperationsRegistry registry = registry(
                catalog, providers, Duration.ofSeconds(1), Duration.ofMillis(200))) {
            registry.activateTelemetry();
            await(() -> contribution(registry.snapshot(), id, "health").lifecycle()
                    == PluginLifecycleState.FAILED);

            PluginBundleRuntimeInfo runtime = bundle(registry.snapshot(), id);
            assertThat(runtime.lifecycle()).isEqualTo(PluginLifecycleState.VALIDATED);
            assertThat(runtime.health()).isEqualTo(PluginHealthStatus.DEGRADED);
            assertThat(runtime.failure().code())
                    .isEqualTo(PluginFailureCode.ACTIVATION_FAILED);
            assertThat(contribution(registry.snapshot(), id, "app-state-machine")
                    .lifecycleObserved()).isFalse();
            assertThat(contribution(registry.snapshot(), id, "health")
                    .lifecycleObserved()).isTrue();
        }
    }

    @Test
    void processFatalActivationEscapesWorkerWithoutPublishingOrdinaryFailure() {
        String id = "com.example.activation-fatal";
        OutOfMemoryError fatal = new OutOfMemoryError("fatal activation");
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginHealthProvider.class, id,
                healthProvider(id, () -> {
                    throw fatal;
                }));
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) ->
                uncaught.compareAndSet(null, failure));
        try (PluginOperationsRegistry registry = registry(
                catalog(List.of(healthBundle(id))), providers,
                Duration.ofSeconds(1), Duration.ofSeconds(5))) {
            registry.activateTelemetry();
            await(() -> uncaught.get() != null);

            assertThat(uncaught.get()).isSameAs(fatal);
            PluginContributionRuntimeInfo contribution =
                    contribution(registry.snapshot(), id, "health");
            assertThat(contribution.lifecycle())
                    .isEqualTo(PluginLifecycleState.ACTIVATING);
            assertThat(contribution.failure().code()).isEqualTo(PluginFailureCode.NONE);
            assertThat(operationTotal(registry.snapshot(), id,
                    PluginOperation.ACTIVATION,
                    PluginOperationOutcome.FAILED)).isZero();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void processFatalSampleEscapesWorkerWithoutPublishingOrdinaryFailureAndUnwindsCallback() {
        String id = "com.example.sample-fatal";
        OutOfMemoryError fatal = new OutOfMemoryError("fatal sample");
        AtomicBoolean firstSample = new AtomicBoolean(true);
        AtomicInteger closes = new AtomicInteger();
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginHealthProvider.class, id,
                healthProvider(id, () -> healthSource(
                        List.of(check("ready")), () -> {
                            if (firstSample.compareAndSet(true, false)) {
                                throw fatal;
                            }
                            return new PluginHealthSnapshot(List.of(
                                    report("ready", PluginHealthStatus.UP)));
                        }, closes::incrementAndGet)));
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) ->
                uncaught.compareAndSet(null, failure));
        try (PluginOperationsRegistry registry = registry(
                catalog(List.of(healthBundle(id))), providers,
                Duration.ofMillis(100), Duration.ofSeconds(5))) {
            registry.activateTelemetry();
            registry.startSampling();
            await(() -> uncaught.get() != null);

            assertThat(uncaught.get()).isSameAs(fatal);
            PluginContributionRuntimeInfo contribution =
                    contribution(registry.snapshot(), id, "health");
            assertThat(contribution.lifecycle()).isEqualTo(PluginLifecycleState.ACTIVE);
            assertThat(contribution.stale()).isFalse();
            assertThat(contribution.failure().code()).isEqualTo(PluginFailureCode.NONE);
            assertThat(operationTotal(registry.snapshot(), id,
                    PluginOperation.HEALTH_SAMPLE,
                    PluginOperationOutcome.FAILED)).isZero();

            registry.sealAndAwait();
            assertThat(closes).hasValue(1);
            assertNoCallbacks(registry.snapshot());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void processFatalActivationCommitRetainsPrimaryAndRollsBackOwnedProduct() throws Exception {
        assertProcessFatalActivationCommitCleanup(null);
        assertProcessFatalActivationCommitCleanup(
                new IllegalStateException("discard cleanup failed"));
    }

    @Test
    void activationCleanupCannotMaskFailureBySelfSuppressingSameThrowable() {
        String id = "com.example.self-suppression";
        IllegalStateException shared = new IllegalStateException("shared failure");
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginHealthProvider.class, id,
                healthProvider(id, () -> new PluginHealthSource() {
                    @Override
                    public List<PluginHealthCheckDescriptor> checks() {
                        throw shared;
                    }

                    @Override
                    public PluginHealthSnapshot snapshot() {
                        throw new AssertionError("source must not activate");
                    }

                    @Override
                    public void close() {
                        throw shared;
                    }
                }));
        PluginCatalogView catalog = catalog(List.of(bundle(id, List.of(
                contribution("health", id, PluginTrustTier.AUXILIARY_LOCAL)))));

        try (PluginOperationsRegistry registry = registry(
                catalog, providers, Duration.ofSeconds(1), Duration.ofMillis(200))) {
            registry.activateTelemetry();
            await(() -> contribution(registry.snapshot(), id, "health")
                    .failure().code() == PluginFailureCode.ACTIVATION_FAILED);
            assertThat(contribution(registry.snapshot(), id, "health").failure().code())
                    .isEqualTo(PluginFailureCode.ACTIVATION_FAILED);
            assertThat(shared.getSuppressed()).isEmpty();
        }
    }

    @Test
    void providerSourceAndCloseCallbacksUsePluginTcclAndRestoreInterruptState() {
        String id = "com.example.tccl";
        ClassLoader caller = Thread.currentThread().getContextClassLoader();
        ClassLoader plugin = new ClassLoader(caller) { };
        AtomicInteger callbacks = new AtomicInteger();
        Runnable assertPluginContext = () -> {
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(plugin);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            callbacks.incrementAndGet();
        };
        PluginHealthProvider raw = new PluginHealthProvider() {
            @Override
            public String id() {
                assertPluginContext.run();
                return id;
            }

            @Override
            public PluginHealthSource create(PluginHealthContext context) {
                assertPluginContext.run();
                return new PluginHealthSource() {
                    @Override
                    public List<PluginHealthCheckDescriptor> checks() {
                        assertPluginContext.run();
                        return List.of(check("ready"));
                    }

                    @Override
                    public PluginHealthSnapshot snapshot() {
                        assertPluginContext.run();
                        Thread.currentThread().interrupt();
                        return new PluginHealthSnapshot(List.of(
                                report("ready", PluginHealthStatus.UP)));
                    }

                    @Override
                    public void close() {
                        assertPluginContext.run();
                    }
                };
            }
        };
        PluginHealthProvider facade = (PluginHealthProvider) PluginSpiFacades.provider(
                ContributionKind.HEALTH, raw, plugin, id, id,
                raw.getClass().getName());
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginHealthProvider.class, id, facade);
        PluginCatalogView catalog = catalog(List.of(bundle(id, List.of(
                contribution("health", id, PluginTrustTier.AUXILIARY_LOCAL)))));

        try (PluginOperationsRegistry registry = registry(
                catalog, providers, Duration.ofMillis(20), Duration.ofMillis(200))) {
            registry.activateTelemetry();
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(caller);
            registry.startSampling();
            await(() -> contribution(registry.snapshot(), id, "health").health()
                    == PluginHealthStatus.UP);
            registry.sealAndAwait();
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(caller);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            assertThat(callbacks.get()).isGreaterThanOrEqualTo(5);
        }
    }

    @Test
    void wrappedProcessFatalSourceCloseFinishesReverseCleanupWithoutOrdinaryPublication() {
        assertWrappedProcessFatalSourceClose(false);
        assertWrappedProcessFatalSourceClose(true);
    }

    @Test
    void staggersManySourcesInsteadOfBurstRejectingTheBoundedQueue()
            throws Exception {
        int sourceCount = 40;
        CountDownLatch sampled = new CountDownLatch(sourceCount);
        RecordingProviders providers = new RecordingProviders();
        List<PluginBundleInfo> bundles = new ArrayList<>();
        for (int index = 0; index < sourceCount; index++) {
            String id = "com.example.stagger-" + index;
            providers.add(PluginHealthProvider.class, id,
                    healthProvider(id, () -> healthSource(
                            List.of(check("ready")), () -> {
                                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                                sampled.countDown();
                                return new PluginHealthSnapshot(List.of(
                                        report("ready", PluginHealthStatus.UP)));
                            }, () -> { })));
            bundles.add(bundle(id, List.of(contribution(
                    "health", id, PluginTrustTier.AUXILIARY_LOCAL))));
        }

        try (PluginOperationsRegistry registry = registry(
                catalog(bundles), providers,
                Duration.ofMillis(400), Duration.ofMillis(300), 4, 8)) {
            registry.activateTelemetry();
            long buildsBeforeSampling = registry.snapshotBuildCountForTesting();
            registry.startSampling();
            assertThat(sampled.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.snapshotBuildCountForTesting() - buildsBeforeSampling)
                    .isLessThan(15);
            assertThat(registry.snapshot().bundles()).allSatisfy(bundle ->
                    assertThat(bundle.operationCounts().stream()
                            .filter(count -> count.operation()
                                    == PluginOperation.HEALTH_SAMPLE)
                            .filter(count -> count.outcome()
                                    == PluginOperationOutcome.REJECTED)
                            .mapToLong(count -> count.total())
                            .sum()).isZero());
        }
    }

    @Test
    void rejectionAndSealCancellationReturnBundleAndHostCountersToZero()
            throws Exception {
        int sourceCount = 4;
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sampleCalls = new AtomicInteger();
        RecordingProviders providers = new RecordingProviders();
        List<PluginBundleInfo> bundles = new ArrayList<>();
        for (int index = 0; index < sourceCount; index++) {
            String id = "com.example.queue-coherence-" + index;
            int ordinal = index;
            providers.add(PluginHealthProvider.class, id,
                    healthProvider(id, () -> healthSource(
                            List.of(check("ready")), () -> {
                                sampleCalls.incrementAndGet();
                                if (ordinal == 0) {
                                    firstEntered.countDown();
                                    awaitIgnoringInterrupt(release);
                                }
                                return new PluginHealthSnapshot(List.of(
                                        report("ready", PluginHealthStatus.UP)));
                            }, () -> { })));
            bundles.add(healthBundle(id));
        }
        ExecutorService stopper = Executors.newSingleThreadExecutor();

        try (PluginOperationsRegistry registry = registry(
                catalog(bundles), providers,
                Duration.ofMillis(200), Duration.ofSeconds(1), 1, 1)) {
            registry.activateTelemetry();
            await(() -> registry.snapshot().totals().observedActiveContributions()
                    == sourceCount);
            registry.startSampling();
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
            await(() -> registry.snapshot().bundles().stream()
                    .mapToLong(bundle -> operationTotal(
                            registry.snapshot(), bundle.id(),
                            PluginOperation.HEALTH_SAMPLE,
                            PluginOperationOutcome.REJECTED))
                    .sum() > 0);

            Future<?> sealing = stopper.submit(registry::sealAndAwait);
            Thread.sleep(30);
            assertThat(sealing.isDone()).isFalse();
            int callsBeforeRelease = sampleCalls.get();
            release.countDown();
            sealing.get(1, TimeUnit.SECONDS);

            assertThat(sampleCalls).hasValue(callsBeforeRelease);
            assertNoCallbacks(registry.snapshot());
        } finally {
            release.countDown();
            stopper.shutdownNow();
        }
    }

    @Test
    void activationCallbacksAreBoundedAndLateProductsStayGenerationOwned()
            throws Exception {
        String constructionId = "com.example.activation-construction";
        String createId = "com.example.activation-create";
        String schemaId = "com.example.activation-schema";
        CountDownLatch constructionEntered = new CountDownLatch(1);
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch schemaEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        RecordingProviders providers = new RecordingProviders();
        providers.beforeFind(constructionId, () -> {
            constructionEntered.countDown();
            awaitIgnoringInterrupt(release);
        });
        providers.add(PluginHealthProvider.class, constructionId,
                healthProvider(constructionId, () -> healthSource(
                        List.of(check("construction")),
                        () -> new PluginHealthSnapshot(List.of(
                                report("construction", PluginHealthStatus.UP))),
                        closes::incrementAndGet)));
        providers.add(PluginHealthProvider.class, createId,
                healthProvider(createId, () -> {
                    createEntered.countDown();
                    awaitIgnoringInterrupt(release);
                    return healthSource(
                            List.of(check("create")),
                            () -> new PluginHealthSnapshot(List.of(
                                    report("create", PluginHealthStatus.UP))),
                            closes::incrementAndGet);
                }));
        providers.add(PluginHealthProvider.class, schemaId,
                healthProvider(schemaId, () -> new PluginHealthSource() {
                    @Override
                    public List<PluginHealthCheckDescriptor> checks() {
                        schemaEntered.countDown();
                        awaitIgnoringInterrupt(release);
                        return List.of(check("schema"));
                    }

                    @Override
                    public PluginHealthSnapshot snapshot() {
                        return new PluginHealthSnapshot(List.of(
                                report("schema", PluginHealthStatus.UP)));
                    }

                    @Override
                    public void close() {
                        closes.incrementAndGet();
                    }
                }));
        PluginCatalogView catalog = catalog(List.of(
                healthBundle(constructionId),
                healthBundle(createId),
                healthBundle(schemaId)));

        try (PluginOperationsRegistry registry = registry(
                catalog, providers, Duration.ofSeconds(1),
                Duration.ofMillis(40), 3, 8)) {
            long started = System.nanoTime();
            registry.activateTelemetry();
            assertThat(System.nanoTime() - started)
                    .isLessThan(TimeUnit.MILLISECONDS.toNanos(200));
            registry.startSampling();
            assertThat(constructionEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(createEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(schemaEntered.await(1, TimeUnit.SECONDS)).isTrue();

            await(() -> List.of(constructionId, createId, schemaId).stream()
                    .allMatch(id -> contribution(registry.snapshot(), id, "health")
                            .lifecycle() == PluginLifecycleState.FAILED));
            assertThat(List.of(constructionId, createId, schemaId))
                    .allSatisfy(id -> {
                        assertThat(bundle(registry.snapshot(), id).activeCallbacks())
                                .isOne();
                        assertThat(operationTotal(registry.snapshot(), id,
                                PluginOperation.ACTIVATION,
                                PluginOperationOutcome.TIMED_OUT)).isOne();
                    });

            release.countDown();
            await(() -> List.of(constructionId, createId, schemaId).stream()
                    .allMatch(id -> bundle(registry.snapshot(), id).activeCallbacks() == 0));
            assertThat(closes).hasValue(3);
            registry.sealAndAwait();
            assertNoCallbacks(registry.snapshot());
        } finally {
            release.countDown();
        }
    }

    @Test
    void generationAdmissionDeadlineDeterministicallyTimesOutUnenteredTail()
            throws Exception {
        int sourceCount = 6;
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fastCalls = new AtomicInteger();
        RecordingProviders providers = new RecordingProviders();
        List<PluginBundleInfo> bundles = new ArrayList<>();
        for (int index = 0; index < sourceCount; index++) {
            String id = "com.example.admission-" + index;
            int ordinal = index;
            providers.add(PluginHealthProvider.class, id,
                    healthProvider(id, () -> {
                        if (ordinal == 0) {
                            firstEntered.countDown();
                            awaitIgnoringInterrupt(release);
                        } else {
                            fastCalls.incrementAndGet();
                        }
                        return healthSource(
                                List.of(check("ready")),
                                () -> new PluginHealthSnapshot(List.of(
                                        report("ready", PluginHealthStatus.UP))),
                                () -> { });
                    }));
            bundles.add(healthBundle(id));
        }

        try (PluginOperationsRegistry registry = registry(
                catalog(bundles), providers, Duration.ofSeconds(1),
                Duration.ofMillis(40), 1, 2)) {
            registry.activateTelemetry();
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
            await(() -> registry.snapshot().totals().failedBundles() == sourceCount);

            assertThat(fastCalls).hasValue(0);
            assertThat(registry.snapshot().bundles()).allSatisfy(runtime -> {
                assertThat(runtime.lifecycle()).isEqualTo(PluginLifecycleState.FAILED);
                assertThat(runtime.queuedCallbacks()).isZero();
                assertThat(operationTotal(registry.snapshot(), runtime.id(),
                        PluginOperation.ACTIVATION,
                        PluginOperationOutcome.TIMED_OUT)).isOne();
            });
            release.countDown();
            await(() -> bundle(registry.snapshot(), "com.example.admission-0")
                    .activeCallbacks() == 0);
        } finally {
            release.countDown();
        }
    }

    @Test
    void commitsOutOfOrderActivationResultsInCatalogOrderAndReverseCloses()
            throws Exception {
        String firstId = "com.example.order-a";
        String secondId = "com.example.order-b";
        String thirdId = "com.example.order-c";
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch laterCreated = new CountDownLatch(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> closes = java.util.Collections.synchronizedList(new ArrayList<>());
        RecordingProviders providers = new RecordingProviders();
        for (String id : List.of(firstId, secondId, thirdId)) {
            providers.add(PluginHealthProvider.class, id,
                    healthProvider(id, () -> {
                        if (id.equals(firstId)) {
                            firstEntered.countDown();
                            awaitIgnoringInterrupt(releaseFirst);
                        } else {
                            laterCreated.countDown();
                        }
                        return healthSource(
                                List.of(check("ready")),
                                () -> new PluginHealthSnapshot(List.of(
                                        report("ready", PluginHealthStatus.UP))),
                                () -> closes.add(id));
                    }));
        }

        try (PluginOperationsRegistry registry = registry(
                catalog(List.of(
                        healthBundle(firstId),
                        healthBundle(secondId),
                        healthBundle(thirdId))),
                providers, Duration.ofSeconds(1), Duration.ofSeconds(2), 3, 8)) {
            registry.activateTelemetry();
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(laterCreated.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(contribution(registry.snapshot(), secondId, "health").lifecycle())
                    .isEqualTo(PluginLifecycleState.ACTIVATING);
            assertThat(contribution(registry.snapshot(), thirdId, "health").lifecycle())
                    .isEqualTo(PluginLifecycleState.ACTIVATING);

            releaseFirst.countDown();
            await(() -> registry.snapshot().totals().observedActiveContributions() == 3);
            registry.sealAndAwait();
            assertThat(closes).containsExactly(thirdId, secondId, firstId);
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void sealCancelsUnenteredActivationsAndWaitsOnlyEnteredPluginCode()
            throws Exception {
        int sourceCount = 12;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        RecordingProviders providers = new RecordingProviders();
        List<PluginBundleInfo> bundles = new ArrayList<>();
        for (int index = 0; index < sourceCount; index++) {
            String id = "com.example.cancel-activation-" + index;
            providers.add(PluginHealthProvider.class, id,
                    healthProvider(id, () -> {
                        callbacks.incrementAndGet();
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return healthSource(List.of(),
                                () -> new PluginHealthSnapshot(List.of()), () -> { });
                    }));
            bundles.add(healthBundle(id));
        }
        ExecutorService stopper = Executors.newSingleThreadExecutor();

        try (PluginOperationsRegistry registry = registry(
                catalog(bundles), providers, Duration.ofSeconds(1),
                Duration.ofSeconds(5), 1, 2)) {
            registry.activateTelemetry();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> sealing = stopper.submit(registry::sealAndAwait);
            Thread.sleep(30);
            assertThat(sealing.isDone()).isFalse();
            assertThat(callbacks).hasValue(1);
            release.countDown();
            sealing.get(1, TimeUnit.SECONDS);
            assertThat(callbacks).hasValue(1);
            assertNoCallbacks(registry.snapshot());
        } finally {
            release.countDown();
            stopper.shutdownNow();
        }
    }

    @Test
    void cleanupRegistrationFailureDoesNotPublishHalfOwnedGeneration() {
        RecordingProviders providers = new RecordingProviders();
        providers.failCleanupRegistration = true;
        PluginCatalogView catalog = catalog(List.of(bundle(
                "com.example.registration", List.of())));

        try (PluginOperationsRegistry registry = registry(
                catalog, providers, Duration.ofSeconds(1), Duration.ofMillis(200))) {
            assertThatThrownBy(registry::activateTelemetry)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("registration failed");
            assertThat(registry.snapshot().generation()).isZero();
            assertThat(providers.cleanupSignals).singleElement()
                    .satisfies(signal -> assertThat(signal).isDone());

            providers.failCleanupRegistration = false;
            registry.activateTelemetry();
            assertThat(registry.snapshot().generation()).isEqualTo(1);
            registry.sealAndAwait();
            assertThat(providers.cleanupSignals).hasSize(2)
                    .allMatch(CompletableFuture::isDone);
        }
    }

    @Test
    void throwingOperationsObservationCannotChangeDomainResponseOrLifetime() {
        String id = "com.example.domain-observation";
        RecordingProviders providers = new RecordingProviders();
        providers.add(DomainApiProvider.class, id, new DomainApiProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public DomainApi create(DomainApiContext context) {
                return new DomainApi() {
                    @Override
                    public List<DomainApiRoute> routes() {
                        return List.of(new DomainApiRoute(
                                "read", DomainHttpMethod.GET, "read",
                                DomainApiAccess.READ));
                    }

                    @Override
                    public DomainApiResponse handle(
                            com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest request
                    ) {
                        return new DomainApiResponse(
                                200, DomainApiMediaType.OCTET_STREAM, new byte[]{7});
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        });
        PluginCatalogView catalog = catalog(List.of(bundle(id, List.of(
                contribution("domain-api", id, PluginTrustTier.PRIVILEGED_LOCAL)))));
        AtomicBoolean throwFromClock = new AtomicBoolean();
        LongSupplier epochClock = () -> {
            if (throwFromClock.get()) {
                throw new IllegalStateException("observation failed");
            }
            return System.currentTimeMillis();
        };
        PluginOperationsRegistry operations = new PluginOperationsRegistry(
                catalog, providers,
                LoggerFactory.getLogger(PluginOperationsRegistryTest.class),
                Duration.ofSeconds(1), Duration.ofMillis(200), 4, 256,
                epochClock, System::nanoTime);
        DomainApiRegistry domain = new DomainApiRegistry(
                providers, ignored -> Map.of(), AppChainGateways.empty(),
                LoggerFactory.getLogger(PluginOperationsRegistryTest.class),
                Duration.ofSeconds(1), 16, operations);
        try {
            throwFromClock.set(true);
            domain.resume();
            assertThat(domain.dispatch(
                    id, DomainHttpMethod.GET, "read", Map.of(), new byte[0]).body())
                    .containsExactly(7);
            domain.sealAndAwait();
            assertThat(providers.cleanupSignals).singleElement()
                    .satisfies(signal -> assertThat(signal).isDone());

            throwFromClock.set(false);
            operations.close();
            domain.resume();
            assertThat(domain.dispatch(
                    id, DomainHttpMethod.GET, "read", Map.of(), new byte[0]).body())
                    .containsExactly(7);
            domain.sealAndAwait();
            assertThat(providers.cleanupSignals).hasSize(2)
                    .allMatch(CompletableFuture::isDone);
        } finally {
            throwFromClock.set(false);
            domain.close();
            operations.close();
        }
    }

    private static void assertProcessFatalActivationCommitCleanup(
            RuntimeException cleanupFailure
    ) throws Exception {
        String id = cleanupFailure == null
                ? "com.example.commit-fatal-clean"
                : "com.example.commit-fatal-cleanup-failure";
        OutOfMemoryError fatal = new OutOfMemoryError("fatal activation commit");
        AtomicBoolean failClock = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginHealthProvider.class, id,
                healthProvider(id, () -> {
                    int creation = creations.incrementAndGet();
                    if (creation == 1) {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                    }
                    String checkId = creation == 1 ? "first" : "second";
                    return healthSource(List.of(check(checkId)),
                            () -> new PluginHealthSnapshot(List.of(
                                    report(checkId, PluginHealthStatus.UP))), () -> {
                                closes.incrementAndGet();
                                if (creation == 1 && cleanupFailure != null) {
                                    throw cleanupFailure;
                                }
                            });
                }));
        LongSupplier epochClock = () -> {
            if (failClock.get()) {
                throw fatal;
            }
            return System.currentTimeMillis();
        };
        PluginOperationsRegistry registry = new PluginOperationsRegistry(
                catalog(List.of(healthBundle(id))), providers,
                LoggerFactory.getLogger(PluginOperationsRegistryTest.class),
                Duration.ofSeconds(1), Duration.ofSeconds(5), 1, 8,
                epochClock, System::nanoTime);
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) ->
                uncaught.compareAndSet(null, failure));
        try {
            registry.activateTelemetry();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            failClock.set(true);
            release.countDown();
            await(() -> uncaught.get() != null);
            failClock.set(false);

            assertThat(uncaught.get()).isSameAs(fatal);
            if (cleanupFailure == null) {
                assertThat(fatal.getSuppressed()).isEmpty();
            } else {
                assertThat(fatal.getSuppressed()).containsExactly(cleanupFailure);
            }
            assertThat(closes).hasValue(1);
            registry.startSampling();
            PluginContributionRuntimeInfo contribution =
                    contribution(registry.snapshot(), id, "health");
            assertThat(contribution.lifecycle())
                    .isEqualTo(PluginLifecycleState.ACTIVATING);
            assertThat(contribution.failure().code()).isEqualTo(PluginFailureCode.NONE);
            assertThat(operationTotal(registry.snapshot(), id,
                    PluginOperation.ACTIVATION,
                    PluginOperationOutcome.FAILED)).isZero();
            assertNoCallbacks(registry.snapshot());

            registry.sealAndAwait();
            assertThat(closes).hasValue(1);
            registry.activateTelemetry();
            await(() -> contribution(registry.snapshot(), id, "health").lifecycle()
                    == PluginLifecycleState.ACTIVE);
            registry.sealAndAwait();
            assertThat(closes).hasValue(2);
            assertThat(providers.cleanupSignals).hasSize(2)
                    .allMatch(CompletableFuture::isDone);
        } finally {
            failClock.set(false);
            release.countDown();
            try {
                registry.close();
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previous);
            }
        }
    }

    private static void assertWrappedProcessFatalSourceClose(boolean suppressed) {
        String suffix = suppressed ? "suppressed" : "cause";
        String firstId = "com.example.a-close-ordinary-" + suffix;
        String secondId = "com.example.b-close-fatal-" + suffix;
        OutOfMemoryError fatal = new OutOfMemoryError("fatal source close " + suffix);
        RuntimeException wrapper = suppressed
                ? new RuntimeException("suppressed wrapper")
                : new RuntimeException("cause wrapper", fatal);
        if (suppressed) {
            wrapper.addSuppressed(fatal);
        }
        IllegalStateException ordinary = new IllegalStateException("ordinary source close");
        List<String> closes = new ArrayList<>();
        RecordingProviders providers = new RecordingProviders();
        providers.add(PluginHealthProvider.class, firstId,
                healthProvider(firstId, () -> healthSource(
                        List.of(), () -> new PluginHealthSnapshot(List.of()), () -> {
                            closes.add(firstId);
                            throw ordinary;
                        })));
        providers.add(PluginHealthProvider.class, secondId,
                healthProvider(secondId, () -> healthSource(
                        List.of(), () -> new PluginHealthSnapshot(List.of()), () -> {
                            closes.add(secondId);
                            throw wrapper;
                        })));
        PluginOperationsRegistry registry = registry(
                catalog(List.of(healthBundle(firstId), healthBundle(secondId))),
                providers, Duration.ofSeconds(1), Duration.ofMillis(200));
        try {
            registry.activateTelemetry();
            await(() -> registry.snapshot().totals().observedActiveContributions() == 2);

            assertThatThrownBy(registry::sealAndAwait).isSameAs(fatal);

            assertThat(closes).containsExactly(secondId, firstId);
            assertThat(fatal.getSuppressed()).containsExactly(ordinary);
            assertThat(contribution(registry.snapshot(), secondId, "health").lifecycle())
                    .isEqualTo(PluginLifecycleState.STOPPED);
            assertThat(contribution(registry.snapshot(), secondId, "health").failure().code())
                    .isEqualTo(PluginFailureCode.NONE);
            assertThat(contribution(registry.snapshot(), firstId, "health").lifecycle())
                    .isEqualTo(PluginLifecycleState.FAILED);
            assertThat(contribution(registry.snapshot(), firstId, "health").failure().code())
                    .isEqualTo(PluginFailureCode.CLOSE_FAILED);
        } finally {
            registry.close();
        }
    }

    private static PluginOperationsRegistry registry(
            PluginCatalogView catalog,
            PluginProviderRegistry providers,
            Duration interval,
            Duration deadline
    ) {
        return registry(catalog, providers, interval, deadline, 4, 256);
    }

    private static PluginOperationsRegistry registry(
            PluginCatalogView catalog,
            PluginProviderRegistry providers,
            Duration interval,
            Duration deadline,
            int workers,
            int queueCapacity
    ) {
        return new PluginOperationsRegistry(
                catalog, providers,
                LoggerFactory.getLogger(PluginOperationsRegistryTest.class),
                interval, deadline, workers, queueCapacity,
                System::currentTimeMillis, System::nanoTime);
    }

    private static PluginCatalogView catalog(List<PluginBundleInfo> bundles) {
        return new PluginCatalogSnapshot(
                PluginApiVersion.CURRENT_MAJOR, PluginApiVersion.CURRENT_LEVEL,
                FINGERPRINT, bundles,
                bundles.stream().filter(PluginBundleInfo::selected)
                        .map(PluginBundleInfo::id).toList());
    }

    private static PluginBundleInfo bundle(
            String id,
            List<PluginContributionInfo> contributions
    ) {
        return new PluginBundleInfo(
                id, "1.0.0", true, PluginSelectionStatus.SELECTED, false,
                PluginSourceCategory.CLASSPATH, "sha256:" + "1".repeat(64),
                PluginDigestMode.ARTIFACT_CLOSURE, List.of(), contributions);
    }

    private static PluginContributionInfo contribution(
            String kind,
            String name,
            PluginTrustTier trustTier
    ) {
        return new PluginContributionInfo(kind, name, "example.Provider", trustTier);
    }

    private static PluginBundleInfo healthBundle(String id) {
        return bundle(id, List.of(contribution(
                "health", id, PluginTrustTier.AUXILIARY_LOCAL)));
    }

    private static PluginHealthCheckDescriptor check(String id) {
        return new PluginHealthCheckDescriptor(id, "Check " + id);
    }

    private static PluginHealthReport report(String id, PluginHealthStatus status) {
        return new PluginHealthReport(id, status);
    }

    private static PluginHealthProvider healthProvider(
            String id,
            Supplier<PluginHealthSource> source
    ) {
        return new PluginHealthProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public PluginHealthSource create(PluginHealthContext context) {
                return source.get();
            }
        };
    }

    private static PluginHealthSource healthSource(
            List<PluginHealthCheckDescriptor> checks,
            Supplier<PluginHealthSnapshot> snapshot,
            Runnable close
    ) {
        return new PluginHealthSource() {
            @Override
            public List<PluginHealthCheckDescriptor> checks() {
                return checks;
            }

            @Override
            public PluginHealthSnapshot snapshot() {
                return snapshot.get();
            }

            @Override
            public void close() {
                close.run();
            }
        };
    }

    private static PluginMetricsProvider metricsProvider(
            String id,
            Supplier<PluginMetricsSource> source
    ) {
        return new PluginMetricsProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public PluginMetricsSource create(PluginMetricsContext context) {
                return source.get();
            }
        };
    }

    private static PluginMetricsSource metricsSource(
            List<PluginMetricDescriptor> descriptors,
            Supplier<PluginMetricSnapshot> snapshot,
            Runnable close
    ) {
        return new PluginMetricsSource() {
            @Override
            public List<PluginMetricDescriptor> descriptors() {
                return descriptors;
            }

            @Override
            public PluginMetricSnapshot snapshot() {
                return snapshot.get();
            }

            @Override
            public void close() {
                close.run();
            }
        };
    }

    private static PluginMetricSnapshot metricSnapshot(String id, double total) {
        return new PluginMetricSnapshot(Map.of(id, new PluginCounterValue(total)));
    }

    private static PluginBundleRuntimeInfo bundle(
            PluginOperationsSnapshot snapshot,
            String id
    ) {
        return snapshot.bundles().stream()
                .filter(bundle -> bundle.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static PluginContributionRuntimeInfo contribution(
            PluginOperationsSnapshot snapshot,
            String bundleId,
            String kind
    ) {
        return bundle(snapshot, bundleId).contributions().stream()
                .filter(contribution -> contribution.kind().equals(kind))
                .findFirst()
                .orElseThrow();
    }

    private static PluginHealthCheckRuntimeInfo runtimeCheck(
            PluginOperationsSnapshot snapshot,
            String bundleId,
            String checkId
    ) {
        return snapshot.healthChecks().stream()
                .filter(check -> check.bundleId().equals(bundleId)
                        && check.descriptor().id().equals(checkId))
                .findFirst()
                .orElseThrow();
    }

    private static void assertLastGoodChecksStale(
            PluginOperationsSnapshot snapshot,
            String bundleId
    ) {
        assertThat(runtimeCheck(snapshot, bundleId, "alpha").status())
                .isEqualTo(PluginHealthStatus.UP);
        assertThat(runtimeCheck(snapshot, bundleId, "alpha").descriptor().description())
                .isEqualTo("Check alpha");
        assertThat(runtimeCheck(snapshot, bundleId, "zeta").status())
                .isEqualTo(PluginHealthStatus.DOWN);
        assertThat(snapshot.healthChecks().stream()
                .filter(check -> check.bundleId().equals(bundleId))
                .toList())
                .allSatisfy(check -> assertThat(check.stale()).isTrue());
    }

    private static double metricTotal(PluginOperationsSnapshot snapshot, String bundleId) {
        return snapshot.metrics().stream()
                .filter(series -> series.bundleId().equals(bundleId))
                .map(PluginMetricSeries::value)
                .map(PluginCounterValue.class::cast)
                .mapToDouble(PluginCounterValue::total)
                .findFirst()
                .orElse(-1);
    }

    private static long operationTotal(
            PluginOperationsSnapshot snapshot,
            String bundleId,
            PluginOperation operation,
            PluginOperationOutcome outcome
    ) {
        return bundle(snapshot, bundleId).operationCounts().stream()
                .filter(count -> count.operation() == operation && count.outcome() == outcome)
                .mapToLong(count -> count.total())
                .sum();
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        while (latch.getCount() != 0) {
            try {
                latch.await(1, TimeUnit.DAYS);
            } catch (InterruptedException ignored) {
                // Host timeouts are advisory; this emulates hostile plugin code.
            }
        }
    }

    private static void assertNoCallbacks(PluginOperationsSnapshot snapshot) {
        assertThat(snapshot.totals().activeSamples()).isZero();
        assertThat(snapshot.bundles()).allSatisfy(bundle -> {
            assertThat(bundle.activeCallbacks()).isZero();
            assertThat(bundle.queuedCallbacks()).isZero();
        });
    }

    private static final class RecordingProviders implements PluginProviderRegistry {
        private final Map<Class<?>, Map<String, Object>> values = new LinkedHashMap<>();
        private final Map<String, Runnable> beforeFind = new LinkedHashMap<>();
        private final List<CompletableFuture<Void>> cleanupSignals = new ArrayList<>();
        private boolean failCleanupRegistration;

        private <P> void add(Class<P> type, String name, P provider) {
            values.computeIfAbsent(type, ignored -> new LinkedHashMap<>())
                    .put(name, provider);
        }

        private void beforeFind(String selector, Runnable callback) {
            beforeFind.put(selector, callback);
        }

        @Override
        public <P> Optional<P> find(Class<P> providerType, String selector) {
            beforeFind.getOrDefault(selector, () -> { }).run();
            return Optional.ofNullable(values.getOrDefault(providerType, Map.of()).get(selector))
                    .map(providerType::cast);
        }

        @Override
        public <P> List<String> names(Class<P> providerType) {
            return values.getOrDefault(providerType, Map.of()).keySet().stream()
                    .sorted()
                    .toList();
        }

        @Override
        public void registerContributionCleanup(CompletableFuture<Void> completion) {
            if (failCleanupRegistration) {
                cleanupSignals.add(completion);
                throw new IllegalStateException("registration failed");
            }
            cleanupSignals.add(completion);
        }
    }
}
