package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutorOperationalSnapshot;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The execution plane (ADR app-layer/010 F5): discovers finalized effects
 * from the consensus-tier outbox via a persisted intake cursor, gates them on
 * finality policy, and drives {@link AppEffectExecutor} attempts with
 * per-effect retry state — no head-of-line blocking (skipped rows never
 * consume the dispatch budget; foreign partitions are filtered at intake),
 * attempt cap into the PARKED lane, and a backfill quarantine so a
 * late-enabled executor never blind-fires history.
 * <p>
 * Deliberately NOT deterministic and NOT consensus: everything here is
 * node-local bookkeeping in the {@code app_fx_runtime} CF. Discovery reads
 * only atomically-committed records (never a re-run of apply), so replay and
 * restart can never re-fire a chain-terminal effect; the at-least-once window
 * (crash between the external call and the local terminal) is closed by
 * executor idempotency on {@code idHash}, not by the runtime.
 * <p>
 * Threading: one scheduler tick thread, a bounded worker pool, plus REST
 * threads (stats/requeue). Status+queue transition pairs are serialized on
 * {@code transitionLock}; {@code closed} gates every ledger access during
 * shutdown so the RocksDB handle is never touched after {@code close()}
 * returns.
 */
final class EffectRuntime implements AutoCloseable {

    /** How close to its expiry height a CHAIN effect may still be dispatched. */
    private static final long EXPIRY_SAFETY_BLOCKS = 2;
    /** Shared best-effort join budget for cooperative plugin cleanup. */
    private static final long EXECUTOR_CLOSE_JOIN_MILLIS = 250;
    /** Bounded, secret-free diagnostic retained for an implicitly failed callback. */
    private static final int MAX_PLUGIN_FAILURE_TYPE_CHARS = 256;
    /** A slow product observation is never accepted as a fresh sample. */
    private static final long OPERATIONS_CALLBACK_BUDGET_NANOS =
            TimeUnit.MILLISECONDS.toNanos(100);
    /** Sampling faster than this adds no operational value. */
    private static final long OPERATIONS_SAMPLE_INTERVAL_NANOS =
            TimeUnit.SECONDS.toNanos(1);
    /** A previously good observation degrades after this host-owned age. */
    private static final long OPERATIONS_STALE_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** Defensive bound independent of plugin-provided numeric values. */
    private static final int MAX_OBSERVED_IN_FLIGHT = 1_000_000;
    /** Bounds sampler threads, cache entries, status rows, and meter series. */
    private static final int MAX_EXECUTOR_PRODUCTS = 256;

    /** Everything the runtime needs from configuration (effects.executor.*). */
    record Settings(boolean enabled,
                    Set<String> types,
                    long tickMs,
                    int maxParallel,
                    int maxAttempts,
                    long backoffInitialMs,
                    long backoffMaxMs,
                    long anchorMarginBlocks,
                    int maxBatch,
                    Set<String> metricsTypes) {

        Settings(boolean enabled, Set<String> types, long tickMs, int maxParallel,
                 int maxAttempts, long backoffInitialMs, long backoffMaxMs,
                 long anchorMarginBlocks, int maxBatch) {
            this(enabled, types, tickMs, maxParallel, maxAttempts, backoffInitialMs,
                    backoffMaxMs, anchorMarginBlocks, maxBatch, Set.of());
        }

        Settings {
            types = types != null ? Set.copyOf(types) : Set.of();
            metricsTypes = metricsTypes != null ? Set.copyOf(metricsTypes) : Set.of();
            if (types.size() > 256) {
                throw new IllegalArgumentException(
                        "effects.executor.types supports at most 256 types");
            }
            for (String type : types) {
                try {
                    requireEffectType(type, "effects.executor.types");
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException(
                            "effects.executor.types contains an invalid effect type");
                }
            }
            if (metricsTypes.size() > 32) {
                throw new IllegalArgumentException("effects.metrics.types supports at most 32 types");
            }
            if (metricsTypes.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("effects.metrics.types must not contain blank types");
            }
        }

        static Settings fromSettings(Map<String, String> settings) {
            boolean enabled = Boolean.parseBoolean(
                    settings.getOrDefault("effects.executor.enabled", "false"));
            Set<String> types = Set.of();
            String typesValue = settings.getOrDefault("effects.executor.types", "").trim();
            if (!typesValue.isEmpty()) {
                types = Set.copyOf(new LinkedHashSet<>(List.of(typesValue.split("\\s*,\\s*"))));
            }
            Set<String> metricsTypes = metricsTypesFrom(settings);
            return new Settings(enabled, types,
                    longOf(settings, "effects.executor.tick-ms", 2000),
                    (int) longOf(settings, "effects.executor.max-parallel", 4),
                    (int) longOf(settings, "effects.executor.max-attempts", 8),
                    longOf(settings, "effects.executor.backoff-initial-ms", 2000),
                    longOf(settings, "effects.executor.backoff-max-ms", 300_000),
                    longOf(settings, "effects.gate.anchor-margin-blocks", 0),
                    (int) longOf(settings, "effects.executor.max-batch", 256),
                    metricsTypes);
        }

        static Set<String> metricsTypesFrom(Map<String, String> settings) {
            String value = settings.getOrDefault("effects.metrics.types", "").trim();
            if (value.isEmpty()) {
                return Set.of();
            }
            Set<String> parsed = Set.copyOf(
                    new LinkedHashSet<>(List.of(value.split("\\s*,\\s*", -1))));
            if (parsed.size() > 32) {
                throw new IllegalArgumentException(
                        "effects.metrics.types supports at most 32 types");
            }
            if (parsed.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException(
                        "effects.metrics.types must not contain blank types");
            }
            return parsed;
        }

        private static long longOf(Map<String, String> settings, String key, long defaultValue) {
            String value = settings.get(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                // Name the offending key — this surfaces during subsystem start
                throw new IllegalArgumentException(
                        "Invalid numeric value for " + key + ": '" + value + "'");
            }
        }

        /** Queue rows examined per tick — far above the dispatch cap so skipped rows never starve it. */
        int scanLimit() {
            return Math.max(4096, maxBatch * 16);
        }

        /** New executions submitted per tick. */
        int dispatchCap() {
            return Math.max(1, maxParallel * 2);
        }
    }

    private final AppLedgerStore ledger;
    private final String chainId;
    private final String runtimeOwner;
    private final Settings settings;
    private final List<ExecutorBinding> executorBindings;
    private final Map<String, ExecutorBinding> declaredOwners;
    private final List<ExecutorBinding> legacyBindings;
    private final ExecutorService pool;
    private final ExecutorService operationsPool;
    private final ThreadFactory cleanupCoordinatorThreadFactory;
    private final ThreadFactory executorCloseThreadFactory;
    private final EffectRuntimeTestFaultSeam testFaultSeam;
    private final Logger log;

    /** Effects currently in flight on the worker pool (in-memory only). */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** Serializes status+queue transition PAIRS (worker terminals vs REST requeue). */
    private final Object transitionLock = new Object();
    /** Makes close() a barrier for the single scheduler tick and its ledger reads. */
    private final Object tickLock = new Object();
    /** Serializes complete scan+resolution cycles; close deliberately does not wait on it. */
    private final Object tickCycleLock = new Object();
    /** Allows concurrent API reads while making close() their teardown barrier. */
    private final ReentrantReadWriteLock apiOperations = new ReentrantReadWriteLock(true);
    /** Concurrent close callers wait for the first caller's complete fence. */
    private final Object closeLock = new Object();
    private final CompletableFuture<Void> closeFence = new CompletableFuture<>();
    /** A terminal executor callback may re-enter close without waiting on itself. */
    private final ThreadLocal<Boolean> inExecutorCloseCallback =
            ThreadLocal.withInitial(() -> false);
    private volatile Thread closeOwnerThread;
    private final AtomicBoolean executorCleanupScheduled = new AtomicBoolean();
    /** Exact-once owner for the coordinator, including a failed-handoff fallback. */
    private final AtomicBoolean executorCleanupCoordinatorClaimed = new AtomicBoolean();
    /** Infrastructure failures are reported only after the real product lifetime ends. */
    private final AtomicReference<Throwable> executorCleanupHandoffFailure = new AtomicReference<>();
    /**
     * Eventual plugin-lifecycle barrier. Unlike close(), this remains pending
     * for interrupt-resistant execute/supports/close callbacks.
     */
    private final CompletableFuture<Void> executorCleanupCompletion = new CompletableFuture<>();
    /** Tracks plugin capability callbacks that deliberately run outside tickLock. */
    private final Object pluginCallbackLock = new Object();
    private int activePluginCallbacks;
    /** Linearizes external-call admission against close setting the gate. */
    private final Object executionStartLock = new Object();
    private final AtomicLong confirmedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong parkedCount = new AtomicLong();
    private final String metricsGeneration = java.util.UUID.randomUUID().toString();
    private final Map<String, LatencyAccumulator> latencyByType = new ConcurrentHashMap<>();
    private final Set<String> warnedLegacyConflicts = ConcurrentHashMap.newKeySet();
    private final Object statsLock = new Object();
    private volatile Map<String, Object> cachedStats = Map.of();
    private volatile long statsCachedAt;
    private volatile boolean closed;
    private volatile String lastError;

    EffectRuntime(AppLedgerStore ledger, String chainId, Settings settings,
                  List<AppEffectExecutor> executors,
                  Map<String, Map<String, String>> executorConfigs, Logger log) {
        this(ledger, chainId, settings, executors, executorConfigs,
                AppChainSubsystem.effectRuntimeOwner("test:" + chainId, settings.types()), log);
    }

    EffectRuntime(AppLedgerStore ledger, String chainId, Settings settings,
                  List<AppEffectExecutor> executors,
                  Map<String, Map<String, String>> executorConfigs,
                  String runtimeOwner, Logger log) {
        this(ledger, chainId, settings, executors, executorConfigs, runtimeOwner, log,
                task -> {
                    Thread thread = new Thread(task,
                            "app-chain-fx-executor-cleanup-coordinator-" + chainId);
                    thread.setDaemon(true);
                    return thread;
                });
    }

    EffectRuntime(AppLedgerStore ledger, String chainId, Settings settings,
                  List<AppEffectExecutor> executors,
                  Map<String, Map<String, String>> executorConfigs,
                  Map<String, ExecutorSource> executorSources,
                  String runtimeOwner, Logger log) {
        this(ledger, chainId, settings, executors, executorConfigs, executorSources,
                runtimeOwner, log, task -> {
                    Thread thread = new Thread(task,
                            "app-chain-fx-executor-cleanup-coordinator-" + chainId);
                    thread.setDaemon(true);
                    return thread;
                }, task -> {
                    Thread thread = new Thread(task,
                            "app-chain-fx-executor-close-" + chainId);
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** Package-private seam for coordinator hand-off lifecycle regressions. */
    EffectRuntime(AppLedgerStore ledger, String chainId, Settings settings,
                  List<AppEffectExecutor> executors,
                  Map<String, Map<String, String>> executorConfigs,
                  String runtimeOwner, Logger log,
                  ThreadFactory cleanupCoordinatorThreadFactory) {
        this(ledger, chainId, settings, executors, executorConfigs, runtimeOwner, log,
                cleanupCoordinatorThreadFactory, task -> {
                    Thread thread = new Thread(task, "app-chain-fx-executor-close-" + chainId);
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** Package-private seam for per-product close hand-off lifecycle regressions. */
    EffectRuntime(AppLedgerStore ledger, String chainId, Settings settings,
                  List<AppEffectExecutor> executors,
                  Map<String, Map<String, String>> executorConfigs,
                  String runtimeOwner, Logger log,
                  ThreadFactory cleanupCoordinatorThreadFactory,
                  ThreadFactory executorCloseThreadFactory) {
        this(ledger, chainId, settings, executors, executorConfigs, Map.of(),
                runtimeOwner, log, cleanupCoordinatorThreadFactory,
                executorCloseThreadFactory);
    }

    private EffectRuntime(AppLedgerStore ledger, String chainId, Settings settings,
                  List<AppEffectExecutor> executors,
                  Map<String, Map<String, String>> executorConfigs,
                  Map<String, ExecutorSource> executorSources,
                  String runtimeOwner, Logger log,
                  ThreadFactory cleanupCoordinatorThreadFactory,
                  ThreadFactory executorCloseThreadFactory) {
        this.ledger = ledger;
        this.chainId = chainId;
        this.runtimeOwner = runtimeOwner;
        this.settings = settings;
        this.executorBindings = snapshotBindings(executors, executorConfigs, executorSources);
        Ownership ownership = buildOwnership(executorBindings, settings.types());
        this.declaredOwners = ownership.declaredOwners();
        this.legacyBindings = ownership.legacyBindings();
        this.log = log;
        this.testFaultSeam = EffectRuntimeTestFaultSeam.fromSystemProperties(log);
        this.cleanupCoordinatorThreadFactory = Objects.requireNonNull(
                cleanupCoordinatorThreadFactory, "cleanupCoordinatorThreadFactory");
        this.executorCloseThreadFactory = Objects.requireNonNull(
                executorCloseThreadFactory, "executorCloseThreadFactory");
        AppLedgerStore.FxRuntimeBinding binding = ledger.bindFxRuntimeOwner(runtimeOwner);
        if (binding.discardedState()) {
            log.warn("App-chain '{}' effect runtime owner changed from {} to {} — discarded "
                            + "foreign/legacy node-local statuses, leases, queue and intake cursor; "
                            + "historical open effects will be QUARANTINED",
                    chainId, binding.previousOwner() != null ? binding.previousOwner() : "<unowned>",
                    "v1:" + runtimeOwner);
        } else if (binding.ownerChanged()) {
            log.info("App-chain '{}' effect runtime bound to {}", chainId, "v1:" + runtimeOwner);
        }
        int workerCount = Math.max(1, settings.maxParallel());
        int queuedCapacity = settings.dispatchCap() - workerCount;
        java.util.concurrent.BlockingQueue<Runnable> workQueue = queuedCapacity > 0
                ? new ArrayBlockingQueue<>(queuedCapacity)
                : new SynchronousQueue<>();
        this.pool = new ThreadPoolExecutor(workerCount, workerCount,
                0L, TimeUnit.MILLISECONDS, workQueue, r -> {
            Thread t = new Thread(r, "app-chain-fx-worker-" + chainId);
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.operationsPool = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r,
                    "app-chain-fx-operations-sampler-" + chainId);
            thread.setDaemon(true);
            return thread;
        });
        try {
            initializeCursor();
            ledger.fxEnsureResultReadyIndex();
            initializeLatencyBuckets();
        } catch (Throwable constructionFailure) {
            // The caller still owns executor products until this constructor
            // returns, but this internal worker pool is already ours. Do not
            // leak it when RocksDB initialization/binding fails.
            pool.shutdownNow();
            operationsPool.shutdownNow();
            throw constructionFailure;
        }
    }

    private record ExecutorBinding(
            int index,
            AppEffectExecutor executor,
            String id,
            Set<String> declaredTypes,
            ExecutorSource source,
            Map<String, String> config,
            AtomicBoolean closeClaimed,
            AtomicBoolean closeTerminated,
            CountDownLatch closeTerminal,
            AtomicReference<Throwable> closeFailure,
            AtomicBoolean operationsInFlight,
            AtomicLong operationsStartedNanos,
            AtomicLong operationsSampledNanos,
            AtomicReference<EffectExecutorOperationalSnapshot> operationsSnapshot
    ) {
    }

    record ExecutorSource(String bundleId, String scheme) {
        ExecutorSource {
            bundleId = boundedDiagnostic(bundleId, "direct");
            scheme = boundedDiagnostic(scheme, "direct");
        }

        private static String boundedDiagnostic(String value, String fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            String trimmed = value.trim();
            if (trimmed.length() > 128) {
                return fallback;
            }
            for (int index = 0; index < trimmed.length(); index++) {
                char character = trimmed.charAt(index);
                if (!isAsciiIdentifierStart(character)
                        && character != '.' && character != '_' && character != '-'
                        && character != ':' && character != '+') {
                    return fallback;
                }
            }
            return trimmed;
        }
    }

    private record Ownership(
            Map<String, ExecutorBinding> declaredOwners,
            List<ExecutorBinding> legacyBindings
    ) {
    }

    private static List<ExecutorBinding> snapshotBindings(
            List<AppEffectExecutor> executors,
            Map<String, Map<String, String>> executorConfigs,
            Map<String, ExecutorSource> executorSources) {
        if (executors.size() > MAX_EXECUTOR_PRODUCTS) {
            throw new IllegalArgumentException(
                    "Effect Runtime supports at most 256 executor products per chain");
        }
        List<ExecutorBinding> bindings = new ArrayList<>(executors.size());
        for (int i = 0; i < executors.size(); i++) {
            AppEffectExecutor executor = Objects.requireNonNull(
                    executors.get(i), "executor");
            String id = java.util.Objects.requireNonNull(executor.id(), "executor id");
            Set<String> declaredTypes = snapshotDeclaredTypes(executor.effectTypes(), id);
            Map<String, String> source = executorConfigs.get(id);
            // Map.copyOf snapshots the mutable inner config and exposes an
            // immutable view to every execution context.
            Map<String, String> config = source != null ? Map.copyOf(source) : Map.of();
            bindings.add(new ExecutorBinding(
                    i,
                    executor,
                    id,
                    declaredTypes,
                    executorSources.getOrDefault(id,
                            new ExecutorSource("direct", "direct")),
                    config,
                    new AtomicBoolean(),
                    new AtomicBoolean(),
                    new CountDownLatch(1),
                    new AtomicReference<>(),
                    new AtomicBoolean(),
                    new AtomicLong(),
                    new AtomicLong(),
                    new AtomicReference<>(EffectExecutorOperationalSnapshot.unknown())));
        }
        return List.copyOf(bindings);
    }

    private static Set<String> snapshotDeclaredTypes(Set<String> types, String executorId) {
        if (types == null) {
            throw new IllegalArgumentException("Effect executor '" + executorId
                    + "' returned a null effectTypes declaration");
        }
        if (types.size() > 64) {
            throw new IllegalArgumentException("Effect executor '" + executorId
                    + "' declares more than 64 effect types");
        }
        Set<String> snapshot = new java.util.TreeSet<>();
        for (String type : types) {
            snapshot.add(requireEffectType(type, executorId));
        }
        return Set.copyOf(snapshot);
    }

    private static String requireEffectType(String type, String executorId) {
        if (type == null || type.isEmpty() || !type.equals(type.trim())
                || type.length() > 128 || type.charAt(0) == '~'
                || !isAsciiIdentifierStart(type.charAt(0))) {
            throw invalidEffectType(executorId);
        }
        for (int index = 1; index < type.length(); index++) {
            char character = type.charAt(index);
            if (!isAsciiIdentifierStart(character)
                    && character != '.' && character != '_' && character != '-'
                    && character != ':' && character != '+') {
                throw invalidEffectType(executorId);
            }
        }
        return type;
    }

    private static IllegalArgumentException invalidEffectType(String executorId) {
        return new IllegalArgumentException("Effect executor '" + executorId
                + "' declares an invalid effect type; expected 1-128 ASCII identifier "
                + "characters and no reserved '~' prefix");
    }

    private static boolean isAsciiIdentifierStart(char value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9';
    }

    private static Ownership buildOwnership(
            List<ExecutorBinding> bindings,
            Set<String> configuredTypes
    ) {
        Map<String, List<ExecutorBinding>> candidates = new java.util.TreeMap<>();
        List<ExecutorBinding> legacy = new ArrayList<>();
        for (ExecutorBinding binding : bindings) {
            if (binding.declaredTypes().isEmpty()) {
                legacy.add(binding);
                continue;
            }
            for (String type : binding.declaredTypes()) {
                if (configuredTypes.isEmpty() || configuredTypes.contains(type)) {
                    candidates.computeIfAbsent(type, ignored -> new ArrayList<>())
                            .add(binding);
                }
            }
        }

        List<String> conflicts = new ArrayList<>();
        candidates.forEach((type, owners) -> {
            if (owners.size() > 1) {
                String joined = owners.stream()
                        .map(owner -> "bundle=" + owner.source().bundleId()
                                + ", scheme=" + owner.source().scheme()
                                + ", executor=" + owner.id())
                        .collect(java.util.stream.Collectors.joining("; "));
                conflicts.add("type='" + type + "' [" + joined + "]");
            }
        });
        if (!conflicts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Duplicate declarative effect ownership: " + String.join(", ", conflicts));
        }

        Map<String, ExecutorBinding> owners = new LinkedHashMap<>();
        candidates.forEach((type, values) -> owners.put(type, values.getFirst()));
        return new Ownership(Map.copyOf(owners), List.copyOf(legacy));
    }

    private static final class LatencyAccumulator {
        final AtomicLong count = new AtomicLong();
        final AtomicLong totalMillis = new AtomicLong();
    }

    private void initializeLatencyBuckets() {
        if (settings.metricsTypes().isEmpty()) {
            latencyByType.put("all", new LatencyAccumulator());
            return;
        }
        new java.util.TreeSet<>(settings.metricsTypes())
                .forEach(type -> latencyByType.put(type, new LatencyAccumulator()));
        latencyByType.put("other", new LatencyAccumulator());
    }

    Settings settings() {
        return settings;
    }

    boolean isClosed() {
        return closed;
    }

    /**
     * Completes only after every admitted plugin callback has ended and every
     * executor close callback has returned. The stage completes exceptionally
     * when cleanup callbacks fail, but never before those callbacks terminate.
     */
    CompletionStage<Void> closeCompletion() {
        return executorCleanupCompletion.minimalCompletionStage();
    }

    /**
     * First enablement (no cursor): start at the current tip and QUARANTINE
     * historical open effects owned by this executor partition — unexecuted
     * obligations need an explicit operator requeue, never a blind backfill
     * (ADR-010 F10). Foreign partitions remain entirely absent from this
     * node's runtime tier.
     */
    private void initializeCursor() {
        if (ledger.fxIntakeCursor(-1) >= 0) {
            return;
        }
        long tip = ledger.tipHeight();
        List<long[]> open = ledger.fxOpenRecordKeysUpTo(tip);
        int quarantined = 0;
        for (long[] key : open) {
            EffectRecord record = ledger.fxRecord(key[0], (int) key[1]).orElse(null);
            if (record == null || !ownsType(record.type())) {
                continue;
            }
            ledger.fxRuntimePutStatus(key[0], (int) key[1], FxStatusRecord.quarantined());
            quarantined++;
        }
        ledger.fxPutIntakeCursor(tip);
        if (quarantined > 0) {
            log.warn("App-chain '{}' effect executor enabled with {} historical open effect(s) — "
                    + "QUARANTINED; review and requeue via the effects admin surface",
                    chainId, quarantined);
        }
    }

    /** One scheduler tick: intake new blocks, then gate + dispatch eligible effects. */
    void tick() {
        synchronized (tickCycleLock) {
            try {
                List<DispatchEntry> candidates;
                synchronized (tickLock) {
                    if (closed) {
                        return;
                    }
                    intake();
                    candidates = !closed ? dispatchCandidates() : List.of();
                }
                // Sampling is asynchronous and single-flight per product. It
                // cannot delay intake, routing, execution, status, or metrics.
                scheduleOperationsSamples();
                // supports() is plugin code. Resolve candidates only after the
                // scheduler has left its ledger barrier so a non-cooperative
                // plugin cannot make close() wait indefinitely.
                resolveAndSubmit(candidates);
            } catch (Throwable failure) {
                if (!closed || isFatal(failure)) {
                    reportFailure("runtime tick", failure);
                }
                rethrowIfFatal(failure);
            }
        }
    }

    private void scheduleOperationsSamples() {
        if (closed || executorBindings.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        for (ExecutorBinding binding : executorBindings) {
            long lastStarted = binding.operationsStartedNanos().get();
            if (lastStarted != 0 && now - lastStarted < OPERATIONS_SAMPLE_INTERVAL_NANOS) {
                continue;
            }
            if (!binding.operationsInFlight().compareAndSet(false, true)) {
                continue;
            }
            binding.operationsStartedNanos().set(now);
            try {
                operationsPool.execute(() -> sampleOperations(binding, now));
            } catch (RejectedExecutionException rejected) {
                binding.operationsInFlight().set(false);
            }
        }
    }

    private void sampleOperations(ExecutorBinding binding, long startedNanos) {
        boolean admitted = beginPluginCallback();
        if (!admitted) {
            binding.operationsInFlight().set(false);
            return;
        }
        try {
            EffectExecutorOperationalSnapshot sampled = null;
            Throwable failure = null;
            try {
                sampled = binding.executor().operationalSnapshot();
            } catch (Throwable callbackFailure) {
                failure = callbackFailure;
            } finally {
                endPluginCallback();
            }

            long completedNanos = System.nanoTime();
            if (failure != null) {
                rethrowIfFatal(failure);
                reportFailure("executor '" + binding.id() + "' operational snapshot", failure);
                publishOperations(binding, callbackFailureSnapshot(
                        binding.operationsSnapshot().get(),
                        EffectExecutorOperationalSnapshot.FailureCode.CALLBACK_FAILED),
                        completedNanos);
                return;
            }
            if (completedNanos - startedNanos > OPERATIONS_CALLBACK_BUDGET_NANOS) {
                publishOperations(binding, callbackFailureSnapshot(
                        binding.operationsSnapshot().get(),
                        EffectExecutorOperationalSnapshot.FailureCode.CALLBACK_TIMEOUT),
                        completedNanos);
                return;
            }
            try {
                publishOperations(binding, sanitizeOperations(
                        sampled, binding.operationsSnapshot().get()), completedNanos);
            } catch (RuntimeException invalid) {
                publishOperations(binding, callbackFailureSnapshot(
                        binding.operationsSnapshot().get(),
                        EffectExecutorOperationalSnapshot.FailureCode.CALLBACK_INVALID),
                        completedNanos);
            }
        } finally {
            // Keep the single-flight gate held through validation/publication,
            // so an older completion can never overwrite a newer sample.
            binding.operationsInFlight().set(false);
        }
    }

    private void publishOperations(ExecutorBinding binding,
                                   EffectExecutorOperationalSnapshot snapshot,
                                   long sampledNanos) {
        synchronized (executionStartLock) {
            if (closed) {
                return;
            }
            binding.operationsSnapshot().set(snapshot);
            binding.operationsSampledNanos().set(sampledNanos);
            statsCachedAt = 0;
        }
    }

    private static EffectExecutorOperationalSnapshot sanitizeOperations(
            EffectExecutorOperationalSnapshot current,
            EffectExecutorOperationalSnapshot previous) {
        Objects.requireNonNull(current, "executor operational snapshot");
        if (current.inFlight() > MAX_OBSERVED_IN_FLIGHT
                || current.attempts() < previous.attempts()
                || current.successes() < previous.successes()
                || current.retryableFailures() < previous.retryableFailures()
                || current.terminalFailures() < previous.terminalFailures()) {
            throw new IllegalArgumentException("invalid executor operational snapshot");
        }
        return new EffectExecutorOperationalSnapshot(
                current.readiness(), current.attempts(), current.successes(),
                current.retryableFailures(), current.terminalFailures(), current.inFlight(),
                current.lastSuccessAge(), current.lastFailureAge(), current.failureCode());
    }

    private static EffectExecutorOperationalSnapshot callbackFailureSnapshot(
            EffectExecutorOperationalSnapshot previous,
            EffectExecutorOperationalSnapshot.FailureCode failureCode) {
        return new EffectExecutorOperationalSnapshot(
                EffectExecutorOperationalSnapshot.Readiness.DEGRADED,
                previous.attempts(), previous.successes(), previous.retryableFailures(),
                previous.terminalFailures(), 0,
                previous.lastSuccessAge(), previous.lastFailureAge(), failureCode);
    }

    private void intake() {
        if (closed) {
            return;
        }
        long tip = ledger.tipHeight();
        if (closed) {
            return;
        }
        long cursor = ledger.fxIntakeCursor(0);
        while (cursor < tip && !closed) {
            long next = cursor + 1;
            for (EffectRecord record : ledger.fxRecordsAt(next)) {
                synchronized (transitionLock) {
                    if (closed) {
                        return;
                    }
                    if (ledger.fxClosed(record.height(), record.ordinal())) {
                        continue; // e.g. expired in the same block span
                    }
                    if (!ownsType(record.type())) {
                        continue; // another executor node's partition — never enqueued here
                    }
                    // close() can deliberately stop intake between records
                    // without advancing this height's cursor. Re-intake must
                    // preserve terminal/leased progress and restore a missing
                    // row for any still-executable status.
                    FxStatusRecord status = ledger.fxRuntimeStatus(
                            record.height(), record.ordinal()).orElse(null);
                    if (status == null) {
                        status = FxStatusRecord.pending();
                        ledger.fxRuntimePutStatus(record.height(), record.ordinal(), status);
                    }
                    if (status.executable()
                            && !ledger.fxQueueExists(record.height(), record.ordinal())) {
                        ledger.fxQueuePut(record.height(), record.ordinal());
                    }
                    statsCachedAt = 0;
                }
            }
            if (closed) {
                return;
            }
            ledger.fxPutIntakeCursor(next);
            cursor = next;
        }
    }

    private record DispatchEntry(long height, int ordinal, EffectRecord candidate) {
    }

    /** Ledger-only eligibility pass. No plugin callback may be added here. */
    private List<DispatchEntry> dispatchCandidates() {
        if (closed || executorBindings.isEmpty()) {
            return List.of(); // external-only mode: claim()/report() drive the queue
        }
        long tip = ledger.tipHeight();
        if (closed) {
            return List.of();
        }
        long anchored = ledger.metaLong("anchor_last_height", 0L);
        long now = System.currentTimeMillis();
        List<DispatchEntry> candidates = new ArrayList<>();
        long[] cursor = ledger.fxDispatchCursor().orElse(null);
        for (long[] entry : ledger.fxQueueScanAfter(settings.scanLimit(), cursor)) {
            if (closed || inFlight.size() >= settings.dispatchCap()) {
                break;
            }
            long height = entry[0];
            int ordinal = (int) entry[1];
            int scannedIndex = candidates.size();
            // Null candidate means this position was fully classified by the
            // ledger-only pass and is safe for the processed-prefix cursor.
            candidates.add(new DispatchEntry(height, ordinal, null));
            String key = height + "/" + ordinal;
            if (inFlight.contains(key)) {
                continue;
            }
            if (ledger.fxClosed(height, ordinal)) {
                ledger.fxQueueDelete(height, ordinal); // chain terminal won the race (expiry)
                continue;
            }
            EffectRecord record = ledger.fxRecord(height, ordinal).orElse(null);
            if (record == null) {
                log.error("App-chain '{}' effect queue references missing record {}/{} — "
                        + "fx CF inconsistent; rebuild by replay", chainId, height, ordinal);
                ledger.fxQueueDelete(height, ordinal);
                continue;
            }
            if (!ownsType(record.type())) {
                // Defensive cleanup for legacy/corrupt runtime rows. Intake
                // and owner rebinding normally prevent a foreign partition
                // from reaching this queue at all.
                ledger.fxQueueDelete(height, ordinal);
                continue;
            }
            if (record.gate() == FinalityGate.ZK_SETTLED
                    && height > ledger.metaLong("zk_settled_height", 0L)) {
                continue; // awaits an accepted validity proof (ADR-006 E7.x wires the HWM)
            }
            if (record.gate() == FinalityGate.L1_ANCHORED
                    && height > anchored - settings.anchorMarginBlocks()) {
                continue; // wait for the anchor high-water-mark (row stays queued)
            }
            if (record.expiryHeight() > 0 && record.expiryHeight() <= tip + EXPIRY_SAFETY_BLOCKS) {
                continue; // too close to deterministic expiry — let the sweep close it
            }
            FxStatusRecord status = ledger.fxRuntimeStatus(height, ordinal)
                    .orElse(FxStatusRecord.pending());
            if (!status.executable()) {
                // Orphan row (crash between a terminal status write and the
                // queue delete) — the status list, not the queue, tracks it now
                ledger.fxQueueDelete(height, ordinal);
                continue;
            }
            if (status.nextAttemptAt() > now) {
                continue;
            }
            candidates.set(scannedIndex, new DispatchEntry(height, ordinal, record));
        }
        return candidates;
    }

    /** Plugin-only resolution plus in-memory/pool admission; never touches the ledger. */
    private void resolveAndSubmit(List<DispatchEntry> candidates) {
        int dispatched = 0;
        DispatchEntry lastProcessed = null;
        try {
            for (DispatchEntry entry : candidates) {
                EffectRecord record = entry.candidate();
                if (record == null) {
                    lastProcessed = entry;
                    continue; // ledger-ineligible/deleted: fully processed
                }
                if (closed || dispatched >= settings.dispatchCap()
                        || inFlight.size() >= settings.dispatchCap()) {
                    break; // never advance the cursor past this unresolved row
                }
                ExecutorBinding binding = find(record.type());
                // A supports() callback may have blocked while close completed.
                // Never turn its stale answer into a worker submission.
                if (closed) {
                    break;
                }
                if (binding == null) {
                    lastProcessed = entry;
                    continue; // unsupported: fully resolved for this pass
                }
                String key = record.height() + "/" + record.ordinal();
                if (!inFlight.add(key)) {
                    lastProcessed = entry; // already admitted by the serialized prior cycle
                    continue;
                }
                if (closed) {
                    inFlight.remove(key);
                    break;
                }
                try {
                    pool.execute(() -> run(key, record, binding));
                    dispatched++;
                    lastProcessed = entry;
                    statsCachedAt = 0;
                } catch (RejectedExecutionException e) {
                    // close() may race admission. Keep the cursor before this
                    // row so the durable queue retries it after restart.
                    inFlight.remove(key);
                    statsCachedAt = 0;
                    break;
                }
            }
        } finally {
            persistDispatchCursor(lastProcessed);
        }
    }

    private void persistDispatchCursor(DispatchEntry lastProcessed) {
        if (lastProcessed == null) {
            return;
        }
        synchronized (tickLock) {
            if (!closed) {
                ledger.fxPutDispatchCursor(lastProcessed.height(), lastProcessed.ordinal());
            }
        }
    }

    private void run(String key, EffectRecord record, ExecutorBinding binding) {
        long height = record.height();
        int ordinal = record.ordinal();
        try {
            ExecutionPermit permit = executionPreflight(record, binding);
            if (permit == null) {
                return;
            }
            FxStatusRecord before = permit.status();
            EffectExecution outcome;
            try {
                EffectExecutionContext context = contextFor(before.attempts() + 1,
                        binding, before.submittedRef(), permit.tipHeight(), permit.anchoredHeight());
                if (!beginExternalExecution()) {
                    return; // final fence immediately before the external call
                }
                outcome = binding.executor().execute(context,
                        PendingEffect.of(record));
            } catch (Throwable failure) {
                rethrowIfFatal(failure);
                String reason = failureType(failure);
                reportFailure("executor '" + binding.id() + "' execute", failure);
                outcome = EffectExecution.failed(reason, true);
            }
            testFaultSeam.afterConfirmedBeforePersistence(
                    chainId, runtimeOwner, record, outcome);
            if (closed) {
                return; // never touch the ledger after close() — re-attempted on restart
            }
            synchronized (transitionLock) {
                if (closed) {
                    return;
                }
                // Steal-in-flight fence (M4 review): another actor (external
                // report racing the dispatch window, operator action) may have
                // written a terminal while we executed — never overwrite it
                FxStatusRecord current = ledger.fxRuntimeStatus(height, ordinal).orElse(before);
                if (ledger.fxClosed(height, ordinal) || current.locallyTerminal()
                        || current.status() == FxStatusRecord.PARKED
                        || current.status() == FxStatusRecord.QUARANTINED) {
                    log.info("App-chain '{}' effect {} outcome dropped — terminal already "
                            + "recorded by another actor (idempotency absorbs the duplicate "
                            + "attempt)", chainId, key);
                    return;
                }
                switch (outcome) {
                    case EffectExecution.Confirmed confirmed -> {
                        ledger.fxRuntimeComplete(height, ordinal,
                                before.done(clampRef(confirmed.externalRef()),
                                        confirmed.detailHash()),
                                record.result() == com.bloxbean.cardano.yano.api.appchain.effects
                                        .ResultPolicy.CHAIN);
                        recordTerminal(record, "confirmed");
                        lastError = null;
                        // ResultPolicy.CHAIN outcomes are injected as ~fx/result in
                        // FX-M3; until then the local DONE record holds the ref and
                        // the on-chain effect stays open (may still EXPIRE).
                    }
                    case EffectExecution.Failed failed -> {
                        if (!failed.retryable() && record.result()
                                == com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy.CHAIN) {
                            // Definitive external answer for a CHAIN effect: a
                            // local terminal whose FAILED outcome gets injected
                            // as ~fx/result so the application learns of it
                            ledger.fxRuntimeComplete(height, ordinal,
                                    before.doneFailed(failed.reason()), true);
                            recordTerminal(record, "failed");
                            lastError = failed.reason();
                        } else if (!failed.retryable()
                                || before.attempts() + 1 >= settings.maxAttempts()) {
                            ledger.fxRuntimeComplete(height, ordinal,
                                    before.parked(failed.reason()), false);
                            recordTerminal(record, "parked");
                            lastError = failed.reason();
                            log.warn("App-chain '{}' effect {} PARKED after {} attempt(s): {}",
                                    chainId, key, before.attempts() + 1, failed.reason());
                        } else {
                            long delay = Math.min(settings.backoffMaxMs(),
                                    settings.backoffInitialMs() << Math.min(20, before.attempts()));
                            ledger.fxRuntimePutStatus(height, ordinal,
                                    before.retry(failed.reason(), System.currentTimeMillis() + delay));
                            lastError = failed.reason();
                        }
                    }
                    case EffectExecution.Submitted submitted ->
                            // Pace re-polls: probing an external system every
                            // tick hammers it for long confirmations
                            ledger.fxRuntimePutStatus(height, ordinal,
                                    before.submitted(submitted.externalRef(),
                                            System.currentTimeMillis() + settings.backoffInitialMs()));
                    case EffectExecution.Retry retry ->
                            ledger.fxRuntimePutStatus(height, ordinal,
                                    before.deferred("not ready",
                                            System.currentTimeMillis() + retry.notBefore().toMillis()));
                }
            }
        } catch (Throwable failure) {
            if (!closed || isFatal(failure)) {
                reportFailure("effect " + key + " handling", failure);
            }
            rethrowIfFatal(failure);
        } finally {
            inFlight.remove(key);
            statsCachedAt = 0;
        }
    }

    /**
     * Revalidate durable eligibility at the last possible point before an
     * irreversible external call. A task may have waited in the bounded pool
     * while the chain incorporated a result/expiry or an operator changed its
     * local status. The scheduling snapshot is therefore never authority to
     * execute.
     */
    private record ExecutionPermit(FxStatusRecord status, long tipHeight, long anchoredHeight) {
    }

    private ExecutionPermit executionPreflight(EffectRecord scheduled,
                                                ExecutorBinding binding) {
        // A legacy supports() callback is plugin code and may block. Keep it
        // outside the ledger section, then recheck closed before the first
        // store access. Declarative ownership is an immutable construction-
        // time snapshot and never re-enters the product.
        if (closed || !ownsType(scheduled.type())
                || !bindingOwns(binding, scheduled.type())) {
            return null;
        }
        synchronized (transitionLock) {
            if (closed) {
                return null;
            }
            long height = scheduled.height();
            int ordinal = scheduled.ordinal();
            if (ledger.fxClosed(height, ordinal)) {
                ledger.fxQueueDelete(height, ordinal);
                return null;
            }
            EffectRecord currentRecord = ledger.fxRecord(height, ordinal).orElse(null);
            if (currentRecord == null || !ownsType(currentRecord.type())
                    || !currentRecord.type().equals(scheduled.type())) {
                ledger.fxQueueDelete(height, ordinal);
                return null;
            }
            FxStatusRecord current = ledger.fxRuntimeStatus(height, ordinal).orElse(null);
            long now = System.currentTimeMillis();
            if (current == null || !current.executable() || current.nextAttemptAt() > now
                    || !ledger.fxQueueExists(height, ordinal)) {
                return null;
            }
            long tip = ledger.tipHeight();
            if (currentRecord.expiryHeight() > 0
                    && currentRecord.expiryHeight() <= tip + EXPIRY_SAFETY_BLOCKS) {
                return null;
            }
            if (currentRecord.gate() == FinalityGate.ZK_SETTLED
                    && height > ledger.metaLong("zk_settled_height", 0L)) {
                return null;
            }
            long anchoredHeight = ledger.metaLong("anchor_last_height", 0L);
            if (currentRecord.gate() == FinalityGate.L1_ANCHORED
                    && height > anchoredHeight - settings.anchorMarginBlocks()) {
                return null;
            }
            // The context must remain safe for an interrupt-resistant plugin
            // after close() returns and the ledger owner closes RocksDB.
            return new ExecutionPermit(current, tip, anchoredHeight);
        }
    }

    /** Bound an external ref to what a ~fx/result can carry (defensive; report() also rejects). */
    private static byte[] clampRef(byte[] ref) {
        int max = com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody.MAX_EXTERNAL_REF_BYTES;
        if (ref == null || ref.length <= max) {
            return ref;
        }
        return java.util.Arrays.copyOf(ref, max);
    }

    private ExecutorBinding find(String type) {
        ExecutorBinding declared = declaredOwners.get(type);
        if (declared != null) {
            return declared;
        }
        ExecutorBinding selected = null;
        for (ExecutorBinding binding : legacyBindings) {
            if (supports(binding, type)) {
                if (selected == null) {
                    selected = binding;
                } else if (warnedLegacyConflicts.add(type)) {
                    log.warn("App-chain '{}' has multiple legacy predicate owners for effect "
                                    + "type '{}': executors '{}' and '{}'; using stable order. "
                                    + "Migrate them to effectTypes() declarations",
                            chainId, type, selected.id(), binding.id());
                }
            }
        }
        return selected;
    }

    private boolean bindingOwns(ExecutorBinding binding, String type) {
        if (!binding.declaredTypes().isEmpty()) {
            return declaredOwners.get(type) == binding;
        }
        return supports(binding, type);
    }

    private boolean supports(ExecutorBinding binding, String type) {
        if (!beginPluginCallback()) {
            return false;
        }
        try {
            return binding.executor().supports(type);
        } finally {
            endPluginCallback();
        }
    }

    private boolean beginPluginCallback() {
        synchronized (pluginCallbackLock) {
            if (closed) {
                return false;
            }
            activePluginCallbacks++;
            return true;
        }
    }

    private void endPluginCallback() {
        synchronized (pluginCallbackLock) {
            activePluginCallbacks--;
            pluginCallbackLock.notifyAll();
        }
    }

    private boolean pluginCallbacksIdle() {
        synchronized (pluginCallbackLock) {
            return activePluginCallbacks == 0;
        }
    }

    private void reportFailure(String operation, Throwable failure) {
        String errorType = failureType(failure);
        lastError = errorType;
        if (isFatal(failure)) {
            log.error("App-chain '{}' effect {} failed fatally (errorType={})",
                    chainId, operation, errorType);
        } else {
            log.warn("App-chain '{}' effect {} failed (errorType={})",
                    chainId, operation, errorType);
        }
    }

    private static String failureType(Throwable failure) {
        String errorType = failure.getClass().getName();
        return errorType.length() <= MAX_PLUGIN_FAILURE_TYPE_CHARS
                ? errorType : errorType.substring(0, MAX_PLUGIN_FAILURE_TYPE_CHARS);
    }

    /**
     * Plugin linkage/assertion errors are isolated like other plugin failures;
     * VM-fatal conditions and explicit thread termination must retain JVM
     * semantics and are rethrown after the runtime has recorded the failure.
     */
    @SuppressWarnings("removal")
    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    @SuppressWarnings("removal")
    private static void rethrowIfFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    /**
     * The external call is considered started when this permission is
     * granted. close() closes the same gate under the same monitor, giving a
     * total order without holding any runtime lock during plugin execution.
     */
    private boolean beginExternalExecution() {
        synchronized (executionStartLock) {
            return !closed;
        }
    }

    private boolean ownsType(String type) {
        return settings.types().isEmpty() || settings.types().contains(type);
    }

    private boolean beginApiOperation() {
        apiOperations.readLock().lock();
        if (closed) {
            apiOperations.readLock().unlock();
            return false;
        }
        return true;
    }

    private void endApiOperation() {
        apiOperations.readLock().unlock();
    }

    private EffectExecutionContext contextFor(int attempt, ExecutorBinding binding,
                                              byte[] submittedRef, long tipHeight,
                                              long anchoredHeight) {
        Map<String, String> config = binding.config();
        byte[] priorRef = submittedRef != null ? submittedRef.clone() : new byte[0];
        return new EffectExecutionContext() {
            @Override public String chainId() { return chainId; }
            @Override public long tipHeight() { return tipHeight; }
            @Override public long anchoredHeight() { return anchoredHeight; }
            @Override public int attempt() { return attempt; }
            @Override public byte[] submittedRef() { return priorRef.clone(); }
            @Override public Map<String, String> settings() { return config; }
        };
    }

    /** One injectable outcome: a locally-terminal CHAIN effect not yet chain-closed. */
    record Injection(long height, int ordinal, boolean confirmed, byte[] externalRef,
                     byte[] detailHash, String reason) {
        Injection {
            externalRef = externalRef != null ? externalRef.clone() : new byte[0];
            detailHash = detailHash != null ? detailHash.clone() : null;
        }

        @Override public byte[] externalRef() { return externalRef.clone(); }
        @Override public byte[] detailHash() {
            return detailHash != null ? detailHash.clone() : null;
        }
    }

    /**
     * External-executor claim (ADR-010 F5, "external" deployment model):
     * lease eligible queued effects to a named external worker. The lease is
     * a WALL-CLOCK deadline in this node's runtime tier — purely node-local
     * work-dispatch, never consensus (ADR-010 F6). An expired lease makes the
     * effect re-eligible for anyone (in-process dispatch included);
     * duplicates are absorbed by idempotency + first-result-wins.
     */
    List<PendingEffect> claim(String executorId, Set<String> types, int max, long leaseSeconds) {
        if (!validExecutorIdentity(executorId) || !beginApiOperation()) {
            return List.of();
        }
        try {
            long tip = ledger.tipHeight();
            long anchored = ledger.metaLong("anchor_last_height", 0L);
            long now = System.currentTimeMillis();
            long leaseUntil = now + Math.max(1, Math.min(leaseSeconds, 3600)) * 1000L;
            int cap = Math.max(1, Math.min(max, 256));
            List<PendingEffect> claimed = new ArrayList<>();
            // Scan + decode LOCK-FREE (a REST poll must not stall worker
            // terminals); take the lock only per row for revalidate + stamp
            long[] cursor = ledger.fxClaimCursor().orElse(null);
            long[] lastVisited = null;
            for (long[] entry : ledger.fxQueueScanAfter(settings.scanLimit(), cursor)) {
                if (closed || claimed.size() >= cap) {
                    break;
                }
                lastVisited = entry;
                long height = entry[0];
                int ordinal = (int) entry[1];
                if (inFlight.contains(height + "/" + ordinal) || ledger.fxClosed(height, ordinal)) {
                    continue;
                }
                EffectRecord record = ledger.fxRecord(height, ordinal).orElse(null);
                if (record == null
                        || !ownsType(record.type())
                        || (types != null && !types.isEmpty() && !types.contains(record.type()))) {
                    continue;
                }
                if (record.gate() == FinalityGate.ZK_SETTLED
                        && height > ledger.metaLong("zk_settled_height", 0L)) {
                    continue; // awaits an accepted validity proof (ADR-006 E7.x wires the HWM)
                }
                if (record.gate() == FinalityGate.L1_ANCHORED
                        && height > anchored - settings.anchorMarginBlocks()) {
                    continue;
                }
                if (record.expiryHeight() > 0
                        && record.expiryHeight() <= tip + EXPIRY_SAFETY_BLOCKS) {
                    continue;
                }
                synchronized (transitionLock) {
                    if (closed || ledger.fxClosed(height, ordinal) || !ownsType(record.type())) {
                        continue;
                    }
                    FxStatusRecord status = ledger.fxRuntimeStatus(height, ordinal)
                            .orElse(FxStatusRecord.pending());
                    // SUBMITTED = an in-process long-running action already fired
                    // (M4 review): leasing it out would double-execute
                    if (!status.executable() || status.status() == FxStatusRecord.SUBMITTED
                            || status.nextAttemptAt() > now
                            || inFlight.contains(height + "/" + ordinal)) {
                        continue; // backoff, another live lease, or mid-flight
                    }
                    ledger.fxRuntimePutStatus(
                            height, ordinal, status.external(executorId, leaseUntil));
                    claimed.add(PendingEffect.of(record));
                    statsCachedAt = 0;
                }
            }
            if (!closed && lastVisited != null) {
                ledger.fxPutClaimCursor(lastVisited[0], (int) lastVisited[1]);
            }
            return claimed;
        } finally {
            endApiOperation();
        }
    }

    /**
     * External-executor report: a definitive outcome for a claimed effect
     * (the external worker owns its own retrying). Lands as the same local
     * terminal an in-process executor produces — the injection loop then
     * feeds CHAIN outcomes back on-chain. Reports from a non-holder, or for
     * an unknown/closed effect, are rejected.
     */
    boolean report(String executorId, long height, int ordinal, boolean success,
                   byte[] externalRef, String reason) {
        if (!validExecutorIdentity(executorId) || !beginApiOperation()) {
            return false;
        }
        try {
            synchronized (transitionLock) {
                if (closed) {
                    return false;
                }
                EffectRecord record = ledger.fxRecord(height, ordinal).orElse(null);
                if (record == null || !ownsType(record.type()) || ledger.fxClosed(height, ordinal)) {
                    return false;
                }
                FxStatusRecord status = ledger.fxRuntimeStatus(height, ordinal).orElse(null);
                if (status == null || status.status() != FxStatusRecord.EXTERNAL
                        || !status.lastError().equals(executorId)) {
                    return false; // not claimed here / claimed by someone else
                }
                if (inFlight.contains(height + "/" + ordinal)) {
                    // An in-process executor stole the expired lease and is
                    // mid-flight (M4 review): fence the late report — the
                    // in-process outcome will land, duplicates absorbed by
                    // idempotency
                    return false;
                }
                if (externalRef != null
                        && externalRef.length > com.bloxbean.cardano.yano.api.appchain.effects
                                .FxResultBody.MAX_EXTERNAL_REF_BYTES) {
                    return false; // over the on-chain ref bound — reject at the boundary
                }
                if (success) {
                    ledger.fxRuntimeComplete(height, ordinal, status.done(externalRef),
                            record.result() == com.bloxbean.cardano.yano.api.appchain.effects
                                    .ResultPolicy.CHAIN);
                    recordTerminal(record, "confirmed");
                } else if (record.result()
                        == com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy.CHAIN) {
                    String boundedReason = boundedFailureReason(reason);
                    ledger.fxRuntimeComplete(height, ordinal,
                            status.doneFailed(boundedReason), true);
                    recordTerminal(record, "failed");
                } else {
                    String boundedReason = boundedFailureReason(reason);
                    ledger.fxRuntimeComplete(height, ordinal,
                            status.parked(boundedReason), false);
                    recordTerminal(record, "parked");
                }
                return true;
            }
        } finally {
            endApiOperation();
        }
    }

    private static String boundedFailureReason(String reason) {
        return new EffectExecution.Failed(
                reason != null && !reason.isBlank() ? reason : "external failure",
                false).reason();
    }

    private static boolean validExecutorIdentity(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean alphaNumeric = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9';
            if (!alphaNumeric && character != '.' && character != '_'
                    && character != '-' && character != ':' && character != '+') {
                return false;
            }
        }
        return true;
    }

    private void recordTerminal(EffectRecord record, String outcome) {
        switch (outcome) {
            case "confirmed" -> confirmedCount.incrementAndGet();
            case "failed" -> failedCount.incrementAndGet();
            case "parked" -> parkedCount.incrementAndGet();
            default -> throw new IllegalArgumentException("Unknown effect outcome: " + outcome);
        }
        String bucket = settings.metricsTypes().isEmpty()
                ? "all"
                : settings.metricsTypes().contains(record.type()) ? record.type() : "other";
        LatencyAccumulator latency = latencyByType.computeIfAbsent(bucket,
                ignored -> new LatencyAccumulator());
        long emittedAt = ledger.block(record.height()).map(block -> block.timestamp())
                .orElse(System.currentTimeMillis());
        latency.count.incrementAndGet();
        latency.totalMillis.addAndGet(Math.max(0, System.currentTimeMillis() - emittedAt));
        statsCachedAt = 0;
    }

    /**
     * Locally-terminal CHAIN outcomes awaiting {@code ~fx/result} injection
     * (ADR-010 F8): DONE statuses with an outcome, whose effect is still open
     * on-chain, throttled by {@code reinjectIntervalMs} so the pool isn't
     * spammed while a prior injection awaits sequencing. Marks each returned
     * entry's {@code injectedAt} — first-result-wins absorbs any overlap.
     */
    List<Injection> pendingInjections(int limit, long reinjectIntervalMs) {
        if (limit <= 0 || !beginApiOperation()) {
            return List.of();
        }
        try {
            List<Injection> injections = new ArrayList<>();
            long now = System.currentTimeMillis();
            // Drive from the dedicated index, not a bounded prefix of every old
            // status row. Otherwise retained historical statuses can permanently
            // starve a newer DONE outcome from on-chain incorporation.
            long[] cursor = ledger.fxResultInjectionCursor().orElse(null);
            List<long[]> ready = ledger.fxResultReadyScanAfter(
                    Math.max(settings.scanLimit(), limit), cursor);
            if (ready.isEmpty()) {
                return List.of();
            }
            long[] lastVisited = null;
            for (long[] entry : ready) {
                if (closed || injections.size() >= limit) {
                    break;
                }
                lastVisited = entry;
                long height = entry[0];
                int ordinal = (int) entry[1];
                FxStatusRecord status = ledger.fxRuntimeStatus(height, ordinal).orElse(null);
                if (status == null) {
                    ledger.fxResultReadyDelete(height, ordinal);
                    continue;
                }
                if (status.status() != FxStatusRecord.DONE
                        || status.outcomeCode() == FxStatusRecord.OUTCOME_NONE
                        || now - status.injectedAt() < reinjectIntervalMs
                        || ledger.fxClosed(height, ordinal)) {
                    continue;
                }
                EffectRecord record = ledger.fxRecord(height, ordinal).orElse(null);
                if (record == null || record.result()
                        != com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy.CHAIN) {
                    ledger.fxResultReadyDelete(height, ordinal);
                    continue;
                }
                synchronized (transitionLock) {
                    if (closed) {
                        break;
                    }
                    FxStatusRecord current = ledger.fxRuntimeStatus(height, ordinal).orElse(null);
                    if (current == null || current.status() != FxStatusRecord.DONE
                            || current.outcomeCode() == FxStatusRecord.OUTCOME_NONE
                            || now - current.injectedAt() < reinjectIntervalMs
                            || ledger.fxClosed(height, ordinal)) {
                        continue;
                    }
                    ledger.fxRuntimePutStatus(height, ordinal, current.injected(now));
                    status = current;
                }
                injections.add(new Injection(height, ordinal,
                        status.outcomeCode() == FxStatusRecord.OUTCOME_CONFIRMED,
                        status.externalRef(), status.detailHash(), status.lastError()));
            }
            if (!closed && lastVisited != null) {
                ledger.fxPutResultInjectionCursor(lastVisited[0], (int) lastVisited[1]);
            }
            return injections;
        } finally {
            endApiOperation();
        }
    }

    /**
     * Operator requeue (ADR-010 F9): PARKED/QUARANTINED/SKIPPED → PENDING.
     * Also the self-heal for a stranded executable status with no queue row
     * (crash/race leftovers) — re-adds the row.
     */
    boolean requeue(long height, int ordinal) {
        if (!beginApiOperation()) {
            return false;
        }
        try {
            synchronized (transitionLock) {
                if (closed) {
                    return false;
                }
                EffectRecord record = ledger.fxRecord(height, ordinal).orElse(null);
                if (record == null || !ownsType(record.type())
                        || ledger.fxClosed(height, ordinal)) {
                    return false;
                }
                Optional<FxStatusRecord> status = ledger.fxRuntimeStatus(height, ordinal);
                if (status.isPresent() && status.get().executable()) {
                    if (ledger.fxQueueExists(height, ordinal)) {
                        return false; // genuinely live
                    }
                    ledger.fxQueuePut(height, ordinal); // stranded executable — heal the row
                    statsCachedAt = 0;
                    log.info("App-chain '{}' effect {}/{} queue row restored by operator",
                            chainId, height, ordinal);
                    return true;
                }
                if (status.isPresent() && status.get().status() == FxStatusRecord.DONE) {
                    return false; // completed CHAIN outcomes await incorporation, never re-execution
                }
                ledger.fxRuntimePutStatus(height, ordinal,
                        status.map(FxStatusRecord::requeued).orElse(FxStatusRecord.pending()));
                ledger.fxQueuePut(height, ordinal);
                statsCachedAt = 0;
                log.info("App-chain '{}' effect {}/{} requeued by operator", chainId, height, ordinal);
                return true;
            }
        } finally {
            endApiOperation();
        }
    }

    Map<String, Object> stats() {
        if (!beginApiOperation()) {
            return Map.of("closed", true);
        }
        try {
            if (closed) {
                return Map.of("closed", true);
            }
            long now = System.currentTimeMillis();
            if (now - statsCachedAt <= 1_000) {
                return cachedStats;
            }
            synchronized (statsLock) {
                if (closed) {
                    return Map.of("closed", true);
                }
                now = System.currentTimeMillis();
                if (now - statsCachedAt <= 1_000) {
                    return cachedStats;
                }
                cachedStats = buildStats(now);
                statsCachedAt = now;
                return cachedStats;
            }
        } finally {
            endApiOperation();
        }
    }

    private Map<String, Object> buildStats(long now) {
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "RETRY", "SUBMITTED", "EXTERNAL",
                "DONE", "PARKED", "QUARANTINED", "SKIPPED")) {
            statusCounts.put(status, 0L);
        }
        for (Object[] entry : ledger.fxRuntimeStatusScanAll()) {
            if (closed) {
                return Map.of("closed", true);
            }
            long height = (long) entry[0];
            int ordinal = (int) entry[1];
            if (ledger.fxClosed(height, ordinal) || ledger.fxRecord(height, ordinal).isEmpty()) {
                continue;
            }
            FxStatusRecord status = (FxStatusRecord) entry[2];
            statusCounts.computeIfPresent(status.statusName(), (ignored, count) -> count + 1);
        }

        long queueDepth = 0;
        long oldestHeight = -1;
        long oldestTimestamp = 0;
        for (long[] entry : ledger.fxQueueScanAll()) {
            if (closed) {
                return Map.of("closed", true);
            }
            long height = entry[0];
            int ordinal = (int) entry[1];
            if (ledger.fxClosed(height, ordinal)) {
                continue;
            }
            EffectRecord record = ledger.fxRecord(height, ordinal).orElse(null);
            FxStatusRecord status = ledger.fxRuntimeStatus(height, ordinal).orElse(null);
            if (record == null || (status != null && !status.executable())) {
                continue;
            }
            queueDepth++;
            if (oldestHeight < 0 || height < oldestHeight) {
                oldestHeight = height;
                oldestTimestamp = ledger.block(height).map(block -> block.timestamp()).orElse(now);
            }
        }

        Map<String, Long> backlogByType = new LinkedHashMap<>();
        if (settings.metricsTypes().isEmpty()) {
            backlogByType.put("all", 0L);
        } else {
            new java.util.TreeSet<>(settings.metricsTypes())
                    .forEach(type -> backlogByType.put(type, 0L));
            backlogByType.put("other", 0L);
        }
        long resultBacklog = 0;
        for (long[] entry : ledger.fxResultReadyScan()) {
            if (closed) {
                return Map.of("closed", true);
            }
            long height = entry[0];
            int ordinal = (int) entry[1];
            if (ledger.fxClosed(height, ordinal)) {
                continue;
            }
            EffectRecord record = ledger.fxRecord(height, ordinal).orElse(null);
            if (record == null) {
                continue;
            }
            resultBacklog++;
            String bucket = settings.metricsTypes().isEmpty()
                    ? "all"
                    : settings.metricsTypes().contains(record.type()) ? record.type() : "other";
            backlogByType.computeIfPresent(bucket, (ignored, count) -> count + 1);
        }

        Map<String, Object> oldestPending = new LinkedHashMap<>();
        oldestPending.put("height", Math.max(0, oldestHeight));
        oldestPending.put("ageBlocks", oldestHeight >= 0
                ? Math.max(0, ledger.tipHeight() - oldestHeight) : 0L);
        oldestPending.put("ageSeconds", oldestHeight >= 0
                ? Math.max(0, now - oldestTimestamp) / 1_000d : 0d);

        Map<String, Object> latency = new LinkedHashMap<>();
        new java.util.TreeMap<>(latencyByType).forEach((type, accumulator) ->
                latency.put(type, Map.of("count", accumulator.count.get(),
                        "totalMillis", accumulator.totalMillis.get())));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", true);
        stats.put("intakeCursor", ledger.fxIntakeCursor(0));
        stats.put("queueDepth", queueDepth);
        stats.put("inFlight", inFlight.size());
        stats.put("executed", confirmedCount.get()); // legacy field
        stats.put("parked", parkedCount.get());
        stats.put("openOnChain", ledger.fxOpenCount());
        stats.put("statusCounts", Map.copyOf(statusCounts));
        stats.put("resultBacklog", resultBacklog);
        stats.put("resultBacklogByType", Map.copyOf(backlogByType));
        stats.put("oldestPending", Map.copyOf(oldestPending));
        stats.put("executionTotals", Map.of(
                "confirmed", confirmedCount.get(),
                "failed", failedCount.get(),
                "parked", parkedCount.get()));
        stats.put("expiredTotal", ledger.fxExpiredCount());
        stats.put("latencyByType", Map.copyOf(latency));
        stats.put("metricsGeneration", metricsGeneration);
        stats.put("owner", "v1:" + runtimeOwner);
        stats.put("executors", executorBindings.stream().map(ExecutorBinding::id).toList());
        stats.put("executorOperations", executorOperationsViews(System.nanoTime()));
        if (lastError != null) {
            stats.put("lastError", lastError);
        }
        return java.util.Collections.unmodifiableMap(stats);
    }

    private List<Map<String, Object>> executorOperationsViews(long nowNanos) {
        List<Map<String, Object>> views = new ArrayList<>(executorBindings.size());
        for (ExecutorBinding binding : executorBindings) {
            EffectExecutorOperationalSnapshot snapshot = binding.operationsSnapshot().get();
            long sampledAt = binding.operationsSampledNanos().get();
            long startedAt = binding.operationsStartedNanos().get();
            String sampleState;
            if (binding.operationsInFlight().get() && startedAt != 0
                    && nowNanos - startedAt > OPERATIONS_CALLBACK_BUDGET_NANOS) {
                snapshot = callbackFailureSnapshot(snapshot,
                        EffectExecutorOperationalSnapshot.FailureCode.CALLBACK_TIMEOUT);
                sampleState = "TIMED_OUT";
            } else if (sampledAt == 0) {
                sampleState = "UNKNOWN";
            } else if (nowNanos - sampledAt > OPERATIONS_STALE_NANOS) {
                snapshot = callbackFailureSnapshot(snapshot,
                        EffectExecutorOperationalSnapshot.FailureCode.STALE);
                sampleState = "STALE";
            } else {
                sampleState = "FRESH";
            }

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", binding.id());
            view.put("bundleId", binding.source().bundleId());
            view.put("scheme", binding.source().scheme());
            view.put("types", binding.declaredTypes().stream().sorted().toList());
            view.put("sampleState", sampleState);
            view.put("readiness", snapshot.readiness().name());
            view.put("attempts", snapshot.attempts());
            view.put("successes", snapshot.successes());
            view.put("retryableFailures", snapshot.retryableFailures());
            view.put("terminalFailures", snapshot.terminalFailures());
            view.put("inFlight", snapshot.inFlight());
            view.put("lastSuccessAge", snapshot.lastSuccessAge().name());
            view.put("lastFailureAge", snapshot.lastFailureAge().name());
            view.put("failureCode", snapshot.failureCode().name());
            views.add(java.util.Collections.unmodifiableMap(view));
        }
        return List.copyOf(views);
    }

    Optional<Map<String, Object>> statusOf(long height, int ordinal) {
        if (!beginApiOperation()) {
            return Optional.empty();
        }
        try {
            if (closed) {
                return Optional.empty();
            }
            return ledger.fxRuntimeStatus(height, ordinal).map(status -> {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("status", status.statusName());
                view.put("attempts", status.attempts());
                if (status.nextAttemptAt() > 0) {
                    view.put("nextAttemptAt", status.nextAttemptAt());
                }
                if (!status.lastError().isEmpty()) {
                    if (status.status() == FxStatusRecord.EXTERNAL) {
                        // The holder id is the report fence (M4/M5 review): expose
                        // only that a lease is HELD, never the token itself — the
                        // holding worker knows its own id, others must not learn it
                        view.put("leased", true);
                    } else {
                        view.put("lastError", status.lastError());
                    }
                }
                if (status.submittedRef().length > 0) {
                    view.put("submittedRef", HexUtil.encodeHexString(status.submittedRef()));
                }
                if (status.externalRef().length > 0) {
                    view.put("externalRef", HexUtil.encodeHexString(status.externalRef()));
                }
                view.put("updatedAt", status.updatedAt());
                return view;
            });
        } finally {
            endApiOperation();
        }
    }

    /**
     * Shutdown: stop accepting work, fence every remaining ledger access,
     * then wait a bounded period for workers. An interrupt-resistant external
     * call may outlive this method, but its context is a ledger-free snapshot;
     * its executor is closed asynchronously only after the worker terminates.
     */
    @Override
    public void close() {
        close(10_000, 5_000);
    }

    /** Package-private timeout override keeps shutdown-failure tests fast. */
    void close(long gracefulWaitMillis, long forcedWaitMillis) {
        boolean closeOwner = false;
        synchronized (closeLock) {
            if (!closed) {
                synchronized (executionStartLock) {
                    if (!closed) {
                        closed = true;
                        closeOwner = true;
                        closeOwnerThread = Thread.currentThread();
                    }
                }
            }
        }
        if (!closeOwner) {
            if (Boolean.TRUE.equals(inExecutorCloseCallback.get())
                    || Thread.currentThread() == closeOwnerThread) {
                return;
            }
            closeFence.handle((ignored, failure) -> null).join();
            return;
        }

        // Seal observation admission before product shutdown. Interrupt is a
        // best effort only; a non-cooperative callback remains covered by the
        // plugin-callback lifecycle barrier and cannot republish after close.
        operationsPool.shutdownNow();
        for (ExecutorBinding binding : executorBindings) {
            binding.operationsSnapshot().set(EffectExecutorOperationalSnapshot.unknown());
            binding.operationsSampledNanos().set(0);
        }

        try {
            // A tick that observed open may already be inside intake/dispatch.
            // Wait until it has noticed closed and left all scheduler store calls.
            synchronized (tickLock) {
                // barrier only
            }

            // REST/result-loop calls accepted before closed=true hold the read
            // side for their complete ledger lifetime. Crossing the write side
            // proves they have returned; later entrants observe closed and reject.
            apiOperations.writeLock().lock();
            apiOperations.writeLock().unlock();

            // Likewise, let a worker already inside its short preflight/terminal
            // store section leave. External execution never holds this lock.
            synchronized (transitionLock) {
                // barrier only
            }

            pool.shutdown();
            boolean terminated = pool.isTerminated();
            try {
                if (!terminated && !pool.awaitTermination(
                        Math.max(0, gracefulWaitMillis), TimeUnit.MILLISECONDS)) {
                    pool.shutdownNow();
                    terminated = pool.awaitTermination(
                            Math.max(0, forcedWaitMillis), TimeUnit.MILLISECONDS);
                } else {
                    terminated = true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
                terminated = pool.isTerminated();
            }

            boolean cleanupReady = terminated && pluginCallbacksIdle();
            if (!cleanupReady) {
                try {
                    log.warn("App-chain '{}' effect plugin calls remain active after shutdown grace — "
                            + "ledger access is fenced and executor close is deferred", chainId);
                } catch (Throwable diagnosticFailure) {
                    recordExecutorCleanupHandoffFailure(diagnosticFailure);
                }
            }
            // Plugin close() is untrusted too. Always invoke it from the
            // daemon cleanup path, even when workers are already idle, so it
            // can never extend the bounded runtime close call.
            scheduleExecutorCleanup();
            if (cleanupReady) {
                awaitCooperativeExecutorCleanup();
            }
        } finally {
            closeOwnerThread = null;
            closeFence.complete(null);
        }
    }

    private void scheduleExecutorCleanup() {
        if (!executorCleanupScheduled.compareAndSet(false, true)) {
            return;
        }
        Throwable handoffFailure;
        try {
            Thread coordinator = Objects.requireNonNull(
                    cleanupCoordinatorThreadFactory.newThread(
                            () -> coordinateExecutorCleanup()),
                    "cleanupCoordinatorThreadFactory returned null");
            coordinator.start();
            return;
        } catch (Throwable startFailure) {
            handoffFailure = reportLifecycleFailure(
                    "executor cleanup coordinator start", startFailure);
            recordExecutorCleanupHandoffFailure(handoffFailure);
        }

        if (isFatal(handoffFailure) && pool.isTerminated() && pluginCallbacksIdle()) {
            // Preserve the fatal on this thread, but only after every actual
            // product close has ended and the lifetime stage is truthful.
            if (!coordinateExecutorCleanup()) {
                executorCleanupCompletion.handle((ignored, failure) -> null).join();
            }
            rethrowIfFatal(handoffFailure);
            return; // unreachable for a process-fatal hand-off
        }

        // A hand-off failure says nothing about product termination. Prefer a
        // fresh platform daemon so close() remains bounded while a plugin call
        // drains. If even that cannot be started, the current thread is the
        // last possible owner and must perform the cleanup synchronously.
        try {
            Thread fallback = new Thread(() -> coordinateExecutorCleanup(),
                    "app-chain-fx-executor-cleanup-fallback-" + chainId);
            fallback.setDaemon(true);
            fallback.start();
        } catch (Throwable fallbackFailure) {
            Throwable diagnosedFailure = reportLifecycleFailure(
                    "executor cleanup coordinator fallback start", fallbackFailure);
            recordExecutorCleanupHandoffFailure(diagnosedFailure);
            coordinateExecutorCleanup();
        }

        // Non-process failures remain visible to the synchronous resource
        // owner. A process-fatal failure is rethrown by the coordinator only
        // after every actual executor close callback has terminated and the
        // lifetime future has become truthful.
        if (!isFatal(handoffFailure)) {
            throw propagateLifecycleFailure(
                    handoffFailure, "Effect executor cleanup coordinator failed to start");
        }
    }

    private void recordExecutorCleanupHandoffFailure(Throwable failure) {
        mergeFailure(executorCleanupHandoffFailure, failure);
    }

    /** Diagnostics are never allowed to abort terminal callback ownership. */
    private Throwable reportLifecycleFailure(String operation, Throwable failure) {
        Throwable outcome = failure;
        try {
            reportFailure(operation, failure);
        } catch (Throwable diagnosticFailure) {
            outcome = addFailure(outcome, diagnosticFailure);
        }
        return outcome;
    }

    private static RuntimeException propagateLifecycleFailure(
            Throwable failure,
            String message
    ) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(message, failure);
    }

    private record ExecutorCloseTask(
            ExecutorBinding binding,
            Thread thread,
            AtomicBoolean started
    ) {
    }

    private boolean coordinateExecutorCleanup() {
        if (!executorCleanupCoordinatorClaimed.compareAndSet(false, true)) {
            return false;
        }
        boolean interrupted = false;
        Throwable cleanupFailure = null;
        Throwable coordinatorFailure = null;
        Throwable terminalOutcome = null;
        boolean truthfulCompletion = false;
        try {
            while (!pool.isTerminated()) {
                try {
                    pool.awaitTermination(1, TimeUnit.DAYS);
                } catch (InterruptedException e) {
                    // This daemon owns no ledger/resource other than the
                    // executors. Keep their lifecycle coupled to live calls.
                    interrupted = true;
                }
            }
            synchronized (pluginCallbackLock) {
                while (activePluginCallbacks > 0) {
                    try {
                        pluginCallbackLock.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }

            cleanupFailure = addFailure(
                    cleanupFailure, executorCleanupHandoffFailure.get());
            startExecutorCloseTasks();
            for (ExecutorBinding binding : executorBindings) {
                while (binding.closeTerminal().getCount() != 0) {
                    try {
                        binding.closeTerminal().await();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                cleanupFailure = addFailure(cleanupFailure, binding.closeFailure().get());
            }
            if (isFatal(cleanupFailure)) {
                coordinatorFailure = cleanupFailure;
            }
        } catch (Throwable failure) {
            Throwable diagnosedFailure = reportLifecycleFailure(
                    "executor cleanup coordinator", failure);
            coordinatorFailure = diagnosedFailure;
            cleanupFailure = addFailure(cleanupFailure, diagnosedFailure);
        } finally {
            terminalOutcome = addFailure(
                    cleanupFailure, executorCleanupHandoffFailure.get());
            truthfulCompletion = allExecutorClosesTerminated();
            if (truthfulCompletion) {
                for (ExecutorBinding binding : executorBindings) {
                    terminalOutcome = addFailure(
                            terminalOutcome, binding.closeFailure().get());
                }
                if (terminalOutcome == null) {
                    executorCleanupCompletion.complete(null);
                } else {
                    executorCleanupCompletion.completeExceptionally(terminalOutcome);
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        rethrowIfFatal(truthfulCompletion
                ? terminalOutcome
                : addFailure(coordinatorFailure, terminalOutcome));
        return true;
    }

    private boolean allExecutorClosesTerminated() {
        for (ExecutorBinding binding : executorBindings) {
            if (!binding.closeTerminated().get()) {
                return false;
            }
        }
        return true;
    }

    private void startExecutorCloseTasks() {
        List<ExecutorCloseTask> tasks = new ArrayList<>(executorBindings.size());
        for (ExecutorBinding binding : executorBindings) {
            Thread task = null;
            try {
                task = Objects.requireNonNull(
                        executorCloseThreadFactory.newThread(
                                () -> closeExecutorIfUnclaimed(binding)),
                        "executorCloseThreadFactory returned null");
            } catch (Throwable creationFailure) {
                recordExecutorCloseFailure(binding, reportLifecycleFailure(
                        "executor cleanup task creation", creationFailure));
            }
            tasks.add(new ExecutorCloseTask(binding, task, new AtomicBoolean()));
        }
        // Attempt every start before a synchronous fallback. That preserves
        // fan-out: one failed hand-off followed by a blocking close callback
        // cannot prevent independent executor products from being closed.
        for (ExecutorCloseTask task : tasks) {
            if (task.thread() == null) {
                continue;
            }
            try {
                task.thread().start();
                task.started().set(true);
            } catch (Throwable startFailure) {
                recordExecutorCloseFailure(task.binding(), reportLifecycleFailure(
                        "executor cleanup task start", startFailure));
            }
        }
        // A failed ThreadFactory/Thread.start hand-off does not terminate the
        // executor product. The coordinator becomes the close owner and only
        // then may its completion stage become terminal.
        for (ExecutorCloseTask task : tasks) {
            if (!task.started().get()) {
                closeExecutorIfUnclaimed(task.binding());
            }
        }
    }

    private void awaitCooperativeExecutorCleanup() {
        try {
            executorCleanupCompletion.get(EXECUTOR_CLOSE_JOIN_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException ignored) {
            // Timeout means an untrusted close callback remains active. A
            // failure was already reported by its task; either way the
            // eventual completion stage remains the authoritative barrier.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeExecutor(ExecutorBinding binding) {
        try {
            binding.executor().close();
        } catch (Throwable failure) {
            recordExecutorCloseFailure(binding, reportLifecycleFailure(
                    "executor '" + binding.id() + "' close", failure));
        }
    }

    private void closeExecutorIfUnclaimed(ExecutorBinding binding) {
        if (binding.closeClaimed().compareAndSet(false, true)) {
            boolean callbackMarkerInstalled = false;
            Throwable markerFailure = null;
            try {
                try {
                    inExecutorCloseCallback.set(true);
                    callbackMarkerInstalled = true;
                } catch (Throwable failure) {
                    markerFailure = failure;
                }
                closeExecutor(binding);
            } finally {
                try {
                    if (callbackMarkerInstalled) {
                        try {
                            inExecutorCloseCallback.remove();
                        } catch (Throwable failure) {
                            markerFailure = addFailure(markerFailure, failure);
                        }
                    }
                    if (markerFailure != null) {
                        recordExecutorCloseFailure(binding, markerFailure);
                    }
                } finally {
                    binding.closeTerminated().set(true);
                    binding.closeTerminal().countDown();
                }
            }
        }
    }

    private void recordExecutorCloseFailure(
            ExecutorBinding binding,
            Throwable failure
    ) {
        mergeFailure(binding.closeFailure(), failure);
    }

    private static void mergeFailure(
            AtomicReference<Throwable> target,
            Throwable failure
    ) {
        while (true) {
            Throwable current = target.get();
            Throwable merged = addFailure(current, failure);
            if (target.compareAndSet(current, merged)) {
                return;
            }
        }
    }

    static Throwable addFailure(Throwable aggregate, Throwable next) {
        if (next == null) {
            return aggregate;
        }
        return LifecycleFailures.merge(aggregate, next);
    }
}
