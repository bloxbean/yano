package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.plugin.StorageFilter;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.runtime.db.RocksDbSupplier;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns UTXO derived-state storage, event handlers, reconcile, pruning, lag
 * metrics, and snapshot-restore pause/resume behavior.
 */
public final class UtxoSubsystem implements Subsystem {
    private final YanoConfig config;
    private final RuntimeOptions runtimeOptions;
    private final ChainState chainState;
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final Logger log;

    private UtxoStoreWriter utxoStore;
    private PruneService pruneService;
    private UtxoEventHandler eventHandler;
    private UtxoEventHandlerAsync asyncEventHandler;
    private ScheduledFuture<?> lagTask;
    private boolean reconcilePending;
    private boolean closed;

    public UtxoSubsystem(YanoConfig config,
                         RuntimeOptions runtimeOptions,
                         ChainState chainState,
                         RocksDbSupplier rocks,
                         EventBus eventBus,
                         ScheduledExecutorService scheduler,
                         Logger log) {
        this.config = Objects.requireNonNull(config, "config");
        this.runtimeOptions = runtimeOptions != null ? runtimeOptions : RuntimeOptions.defaults();
        this.chainState = Objects.requireNonNull(chainState, "chainState");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.log = Objects.requireNonNull(log, "log");
        initialize(rocks);
    }

    UtxoSubsystem(YanoConfig config,
                  RuntimeOptions runtimeOptions,
                  ChainState chainState,
                  UtxoStoreWriter utxoStore,
                  EventBus eventBus,
                  ScheduledExecutorService scheduler,
                  Logger log) {
        this.config = Objects.requireNonNull(config, "config");
        this.runtimeOptions = runtimeOptions != null ? runtimeOptions : RuntimeOptions.defaults();
        this.chainState = Objects.requireNonNull(chainState, "chainState");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.log = Objects.requireNonNull(log, "log");
        this.utxoStore = utxoStore;
    }

    @Override
    public String name() {
        return "utxo";
    }

    public UtxoStoreWriter store() {
        return utxoStore;
    }

    public UtxoState state() {
        return utxoStore instanceof UtxoState state ? state : null;
    }

    public boolean isAsyncHandlerRunning() {
        return asyncEventHandler != null;
    }

    public boolean isPruneServiceRunning() {
        return pruneService != null;
    }

    public void completeStartupRecovery() {
        if (reconcilePending) {
            reconcile();
            reconcilePending = false;
        }
    }

    public void reinitializeAndReconcileAfterSnapshotRestore() {
        if (utxoStore == null) {
            return;
        }
        utxoStore.reinitialize();
        try {
            utxoStore.reconcile(chainState);
        } catch (Throwable t) {
            throw new IllegalStateException("UTXO reconciliation after snapshot restore failed", t);
        }
    }

    public boolean pauseAsyncHandlerAndAwait(Duration timeout) {
        UtxoEventHandlerAsync handler = asyncEventHandler;
        if (handler == null) {
            return true;
        }
        boolean drained = handler.closeAndAwait(timeout);
        if (drained) {
            asyncEventHandler = null;
        }
        return drained;
    }

    public boolean drainAsyncHandlerAndRestart(Duration timeout) {
        UtxoEventHandlerAsync handler = asyncEventHandler;
        if (handler == null) {
            return true;
        }
        boolean drained = handler.closeAndAwait(timeout);
        if (!drained) {
            return false;
        }
        asyncEventHandler = null;
        if (!closed && utxoStore != null) {
            asyncEventHandler = new UtxoEventHandlerAsync(eventBus, utxoStore);
        }
        return true;
    }

    public boolean pausePruneServiceAndAwait(Duration timeout) {
        if (pruneService == null) {
            return true;
        }
        try {
            boolean stopped = pruneService.closeAndAwait(timeout);
            if (stopped) {
                pruneService = null;
                log.info("UTXO prune service paused for snapshot restore");
            } else {
                log.warn("UTXO prune service did not stop within timeout");
            }
            return stopped;
        } catch (Exception e) {
            log.warn("Error pausing UTXO prune service for snapshot restore: {}", e.toString());
            return false;
        }
    }

    public boolean pauseStoreMetricsSamplerAndAwait(Duration timeout) {
        if (utxoStore instanceof DefaultUtxoStore defaultStore) {
            return defaultStore.pauseMetricsSampler(timeout);
        }
        return true;
    }

    public boolean isStoreMetricsSamplerRunning() {
        return utxoStore instanceof DefaultUtxoStore defaultStore
                && defaultStore.isMetricsSamplerRunning();
    }

    public void resumeAfterSnapshotRestore(boolean asyncHandlerPaused,
                                           boolean prunePaused,
                                           boolean metricsSamplerPaused) {
        if (asyncHandlerPaused && utxoStore != null && asyncEventHandler == null) {
            asyncEventHandler = new UtxoEventHandlerAsync(eventBus, utxoStore);
        }
        if (metricsSamplerPaused && utxoStore instanceof DefaultUtxoStore defaultStore) {
            defaultStore.resumeMetricsSampler();
        }
        if (prunePaused) {
            startPruneService();
        }
    }

    public boolean drainAsyncHandlerBeforeClose(Duration timeout) {
        UtxoEventHandlerAsync handler = asyncEventHandler;
        if (handler == null) {
            return true;
        }
        boolean drained = handler.closeAndAwait(timeout);
        if (drained) {
            asyncEventHandler = null;
        }
        return drained;
    }

    public void closeEventHandlers() {
        try {
            if (eventHandler != null) {
                eventHandler.close();
            }
        } catch (Exception ignored) {
        } finally {
            eventHandler = null;
        }
    }

    public void initializeFilterChain(List<StorageFilter> pluginFilters) {
        if (!(utxoStore instanceof DefaultUtxoStore defaultStore)) {
            return;
        }

        boolean filterEnabled = resolveBoolean(runtimeOptions.globals(), YanoPropertyKeys.UtxoFilter.ENABLED, false);
        if (!filterEnabled) {
            defaultStore.setFilterChain(null);
            log.info("UTXO storage filtering disabled");
            return;
        }

        Set<String> addresses = new HashSet<>();
        Set<String> paymentCreds = new HashSet<>();

        Object addrObj = runtimeOptions.globals().get(YanoPropertyKeys.UtxoFilter.ADDRESSES);
        if (addrObj instanceof java.util.Collection<?> collection) {
            for (Object address : collection) {
                if (address != null) {
                    addresses.add(String.valueOf(address));
                }
            }
        } else if (addrObj instanceof String address && !address.isBlank()) {
            addresses.add(address);
        }

        Object pcObj = runtimeOptions.globals().get(YanoPropertyKeys.UtxoFilter.PAYMENT_CREDENTIALS);
        if (pcObj instanceof java.util.Collection<?> collection) {
            for (Object paymentCred : collection) {
                if (paymentCred != null) {
                    paymentCreds.add(String.valueOf(paymentCred));
                }
            }
        } else if (pcObj instanceof String paymentCred && !paymentCred.isBlank()) {
            paymentCreds.add(paymentCred);
        }

        List<StorageFilter> filters = new ArrayList<>();
        if (!addresses.isEmpty() || !paymentCreds.isEmpty()) {
            filters.add(new AddressUtxoFilter(addresses, paymentCreds));
            log.info("UTXO filter configured: {} addresses, {} payment-credentials",
                    addresses.size(), paymentCreds.size());
        }
        if (pluginFilters != null && !pluginFilters.isEmpty()) {
            filters.addAll(pluginFilters);
        }

        StorageFilterChain filterChain = filters.isEmpty()
                ? null : new StorageFilterChain(filters);
        defaultStore.setFilterChain(filterChain);
        if (filterChain != null) {
            log.info("UTXO storage filter chain active with {} filter(s)", filters.size());
        } else {
            log.info("UTXO storage filter chain inactive (no configured filters)");
        }
    }

    @Override
    public void start() {
        startBackgroundServices();
    }

    public void startBackgroundServices() {
        startPruneService();
        startLagTask();
    }

    @Override
    public void stop() {
        pauseBackgroundServices();
    }

    public void pauseBackgroundServices() {
        if (pruneService != null) {
            try {
                pruneService.close();
            } catch (Exception e) {
                log.warn("Error stopping UTXO prune service", e);
            } finally {
                pruneService = null;
            }
        }

        if (lagTask != null) {
            try {
                lagTask.cancel(true);
            } catch (Exception e) {
                log.warn("Error cancelling UTXO lag task", e);
            } finally {
                lagTask = null;
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        pauseBackgroundServices();
        if (!drainAsyncHandlerBeforeClose(Duration.ofSeconds(30))) {
            throw new IllegalStateException("Async UTXO event handler did not drain during subsystem close");
        }
        closeEventHandlers();
        closeStore();
        closed = true;
    }

    private void closeStore() {
        if (utxoStore instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Error closing UTXO store", e);
            }
        }
    }

    @Override
    public SubsystemHealth health() {
        if (closed) {
            return SubsystemHealth.down(name(), "closed");
        }
        return SubsystemHealth.up(name());
    }

    private void initialize(RocksDbSupplier rocks) {
        try {
            boolean utxoEnabled = resolveBoolean(runtimeOptions.globals(), YanoPropertyKeys.Utxo.ENABLED, false);
            if (utxoEnabled && rocks != null) {
                utxoStore = UtxoStoreFactory.create(rocks, log, runtimeOptions.globals());
                if (config.isEnableClient()) {
                    reconcilePending = true;
                    log.info("UTXO store initialized; reconciliation deferred until startup recovery");
                } else {
                    reconcile();
                }

                boolean applyAsync = resolveBoolean(runtimeOptions.globals(), YanoPropertyKeys.Utxo.APPLY_ASYNC, false);
                if (applyAsync && config.isEnableClient()) {
                    log.warn("yano.utxo.applyAsync=true is disabled during client sync; ordered apply requires "
                            + "synchronous UTXO failures to propagate");
                    applyAsync = false;
                }
                if (applyAsync) {
                    asyncEventHandler = new UtxoEventHandlerAsync(eventBus, utxoStore);
                    log.info("UTXO store initialized ({}); UtxoEventHandlerAsync registered (applyAsync=true)",
                            storeType());
                } else {
                    eventHandler = new UtxoEventHandler(eventBus, utxoStore);
                    log.info("UTXO store initialized ({}); UtxoEventHandler registered", storeType());
                }
            } else {
                log.info("UTXO store not initialized (enabled={}, rocksdb={})", utxoEnabled, rocks != null);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to initialize UTXO store", t);
        }
    }

    private void reconcile() {
        if (utxoStore == null) {
            return;
        }
        try {
            utxoStore.reconcile(chainState);
            log.info("UTXO reconciliation complete at startup");
        } catch (Throwable t) {
            throw new IllegalStateException("UTXO reconciliation failed at startup", t);
        }
    }

    private void startPruneService() {
        if (utxoStore == null || pruneService != null || !(utxoStore instanceof Prunable prunable)) {
            return;
        }
        long intervalSec = parseLong(
                runtimeOptions.globals().get(YanoPropertyKeys.Utxo.PRUNE_SCHEDULE_SECONDS),
                5L);
        pruneService = new PruneService(prunable, Math.max(1L, intervalSec) * 1000L);
        pruneService.start();
        log.info("UTXO prune service started (interval={}s)", Math.max(1L, intervalSec));
    }

    private void startLagTask() {
        if (utxoStore == null || lagTask != null) {
            return;
        }
        long lagLogSec = parseLong(
                runtimeOptions.globals().get(YanoPropertyKeys.Utxo.METRICS_LAG_LOG_SECONDS),
                10L);
        final long failIfAbove = parseLong(
                runtimeOptions.globals().get(YanoPropertyKeys.Utxo.LAG_FAIL_IF_ABOVE),
                -1L);
        lagTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                long lastApplied = (utxoStore instanceof UtxoStatusProvider status)
                        ? status.getLastAppliedBlock() : 0L;
                var tip = chainState.getTip();
                long tipBlock = tip != null ? tip.getBlockNumber() : 0L;
                long lag = Math.max(0L, tipBlock - lastApplied);

                if (lag > 0) {
                    log.info("metric utxo.lag.blocks={}", lag);
                }

                if (failIfAbove > 0 && lag > failIfAbove) {
                    log.warn("UTXO lag {} blocks exceeds configured threshold {}", lag, failIfAbove);
                }
            } catch (Throwable ignored) {
                // Best-effort metric only.
            }
        }, Math.max(1L, lagLogSec), Math.max(1L, lagLogSec), TimeUnit.SECONDS);
    }

    private String storeType() {
        return utxoStore instanceof UtxoStatusProvider status ? status.storeType() : "?";
    }

    private static boolean resolveBoolean(Map<String, Object> globals, String key, boolean def) {
        Object value = globals.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return def;
    }

    private static long parseLong(Object obj, long def) {
        if (obj instanceof Number number) {
            return number.longValue();
        }
        if (obj != null) {
            try {
                return Long.parseLong(String.valueOf(obj));
            } catch (Exception ignored) {
            }
        }
        return def;
    }
}
