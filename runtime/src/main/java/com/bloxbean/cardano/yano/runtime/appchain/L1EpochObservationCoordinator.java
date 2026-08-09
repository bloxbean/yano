package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.l1view.EpochObservationManifest;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochBoundary;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserver;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserverProvider;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochState;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochStateProvider;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Async reconciliation, generation, verification, and injection for ADR-028. */
final class L1EpochObservationCoordinator implements AutoCloseable {
    private static final int RECONCILIATION_BOUNDARIES = 4_096;
    private static final int MINIMUM_RETENTION_EPOCHS = 2;

    private final List<L1EpochObserver> observers;
    private final L1EpochStateProvider stateProvider;
    private final EpochObservationSpool spool;
    private final int stabilityDepth;
    private final int maxMessagesPerOffer;
    private final long maxBytesPerOffer;
    private final BooleanSupplier scheduledProposer;
    private final Function<L1Observation, Boolean> injector;
    private final Logger log;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean wakeQueued = new AtomicBoolean();
    private final AtomicLong wakeVersion = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong lastObservedEpoch = new AtomicLong(-1);
    private final AtomicLong latestAppliedBlockNumber = new AtomicLong(-1);
    private final AtomicLong pendingRollbackSlot = new AtomicLong(Long.MAX_VALUE);
    private final ConcurrentSkipListMap<Long, L1EpochBoundary> pendingBoundaries =
            new ConcurrentSkipListMap<>();
    private volatile boolean wasProposer;
    private volatile String unhealthyReason;
    private volatile String haltReason;
    private volatile long completedJobs;
    private volatile long failures;

    L1EpochObservationCoordinator(List<L1EpochObserver> observers,
                                  L1EpochStateProvider stateProvider,
                                  EpochObservationSpool spool,
                                  int stabilityDepth,
                                  int maxMessagesPerOffer,
                                  long maxBytesPerOffer,
                                  BooleanSupplier scheduledProposer,
                                  Function<L1Observation, Boolean> injector,
                                  String chainId,
                                  Logger log) {
        this.observers = List.copyOf(observers);
        this.stateProvider = Objects.requireNonNull(stateProvider, "stateProvider");
        this.spool = Objects.requireNonNull(spool, "spool");
        this.scheduledProposer = Objects.requireNonNull(
                scheduledProposer, "scheduledProposer");
        this.injector = Objects.requireNonNull(injector, "injector");
        this.log = Objects.requireNonNull(log, "log");
        if (this.observers.isEmpty()) {
            throw new IllegalArgumentException("At least one L1 epoch observer is required");
        }
        if (!stateProvider.persistent()) {
            throw new IllegalArgumentException(
                    "L1 epoch observers require persistent account/ledger state");
        }
        if (stateProvider.snapshotRetentionEpochs() < MINIMUM_RETENTION_EPOCHS) {
            throw new IllegalArgumentException("L1 epoch observers require snapshot retention of at least "
                    + MINIMUM_RETENTION_EPOCHS + " epochs");
        }
        if (stabilityDepth <= 0) {
            throw new IllegalArgumentException(
                    "L1 epoch observers require epoch-stability-depth > 0");
        }
        if (maxMessagesPerOffer <= 0 || maxBytesPerOffer <= 0) {
            throw new IllegalArgumentException("Invalid L1 epoch observation offer budget");
        }
        this.stabilityDepth = stabilityDepth;
        this.maxMessagesPerOffer = maxMessagesPerOffer;
        this.maxBytesPerOffer = maxBytesPerOffer;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable,
                            "yano-l1-epoch-" + safeThreadName(chainId));
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    static List<L1EpochObserver> observersFromConfig(Map<String, String> pluginSettings,
                                                     PluginProviderRegistry providers) {
        Map<String, Map<String, String>> byId = configuredObservers(pluginSettings);
        List<L1EpochObserver> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : byId.entrySet()) {
            String type = entry.getValue().getOrDefault("type", "");
            Optional<L1EpochObserverProvider> provider = providers.find(
                    L1EpochObserverProvider.class, type);
            if (provider.isEmpty()) {
                continue;
            }
            try {
                L1EpochObserver observer = Objects.requireNonNull(
                        provider.orElseThrow().create(entry.getKey(), entry.getValue()),
                        "L1EpochObserverProvider.create returned null");
                if (!entry.getKey().equals(observer.observerId())) {
                    throw new IllegalStateException(
                            "L1 epoch observer id does not match configured id");
                }
                result.add(observer);
            } catch (PluginActivationException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new PluginActivationException(
                        "Configured plugin L1 epoch observer '" + type
                                + "' failed to activate", failure);
            }
        }
        return List.copyOf(result);
    }

    static boolean isEpochObserverType(String type, PluginProviderRegistry providers) {
        return providers.find(L1EpochObserverProvider.class, type).isPresent();
    }

    void start() {
        spool.releaseOffers();
        wake();
    }

    /** Publisher-thread path: arithmetic, atomics, and one coalesced wake-up only. */
    void onBlockApplied(long slot, long blockNumber, byte[] blockHash) {
        if (closed.get()) {
            return;
        }
        long epoch = stateProvider.epochAtSlot(slot);
        if (epoch < 0) {
            fail("slot-to-epoch calculation");
            return;
        }
        latestAppliedBlockNumber.accumulateAndGet(blockNumber, Math::max);
        long previous = lastObservedEpoch.getAndSet(epoch);
        if (previous >= 0 && epoch > previous) {
            for (long next = previous + 1; next <= epoch; next++) {
                pendingBoundaries.put(next, new L1EpochBoundary(
                        next - 1, next, slot, blockHash, blockNumber));
            }
        }
        wake();
    }

    /** Publisher-thread rollback path: record intent and wake the coordinator. */
    void onRollback(long rollbackToSlot) {
        pendingRollbackSlot.accumulateAndGet(rollbackToSlot, Math::min);
        pendingBoundaries.entrySet().removeIf(
                entry -> entry.getValue().boundarySlot() > rollbackToSlot);
        lastObservedEpoch.set(stateProvider.epochAtSlot(rollbackToSlot));
        latestAppliedBlockNumber.set(-1);
        wake();
    }

    void onFinalized(L1Observation observation) {
        if (observation.anchor() instanceof L1Observation.EpochAnchor) {
            spool.acknowledge(observation);
            wake();
        }
    }

    AppChainEngine.L1RefVerdict verify(L1Observation observation,
                                       boolean historicalCatchUp) {
        return spool.verify(observation, historicalCatchUp);
    }

    Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("healthy", unhealthyReason == null);
        if (unhealthyReason != null) {
            result.put("unhealthyReason", unhealthyReason);
        }
        result.put("completedJobs", completedJobs);
        result.put("failures", failures);
        result.put("latestAppliedBlockNumber", latestAppliedBlockNumber.get());
        result.put("coordinatorQueueDepth", executor.getQueue().size());
        result.put("spool", spool.status());
        return Map.copyOf(result);
    }

    boolean healthy() {
        return unhealthyReason == null;
    }

    private void wake() {
        wakeVersion.incrementAndGet();
        scheduleWake();
    }

    private void scheduleWake() {
        if (closed.get() || !wakeQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::runWake);
        } catch (RejectedExecutionException failure) {
            wakeQueued.set(false);
            if (!closed.get()) {
                fail("coordinator queue rejection");
            }
        }
    }

    private void runWake() {
        long processedVersion = -1;
        try {
            do {
                processedVersion = wakeVersion.get();
                reconcile();
            } while (!closed.get() && processedVersion != wakeVersion.get());
        } catch (Throwable failure) {
            LifecycleFailures.rethrowIfProcessFatal(failure);
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            fail(haltReason != null ? haltReason : "asynchronous reconciliation");
        } finally {
            wakeQueued.set(false);
            if (!closed.get() && processedVersion != wakeVersion.get()) {
                scheduleWake();
            }
        }
    }

    private void reconcile() {
        if (haltReason != null) {
            throw new IllegalStateException(haltReason);
        }
        long rollback = pendingRollbackSlot.getAndSet(Long.MAX_VALUE);
        if (rollback != Long.MAX_VALUE) {
            try {
                spool.rollback(rollback);
            } catch (IllegalStateException failure) {
                if ("DEEP_ROLLBACK_BELOW_FINALIZED_EPOCH_ATTESTATION"
                        .equals(failure.getMessage())) {
                    haltReason = failure.getMessage();
                }
                throw failure;
            }
        }
        for (L1EpochBoundary boundary : stateProvider.completedBoundaries(
                -1, RECONCILIATION_BOUNDARIES)) {
            pendingBoundaries.putIfAbsent(boundary.newEpoch(), boundary);
        }
        for (L1EpochBoundary boundary : List.copyOf(pendingBoundaries.values())) {
            prepareBoundary(boundary);
        }
        offerStable();
        if (haltReason == null) unhealthyReason = null;
    }

    private void prepareBoundary(L1EpochBoundary boundary) {
        for (L1EpochObserver observer : observers) {
            if (spool.prepared(observer.observerId(), boundary.newEpoch())) {
                continue;
            }
            EpochObservationManifest manifest;
            try (L1EpochState state = open(boundary)) {
                validateState(state, boundary);
                manifest = observer.prepare(boundary, state);
            }
            if (!observer.observerId().equals(manifest.observerId())
                    || manifest.previousEpoch() != boundary.previousEpoch()
                    || manifest.newEpoch() != boundary.newEpoch()) {
                throw new IllegalStateException(
                        "L1 epoch observer returned a manifest for another boundary");
            }
            spool.begin(boundary, manifest);
            AtomicLong expected = new AtomicLong();
            try (L1EpochState state = open(boundary)) {
                validateState(state, boundary);
                observer.writeObservations(manifest, state, (index, claim) -> {
                    long next = expected.getAndIncrement();
                    if (index != next) {
                        throw new IllegalStateException(
                                "L1 epoch observer emitted non-consecutive chunk indexes");
                    }
                    L1Observation encoded = L1Observation.epoch(
                            manifest.observerId(), boundary.newEpoch(),
                            boundary.boundarySlot(), boundary.boundaryBlockHash(), claim);
                    if (encoded.encode().length > maxBytesPerOffer) {
                        throw new IllegalStateException(
                                "L1 epoch observer emitted a record above the configured message budget");
                    }
                    spool.append(boundary, manifest, index, claim);
                });
            }
            if (expected.get() != manifest.observationCount()) {
                throw new IllegalStateException(
                        "L1 epoch observer emitted a different observation count than its manifest");
            }
            spool.complete(manifest);
            completedJobs++;
        }
        pendingBoundaries.remove(boundary.newEpoch(), boundary);
    }

    private L1EpochState open(L1EpochBoundary boundary) {
        return stateProvider.open(boundary).orElseThrow(() ->
                new IllegalStateException("Epoch-pinned L1 state is unavailable for epoch "
                        + boundary.newEpoch()));
    }

    private static void validateState(L1EpochState state, L1EpochBoundary boundary) {
        if (state.previousEpoch() != boundary.previousEpoch()
                || state.newEpoch() != boundary.newEpoch()) {
            throw new IllegalStateException("Epoch-pinned state does not match its boundary");
        }
    }

    private void offerStable() {
        boolean proposer = scheduledProposer.getAsBoolean();
        if (!proposer) {
            if (wasProposer) {
                spool.releaseOffers();
            }
            wasProposer = false;
            return;
        }
        wasProposer = true;
        long latest = latestAppliedBlockNumber.get();
        if (latest < stabilityDepth) {
            return;
        }
        long maximumBoundary = latest - stabilityDepth;
        for (EpochObservationSpool.Offered offered : spool.offer(
                maximumBoundary, maxMessagesPerOffer, maxBytesPerOffer)) {
            boolean accepted;
            try {
                accepted = Boolean.TRUE.equals(injector.apply(offered.observation()));
            } catch (RuntimeException failure) {
                accepted = false;
            }
            if (!accepted) {
                spool.offerFailed(offered);
            }
        }
    }

    private void fail(String reason) {
        failures++;
        unhealthyReason = reason;
        log.warn("L1 epoch observation coordinator unhealthy (reason={})", reason);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "L1 epoch observation coordinator did not stop within 5 seconds");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while stopping L1 epoch observation coordinator", failure);
        }
    }

    private static Map<String, Map<String, String>> configuredObservers(
            Map<String, String> pluginSettings) {
        Map<String, Map<String, String>> byId = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : pluginSettings.entrySet()) {
            if (!entry.getKey().startsWith("observers.")) {
                continue;
            }
            String rest = entry.getKey().substring("observers.".length());
            int separator = rest.indexOf('.');
            if (separator <= 0) {
                continue;
            }
            byId.computeIfAbsent(rest.substring(0, separator), ignored -> new HashMap<>())
                    .put(rest.substring(separator + 1), entry.getValue());
        }
        return byId;
    }

    private static String safeThreadName(String chainId) {
        String sanitized = chainId.replaceAll("[^A-Za-z0-9_.-]", "_");
        return sanitized.substring(0, Math.min(sanitized.length(), 48));
    }
}
