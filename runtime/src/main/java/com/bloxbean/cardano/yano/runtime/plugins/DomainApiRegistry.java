package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiGateway;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRoute;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRouteInfo;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRouteSet;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainQueryService;
import com.bloxbean.cardano.yano.api.plugin.operations.PluginOperationOutcome;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Lifecycle-fenced, host-owned dispatcher for ADR-011.3 domain API products.
 *
 * <p>Each bundle owns a single-worker bounded lane. A caller timeout removes
 * queued work so it can never enter plugin code later. A callback that has
 * already started may be interrupted, but it retains the generation lease and
 * therefore the plugin product/class-loader lifetime until it actually
 * returns.</p>
 */
public final class DomainApiRegistry implements DomainApiGateway, AutoCloseable {
    public static final int DEFAULT_QUEUE_CAPACITY = 16;
    public static final Duration DEFAULT_CALLER_TIMEOUT = Duration.ofSeconds(2);
    public static final int DEFAULT_HOST_WORKERS = 4;
    public static final int DEFAULT_HOST_QUEUE_CAPACITY = 64;

    private static final Comparator<DomainApiRouteInfo> ROUTE_ORDER =
            Comparator.comparing(DomainApiRouteInfo::bundleId)
                    .thenComparing(info -> info.route().method())
                    .thenComparing(info -> info.route().template())
                    .thenComparing(info -> info.route().routeId());

    private final PluginProviderRegistry pluginProviders;
    private final Function<String, Map<String, Object>> bundleConfig;
    private final AppChainGateways appChains;
    private final Logger log;
    private final OperationsObserver operations;
    private final List<String> bundleIds;
    private final long callerTimeoutNanos;
    private final int queueCapacity;
    private final ThreadPoolExecutor hostExecutor;
    private final Semaphore hostAdmissions = new Semaphore(
            DEFAULT_HOST_WORKERS + DEFAULT_HOST_QUEUE_CAPACITY, true);
    private final Object lifecycleMonitor = new Object();
    private final Object admissionMonitor = new Object();
    private final ThreadLocal<Integer> callbackDepth = ThreadLocal.withInitial(() -> 0);
    private final Map<String, BundleLane> lanes = new HashMap<>();
    private final Set<Invocation> admittedInvocations = new HashSet<>();

    private volatile Publication publication = Publication.empty();
    private List<DomainApiRouteInfo> baselineRoutes;
    private Generation generation;
    private boolean accepting;
    private boolean closed;
    private int activeAdmissions;

    public DomainApiRegistry(
            PluginRuntimeEnvironment environment,
            AppChainGateways appChains,
            Logger log
    ) {
        this(environment, appChains, log, null);
    }

    public DomainApiRegistry(
            PluginRuntimeEnvironment environment,
            AppChainGateways appChains,
            Logger log,
            PluginOperationsRegistry operations
    ) {
        this(Objects.requireNonNull(environment, "environment").providers(),
                environment::domainApiConfig, appChains, log,
                DEFAULT_CALLER_TIMEOUT, DEFAULT_QUEUE_CAPACITY, operations);
    }

    /** Package-private timing seam for deterministic lifecycle tests. */
    DomainApiRegistry(
            PluginProviderRegistry pluginProviders,
            Function<String, Map<String, Object>> bundleConfig,
            AppChainGateways appChains,
            Logger log,
            Duration callerTimeout,
            int queueCapacity
    ) {
        this(pluginProviders, bundleConfig, appChains, log,
                callerTimeout, queueCapacity, OperationsObserver.NOOP);
    }

    DomainApiRegistry(
            PluginProviderRegistry pluginProviders,
            Function<String, Map<String, Object>> bundleConfig,
            AppChainGateways appChains,
            Logger log,
            Duration callerTimeout,
            int queueCapacity,
            PluginOperationsRegistry operations
    ) {
        this(pluginProviders, bundleConfig, appChains, log,
                callerTimeout, queueCapacity, observer(operations));
    }

    DomainApiRegistry(
            PluginProviderRegistry pluginProviders,
            Function<String, Map<String, Object>> bundleConfig,
            AppChainGateways appChains,
            Logger log,
            Duration callerTimeout,
            int queueCapacity,
            OperationsObserver operations
    ) {
        this.pluginProviders = Objects.requireNonNull(pluginProviders, "pluginProviders");
        this.bundleConfig = Objects.requireNonNull(bundleConfig, "bundleConfig");
        this.appChains = Objects.requireNonNull(appChains, "appChains");
        this.log = Objects.requireNonNull(log, "log");
        this.operations = Objects.requireNonNull(operations, "operations");
        Objects.requireNonNull(callerTimeout, "callerTimeout");
        if (callerTimeout.isZero() || callerTimeout.isNegative()) {
            throw new IllegalArgumentException("callerTimeout must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.callerTimeoutNanos = callerTimeout.toNanos();
        this.queueCapacity = queueCapacity;
        this.bundleIds = pluginProviders.names(DomainApiProvider.class).stream()
                .sorted()
                .toList();
        AtomicInteger threadNumber = new AtomicInteger();
        this.hostExecutor = new ThreadPoolExecutor(
                DEFAULT_HOST_WORKERS, DEFAULT_HOST_WORKERS,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(DEFAULT_HOST_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "yano-domain-api-host-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setContextClassLoader(DomainApiRegistry.class.getClassLoader());
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static OperationsObserver observer(PluginOperationsRegistry operations) {
        if (operations == null) {
            return OperationsObserver.NOOP;
        }
        return new OperationsObserver() {
            @Override
            public void domainActivating(String bundleId) {
                operations.domainActivating(bundleId);
            }

            @Override
            public void domainActive(String bundleId) {
                operations.domainActive(bundleId);
            }

            @Override
            public void domainActivationFailed(String bundleId) {
                operations.domainActivationFailed(bundleId);
            }

            @Override
            public void domainStopped(String bundleId, boolean succeeded) {
                operations.domainStopped(bundleId, succeeded);
            }

            @Override
            public void domainAdmitted(String bundleId) {
                operations.domainAdmitted(bundleId);
            }

            @Override
            public void domainStarted(String bundleId) {
                operations.domainStarted(bundleId);
            }

            @Override
            public void domainQueueFinished(String bundleId) {
                operations.domainQueueFinished(bundleId);
            }

            @Override
            public void domainCallbackFinished(String bundleId) {
                operations.domainCallbackFinished(bundleId);
            }

            @Override
            public void domainOutcome(
                    String bundleId,
                    DomainHttpMethod method,
                    PluginOperationOutcome outcome
            ) {
                operations.domainOutcome(bundleId, method, outcome);
            }
        };
    }

    /**
     * Construct and validate one complete product generation, then publish it
     * atomically. The runtime calls this only after catalog callbacks reopen.
     */
    public void resume() {
        requireHostLifecycleCaller("resume domain API admission");
        synchronized (lifecycleMonitor) {
            synchronized (admissionMonitor) {
                ensureNotClosed();
                if (accepting) {
                    return;
                }
                if (generation != null || activeAdmissions != 0) {
                    throw new IllegalStateException(
                            "Cannot resume domain APIs while a previous generation is active");
                }
            }

            CompletableFuture<Void> terminal = new CompletableFuture<>();
            try {
                pluginProviders.registerContributionCleanup(terminal);
            } catch (Throwable registrationFailure) {
                terminal.complete(null);
                rethrowLifecycleFailure(
                        registrationFailure,
                        "Domain API cleanup registration failed");
            }
            Throwable failure = observeAllActivating(null);
            Generation created = null;
            Publication previousPublication = publication;
            if (failure == null) {
                try {
                    created = createGeneration(terminal);
                    List<DomainApiRouteInfo> routes = created.publication().routes();
                    if (baselineRoutes == null) {
                        baselineRoutes = routes;
                    } else if (!baselineRoutes.equals(routes)) {
                        throw new IllegalStateException(
                                "Domain API route metadata changed across runtime generations");
                    }
                    synchronized (admissionMonitor) {
                        publication = created.publication();
                        generation = created;
                        accepting = true;
                    }
                    failure = observeAllActive(created.products().keySet(), failure);
                    if (failure == null) {
                        return;
                    }
                } catch (Throwable startupFailure) {
                    failure = LifecycleFailures.merge(failure, startupFailure);
                }
            }

            synchronized (admissionMonitor) {
                accepting = false;
                if (generation == created) {
                    generation = null;
                }
                publication = previousPublication;
            }
            failure = observeAllActivationFailed(failure);
            if (created != null) {
                failure = closeProducts(created.products(), failure, false);
            }
            terminal.complete(null);
            rethrowLifecycleFailure(failure, "Domain API startup failed");
        }
    }

    private Throwable observeAllActivating(Throwable failure) {
        Throwable outcome = failure;
        for (String bundleId : bundleIds) {
            outcome = observeOperations(outcome,
                    () -> operations.domainActivating(bundleId));
        }
        return outcome;
    }

    private Throwable observeAllActive(Set<String> activeBundleIds, Throwable failure) {
        Throwable outcome = failure;
        for (String bundleId : bundleIds) {
            if (activeBundleIds.contains(bundleId)) {
                outcome = observeOperations(outcome,
                        () -> operations.domainActive(bundleId));
            }
        }
        return outcome;
    }

    private Throwable observeAllActivationFailed(Throwable failure) {
        Throwable outcome = failure;
        for (String bundleId : bundleIds) {
            outcome = observeOperations(outcome,
                    () -> operations.domainActivationFailed(bundleId));
        }
        return outcome;
    }

    /**
     * Stop admission, cancel queued work, drain running work, close products
     * in reverse deterministic order, and complete the catalog lifetime signal.
     */
    public void sealAndAwait() {
        requireHostLifecycleCaller("stop domain APIs");
        synchronized (lifecycleMonitor) {
            StopResult stopped = stopAdmissionAndAwait();
            Generation closing = stopped.generation();
            if (closing == null) {
                rethrowLifecycleFailure(
                        stopped.failure(), "Domain API shutdown failed");
                return;
            }
            Throwable failure = closeProducts(
                    closing.products(), stopped.failure(), true);
            closing.terminal().complete(null);
            rethrowLifecycleFailure(failure, "Domain API shutdown failed");
        }
    }

    @Override
    public List<DomainApiRouteInfo> routes() {
        return publication.routes();
    }

    @Override
    public Optional<DomainApiAccess> access(
            String bundleId,
            DomainHttpMethod method,
            String relativePath
    ) {
        if (bundleId == null || method == null || relativePath == null) {
            return Optional.empty();
        }
        final String path;
        try {
            path = DomainApiRoute.validatePath(relativePath);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        return publication.match(bundleId, method, path)
                .map(match -> match.route().access());
    }

    @Override
    public DomainApiResponse dispatch(
            String bundleId,
            DomainHttpMethod method,
            String relativePath,
            Map<String, List<String>> queryParameters,
            byte[] body
    ) {
        long now = System.nanoTime();
        long deadline = now > Long.MAX_VALUE - callerTimeoutNanos
                ? Long.MAX_VALUE : now + callerTimeoutNanos;
        if (bundleId == null || method == null || relativePath == null
                || queryParameters == null || body == null) {
            throw invalidRequest("Domain API request fields must not be null");
        }
        final String path;
        try {
            path = DomainApiRoute.validatePath(relativePath);
        } catch (IllegalArgumentException invalid) {
            throw new DomainApiException(DomainApiException.Code.INVALID_REQUEST,
                    "Domain API path is invalid", invalid);
        }

        Publication current = publication;
        RouteMatch match = current.match(bundleId, method, path).orElseThrow(() ->
                new DomainApiException(DomainApiException.Code.NOT_FOUND,
                        "No domain API route matches the request"));
        if (match.route().access() == DomainApiAccess.INTERNAL) {
            throw new DomainApiException(DomainApiException.Code.NOT_FOUND,
                    "No public domain API route matches the request");
        }

        final DomainApiRequest request;
        try {
            request = new DomainApiRequest(
                    match.route().routeId(), method, path, match.pathParameters(),
                    queryParameters, body);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new DomainApiException(DomainApiException.Code.INVALID_REQUEST,
                    "Domain API request is invalid", invalid);
        }

        if (deadline - System.nanoTime() <= 0) {
            rethrowOperationsFailure(observeOperations(null,
                    () -> operations.domainOutcome(match.bundleId(), method,
                            PluginOperationOutcome.TIMED_OUT)));
            throw timedOut();
        }
        if (!hostAdmissions.tryAcquire()) {
            rethrowOperationsFailure(observeOperations(null,
                    () -> operations.domainOutcome(match.bundleId(), method,
                            PluginOperationOutcome.REJECTED)));
            if (!isAcceptingBundle(match.bundleId())) {
                throw new DomainApiException(DomainApiException.Code.UNAVAILABLE,
                        "Domain API service is stopped");
            }
            throw new DomainApiException(DomainApiException.Code.BUSY,
                    "Domain API host queue is full");
        }
        final AdmissionResult admitted;
        try {
            admitted = admitInvocation(match, request);
        } catch (RuntimeException | Error admissionFailure) {
            hostAdmissions.release();
            Throwable failure = observeOperations(admissionFailure,
                    () -> operations.domainOutcome(match.bundleId(), method,
                            PluginOperationOutcome.REJECTED));
            rethrowLifecycleFailure(failure, "Domain API admission failed");
            throw new AssertionError("unreachable");
        }
        Invocation invocation = admitted.invocation();
        if (admitted.failure() != null) {
            Throwable failure = invocation.cancelBeforeQueue(admitted.failure());
            rethrowLifecycleFailure(failure, "Domain API admission observation failed");
        }
        try {
            invocation.bundle.lane().execute(invocation);
        } catch (RejectedExecutionException rejected) {
            rethrowOperationsFailure(invocation.cancelQueued(
                    PluginOperationOutcome.REJECTED, null));
            if (!isAcceptingGeneration(invocation.bundle)) {
                throw new DomainApiException(DomainApiException.Code.UNAVAILABLE,
                        "Domain API service is stopped", rejected);
            }
            throw new DomainApiException(DomainApiException.Code.BUSY,
                    "Domain API bundle queue is full", rejected);
        }

        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            invocation.timeout();
            throw timedOut();
        }
        try {
            return invocation.result().get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            invocation.timeout();
            throw timedOut();
        } catch (InterruptedException interrupted) {
            invocation.timeout();
            Thread.currentThread().interrupt();
            throw new DomainApiException(DomainApiException.Code.TIMEOUT,
                    "Domain API request was interrupted", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            LifecycleFailures.rethrowIfProcessFatalReachable(cause);
            if (cause instanceof InvocationUnavailableException) {
                throw new DomainApiException(DomainApiException.Code.UNAVAILABLE,
                        "Domain API service is stopped");
            }
            if (cause instanceof DomainApiException domainFailure) {
                throw canonicalHandlerFailure(domainFailure);
            }
            log.warn("Domain API callback failed (bundle={}, route={}, errorType={})",
                    bundleId, match.route().routeId(), cause.getClass().getName());
            throw new DomainApiException(DomainApiException.Code.FAILED,
                    "Domain API handler failed", cause);
        }
    }

    @Override
    public void close() {
        requireHostLifecycleCaller("close domain APIs");
        synchronized (lifecycleMonitor) {
            synchronized (admissionMonitor) {
                if (closed) {
                    return;
                }
            }
            Throwable failure = null;
            try {
                sealAndAwaitWithinLifecycleMonitor();
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
            synchronized (admissionMonitor) {
                closed = true;
            }
            hostExecutor.shutdownNow();
            boolean interrupted = false;
            try {
                while (!hostExecutor.awaitTermination(1, TimeUnit.DAYS)) {
                    // All admitted invocations were drained above.
                }
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            rethrowLifecycleFailure(failure, "Domain API registry close failed");
        }
    }

    private void sealAndAwaitWithinLifecycleMonitor() {
        StopResult stopped = stopAdmissionAndAwait();
        Generation closing = stopped.generation();
        if (closing == null) {
            rethrowLifecycleFailure(
                    stopped.failure(), "Domain API shutdown failed");
            return;
        }
        Throwable failure = closeProducts(
                closing.products(), stopped.failure(), true);
        closing.terminal().complete(null);
        rethrowLifecycleFailure(failure, "Domain API shutdown failed");
    }

    private StopResult stopAdmissionAndAwait() {
        List<Invocation> admitted;
        synchronized (admissionMonitor) {
            accepting = false;
            admitted = List.copyOf(admittedInvocations);
        }
        Throwable failure = null;
        for (Invocation invocation : admitted) {
            failure = invocation.cancelForSeal(failure);
        }
        synchronized (admissionMonitor) {
            awaitNoAdmissions();
            Generation closing = generation;
            generation = null;
            return new StopResult(closing, failure);
        }
    }

    private Generation createGeneration(CompletableFuture<Void> terminal) {
        Map<String, ActiveBundle> products = new LinkedHashMap<>();
        Map<String, List<CompiledRoute>> routeTables = new LinkedHashMap<>();
        List<DomainApiRouteInfo> allRoutes = new ArrayList<>();
        try {
            for (String bundleId : bundleIds) {
                DomainApiProvider provider = pluginProviders.require(
                        DomainApiProvider.class, bundleId);
                String providerId = Objects.requireNonNull(
                        provider.id(), "DomainApiProvider.id() must not return null");
                if (!bundleId.equals(providerId)) {
                    throw new IllegalStateException("Domain API provider id '" + providerId
                            + "' does not equal bundle id '" + bundleId + "'");
                }
                Map<String, Object> config = Objects.requireNonNull(
                        bundleConfig.apply(bundleId), "bundleConfig must not return null");
                DomainApi api = Objects.requireNonNull(
                        provider.create(new DomainApiContext(config, queryService())),
                        "DomainApiProvider.create() must not return null");
                BundleLane lane = lanes.computeIfAbsent(bundleId,
                        ignored -> new BundleLane(bundleId));
                List<CompiledRoute> routes;
                try {
                    routes = compileRoutes(bundleId, api.routes());
                } catch (Throwable validationFailure) {
                    try {
                        api.close();
                    } catch (Throwable closeFailure) {
                        validationFailure = LifecycleFailures.merge(
                                validationFailure, closeFailure);
                    }
                    throw validationFailure;
                }
                ActiveBundle bundle = new ActiveBundle(bundleId, api, lane);
                products.put(bundleId, bundle);
                routeTables.put(bundleId, routes);
                routes.forEach(route -> allRoutes.add(
                        new DomainApiRouteInfo(bundleId, route.route())));
            }
        } catch (Throwable failure) {
            Throwable outcome = closeProducts(products, failure, false);
            rethrowLifecycleFailure(outcome, "Domain API product construction failed");
        }
        allRoutes.sort(ROUTE_ORDER);
        return new Generation(
                new Publication(routeTables, List.copyOf(allRoutes)),
                Map.copyOf(products),
                terminal);
    }

    private List<CompiledRoute> compileRoutes(
            String bundleId,
            List<DomainApiRoute> declared
    ) {
        try {
            return DomainApiRouteSet.validateAndOrder(declared).stream()
                    .map(CompiledRoute::new)
                    .toList();
        } catch (IllegalArgumentException | NullPointerException invalidRoutes) {
            throw new IllegalStateException("Invalid domain API routes in bundle '"
                    + bundleId + "'", invalidRoutes);
        }
    }

    private DomainQueryService queryService() {
        return new DomainQueryService() {
            @Override
            public List<String> chainIds() {
                return appChains.all().stream()
                        .map(AppChainGateway::chainId)
                        .sorted()
                        .toList();
            }

            @Override
            public AppQueryResult query(String chainId, String path, byte[] params) {
                AppChainGateway gateway = appChains.byId(chainId).orElseThrow(() ->
                        new AppQueryException(AppQueryException.Code.UNAVAILABLE,
                                "App chain is not hosted"));
                return gateway.query(path, params);
            }
        };
    }

    private AdmissionResult admitInvocation(
            RouteMatch match,
            DomainApiRequest request
    ) {
        synchronized (admissionMonitor) {
            ActiveBundle bundle = generation != null
                    ? generation.products().get(match.bundleId()) : null;
            if (closed || !accepting || bundle == null) {
                throw new DomainApiException(DomainApiException.Code.UNAVAILABLE,
                        "Domain API service is stopped");
            }
            activeAdmissions++;
            Invocation invocation = new Invocation(
                    new GenerationLease(), bundle, request);
            if (!admittedInvocations.add(invocation)) {
                activeAdmissions--;
                throw new IllegalStateException("Duplicate domain API invocation admission");
            }
            Throwable observationFailure = observeOperations(null,
                    () -> operations.domainAdmitted(bundle.bundleId()));
            return new AdmissionResult(invocation, observationFailure);
        }
    }

    private boolean isAcceptingBundle(String bundleId) {
        synchronized (admissionMonitor) {
            return accepting && generation != null
                    && generation.products().containsKey(bundleId);
        }
    }

    private boolean isAcceptingGeneration(ActiveBundle expected) {
        synchronized (admissionMonitor) {
            return accepting && generation != null
                    && generation.products().get(expected.bundleId()) == expected;
        }
    }

    private void releaseGeneration() {
        synchronized (admissionMonitor) {
            activeAdmissions--;
            if (activeAdmissions < 0) {
                throw new IllegalStateException("Domain API admission underflow");
            }
            if (activeAdmissions == 0) {
                admissionMonitor.notifyAll();
            }
        }
    }

    private void unregisterInvocation(Invocation invocation) {
        synchronized (admissionMonitor) {
            admittedInvocations.remove(invocation);
        }
    }

    /** Package-private lifecycle observation used by race-focused tests. */
    int activeAdmissionsForTesting() {
        synchronized (admissionMonitor) {
            return activeAdmissions;
        }
    }

    private void awaitNoAdmissions() {
        boolean interrupted = false;
        while (activeAdmissions != 0) {
            try {
                admissionMonitor.wait();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Throwable closeProducts(
            Map<String, ActiveBundle> products,
            Throwable current,
            boolean observeStopped
    ) {
        List<ActiveBundle> reverse = new ArrayList<>(products.values());
        reverse.sort(Comparator.comparing(ActiveBundle::bundleId).reversed());
        Throwable failure = current;
        for (ActiveBundle bundle : reverse) {
            boolean succeeded = false;
            try {
                bundle.api().close();
                succeeded = true;
            } catch (Throwable closeFailure) {
                failure = LifecycleFailures.merge(failure, closeFailure);
            } finally {
                if (observeStopped) {
                    boolean stopped = succeeded;
                    failure = observeOperations(failure,
                            () -> operations.domainStopped(
                                    bundle.bundleId(), stopped));
                }
            }
        }
        return failure;
    }

    private void requireHostLifecycleCaller(String action) {
        if (callbackDepth.get() != 0) {
            throw new IllegalStateException("Cannot " + action
                    + " from a domain API callback");
        }
    }

    private void enterCallback() {
        callbackDepth.set(callbackDepth.get() + 1);
    }

    private void exitCallback() {
        int depth = callbackDepth.get() - 1;
        if (depth == 0) {
            callbackDepth.remove();
        } else {
            callbackDepth.set(depth);
        }
    }

    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("Domain API registry is closed");
        }
    }

    private Throwable observeOperations(Throwable current, Runnable observation) {
        try {
            observation.run();
        } catch (Throwable observationFailure) {
            Throwable fatal = processFatal(observationFailure);
            if (fatal != null) {
                return LifecycleFailures.merge(current, fatal);
            }
            log.warn("Plugin operations observation failed (errorType={})",
                    observationFailure.getClass().getName());
        }
        return current;
    }

    private static Throwable processFatal(Throwable failure) {
        if (failure == null) {
            return null;
        }
        try {
            LifecycleFailures.rethrowIfProcessFatalReachable(failure);
            return null;
        } catch (Throwable fatal) {
            return fatal;
        }
    }

    private static void rethrowOperationsFailure(Throwable failure) {
        if (failure != null) {
            rethrowLifecycleFailure(failure, "Plugin operations observation failed");
        }
    }

    private static DomainApiException invalidRequest(String message) {
        return new DomainApiException(DomainApiException.Code.INVALID_REQUEST, message);
    }

    private static DomainApiException timedOut() {
        return new DomainApiException(DomainApiException.Code.TIMEOUT,
                "Domain API handler exceeded its caller deadline");
    }

    private static DomainApiException canonicalHandlerFailure(
            DomainApiException failure
    ) {
        String message = switch (failure.code()) {
            case INVALID_REQUEST -> "Domain API request is invalid";
            case NOT_FOUND -> "Domain API resource was not found";
            case BUSY -> "Domain API service is busy";
            case TIMEOUT -> "Domain API handler exceeded its caller deadline";
            case RESULT_TOO_LARGE -> "Domain API response exceeds the host size limit";
            case UNAVAILABLE -> "Domain API service is unavailable";
            case FAILED -> "Domain API handler failed";
        };
        return new DomainApiException(failure.code(), message, failure);
    }

    private static void rethrowLifecycleFailure(Throwable failure, String message) {
        if (failure == null) {
            return;
        }
        LifecycleFailures.rethrowIfProcessFatalReachable(failure);
        if (failure instanceof Error fatal) {
            throw fatal;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException(message, failure);
    }

    private record CompiledRoute(DomainApiRoute route) {
    }

    private record ActiveBundle(
            String bundleId,
            DomainApi api,
            BundleLane lane
    ) {
    }

    private record Generation(
            Publication publication,
            Map<String, ActiveBundle> products,
            CompletableFuture<Void> terminal
    ) {
    }

    private record StopResult(Generation generation, Throwable failure) {
    }

    private record AdmissionResult(Invocation invocation, Throwable failure) {
    }

    private record RouteMatch(
            String bundleId,
            DomainApiRoute route,
            Map<String, String> pathParameters
    ) {
    }

    private record Publication(
            Map<String, List<CompiledRoute>> routeTables,
            List<DomainApiRouteInfo> routes
    ) {
        private Publication {
            routeTables = Map.copyOf(routeTables);
            routes = List.copyOf(routes);
        }

        private static Publication empty() {
            return new Publication(Map.of(), List.of());
        }

        private Optional<RouteMatch> match(
                String bundleId,
                DomainHttpMethod method,
                String path
        ) {
            List<CompiledRoute> candidates = routeTables.get(bundleId);
            if (candidates == null) {
                return Optional.empty();
            }
            for (CompiledRoute candidate : candidates) {
                if (candidate.route().method() != method) {
                    continue;
                }
                Optional<Map<String, String>> parameters = candidate.route().match(path);
                if (parameters.isPresent()) {
                    return Optional.of(new RouteMatch(
                            bundleId, candidate.route(), parameters.orElseThrow()));
                }
            }
            return Optional.empty();
        }
    }

    private final class GenerationLease implements AutoCloseable {
        private boolean released;

        @Override
        public synchronized void close() {
            if (!released) {
                released = true;
                releaseGeneration();
            }
        }
    }

    private final class Invocation implements Runnable {
        private final GenerationLease lease;
        private final ActiveBundle bundle;
        private final DomainApiRequest request;
        private final CompletableFuture<DomainApiResponse> result = new CompletableFuture<>();
        private InvocationState state = InvocationState.QUEUED;
        private Thread runner;
        private boolean admissionReleased;
        private boolean operationRecorded;

        private Invocation(
                GenerationLease lease,
                ActiveBundle bundle,
                DomainApiRequest request
        ) {
            this.lease = lease;
            this.bundle = bundle;
            this.request = request;
        }

        @Override
        public void run() {
            synchronized (this) {
                if (state != InvocationState.QUEUED) {
                    return;
                }
                state = InvocationState.RUNNING;
                runner = Thread.currentThread();
            }
            Throwable lifecycleFailure = observeOperations(null,
                    () -> operations.domainStarted(bundle.bundleId()));
            DomainApiResponse response = null;
            Throwable callbackFailure = null;
            boolean callbackEntered = false;
            if (lifecycleFailure == null) {
                try {
                    enterCallback();
                    callbackEntered = true;
                    response = Objects.requireNonNull(
                            bundle.api().handle(request),
                            "DomainApi.handle() must not return null");
                } catch (Throwable failure) {
                    callbackFailure = failure;
                } finally {
                    if (callbackEntered) {
                        try {
                            exitCallback();
                        } catch (Throwable markerFailure) {
                            lifecycleFailure = LifecycleFailures.merge(
                                    lifecycleFailure, markerFailure);
                        }
                    }
                }
            }

            Throwable callbackFatal = processFatal(callbackFailure);
            if (callbackFatal != null) {
                lifecycleFailure = LifecycleFailures.merge(
                        lifecycleFailure, callbackFatal);
            } else if (lifecycleFailure == null) {
                lifecycleFailure = recordOutcome(
                        callbackFailure == null
                                ? PluginOperationOutcome.SUCCEEDED
                                : PluginOperationOutcome.FAILED,
                        lifecycleFailure);
            }
            lifecycleFailure = observeOperations(lifecycleFailure,
                    () -> operations.domainCallbackFinished(bundle.bundleId()));

            try {
                synchronized (this) {
                    runner = null;
                    state = InvocationState.FINISHED;
                }
            } catch (Throwable stateFailure) {
                lifecycleFailure = LifecycleFailures.merge(
                        lifecycleFailure, stateFailure);
            }
            lifecycleFailure = releaseAdmission(lifecycleFailure);

            if (callbackEntered && callbackFailure == null) {
                result.complete(response);
            } else {
                Throwable resultFailure = callbackFailure != null
                        ? callbackFailure : lifecycleFailure;
                if (resultFailure == null) {
                    resultFailure = new IllegalStateException(
                            "Domain API callback did not enter plugin code");
                }
                result.completeExceptionally(resultFailure);
            }
            rethrowOperationsFailure(lifecycleFailure);
        }

        private CompletableFuture<DomainApiResponse> result() {
            return result;
        }

        private void timeout() {
            boolean cancelled = false;
            boolean recordTimeout;
            synchronized (this) {
                if (state == InvocationState.QUEUED) {
                    state = InvocationState.CANCELLED;
                    cancelled = true;
                } else if (state == InvocationState.RUNNING) {
                    if (runner != null) {
                        // Completion uses this monitor, so the worker cannot
                        // advance to another callback before interruption.
                        runner.interrupt();
                    }
                }
                recordTimeout = claimOutcomeLocked();
            }
            Throwable failure = null;
            if (cancelled) {
                bundle.lane().remove(this);
                failure = observeOperations(failure,
                        () -> operations.domainQueueFinished(bundle.bundleId()));
            }
            if (recordTimeout) {
                failure = observeOperations(failure,
                        () -> operations.domainOutcome(
                                bundle.bundleId(), request.method(),
                                PluginOperationOutcome.TIMED_OUT));
            }
            if (cancelled) {
                failure = releaseAdmission(failure);
                result.completeExceptionally(timedOut());
            }
            rethrowOperationsFailure(failure);
        }

        private Throwable cancelBeforeQueue(Throwable current) {
            synchronized (this) {
                if (state != InvocationState.QUEUED) {
                    return current;
                }
                state = InvocationState.CANCELLED;
            }
            Throwable failure = observeOperations(current,
                    () -> operations.domainQueueFinished(bundle.bundleId()));
            failure = releaseAdmission(failure);
            result.completeExceptionally(failure);
            return failure;
        }

        private Throwable cancelQueued(
                PluginOperationOutcome outcome,
                Throwable current
        ) {
            boolean cancelled = false;
            synchronized (this) {
                if (state == InvocationState.QUEUED) {
                    state = InvocationState.CANCELLED;
                    cancelled = true;
                }
            }
            if (!cancelled) {
                return current;
            }
            bundle.lane().remove(this);
            Throwable failure = observeOperations(current,
                    () -> operations.domainQueueFinished(bundle.bundleId()));
            failure = recordOutcome(outcome, failure);
            failure = releaseAdmission(failure);
            result.completeExceptionally(new InvocationUnavailableException());
            return failure;
        }

        private Throwable cancelForSeal(Throwable current) {
            boolean cancelled = false;
            synchronized (this) {
                if (state == InvocationState.QUEUED) {
                    state = InvocationState.CANCELLED;
                    cancelled = true;
                }
            }
            if (!cancelled) {
                return current;
            }
            bundle.lane().remove(this);
            Throwable failure = observeOperations(current,
                    () -> operations.domainQueueFinished(bundle.bundleId()));
            failure = recordOutcome(PluginOperationOutcome.REJECTED, failure);
            failure = releaseAdmission(failure);
            result.completeExceptionally(new InvocationUnavailableException());
            return failure;
        }

        private Throwable recordOutcome(
                PluginOperationOutcome outcome,
                Throwable current
        ) {
            synchronized (this) {
                if (!claimOutcomeLocked()) {
                    return current;
                }
            }
            return observeOperations(current,
                    () -> operations.domainOutcome(
                            bundle.bundleId(), request.method(), outcome));
        }

        private boolean claimOutcomeLocked() {
            if (operationRecorded) {
                return false;
            }
            operationRecorded = true;
            return true;
        }

        private synchronized boolean isQueueable() {
            return state == InvocationState.QUEUED;
        }

        private Throwable releaseAdmission(Throwable current) {
            synchronized (this) {
                if (admissionReleased) {
                    return current;
                }
                admissionReleased = true;
            }
            Throwable failure = current;
            try {
                hostAdmissions.release();
            } catch (Throwable releaseFailure) {
                failure = LifecycleFailures.merge(failure, releaseFailure);
            }
            try {
                unregisterInvocation(this);
            } catch (Throwable unregisterFailure) {
                failure = LifecycleFailures.merge(failure, unregisterFailure);
            }
            try {
                lease.close();
            } catch (Throwable leaseFailure) {
                failure = LifecycleFailures.merge(failure, leaseFailure);
            }
            return failure;
        }
    }

    private static final class InvocationUnavailableException extends RuntimeException {
        private InvocationUnavailableException() {
            super(null, null, false, false);
        }
    }

    private enum InvocationState {
        QUEUED,
        RUNNING,
        CANCELLED,
        FINISHED
    }

    interface OperationsObserver {
        OperationsObserver NOOP = new OperationsObserver() {
        };

        default void domainActivating(String bundleId) {
        }

        default void domainActive(String bundleId) {
        }

        default void domainActivationFailed(String bundleId) {
        }

        default void domainStopped(String bundleId, boolean succeeded) {
        }

        default void domainAdmitted(String bundleId) {
        }

        default void domainStarted(String bundleId) {
        }

        default void domainQueueFinished(String bundleId) {
        }

        default void domainCallbackFinished(String bundleId) {
        }

        default void domainOutcome(
                String bundleId,
                DomainHttpMethod method,
                PluginOperationOutcome outcome
        ) {
        }
    }

    private final class BundleLane {
        private final java.util.ArrayDeque<Invocation> waiting = new java.util.ArrayDeque<>();
        private boolean scheduled;

        private BundleLane(String bundleId) {
            Objects.requireNonNull(bundleId, "bundleId");
        }

        private synchronized void execute(Invocation invocation) {
            if (!invocation.isQueueable()) {
                throw new RejectedExecutionException(
                        "Domain API invocation is no longer admitted");
            }
            int queuedBehindActiveSlot = scheduled ? waiting.size() : 0;
            if (queuedBehindActiveSlot >= queueCapacity) {
                throw new RejectedExecutionException("Domain API bundle queue is full");
            }
            waiting.addLast(invocation);
            if (!invocation.isQueueable()) {
                waiting.removeLastOccurrence(invocation);
                throw new RejectedExecutionException(
                        "Domain API invocation is no longer admitted");
            }
            if (scheduled) {
                return;
            }
            scheduled = true;
            try {
                hostExecutor.execute(this::drainFairly);
            } catch (RejectedExecutionException rejected) {
                scheduled = false;
                waiting.removeLastOccurrence(invocation);
                throw rejected;
            }
        }

        private synchronized void remove(Invocation invocation) {
            waiting.remove(invocation);
        }

        private void drainFairly() {
            while (true) {
                Invocation next;
                synchronized (this) {
                    next = waiting.pollFirst();
                    if (next == null) {
                        scheduled = false;
                        return;
                    }
                }
                Throwable invocationFailure = null;
                try {
                    next.run();
                } catch (Throwable failure) {
                    invocationFailure = failure;
                } finally {
                    // Do not leak a host-issued timeout interrupt (or a plugin's
                    // self-interrupt) into the next bundle callback when this
                    // worker continues inside the same dispatcher runnable.
                    Thread.interrupted();
                }
                boolean handedOff = false;
                boolean continueLocally = false;
                synchronized (this) {
                    if (waiting.isEmpty()) {
                        scheduled = false;
                    } else {
                        try {
                            hostExecutor.execute(this::drainFairly);
                            handedOff = true;
                        } catch (RejectedExecutionException saturated) {
                            if (invocationFailure == null) {
                                // The current worker still owns this bundle's serial
                                // token. Continue locally rather than stranding an
                                // already-admitted callback; the host pool remains
                                // bounded and no second callback for this bundle runs.
                                continueLocally = true;
                            } else {
                                // The fatal must escape this worker. Mark the lane
                                // unscheduled so a later admission can restart the
                                // existing queue instead of leaving it permanently stuck.
                                scheduled = false;
                            }
                        }
                    }
                }
                if (invocationFailure != null) {
                    rethrowLifecycleFailure(
                            invocationFailure, "Domain API invocation failed fatally");
                }
                if (handedOff || !continueLocally) {
                    return;
                }
            }
        }
    }
}
