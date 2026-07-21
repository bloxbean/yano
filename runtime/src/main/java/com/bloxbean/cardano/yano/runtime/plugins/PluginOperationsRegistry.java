package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.plugin.PluginBundleInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginCatalogView;
import com.bloxbean.cardano.yano.api.plugin.PluginContributionInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginTrustTier;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginBundleRuntimeAggregation;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginBundleRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginContributionRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginCounterValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginFailure;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginFailureCode;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginGaugeValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthCheckDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthCheckRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthReport;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSchema;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthSource;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginHealthStatus;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginInstanceRuntimeInfo;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginLifecycleState;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricDescriptor;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricSchema;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricSeries;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricValue;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsContext;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsProvider;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginMetricsSource;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperation;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationCount;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationOutcome;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsSnapshot;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsTotals;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationsView;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginTimerValue;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Lifecycle owner and cache for ADR-011.4 plugin operations data.
 *
 * <p>{@link #snapshot()} is deliberately a single atomic read. Provider
 * resolution, plugin callbacks, validation, sampling, and lifecycle mutation
 * happen only on the host-owned paths in this class.</p>
 */
public final class PluginOperationsRegistry implements PluginOperationsView, AutoCloseable {
    static final int DEFAULT_WORKERS = 4;
    static final int DEFAULT_QUEUE_CAPACITY = 256;
    static final int MAX_TELEMETRY_SOURCES = PluginOperationsSnapshot.MAX_BUNDLES * 2;
    static final Duration DEFAULT_SAMPLE_INTERVAL = Duration.ofSeconds(5);
    static final Duration DEFAULT_SAMPLE_DEADLINE = Duration.ofSeconds(1);
    static final Duration DOMAIN_PUBLICATION_INTERVAL = Duration.ofMillis(100);

    private static final String NODE_PLUGIN = "node-plugin";
    private static final String DOMAIN_API = "domain-api";
    private static final String HEALTH = "health";
    private static final String METRICS = "metrics";

    private final Object lifecycleMonitor = new Object();
    private final Object stateMonitor = new Object();
    private final PluginCatalogView catalog;
    private final PluginProviderRegistry providers;
    private final Logger log;
    private final long intervalNanos;
    private final long deadlineNanos;
    private final int workerCount;
    private final LongSupplier epochMillis;
    private final LongSupplier nanoTime;
    private final List<String> selectedOrder;
    private final Map<String, BundleState> bundles = new LinkedHashMap<>();
    private final Map<String, List<PluginHealthCheckDescriptor>> healthSchemaBaselines =
            new LinkedHashMap<>();
    private final Map<String, List<PluginMetricDescriptor>> metricSchemaBaselines =
            new LinkedHashMap<>();
    private final ThreadPoolExecutor sampleExecutor;
    private final ScheduledThreadPoolExecutor timer;
    private final AtomicReference<PluginOperationsSnapshot> publication;
    private final ThreadLocal<Boolean> sampleCallback =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private long nextGeneration;
    private Generation generation;
    private boolean sampling;
    private boolean closed;
    private final List<ScheduledFuture<?>> periodicTasks = new ArrayList<>();
    private ScheduledFuture<?> dynamicPublicationTask;
    private long snapshotBuilds;

    public PluginOperationsRegistry(PluginRuntimeEnvironment environment, Logger log) {
        this(environment.catalog(), environment.providers(), log,
                DEFAULT_SAMPLE_INTERVAL, DEFAULT_SAMPLE_DEADLINE,
                DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY,
                System::currentTimeMillis, System::nanoTime);
    }

    PluginOperationsRegistry(
            PluginCatalogView catalog,
            PluginProviderRegistry providers,
            Logger log,
            Duration interval,
            Duration deadline,
            int workers,
            int queueCapacity,
            LongSupplier epochMillis,
            LongSupplier nanoTime
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.log = Objects.requireNonNull(log, "log");
        this.epochMillis = Objects.requireNonNull(epochMillis, "epochMillis");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (deadline == null || deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
        if (workers <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("worker and queue capacities must be positive");
        }
        this.intervalNanos = interval.toNanos();
        this.deadlineNanos = deadline.toNanos();
        this.workerCount = workers;
        this.selectedOrder = List.copyOf(catalog.selectedBundleOrder());
        initializeCatalogState();

        AtomicInteger workerNumber = new AtomicInteger();
        this.sampleExecutor = new ThreadPoolExecutor(
                workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> hostThread(runnable,
                        "yano-plugin-sampler-" + workerNumber.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
        this.timer = new ScheduledThreadPoolExecutor(
                1, runnable -> hostThread(runnable, "yano-plugin-sampler-timer"));
        this.timer.setRemoveOnCancelPolicy(true);
        synchronized (stateMonitor) {
            this.publication = new AtomicReference<>(buildSnapshotLocked());
        }
    }

    private static Thread hostThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.setContextClassLoader(PluginOperationsRegistry.class.getClassLoader());
        return thread;
    }

    private void initializeCatalogState() {
        for (PluginBundleInfo bundle : catalog.bundles()) {
            BundleState state = new BundleState(bundle.id(), bundle.selected());
            for (PluginContributionInfo contribution : bundle.contributions()) {
                PluginLifecycleState lifecycle = bundle.selected()
                        ? PluginLifecycleState.VALIDATED
                        : PluginLifecycleState.NOT_SELECTED;
                state.contributions.put(key(contribution.kind(), contribution.name()),
                        new ContributionState(
                                contribution.kind(), contribution.name(),
                                contribution.trustTier(), lifecycle));
            }
            bundles.put(bundle.id(), state);
        }
    }

    @Override
    public PluginOperationsSnapshot snapshot() {
        return publication.get();
    }

    long snapshotBuildCountForTesting() {
        synchronized (stateMonitor) {
            return snapshotBuilds;
        }
    }

    /** Construct isolated telemetry products in catalog dependency order. */
    public void activateTelemetry() {
        requireHostLifecycleCaller("activate plugin telemetry");
        synchronized (lifecycleMonitor) {
            Generation created;
            Set<String> healthProviders;
            Set<String> metricsProviders;
            synchronized (stateMonitor) {
                ensureOpenLocked();
                if (generation != null) {
                    return;
                }
                created = new Generation(nextGeneration + 1);
            }
            healthProviders = Set.copyOf(providers.names(PluginHealthProvider.class));
            metricsProviders = Set.copyOf(providers.names(PluginMetricsProvider.class));
            try {
                providers.registerContributionCleanup(created.terminal);
            } catch (Throwable registrationFailure) {
                created.terminal.complete(null);
                LifecycleFailures.rethrowIfProcessFatalReachable(registrationFailure);
                if (registrationFailure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (registrationFailure instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(
                        "Plugin telemetry cleanup registration failed",
                        registrationFailure);
            }
            synchronized (stateMonitor) {
                generation = created;
                nextGeneration = created.id;
                enqueueActivationsLocked(created, healthProviders, metricsProviders);
                if (!created.activations.isEmpty()) {
                    // One generation-wide admission window is intentional:
                    // hostile early ordinals may exhaust the bounded
                    // workers, but cannot turn startup into N sequential
                    // deadlines. Every unentered tail reports TIMED_OUT.
                    created.activationDeadlineNanos =
                            deadlineFrom(nanoTime.getAsLong());
                    created.activationDeadlineTask = timer.schedule(
                            () -> timeoutActivations(created),
                            deadlineNanos, TimeUnit.NANOSECONDS);
                    pumpActivationsLocked(created);
                }
                publishLocked();
            }
        }
    }

    /** Start periodic sampling after ordinary runtime contributions have started. */
    public void startSampling() {
        requireHostLifecycleCaller("start plugin telemetry sampling");
        synchronized (lifecycleMonitor) {
            synchronized (stateMonitor) {
                ensureOpenLocked();
                if (sampling) {
                    return;
                }
                if (generation == null) {
                    throw new IllegalStateException("Plugin telemetry is not active");
                }
                sampling = true;
                generation.samplingStartedNanos = nanoTime.getAsLong();
                scheduleSourcesLocked(generation);
                publishLocked();
            }
        }
    }

    /**
     * Seal admission, remove queued callbacks, wait for entered callbacks, and
     * close source products in reverse dependency order.
     */
    public void sealAndAwait() {
        requireHostLifecycleCaller("stop plugin telemetry");
        synchronized (lifecycleMonitor) {
            Generation closing;
            List<DiscardedProduct> discarded;
            synchronized (stateMonitor) {
                closing = generation;
                if (closing == null) {
                    return;
                }
                sampling = false;
                closing.sealed = true;
                closing.acceptingActivations = false;
                if (closing.activationDeadlineTask != null) {
                    closing.activationDeadlineTask.cancel(false);
                }
                if (closing.activationPumpTask != null) {
                    closing.activationPumpTask.cancel(false);
                }
                periodicTasks.forEach(task -> task.cancel(false));
                periodicTasks.clear();
                for (Activation activation : closing.activations) {
                    activation.cancelLocked();
                }
                closing.pendingActivations.clear();
                discarded = commitResolvedActivationsLocked(closing);
                for (SampleSource source : closing.sources) {
                    SampleInvocation invocation = source.inFlight;
                    if (invocation != null && invocation.state == InvocationState.QUEUED) {
                        invocation.cancelQueuedLocked();
                    }
                }
            }

            Throwable failure = null;
            try {
                closeDiscardedProducts(closing, discarded);
            } catch (Throwable activationCloseFailure) {
                failure = activationCloseFailure;
            }
            synchronized (stateMonitor) {
                awaitNoActiveSamplesLocked(closing);
            }

            Throwable sourceCloseFailure = closeGeneration(closing);
            if (sourceCloseFailure != null) {
                failure = failure == null
                        ? sourceCloseFailure
                        : LifecycleFailures.merge(failure, sourceCloseFailure);
            }
            synchronized (stateMonitor) {
                if (generation == closing) {
                    generation = null;
                }
                markTelemetryStoppedLocked();
                publishLocked();
            }
            closing.terminal.complete(null);
            rethrowLifecycleFailure(failure, "Plugin telemetry shutdown failed");
        }
    }

    void nodePluginStarting(String bundleId) {
        contributionTransition(bundleId, NODE_PLUGIN, PluginLifecycleState.ACTIVATING,
                PluginHealthStatus.UNKNOWN, PluginFailure.none());
    }

    void nodePluginStarted(String bundleId) {
        contributionTransition(bundleId, NODE_PLUGIN, PluginLifecycleState.ACTIVE,
                PluginHealthStatus.UP, PluginFailure.none());
        increment(bundleId, PluginOperation.LIFECYCLE, PluginOperationOutcome.SUCCEEDED);
    }

    void nodePluginStartFailed(String bundleId) {
        contributionFailure(bundleId, NODE_PLUGIN, PluginFailureCode.START_FAILED,
                PluginHealthStatus.DOWN);
        increment(bundleId, PluginOperation.LIFECYCLE, PluginOperationOutcome.FAILED);
    }

    void nodePluginStopped(String bundleId, boolean succeeded) {
        if (succeeded) {
            contributionTransitionUnlessFailed(
                    bundleId, NODE_PLUGIN, PluginLifecycleState.STOPPED,
                    PluginHealthStatus.UNKNOWN, PluginFailure.none());
        } else {
            contributionFailure(bundleId, NODE_PLUGIN, PluginFailureCode.STOP_FAILED,
                    PluginHealthStatus.DOWN);
        }
        increment(bundleId, PluginOperation.LIFECYCLE, succeeded
                ? PluginOperationOutcome.SUCCEEDED : PluginOperationOutcome.FAILED);
    }

    void nodePluginClosed(String bundleId, boolean succeeded) {
        if (succeeded) {
            contributionTransitionUnlessFailed(
                    bundleId, NODE_PLUGIN, PluginLifecycleState.CLOSED,
                    PluginHealthStatus.UNKNOWN, PluginFailure.none());
        } else {
            contributionFailure(bundleId, NODE_PLUGIN, PluginFailureCode.CLOSE_FAILED,
                    PluginHealthStatus.DOWN);
        }
    }

    void domainActivating(String bundleId) {
        contributionTransition(bundleId, DOMAIN_API, PluginLifecycleState.ACTIVATING,
                PluginHealthStatus.UNKNOWN, PluginFailure.none());
    }

    void domainActive(String bundleId) {
        contributionTransition(bundleId, DOMAIN_API, PluginLifecycleState.ACTIVE,
                PluginHealthStatus.UP, PluginFailure.none());
        increment(bundleId, PluginOperation.ACTIVATION, PluginOperationOutcome.SUCCEEDED);
    }

    void domainActivationFailed(String bundleId) {
        contributionFailure(bundleId, DOMAIN_API, PluginFailureCode.ACTIVATION_FAILED,
                PluginHealthStatus.DOWN);
        increment(bundleId, PluginOperation.ACTIVATION, PluginOperationOutcome.FAILED);
    }

    void domainStopped(String bundleId, boolean succeeded) {
        if (succeeded) {
            contributionTransition(bundleId, DOMAIN_API, PluginLifecycleState.STOPPED,
                    PluginHealthStatus.UNKNOWN, PluginFailure.none());
        } else {
            contributionFailure(bundleId, DOMAIN_API, PluginFailureCode.CLOSE_FAILED,
                    PluginHealthStatus.DOWN);
        }
    }

    void domainAdmitted(String bundleId) {
        synchronized (stateMonitor) {
            if (closed) {
                return;
            }
            BundleState bundle = bundles.get(bundleId);
            if (bundle != null) {
                bundle.domainQueued++;
                scheduleDynamicPublicationLocked();
            }
        }
    }

    void domainStarted(String bundleId) {
        synchronized (stateMonitor) {
            if (closed) {
                return;
            }
            BundleState bundle = bundles.get(bundleId);
            if (bundle != null) {
                bundle.domainQueued = Math.max(0, bundle.domainQueued - 1);
                bundle.domainActive++;
                scheduleDynamicPublicationLocked();
            }
        }
    }

    void domainQueueFinished(String bundleId) {
        synchronized (stateMonitor) {
            if (closed) {
                return;
            }
            BundleState bundle = bundles.get(bundleId);
            if (bundle == null) {
                return;
            }
            bundle.domainQueued = Math.max(0, bundle.domainQueued - 1);
            scheduleDynamicPublicationLocked();
        }
    }

    void domainCallbackFinished(String bundleId) {
        synchronized (stateMonitor) {
            if (closed) {
                return;
            }
            BundleState bundle = bundles.get(bundleId);
            if (bundle == null) {
                return;
            }
            bundle.domainActive = Math.max(0, bundle.domainActive - 1);
            scheduleDynamicPublicationLocked();
        }
    }

    void domainOutcome(
            String bundleId,
            DomainHttpMethod method,
            PluginOperationOutcome outcome
    ) {
        synchronized (stateMonitor) {
            if (closed) {
                return;
            }
            BundleState bundle = bundles.get(bundleId);
            if (bundle != null) {
                incrementLocked(bundle, domainOperation(method), outcome);
                scheduleDynamicPublicationLocked();
            }
        }
    }

    private void enqueueActivationsLocked(
            Generation owner,
            Set<String> healthProviders,
            Set<String> metricsProviders
    ) {
        int ordinal = 0;
        for (String bundleId : selectedOrder) {
            if (healthProviders.contains(bundleId)) {
                ContributionState contribution = findContribution(
                        bundles.get(bundleId), HEALTH);
                if (contribution != null) {
                    transition(contribution, PluginLifecycleState.ACTIVATING,
                            PluginHealthStatus.UNKNOWN, PluginFailure.none());
                    owner.addActivation(new HealthActivation(
                            owner, bundleId, contribution, ordinal++));
                    bundles.get(bundleId).activationQueued++;
                }
            }
            if (metricsProviders.contains(bundleId)) {
                ContributionState contribution = findContribution(
                        bundles.get(bundleId), METRICS);
                if (contribution != null) {
                    transition(contribution, PluginLifecycleState.ACTIVATING,
                            PluginHealthStatus.UNKNOWN, PluginFailure.none());
                    owner.addActivation(new MetricsActivation(
                            owner, bundleId, contribution, ordinal++));
                    bundles.get(bundleId).activationQueued++;
                }
            }
        }
        owner.plannedSources = ordinal;
    }

    private void pumpActivationsLocked(Generation owner) {
        if (closed || generation != owner || !owner.acceptingActivations) {
            return;
        }
        while (owner.submittedActivations < workerCount
                && !owner.pendingActivations.isEmpty()) {
            Activation activation = owner.pendingActivations.removeFirst();
            activation.state = ActivationState.QUEUED;
            owner.submittedActivations++;
            try {
                sampleExecutor.execute(activation);
            } catch (RejectedExecutionException rejected) {
                owner.submittedActivations--;
                activation.state = ActivationState.PENDING;
                owner.pendingActivations.addFirst(activation);
                scheduleActivationPumpLocked(owner);
                return;
            }
        }
    }

    private void scheduleActivationPumpLocked(Generation owner) {
        if (owner.activationPumpTask != null
                && !owner.activationPumpTask.isDone()) {
            return;
        }
        try {
            owner.activationPumpTask = timer.schedule(() -> {
                synchronized (stateMonitor) {
                    owner.activationPumpTask = null;
                    pumpActivationsLocked(owner);
                }
            }, 1, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // A concurrent close cancels the still-pending activation below.
        }
    }

    private void timeoutActivations(Generation owner) {
        List<DiscardedProduct> discarded;
        synchronized (stateMonitor) {
            if (generation != owner || !owner.acceptingActivations) {
                return;
            }
            owner.acceptingActivations = false;
            for (Activation activation : owner.activations) {
                activation.timeoutLocked();
            }
            owner.pendingActivations.clear();
            discarded = commitResolvedActivationsLocked(owner);
            publishLocked();
        }
        closeDiscardedProducts(owner, discarded);
    }

    private void installSourceLocked(Generation owner, ActivationProduct product) {
        if (owner.sources.size() == MAX_TELEMETRY_SOURCES) {
            throw new IllegalStateException("Plugin telemetry source limit exceeded");
        }
        if (owner.healthChecks > PluginHealthCheckDescriptor.MAX_CHECKS_HOST_WIDE
                - product.healthChecks()) {
            throw new IllegalStateException("Plugin health host-wide limit exceeded");
        }
        if (owner.metricSeries > PluginMetricDescriptor.MAX_SERIES_HOST_WIDE
                - product.metricSeries()) {
            throw new IllegalStateException("Plugin metric host-wide limit exceeded");
        }
        product.requireStableSchema();
        owner.healthChecks += product.healthChecks();
        owner.metricSeries += product.metricSeries();
        owner.sources.add(product.source());
        product.freezeSchema();
        if (sampling) {
            scheduleSourceLocked(owner, product.source());
        }
    }

    private void requireHealthSchemaStable(
            String bundleId,
            List<PluginHealthCheckDescriptor> checks
    ) {
        List<PluginHealthCheckDescriptor> baseline = healthSchemaBaselines.get(bundleId);
        if (baseline != null && !baseline.equals(checks)) {
            throw new IllegalStateException(
                    "Plugin health schema changed across runtime generations");
        }
    }

    private void requireMetricSchemaStable(
            String bundleId,
            List<PluginMetricDescriptor> descriptors
    ) {
        List<PluginMetricDescriptor> baseline = metricSchemaBaselines.get(bundleId);
        if (baseline != null && !baseline.equals(descriptors)) {
            throw new IllegalStateException(
                    "Plugin metric schema changed across runtime generations");
        }
    }

    private void activationFailedLocked(
            String bundleId,
            ContributionState contribution,
            PluginOperationOutcome outcome
    ) {
        transition(contribution, PluginLifecycleState.FAILED,
                PluginHealthStatus.DEGRADED,
                failure(PluginFailureCode.ACTIVATION_FAILED));
        incrementLocked(bundles.get(bundleId), PluginOperation.ACTIVATION, outcome);
    }

    private List<DiscardedProduct> commitResolvedActivationsLocked(Generation owner) {
        List<DiscardedProduct> discarded = new ArrayList<>();
        while (owner.commitCursor < owner.activations.size()) {
            Activation activation = owner.activations.get(owner.commitCursor);
            if (!activation.resolved) {
                break;
            }
            owner.commitCursor++;
            ActivationProduct product = activation.product;
            if (product == null) {
                continue;
            }
            activation.product = null;
            if (owner.sealed || activation.cancelled || activation.timedOut) {
                discarded.add(activation.discard(product, activation.primaryFailure));
                continue;
            }
            PluginLifecycleState previousLifecycle = activation.contribution.lifecycle;
            PluginHealthStatus previousHealth = activation.contribution.health;
            PluginFailure previousFailure = activation.contribution.failure;
            long previousLastTransition = activation.contribution.lastTransition;
            BundleState bundle = bundles.get(activation.bundleId);
            EnumMap<PluginOperationOutcome, Long> activationOutcomes =
                    bundle.operations.get(PluginOperation.ACTIVATION);
            Long previousSuccess = activationOutcomes == null
                    ? null : activationOutcomes.get(PluginOperationOutcome.SUCCEEDED);
            try {
                installSourceLocked(owner, product);
                transition(activation.contribution, PluginLifecycleState.ACTIVE,
                        PluginHealthStatus.UNKNOWN, PluginFailure.none());
                incrementLocked(bundle,
                        PluginOperation.ACTIVATION,
                        PluginOperationOutcome.SUCCEEDED);
                activation.transferProduct();
            } catch (Throwable installFailure) {
                rollbackSourceInstallLocked(owner, product);
                activation.contribution.lifecycle = previousLifecycle;
                activation.contribution.health = previousHealth;
                activation.contribution.failure = previousFailure;
                activation.contribution.lastTransition = previousLastTransition;
                restoreOperationCountLocked(bundle, PluginOperation.ACTIVATION,
                        PluginOperationOutcome.SUCCEEDED, previousSuccess);
                Throwable processFatal = LifecycleFailures.findProcessFatalReachable(
                        installFailure);
                if (processFatal == null) {
                    activationFailedLocked(activation.bundleId, activation.contribution,
                            PluginOperationOutcome.FAILED);
                    log.warn("Plugin telemetry activation commit failed "
                                    + "(bundle={}, kind={}, errorType={})",
                            activation.bundleId, activation.contribution.kind,
                            installFailure.getClass().getName());
                }
                discarded.add(activation.discard(product,
                        processFatal == null ? installFailure : processFatal));
            }
        }
        if (!discarded.isEmpty()) {
            owner.activeCleanupCallbacks++;
        }
        return discarded;
    }

    private void rollbackSourceInstallLocked(Generation owner, ActivationProduct product) {
        product.rollbackSchema();
        SampleSource source = product.source();
        ScheduledFuture<?> periodicTask = source.periodicTask;
        if (periodicTask != null) {
            periodicTask.cancel(false);
            periodicTasks.remove(periodicTask);
            source.periodicTask = null;
        }
        source.scheduled = false;
        if (owner.sources.remove(source)) {
            owner.healthChecks -= product.healthChecks();
            owner.metricSeries -= product.metricSeries();
        }
    }

    private static void restoreOperationCountLocked(
            BundleState bundle,
            PluginOperation operation,
            PluginOperationOutcome outcome,
            Long previous
    ) {
        EnumMap<PluginOperationOutcome, Long> outcomes = bundle.operations.get(operation);
        if (previous == null) {
            if (outcomes != null) {
                outcomes.remove(outcome);
                if (outcomes.isEmpty()) {
                    bundle.operations.remove(operation);
                }
            }
        } else if (outcomes != null) {
            outcomes.put(outcome, previous);
        }
    }

    private void closeDiscardedProducts(
            Generation owner,
            List<DiscardedProduct> discarded
    ) {
        if (discarded.isEmpty()) {
            return;
        }
        Throwable outcome = null;
        try {
            for (DiscardedProduct item : discarded) {
                Throwable primaryFatal = LifecycleFailures.findProcessFatalReachable(
                        item.primary());
                if (primaryFatal != null) {
                    outcome = LifecycleFailures.merge(outcome, primaryFatal);
                }
                try {
                    item.product().close();
                } catch (Throwable closeFailure) {
                    Throwable productOutcome = item.primary() == null
                            ? closeFailure
                            : LifecycleFailures.merge(item.primary(), closeFailure);
                    outcome = LifecycleFailures.merge(outcome, productOutcome);
                    log.warn("Discarded plugin telemetry product close failed "
                                    + "(errorType={})",
                            closeFailure.getClass().getName());
                }
            }
        } finally {
            synchronized (stateMonitor) {
                owner.activeCleanupCallbacks--;
                stateMonitor.notifyAll();
            }
        }
        LifecycleFailures.rethrowIfProcessFatalReachable(outcome);
    }

    private void scheduleSourcesLocked(Generation owner) {
        for (SampleSource source : owner.sources) {
            scheduleSourceLocked(owner, source);
        }
    }

    private void scheduleSourceLocked(Generation owner, SampleSource source) {
        if (source.scheduled || generation != owner || !sampling) {
            return;
        }
        long initialDelay = nextPhaseDelay(
                owner, source.scheduleOrdinal, owner.plannedSources);
        ScheduledFuture<?> task = timer.scheduleAtFixedRate(
                () -> admit(source, owner.id),
                initialDelay, intervalNanos, TimeUnit.NANOSECONDS);
        try {
            periodicTasks.add(task);
            source.periodicTask = task;
            source.scheduled = true;
        } catch (RuntimeException | Error registrationFailure) {
            task.cancel(false);
            throw registrationFailure;
        }
    }

    private long nextPhaseDelay(Generation owner, int ordinal, int sourceCount) {
        long phase = staggerDelay(ordinal, sourceCount);
        long now = nanoTime.getAsLong();
        long elapsed = now >= owner.samplingStartedNanos
                ? now - owner.samplingStartedNanos : 0;
        long currentPhase = elapsed % intervalNanos;
        return phase >= currentPhase
                ? phase - currentPhase
                : intervalNanos - (currentPhase - phase);
    }

    private long staggerDelay(int index, int sourceCount) {
        if (index == 0 || sourceCount <= 1) {
            return 0;
        }
        long quotient = intervalNanos / sourceCount;
        long remainder = intervalNanos % sourceCount;
        return quotient * index + remainder * index / sourceCount;
    }

    private void admit(SampleSource source, long expectedGeneration) {
        SampleInvocation invocation;
        synchronized (stateMonitor) {
            if (!sampling || generation == null || generation.id != expectedGeneration
                    || source.inFlight != null) {
                return;
            }
            invocation = new SampleInvocation(
                    generation, source, deadlineFrom(nanoTime.getAsLong()));
            source.inFlight = invocation;
            generation.queuedCallbacks++;
            bundles.get(source.bundleId).sampleQueued++;
            scheduleDynamicPublicationLocked();
        }
        try {
            sampleExecutor.execute(invocation);
            invocation.timeoutTask = timer.schedule(
                    invocation::timeout, deadlineNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException rejected) {
            invocation.reject();
        }
    }

    private long deadlineFrom(long now) {
        return now > Long.MAX_VALUE - deadlineNanos
                ? Long.MAX_VALUE : now + deadlineNanos;
    }

    private Throwable closeGeneration(Generation closing) {
        Throwable failure = null;
        List<SampleSource> reverse = new ArrayList<>(closing.sources);
        for (int index = reverse.size() - 1; index >= 0; index--) {
            SampleSource source = reverse.get(index);
            try {
                source.close();
            } catch (Throwable closeFailure) {
                closeFailure = LifecycleFailures.normalizeProcessFatalReachable(closeFailure);
                failure = failure == null
                        ? closeFailure : LifecycleFailures.merge(failure, closeFailure);
                if (LifecycleFailures.findProcessFatalReachable(closeFailure) == null) {
                    synchronized (stateMonitor) {
                        transition(source.contribution, PluginLifecycleState.FAILED,
                                PluginHealthStatus.DOWN,
                                failure(PluginFailureCode.CLOSE_FAILED));
                        publishLocked();
                    }
                }
            }
        }
        return failure;
    }

    private void awaitNoActiveSamplesLocked(Generation closing) {
        boolean interrupted = false;
        while (closing.activeCallbacks != 0
                || closing.activeActivationCallbacks != 0
                || closing.activeCleanupCallbacks != 0) {
            try {
                stateMonitor.wait();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void markTelemetryStoppedLocked() {
        for (BundleState bundle : bundles.values()) {
            for (ContributionState contribution : bundle.contributions.values()) {
                if ((HEALTH.equals(contribution.kind) || METRICS.equals(contribution.kind))
                        && contribution.lifecycle != PluginLifecycleState.FAILED
                        && contribution.lifecycle != PluginLifecycleState.CLOSED
                        && contribution.lifecycle != PluginLifecycleState.NOT_SELECTED) {
                    transition(contribution, PluginLifecycleState.STOPPED,
                            contribution.health, contribution.failure);
                }
            }
        }
    }

    private void contributionTransition(
            String bundleId,
            String kind,
            PluginLifecycleState lifecycle,
            PluginHealthStatus health,
            PluginFailure failure
    ) {
        synchronized (stateMonitor) {
            if (closed) {
                return;
            }
            ContributionState contribution = findContribution(bundles.get(bundleId), kind);
            if (contribution != null) {
                transition(contribution, lifecycle, health, failure);
                publishLocked();
            }
        }
    }

    private void contributionFailure(
            String bundleId,
            String kind,
            PluginFailureCode code,
            PluginHealthStatus health
    ) {
        contributionTransition(bundleId, kind, PluginLifecycleState.FAILED,
                health, failure(code));
    }

    private void contributionTransitionUnlessFailed(
            String bundleId,
            String kind,
            PluginLifecycleState lifecycle,
            PluginHealthStatus health,
            PluginFailure failure
    ) {
        synchronized (stateMonitor) {
            if (closed) {
                return;
            }
            ContributionState contribution = findContribution(bundles.get(bundleId), kind);
            if (contribution != null
                    && contribution.lifecycle != PluginLifecycleState.FAILED) {
                transition(contribution, lifecycle, health, failure);
                publishLocked();
            }
        }
    }

    private void increment(
            String bundleId,
            PluginOperation operation,
            PluginOperationOutcome outcome
    ) {
        synchronized (stateMonitor) {
            if (closed) {
                return;
            }
            BundleState bundle = bundles.get(bundleId);
            if (bundle != null) {
                incrementLocked(bundle, operation, outcome);
                publishLocked();
            }
        }
    }

    private static void incrementLocked(
            BundleState bundle,
            PluginOperation operation,
            PluginOperationOutcome outcome
    ) {
        EnumMap<PluginOperationOutcome, Long> outcomes = bundle.operations.computeIfAbsent(
                operation, ignored -> new EnumMap<>(PluginOperationOutcome.class));
        long current = outcomes.getOrDefault(outcome, 0L);
        if (current != Long.MAX_VALUE) {
            outcomes.put(outcome, current + 1);
        }
    }

    private static PluginOperation domainOperation(DomainHttpMethod method) {
        return method == DomainHttpMethod.GET
                ? PluginOperation.DOMAIN_GET : PluginOperation.DOMAIN_POST;
    }

    private static ContributionState findContribution(BundleState bundle, String kind) {
        if (bundle == null) {
            return null;
        }
        return bundle.contributions.values().stream()
                .filter(contribution -> contribution.kind.equals(kind))
                .findFirst()
                .orElse(null);
    }

    private void transition(
            ContributionState contribution,
            PluginLifecycleState lifecycle,
            PluginHealthStatus health,
            PluginFailure failure
    ) {
        if (contribution.lifecycle != lifecycle
                || contribution.health != health
                || !contribution.failure.equals(failure)) {
            contribution.lastTransition = nowEpochMillis();
        }
        contribution.lifecycle = lifecycle;
        contribution.health = health;
        contribution.failure = failure;
    }

    private PluginFailure failure(PluginFailureCode code) {
        return code == PluginFailureCode.NONE
                ? PluginFailure.none()
                : new PluginFailure(code, Math.max(1, nowEpochMillis()));
    }

    private long nowEpochMillis() {
        return Math.max(0, epochMillis.getAsLong());
    }

    private void publishLocked() {
        publication.set(buildSnapshotLocked());
    }

    private void scheduleDynamicPublicationLocked() {
        if (closed || timer.isShutdown()
                || (dynamicPublicationTask != null && !dynamicPublicationTask.isDone())) {
            return;
        }
        try {
            dynamicPublicationTask = timer.schedule(() -> {
                synchronized (stateMonitor) {
                    dynamicPublicationTask = null;
                    if (!closed) {
                        publishLocked();
                    }
                }
            }, DOMAIN_PUBLICATION_INTERVAL.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException ignored) {
            // A concurrent close publishes one final snapshot synchronously.
        }
    }

    private PluginOperationsSnapshot buildSnapshotLocked() {
        snapshotBuilds++;
        List<PluginBundleRuntimeInfo> bundleInfos = new ArrayList<>();
        List<PluginHealthCheckRuntimeInfo> healthCheckInfos = new ArrayList<>();
        List<PluginMetricSeries> metricSeries = new ArrayList<>();
        int selected = 0;
        int activeBundles = 0;
        int degradedBundles = 0;
        int failedBundles = 0;
        int contributions = 0;
        int observedContributions = 0;
        int observedActiveContributions = 0;
        int staleSources = 0;

        for (BundleState bundle : bundles.values()) {
            if (bundle.selected) {
                selected++;
            }
            List<PluginContributionRuntimeInfo> contributionInfos = new ArrayList<>();
            long lastTransition = 0;

            for (ContributionState contribution : bundle.contributions.values()) {
                lastTransition = Math.max(lastTransition, contribution.lastTransition);
                contributionInfos.add(contribution.info(bundle.selected));
            }

            PluginBundleRuntimeAggregation aggregation =
                    PluginBundleRuntimeAggregation.derive(
                            bundle.selected, contributionInfos);
            contributions += aggregation.contributionCount();
            observedContributions += aggregation.observedContributionCount();
            observedActiveContributions += aggregation.observedActiveContributionCount();
            staleSources += aggregation.staleSourceCount();
            if (aggregation.lifecycle() == PluginLifecycleState.ACTIVE) {
                activeBundles++;
            }
            if (aggregation.health() == PluginHealthStatus.DEGRADED) {
                degradedBundles++;
            }
            if (aggregation.hasFailedContribution()) {
                failedBundles++;
            }

            bundleInfos.add(new PluginBundleRuntimeInfo(
                    bundle.id, aggregation.lifecycle(), aggregation.health(),
                    aggregation.failure(), aggregation.metricsStale(), lastTransition,
                    bundle.domainActive + bundle.sampleActive + bundle.activationActive,
                    bundle.domainQueued + bundle.sampleQueued + bundle.activationQueued,
                    operationCounts(bundle), contributionInfos));
        }

        if (generation != null) {
            for (SampleSource source : generation.sources) {
                if (source instanceof HealthSource health) {
                    health.appendChecks(healthCheckInfos);
                } else if (source instanceof MetricsSource metrics) {
                    metrics.appendSeries(metricSeries);
                }
            }
        }
        int activeSamples = generation != null ? generation.activeCallbacks : 0;
        PluginOperationsTotals totals = new PluginOperationsTotals(
                bundles.size(), selected, activeBundles, degradedBundles,
                failedBundles, contributions, observedContributions,
                observedActiveContributions,
                staleSources, activeSamples);
        return new PluginOperationsSnapshot(
                catalog.fingerprint(), generation != null ? generation.id : nextGeneration,
                nowEpochMillis(), totals, bundleInfos, healthCheckInfos, metricSeries);
    }

    private static List<PluginOperationCount> operationCounts(BundleState bundle) {
        List<PluginOperationCount> counts = new ArrayList<>();
        bundle.operations.forEach((operation, outcomes) ->
                outcomes.forEach((outcome, total) ->
                        counts.add(new PluginOperationCount(operation, outcome, total))));
        return counts;
    }

    private static PluginHealthStatus worseReport(
            PluginHealthStatus left,
            PluginHealthStatus right
    ) {
        return reportHealthRank(right) > reportHealthRank(left) ? right : left;
    }

    private static int reportHealthRank(PluginHealthStatus status) {
        return switch (status) {
            case UP -> 0;
            case UNKNOWN -> 1;
            case DEGRADED -> 2;
            case DOWN -> 3;
        };
    }

    private void ensureOpenLocked() {
        if (closed) {
            throw new IllegalStateException("Plugin operations registry is closed");
        }
    }

    private void requireHostLifecycleCaller(String action) {
        if (sampleCallback.get()) {
            throw new IllegalStateException("Cannot " + action
                    + " from a plugin telemetry callback");
        }
    }

    @Override
    public void close() {
        requireHostLifecycleCaller("close plugin operations");
        synchronized (lifecycleMonitor) {
            synchronized (stateMonitor) {
                if (closed) {
                    return;
                }
            }
            Throwable failure = null;
            try {
                sealAndAwait();
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
            synchronized (stateMonitor) {
                closed = true;
                for (BundleState bundle : bundles.values()) {
                    if (!bundle.selected) {
                        continue;
                    }
                    for (ContributionState contribution : bundle.contributions.values()) {
                        boolean registryObserved = DOMAIN_API.equals(contribution.kind)
                                || HEALTH.equals(contribution.kind)
                                || METRICS.equals(contribution.kind);
                        boolean terminallyStopped =
                                contribution.lifecycle == PluginLifecycleState.STOPPED
                                        || contribution.lifecycle == PluginLifecycleState.ACTIVE;
                        if (registryObserved && terminallyStopped) {
                            transition(contribution, PluginLifecycleState.CLOSED,
                                    PluginHealthStatus.UNKNOWN, contribution.failure);
                        }
                    }
                }
                if (dynamicPublicationTask != null) {
                    dynamicPublicationTask.cancel(false);
                    dynamicPublicationTask = null;
                }
                publishLocked();
            }
            timer.shutdownNow();
            sampleExecutor.shutdownNow();
            awaitTermination(timer);
            awaitTermination(sampleExecutor);
            rethrowLifecycleFailure(failure, "Plugin operations registry close failed");
        }
    }

    private static void awaitTermination(java.util.concurrent.ExecutorService executor) {
        boolean interrupted = false;
        try {
            while (!executor.awaitTermination(1, TimeUnit.DAYS)) {
                // No plugin callback remains after sealAndAwait().
            }
        } catch (InterruptedException ignored) {
            interrupted = true;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void restoreInterruptState(boolean interruptedOnEntry) {
        boolean interruptedNow = Thread.currentThread().isInterrupted();
        if (interruptedOnEntry && !interruptedNow) {
            Thread.currentThread().interrupt();
        } else if (!interruptedOnEntry && interruptedNow) {
            Thread.interrupted();
        }
    }

    private static void rethrowLifecycleFailure(Throwable failure, String message) {
        if (failure == null) {
            return;
        }
        LifecycleFailures.rethrowIfProcessFatalReachable(failure);
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException(message, failure);
    }

    private static String key(String kind, String name) {
        return kind + '\u0000' + name;
    }

    private abstract class Activation implements Runnable {
        private final Generation owner;
        protected final String bundleId;
        protected final ContributionState contribution;
        protected final int ordinal;
        private ActivationState state = ActivationState.PENDING;
        private boolean resolved;
        private boolean timedOut;
        private boolean cancelled;
        private Thread runner;
        protected AutoCloseable producedProduct;
        private ActivationProduct product;
        private Throwable primaryFailure;

        private Activation(
                Generation owner,
                String bundleId,
                ContributionState contribution,
                int ordinal
        ) {
            this.owner = owner;
            this.bundleId = bundleId;
            this.contribution = contribution;
            this.ordinal = ordinal;
        }

        abstract ActivationProduct invokeProduct();

        @Override
        public final void run() {
            List<DiscardedProduct> discarded = List.of();
            synchronized (stateMonitor) {
                if (state != ActivationState.QUEUED) {
                    return;
                }
                if (nanoTime.getAsLong() >= owner.activationDeadlineNanos) {
                    timeoutLocked();
                    discarded = commitResolvedActivationsLocked(owner);
                    scheduleDynamicPublicationLocked();
                } else {
                    state = ActivationState.RUNNING;
                    runner = Thread.currentThread();
                    owner.activeActivationCallbacks++;
                    BundleState bundle = bundles.get(bundleId);
                    bundle.activationQueued--;
                    bundle.activationActive++;
                    scheduleDynamicPublicationLocked();
                }
            }
            if (state != ActivationState.RUNNING) {
                closeDiscardedProducts(owner, discarded);
                return;
            }

            ActivationProduct callbackProduct = null;
            Throwable callbackFailure = null;
            boolean interruptedOnEntry = Thread.currentThread().isInterrupted();
            try {
                sampleCallback.set(Boolean.TRUE);
                callbackProduct = invokeProduct();
            } catch (Throwable failure) {
                callbackFailure = failure;
            } finally {
                sampleCallback.remove();
            }
            Throwable processFatal = LifecycleFailures.findProcessFatalReachable(
                    callbackFailure);

            List<DiscardedProduct> cleanup;
            synchronized (stateMonitor) {
                if (!resolved) {
                    if (processFatal != null) {
                        resolved = true;
                        primaryFailure = processFatal;
                    } else if (nanoTime.getAsLong() >= owner.activationDeadlineNanos) {
                        timedOut = true;
                        resolved = true;
                        primaryFailure = callbackFailure;
                        activationFailedLocked(bundleId, contribution,
                                PluginOperationOutcome.TIMED_OUT);
                    } else if (callbackFailure != null) {
                        resolved = true;
                        primaryFailure = callbackFailure;
                        activationFailedLocked(bundleId, contribution,
                                PluginOperationOutcome.FAILED);
                        log.warn("Plugin telemetry activation failed "
                                        + "(bundle={}, kind={}, errorType={})",
                                bundleId, contribution.kind,
                                callbackFailure.getClass().getName());
                    } else {
                        resolved = true;
                        product = callbackProduct;
                    }
                }

                List<DiscardedProduct> next = new ArrayList<>();
                if ((timedOut || cancelled || callbackFailure != null)
                        && producedProduct != null) {
                    next.add(discardProduced(primaryFailure));
                }
                next.addAll(commitResolvedActivationsLocked(owner));
                if (!next.isEmpty() && next.size() == 1
                        && next.getFirst().registeredByCommit()) {
                    // commitResolvedActivationsLocked already registered this batch.
                } else if (!next.isEmpty()) {
                    // A directly failed/late product is added beside any commit batch.
                    // Normalize to one cleanup lease for the combined list.
                    boolean commitRegistered = next.stream()
                            .anyMatch(DiscardedProduct::registeredByCommit);
                    if (!commitRegistered) {
                        owner.activeCleanupCallbacks++;
                    }
                }
                cleanup = List.copyOf(next);

                state = ActivationState.FINISHED;
                runner = null;
                owner.submittedActivations--;
                owner.activeActivationCallbacks--;
                bundles.get(bundleId).activationActive--;
                pumpActivationsLocked(owner);
                stateMonitor.notifyAll();
                scheduleDynamicPublicationLocked();
            }
            try {
                closeDiscardedProducts(owner, cleanup);
            } finally {
                restoreInterruptState(interruptedOnEntry);
            }
            LifecycleFailures.rethrowIfProcessFatalReachable(processFatal);
        }

        private void timeoutLocked() {
            if (resolved) {
                return;
            }
            timedOut = true;
            resolved = true;
            activationFailedLocked(bundleId, contribution,
                    PluginOperationOutcome.TIMED_OUT);
            if (state == ActivationState.PENDING) {
                bundles.get(bundleId).activationQueued--;
                state = ActivationState.FINISHED;
            } else if (state == ActivationState.QUEUED) {
                sampleExecutor.remove(this);
                owner.submittedActivations--;
                bundles.get(bundleId).activationQueued--;
                state = ActivationState.FINISHED;
            } else if (state == ActivationState.RUNNING && runner != null) {
                runner.interrupt();
            }
        }

        private void cancelLocked() {
            if (resolved) {
                return;
            }
            cancelled = true;
            resolved = true;
            if (state == ActivationState.PENDING) {
                bundles.get(bundleId).activationQueued--;
                state = ActivationState.FINISHED;
            } else if (state == ActivationState.QUEUED) {
                sampleExecutor.remove(this);
                owner.submittedActivations--;
                bundles.get(bundleId).activationQueued--;
                state = ActivationState.FINISHED;
            } else if (state == ActivationState.RUNNING && runner != null) {
                runner.interrupt();
            }
        }

        private void transferProduct() {
            producedProduct = null;
        }

        private DiscardedProduct discard(
                ActivationProduct discarded,
                Throwable failure
        ) {
            AutoCloseable rawProduct = Objects.requireNonNull(
                    producedProduct, "activation product ownership");
            producedProduct = null;
            return new DiscardedProduct(rawProduct, failure, true);
        }

        private DiscardedProduct discardProduced(Throwable failure) {
            AutoCloseable discarded = producedProduct;
            producedProduct = null;
            return new DiscardedProduct(discarded, failure, false);
        }
    }

    private final class HealthActivation extends Activation {
        private HealthActivation(
                Generation owner,
                String bundleId,
                ContributionState contribution,
                int ordinal
        ) {
            super(owner, bundleId, contribution, ordinal);
        }

        @Override
        ActivationProduct invokeProduct() {
            PluginHealthProvider provider = providers.require(
                    PluginHealthProvider.class, bundleId);
            if (!bundleId.equals(provider.id())) {
                throw new IllegalStateException("Health provider id does not match bundle id");
            }
            PluginHealthSource source = Objects.requireNonNull(
                    provider.create(new PluginHealthContext(bundleId, Map.of())),
                    "PluginHealthProvider.create() must not return null");
            producedProduct = source;
            List<PluginHealthCheckDescriptor> checks =
                    PluginHealthSchema.validateAndOrder(source.checks());
            return new HealthActivationProduct(
                    new HealthSource(bundleId, contribution, source, checks, ordinal),
                    checks);
        }
    }

    private final class MetricsActivation extends Activation {
        private MetricsActivation(
                Generation owner,
                String bundleId,
                ContributionState contribution,
                int ordinal
        ) {
            super(owner, bundleId, contribution, ordinal);
        }

        @Override
        ActivationProduct invokeProduct() {
            PluginMetricsProvider provider = providers.require(
                    PluginMetricsProvider.class, bundleId);
            if (!bundleId.equals(provider.id())) {
                throw new IllegalStateException("Metrics provider id does not match bundle id");
            }
            PluginMetricsSource source = Objects.requireNonNull(
                    provider.create(new PluginMetricsContext(bundleId, Map.of())),
                    "PluginMetricsProvider.create() must not return null");
            producedProduct = source;
            List<PluginMetricDescriptor> descriptors =
                    PluginMetricSchema.validateAndOrder(source.descriptors());
            return new MetricsActivationProduct(
                    new MetricsSource(
                            bundleId, contribution, source, descriptors, ordinal),
                    descriptors);
        }
    }

    private interface ActivationProduct {
        SampleSource source();

        int healthChecks();

        int metricSeries();

        void requireStableSchema();

        void freezeSchema();

        void rollbackSchema();
    }

    private final class HealthActivationProduct implements ActivationProduct {
        private final HealthSource source;
        private final List<PluginHealthCheckDescriptor> checks;
        private boolean installedSchemaBaseline;

        private HealthActivationProduct(
                HealthSource source,
                List<PluginHealthCheckDescriptor> checks
        ) {
            this.source = source;
            this.checks = checks;
        }

        @Override
        public SampleSource source() {
            return source;
        }

        @Override
        public int healthChecks() {
            return checks.size();
        }

        @Override
        public int metricSeries() {
            return 0;
        }

        @Override
        public void requireStableSchema() {
            requireHealthSchemaStable(source.bundleId, checks);
        }

        @Override
        public void freezeSchema() {
            installedSchemaBaseline = healthSchemaBaselines.putIfAbsent(
                    source.bundleId, checks) == null;
        }

        @Override
        public void rollbackSchema() {
            if (installedSchemaBaseline) {
                healthSchemaBaselines.remove(source.bundleId, checks);
                installedSchemaBaseline = false;
            }
        }
    }

    private final class MetricsActivationProduct implements ActivationProduct {
        private final MetricsSource source;
        private final List<PluginMetricDescriptor> descriptors;
        private boolean installedSchemaBaseline;

        private MetricsActivationProduct(
                MetricsSource source,
                List<PluginMetricDescriptor> descriptors
        ) {
            this.source = source;
            this.descriptors = descriptors;
        }

        @Override
        public SampleSource source() {
            return source;
        }

        @Override
        public int healthChecks() {
            return 0;
        }

        @Override
        public int metricSeries() {
            return descriptors.size();
        }

        @Override
        public void requireStableSchema() {
            requireMetricSchemaStable(source.bundleId, descriptors);
        }

        @Override
        public void freezeSchema() {
            installedSchemaBaseline = metricSchemaBaselines.putIfAbsent(
                    source.bundleId, descriptors) == null;
        }

        @Override
        public void rollbackSchema() {
            if (installedSchemaBaseline) {
                metricSchemaBaselines.remove(source.bundleId, descriptors);
                installedSchemaBaseline = false;
            }
        }
    }

    private record DiscardedProduct(
            AutoCloseable product,
            Throwable primary,
            boolean registeredByCommit
    ) {
    }

    private final class SampleInvocation implements Runnable {
        private final Generation owner;
        private final SampleSource source;
        private final long deadline;
        private InvocationState state = InvocationState.QUEUED;
        private boolean timedOut;
        private Thread runner;
        private volatile ScheduledFuture<?> timeoutTask;

        private SampleInvocation(Generation owner, SampleSource source, long deadline) {
            this.owner = owner;
            this.source = source;
            this.deadline = deadline;
        }

        @Override
        public void run() {
            synchronized (stateMonitor) {
                if (state != InvocationState.QUEUED) {
                    return;
                }
                if (nanoTime.getAsLong() >= deadline) {
                    finishQueuedLocked(PluginOperationOutcome.TIMED_OUT);
                    source.sampleFailureLocked(source.timeoutCode());
                    scheduleDynamicPublicationLocked();
                    return;
                }
                state = InvocationState.RUNNING;
                runner = Thread.currentThread();
                owner.queuedCallbacks--;
                owner.activeCallbacks++;
                BundleState bundle = bundles.get(source.bundleId);
                bundle.sampleQueued--;
                bundle.sampleActive++;
                scheduleDynamicPublicationLocked();
            }

            Object value = null;
            Throwable failure = null;
            Throwable processFatal = null;
            boolean interruptedOnEntry = Thread.currentThread().isInterrupted();
            try {
                sampleCallback.set(Boolean.TRUE);
                value = source.sample();
            } catch (Throwable callbackFailure) {
                failure = callbackFailure;
                processFatal = LifecycleFailures.findProcessFatalReachable(callbackFailure);
            } finally {
                sampleCallback.remove();
                try {
                    synchronized (stateMonitor) {
                        try {
                            if (processFatal == null && !timedOut && generation == owner
                                    && source.inFlight == this) {
                                if (failure == null) {
                                    try {
                                        source.sampleSucceededLocked(value);
                                    } catch (InvalidSampleException invalid) {
                                        source.sampleFailureLocked(source.invalidCode());
                                        incrementLocked(bundles.get(source.bundleId),
                                                source.operation(),
                                                PluginOperationOutcome.FAILED);
                                    }
                                } else {
                                    source.sampleFailureLocked(source.failureCode());
                                    incrementLocked(bundles.get(source.bundleId),
                                            source.operation(),
                                            PluginOperationOutcome.FAILED);
                                    log.warn("Plugin telemetry sample failed "
                                                    + "(bundle={}, kind={}, errorType={})",
                                            source.bundleId, source.contribution.kind,
                                            failure.getClass().getName());
                                }
                            }
                        } finally {
                            state = InvocationState.FINISHED;
                            runner = null;
                            if (source.inFlight == this) {
                                source.inFlight = null;
                            }
                            owner.activeCallbacks--;
                            bundles.get(source.bundleId).sampleActive--;
                            stateMonitor.notifyAll();
                            ScheduledFuture<?> timeout = timeoutTask;
                            if (timeout != null) {
                                timeout.cancel(false);
                            }
                            scheduleDynamicPublicationLocked();
                        }
                    }
                } finally {
                    restoreInterruptState(interruptedOnEntry);
                }
            }
            LifecycleFailures.rethrowIfProcessFatalReachable(processFatal);
        }

        private void timeout() {
            synchronized (stateMonitor) {
                if (state == InvocationState.QUEUED) {
                    sampleExecutor.remove(this);
                    timedOut = true;
                    finishQueuedLocked(PluginOperationOutcome.TIMED_OUT);
                    source.sampleFailureLocked(source.timeoutCode());
                    scheduleDynamicPublicationLocked();
                } else if (state == InvocationState.RUNNING && !timedOut) {
                    timedOut = true;
                    source.sampleFailureLocked(source.timeoutCode());
                    incrementLocked(bundles.get(source.bundleId),
                            source.operation(), PluginOperationOutcome.TIMED_OUT);
                    if (runner != null) {
                        // Completion uses the same monitor, so the worker cannot
                        // leave this invocation and begin another before the
                        // host-issued interrupt has been delivered.
                        runner.interrupt();
                    }
                    scheduleDynamicPublicationLocked();
                }
            }
        }

        private void reject() {
            synchronized (stateMonitor) {
                if (state != InvocationState.QUEUED) {
                    return;
                }
                finishQueuedLocked(PluginOperationOutcome.REJECTED);
                source.sampleFailureLocked(source.failureCode());
                scheduleDynamicPublicationLocked();
            }
        }

        private void cancelQueuedLocked() {
            if (state != InvocationState.QUEUED) {
                return;
            }
            sampleExecutor.remove(this);
            state = InvocationState.FINISHED;
            owner.queuedCallbacks--;
            bundles.get(source.bundleId).sampleQueued--;
            if (source.inFlight == this) {
                source.inFlight = null;
            }
            ScheduledFuture<?> timeout = timeoutTask;
            if (timeout != null) {
                timeout.cancel(false);
            }
        }

        private void finishQueuedLocked(PluginOperationOutcome outcome) {
            state = InvocationState.FINISHED;
            owner.queuedCallbacks--;
            bundles.get(source.bundleId).sampleQueued--;
            if (source.inFlight == this) {
                source.inFlight = null;
            }
            incrementLocked(bundles.get(source.bundleId), source.operation(), outcome);
        }
    }

    private abstract class SampleSource {
        protected final String bundleId;
        protected final ContributionState contribution;
        private final int scheduleOrdinal;
        private SampleInvocation inFlight;
        private boolean scheduled;
        private ScheduledFuture<?> periodicTask;

        private SampleSource(
                String bundleId,
                ContributionState contribution,
                int scheduleOrdinal
        ) {
            this.bundleId = bundleId;
            this.contribution = contribution;
            this.scheduleOrdinal = scheduleOrdinal;
        }

        abstract Object sample();

        abstract void sampleSucceededLocked(Object value);

        abstract PluginOperation operation();

        abstract PluginFailureCode timeoutCode();

        abstract PluginFailureCode failureCode();

        abstract PluginFailureCode invalidCode();

        abstract void close();

        private void sampleFailureLocked(PluginFailureCode code) {
            PluginFailure next = failure(code);
            if (!contribution.stale || !contribution.failure.equals(next)) {
                contribution.lastTransition = nowEpochMillis();
            }
            contribution.stale = true;
            contribution.failure = next;
        }

        protected final void sampleSuccessLocked(PluginHealthStatus health) {
            if (contribution.stale || contribution.health != health
                    || contribution.failure.code() != PluginFailureCode.NONE) {
                contribution.lastTransition = nowEpochMillis();
            }
            contribution.health = health;
            contribution.stale = false;
            contribution.failure = PluginFailure.none();
            incrementLocked(bundles.get(bundleId), operation(),
                    PluginOperationOutcome.SUCCEEDED);
        }
    }

    private final class HealthSource extends SampleSource {
        private final PluginHealthSource source;
        private final List<PluginHealthCheckDescriptor> checks;
        private Map<String, PluginHealthStatus> lastStatuses;

        private HealthSource(
                String bundleId,
                ContributionState contribution,
                PluginHealthSource source,
                List<PluginHealthCheckDescriptor> checks,
                int scheduleOrdinal
        ) {
            super(bundleId, contribution, scheduleOrdinal);
            this.source = source;
            this.checks = checks;
            Map<String, PluginHealthStatus> initial = new TreeMap<>();
            checks.forEach(check -> initial.put(
                    check.id(), PluginHealthStatus.UNKNOWN));
            this.lastStatuses = Map.copyOf(initial);
        }

        @Override
        Object sample() {
            return source.snapshot();
        }

        @Override
        void sampleSucceededLocked(Object value) {
            if (!(value instanceof PluginHealthSnapshot snapshot)) {
                throw new InvalidSampleException();
            }
            Set<String> expected = checks.stream()
                    .map(PluginHealthCheckDescriptor::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> actual = snapshot.reports().stream()
                    .map(PluginHealthReport::checkId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!expected.equals(actual) || snapshot.reports().size() != checks.size()) {
                throw new InvalidSampleException();
            }
            PluginHealthStatus health = checks.isEmpty()
                    ? PluginHealthStatus.UNKNOWN : PluginHealthStatus.UP;
            Map<String, PluginHealthStatus> nextStatuses = new TreeMap<>();
            for (PluginHealthReport report : snapshot.reports()) {
                nextStatuses.put(report.checkId(), report.status());
                health = worseReport(health, report.status());
            }
            sampleSuccessLocked(health);
            lastStatuses = Map.copyOf(nextStatuses);
        }

        private void appendChecks(List<PluginHealthCheckRuntimeInfo> destination) {
            for (PluginHealthCheckDescriptor descriptor : checks) {
                destination.add(new PluginHealthCheckRuntimeInfo(
                        bundleId, descriptor, lastStatuses.get(descriptor.id()),
                        contribution.stale));
            }
        }

        @Override
        PluginOperation operation() {
            return PluginOperation.HEALTH_SAMPLE;
        }

        @Override
        PluginFailureCode timeoutCode() {
            return PluginFailureCode.CHECK_TIMEOUT;
        }

        @Override
        PluginFailureCode failureCode() {
            return PluginFailureCode.CHECK_FAILED;
        }

        @Override
        PluginFailureCode invalidCode() {
            return PluginFailureCode.INVALID_HEALTH_SNAPSHOT;
        }

        @Override
        void close() {
            source.close();
        }
    }

    private final class MetricsSource extends SampleSource {
        private final PluginMetricsSource source;
        private final List<PluginMetricDescriptor> descriptors;
        private Map<String, PluginMetricValue> lastValues = Map.of();

        private MetricsSource(
                String bundleId,
                ContributionState contribution,
                PluginMetricsSource source,
                List<PluginMetricDescriptor> descriptors,
                int scheduleOrdinal
        ) {
            super(bundleId, contribution, scheduleOrdinal);
            this.source = source;
            this.descriptors = descriptors;
        }

        @Override
        Object sample() {
            return source.snapshot();
        }

        @Override
        void sampleSucceededLocked(Object value) {
            if (!(value instanceof PluginMetricSnapshot snapshot)) {
                throw new InvalidSampleException();
            }
            Set<String> expected = descriptors.stream()
                    .map(PluginMetricDescriptor::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!expected.equals(snapshot.values().keySet())
                    || snapshot.values().size() != descriptors.size()) {
                throw new InvalidSampleException();
            }
            Map<String, PluginMetricValue> next = new TreeMap<>();
            for (PluginMetricDescriptor descriptor : descriptors) {
                PluginMetricValue metric = snapshot.values().get(descriptor.id());
                if (!compatible(descriptor, metric)
                        || regressed(lastValues.get(descriptor.id()), metric)) {
                    throw new InvalidSampleException();
                }
                next.put(descriptor.id(), metric);
            }
            lastValues = Map.copyOf(next);
            sampleSuccessLocked(PluginHealthStatus.UP);
        }

        private boolean compatible(PluginMetricDescriptor descriptor, PluginMetricValue value) {
            return switch (descriptor.type()) {
                case GAUGE -> value instanceof PluginGaugeValue;
                case COUNTER -> value instanceof PluginCounterValue;
                case TIMER -> value instanceof PluginTimerValue;
            };
        }

        private boolean regressed(PluginMetricValue previous, PluginMetricValue current) {
            if (previous == null) {
                return false;
            }
            if (previous instanceof PluginCounterValue oldCounter
                    && current instanceof PluginCounterValue newCounter) {
                return newCounter.total() < oldCounter.total();
            }
            if (previous instanceof PluginTimerValue oldTimer
                    && current instanceof PluginTimerValue newTimer) {
                return newTimer.count() < oldTimer.count()
                        || newTimer.totalNanos() < oldTimer.totalNanos();
            }
            return false;
        }

        private void appendSeries(List<PluginMetricSeries> destination) {
            for (PluginMetricDescriptor descriptor : descriptors) {
                PluginMetricValue value = lastValues.get(descriptor.id());
                if (value != null) {
                    destination.add(new PluginMetricSeries(
                            bundleId, descriptor, value, contribution.stale));
                }
            }
        }

        @Override
        PluginOperation operation() {
            return PluginOperation.METRICS_SAMPLE;
        }

        @Override
        PluginFailureCode timeoutCode() {
            return PluginFailureCode.METRICS_TIMEOUT;
        }

        @Override
        PluginFailureCode failureCode() {
            return PluginFailureCode.METRICS_FAILED;
        }

        @Override
        PluginFailureCode invalidCode() {
            return PluginFailureCode.INVALID_METRIC_SNAPSHOT;
        }

        @Override
        void close() {
            source.close();
        }
    }

    private static final class BundleState {
        private final String id;
        private final boolean selected;
        private final Map<String, ContributionState> contributions = new LinkedHashMap<>();
        private final EnumMap<PluginOperation, EnumMap<PluginOperationOutcome, Long>> operations =
                new EnumMap<>(PluginOperation.class);
        private int domainActive;
        private int domainQueued;
        private int sampleActive;
        private int sampleQueued;
        private int activationActive;
        private int activationQueued;

        private BundleState(String id, boolean selected) {
            this.id = id;
            this.selected = selected;
        }
    }

    private static final class ContributionState {
        private final String kind;
        private final String name;
        private final PluginTrustTier trustTier;
        private PluginLifecycleState lifecycle;
        private PluginHealthStatus health = PluginHealthStatus.UNKNOWN;
        private PluginFailure failure = PluginFailure.none();
        private boolean stale;
        private long lastTransition;

        private ContributionState(
                String kind,
                String name,
                PluginTrustTier trustTier,
                PluginLifecycleState lifecycle
        ) {
            this.kind = kind;
            this.name = name;
            this.trustTier = trustTier;
            this.lifecycle = lifecycle;
        }

        private PluginContributionRuntimeInfo info(boolean selected) {
            boolean lifecycleObserved = lifecycleObserved();
            List<PluginInstanceRuntimeInfo> instances = selected && lifecycleObserved
                    ? List.of(new PluginInstanceRuntimeInfo(
                            "node", lifecycle, health, failure, stale))
                    : List.of();
            return new PluginContributionRuntimeInfo(
                    kind, name, trustTier, lifecycleObserved, lifecycle, health,
                    failure, stale, instances);
        }

        private boolean lifecycleObserved() {
            return NODE_PLUGIN.equals(kind) || DOMAIN_API.equals(kind)
                    || HEALTH.equals(kind) || METRICS.equals(kind);
        }
    }

    private static final class Generation {
        private final long id;
        private final CompletableFuture<Void> terminal = new CompletableFuture<>();
        private final List<SampleSource> sources = new ArrayList<>();
        private final List<Activation> activations = new ArrayList<>();
        private final Deque<Activation> pendingActivations = new ArrayDeque<>();
        private int healthChecks;
        private int metricSeries;
        private int queuedCallbacks;
        private int activeCallbacks;
        private int submittedActivations;
        private int activeActivationCallbacks;
        private int activeCleanupCallbacks;
        private int commitCursor;
        private int plannedSources;
        private long activationDeadlineNanos;
        private long samplingStartedNanos;
        private boolean acceptingActivations = true;
        private boolean sealed;
        private ScheduledFuture<?> activationDeadlineTask;
        private ScheduledFuture<?> activationPumpTask;

        private Generation(long id) {
            this.id = id;
        }

        private void addActivation(Activation activation) {
            activations.add(activation);
            pendingActivations.addLast(activation);
        }
    }

    private enum ActivationState {
        PENDING,
        QUEUED,
        RUNNING,
        FINISHED
    }

    private enum InvocationState {
        QUEUED,
        RUNNING,
        FINISHED
    }

    private static final class InvalidSampleException extends RuntimeException {
        private InvalidSampleException() {
            super("Invalid plugin telemetry snapshot");
        }
    }

}
