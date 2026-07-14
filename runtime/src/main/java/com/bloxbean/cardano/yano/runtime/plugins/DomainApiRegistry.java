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
        this(Objects.requireNonNull(environment, "environment").providers(),
                environment::domainApiConfig, appChains, log,
                DEFAULT_CALLER_TIMEOUT, DEFAULT_QUEUE_CAPACITY);
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
        this.pluginProviders = Objects.requireNonNull(pluginProviders, "pluginProviders");
        this.bundleConfig = Objects.requireNonNull(bundleConfig, "bundleConfig");
        this.appChains = Objects.requireNonNull(appChains, "appChains");
        this.log = Objects.requireNonNull(log, "log");
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
            pluginProviders.registerContributionCleanup(terminal);
            Generation created = null;
            Throwable failure = null;
            try {
                created = createGeneration(terminal);
                List<DomainApiRouteInfo> routes = created.publication().routes();
                if (baselineRoutes == null) {
                    baselineRoutes = routes;
                    publication = created.publication();
                } else if (!baselineRoutes.equals(routes)) {
                    throw new IllegalStateException(
                            "Domain API route metadata changed across runtime generations");
                } else {
                    publication = created.publication();
                }
                synchronized (admissionMonitor) {
                    generation = created;
                    accepting = true;
                }
                return;
            } catch (Throwable startupFailure) {
                failure = startupFailure;
            }

            if (created != null) {
                failure = closeProducts(created.products(), failure);
            }
            terminal.complete(null);
            rethrowLifecycleFailure(failure, "Domain API startup failed");
        }
    }

    /**
     * Stop admission, cancel queued work, drain running work, close products
     * in reverse deterministic order, and complete the catalog lifetime signal.
     */
    public void sealAndAwait() {
        requireHostLifecycleCaller("stop domain APIs");
        synchronized (lifecycleMonitor) {
            Generation closing = stopAdmissionAndAwait();
            if (closing == null) {
                return;
            }
            Throwable failure = closeProducts(closing.products(), null);
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
        long deadline = System.nanoTime() + callerTimeoutNanos;
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
            throw timedOut();
        }
        if (!hostAdmissions.tryAcquire()) {
            if (!isAcceptingBundle(match.bundleId())) {
                throw new DomainApiException(DomainApiException.Code.UNAVAILABLE,
                        "Domain API service is stopped");
            }
            throw new DomainApiException(DomainApiException.Code.BUSY,
                    "Domain API host queue is full");
        }
        final Invocation invocation;
        try {
            invocation = admitInvocation(match, request);
        } catch (RuntimeException | Error admissionFailure) {
            hostAdmissions.release();
            throw admissionFailure;
        }
        try {
            invocation.bundle.lane().execute(invocation);
        } catch (RejectedExecutionException rejected) {
            invocation.cancelQueued();
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
        Generation closing = stopAdmissionAndAwait();
        if (closing == null) {
            return;
        }
        Throwable failure = closeProducts(closing.products(), null);
        closing.terminal().complete(null);
        rethrowLifecycleFailure(failure, "Domain API shutdown failed");
    }

    private Generation stopAdmissionAndAwait() {
        List<Invocation> admitted;
        synchronized (admissionMonitor) {
            accepting = false;
            admitted = List.copyOf(admittedInvocations);
        }
        admitted.forEach(Invocation::cancelForSeal);
        synchronized (admissionMonitor) {
            awaitNoAdmissions();
            Generation closing = generation;
            generation = null;
            return closing;
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
            Throwable outcome = closeProducts(products, failure);
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

    private Invocation admitInvocation(
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
            return invocation;
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

    private Throwable closeProducts(Map<String, ActiveBundle> products, Throwable current) {
        List<ActiveBundle> reverse = new ArrayList<>(products.values());
        reverse.sort(Comparator.comparing(ActiveBundle::bundleId).reversed());
        Throwable failure = current;
        for (ActiveBundle bundle : reverse) {
            try {
                bundle.api().close();
            } catch (Throwable closeFailure) {
                failure = LifecycleFailures.merge(failure, closeFailure);
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
            enterCallback();
            Throwable callbackFailure = null;
            try {
                result.complete(Objects.requireNonNull(
                        bundle.api().handle(request),
                        "DomainApi.handle() must not return null"));
            } catch (Throwable failure) {
                callbackFailure = failure;
                result.completeExceptionally(failure);
            } finally {
                exitCallback();
                synchronized (this) {
                    runner = null;
                    state = InvocationState.FINISHED;
                }
                releaseAdmission();
            }
            if (callbackFailure != null) {
                // A caller timeout may mean nobody remains to observe this
                // future. Process-fatal signals must still terminate the host
                // worker rather than being converted into an orphaned value.
                LifecycleFailures.rethrowIfProcessFatalReachable(callbackFailure);
            }
        }

        private CompletableFuture<DomainApiResponse> result() {
            return result;
        }

        private void timeout() {
            Thread interrupt = null;
            boolean cancelled = false;
            synchronized (this) {
                if (state == InvocationState.QUEUED) {
                    state = InvocationState.CANCELLED;
                    cancelled = true;
                } else if (state == InvocationState.RUNNING) {
                    interrupt = runner;
                }
            }
            if (cancelled) {
                bundle.lane().remove(this);
                releaseAdmission();
            }
            if (interrupt != null) {
                interrupt.interrupt();
            }
        }

        private void cancelQueued() {
            boolean cancelled = false;
            synchronized (this) {
                if (state == InvocationState.QUEUED) {
                    state = InvocationState.CANCELLED;
                    cancelled = true;
                }
            }
            if (cancelled) {
                releaseAdmission();
            }
        }

        private void cancelForSeal() {
            boolean cancelled = false;
            synchronized (this) {
                if (state == InvocationState.QUEUED) {
                    state = InvocationState.CANCELLED;
                    cancelled = true;
                }
            }
            if (cancelled) {
                bundle.lane().remove(this);
                result.completeExceptionally(new InvocationUnavailableException());
                releaseAdmission();
            }
        }

        private synchronized boolean isQueueable() {
            return state == InvocationState.QUEUED;
        }

        private void releaseAdmission() {
            synchronized (this) {
                if (admissionReleased) {
                    return;
                }
                admissionReleased = true;
            }
            hostAdmissions.release();
            unregisterInvocation(this);
            lease.close();
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
                next.run();
                // Do not leak a host-issued timeout interrupt (or a plugin's
                // self-interrupt) into the next bundle callback when this
                // worker continues inside the same dispatcher runnable.
                Thread.interrupted();
                synchronized (this) {
                    if (waiting.isEmpty()) {
                        scheduled = false;
                        return;
                    }
                    try {
                        hostExecutor.execute(this::drainFairly);
                        return;
                    } catch (RejectedExecutionException saturated) {
                        // The current worker still owns this bundle's serial
                        // token. Continue locally rather than stranding an
                        // already-admitted callback; the host pool remains
                        // bounded and no second callback for this bundle runs.
                    }
                }
            }
        }
    }
}
