package com.bloxbean.cardano.yano.runtime.ledger;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.db.RocksDbAccess;
import com.bloxbean.cardano.yano.ledgerstate.AccountHistoryEventHandler;
import com.bloxbean.cardano.yano.ledgerstate.AccountHistoryStore;
import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.utxo.PruneService;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Owns account-history derived-state storage, event handler registration,
 * reconciliation, pruning, snapshot-restore reinitialization, and rollback
 * verification.
 */
public final class AccountHistorySubsystem implements Subsystem {
    private final YanoConfig config;
    private final RuntimeOptions runtimeOptions;
    private final ChainState chainState;
    private final EventBus eventBus;
    private final Logger log;

    private AccountHistoryStore store;
    private AccountHistoryEventHandler eventHandler;
    private PruneService pruneService;
    private boolean reconcilePending;
    private boolean closed;

    public AccountHistorySubsystem(YanoConfig config,
                                   RuntimeOptions runtimeOptions,
                                   ChainState chainState,
                                   EventBus eventBus,
                                   Logger log) {
        this.config = Objects.requireNonNull(config, "config");
        this.runtimeOptions = runtimeOptions != null ? runtimeOptions : RuntimeOptions.defaults();
        this.chainState = Objects.requireNonNull(chainState, "chainState");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public String name() {
        return "account-history";
    }

    public AccountHistoryStore store() {
        return store;
    }

    public void initialize(RocksDbAccess rocksAccess, EpochParamProvider epochParamProvider) {
        boolean enabled = resolveBoolean(runtimeOptions.globals(), YanoPropertyKeys.AccountHistory.ENABLED, false);
        if (enabled && rocksAccess != null) {
            try {
                store = new AccountHistoryStore(
                        (RocksDB) rocksAccess.getDb(),
                        cfSupplier(rocksAccess),
                        log,
                        runtimeOptions.globals(),
                        epochParamProvider);
                if (config.isEnableClient()) {
                    reconcilePending = true;
                    log.info("Account history store initialized; reconciliation deferred until startup recovery");
                } else {
                    reconcile();
                }
                eventHandler = new AccountHistoryEventHandler(eventBus, store);

                log.info("Account history store initialized (tx-events={}, rewards-history={}, retentionEpochs={})",
                        store.isTxEventsEnabled(),
                        store.isRewardsHistoryEnabled(),
                        store.getRetentionEpochs());
            } catch (Throwable t) {
                store = null;
                throw new IllegalStateException("Failed to initialize account history store", t);
            }
        } else if (enabled) {
            throw new IllegalStateException("Account history requested but not initialized (rocksdb="
                    + (rocksAccess != null) + ")");
        }
    }

    public void completeStartupRecovery() {
        if (reconcilePending) {
            reconcile();
            reconcilePending = false;
        }
    }

    public void reinitializeAndReconcileAfterSnapshotRestore(RocksDbAccess rocksAccess) {
        if (store == null) {
            return;
        }
        if (rocksAccess == null) {
            throw new IllegalStateException("Account history restore requires RocksDB access");
        }
        store.reinitialize((RocksDB) rocksAccess.getDb(), cfSupplier(rocksAccess));
        try {
            store.reconcile(chainState);
        } catch (Throwable t) {
            throw new IllegalStateException("Account history reconciliation after snapshot restore failed", t);
        }
    }

    public boolean isPruneServiceRunning() {
        return pruneService != null;
    }

    @Override
    public void start() {
        startPruneService();
    }

    public void startPruneService() {
        if (pruneService != null || store == null) {
            return;
        }

        int retentionEpochs = store.getRetentionEpochs();
        if (retentionEpochs <= 0) {
            return;
        }

        long pruneIntervalSec = parseLong(
                runtimeOptions.globals().get(YanoPropertyKeys.AccountHistory.PRUNE_INTERVAL_SECONDS),
                300L);
        pruneService = new PruneService(
                store::pruneOnce,
                Math.max(1L, pruneIntervalSec) * 1000L);
        pruneService.start();
        log.info("Account history prune service started (retention={} epochs, interval={}s)",
                retentionEpochs, pruneIntervalSec);
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
                log.warn("Error stopping account history prune service", e);
            } finally {
                pruneService = null;
            }
        }
    }

    public boolean pausePruneServiceAndAwait(Duration timeout) {
        if (pruneService == null) {
            return true;
        }
        try {
            boolean stopped = pruneService.closeAndAwait(timeout);
            if (stopped) {
                pruneService = null;
                log.info("account-history prune service paused for snapshot restore");
            } else {
                log.warn("account-history prune service did not stop within timeout");
            }
            return stopped;
        } catch (Exception e) {
            log.warn("Error pausing account-history prune service for snapshot restore: {}", e.toString());
            return false;
        }
    }

    public void resumeAfterSnapshotRestore(boolean prunePaused) {
        if (prunePaused) {
            startPruneService();
        }
    }

    public void ensureRolledBack(Point rollbackPoint) {
        if (store == null || !store.isEnabled() || rollbackPoint == null) {
            return;
        }
        long targetSlot = rollbackPoint.getSlot();
        try {
            store.rollbackToSlot(targetSlot);
            long latest = store.getLatestAppliedSlot();
            if (latest > targetSlot) {
                throw new IllegalStateException("Account history latestAppliedSlot=" + latest
                        + " after rollback to " + targetSlot);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("Account history rollback verification failed for slot "
                    + targetSlot, t);
        }
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

    @Override
    public void close() {
        if (closed) {
            return;
        }
        pauseBackgroundServices();
        closeEventHandlers();
        closed = true;
    }

    @Override
    public SubsystemHealth health() {
        if (closed) {
            return SubsystemHealth.down(name(), "closed");
        }
        return SubsystemHealth.up(name());
    }

    private void reconcile() {
        if (store == null) {
            return;
        }
        try {
            store.reconcile(chainState);
            log.info("Account history reconciliation complete at startup");
        } catch (Throwable t) {
            throw new IllegalStateException("Account history reconciliation failed at startup", t);
        }
    }

    private static DefaultAccountStateStore.CfSupplier cfSupplier(RocksDbAccess rocksAccess) {
        return new DefaultAccountStateStore.CfSupplier() {
            @Override
            public ColumnFamilyHandle handle(String cfName) {
                return (ColumnFamilyHandle) rocksAccess.getColumnFamilyHandle(cfName);
            }

            @Override
            public RocksDB db() {
                return (RocksDB) rocksAccess.getDb();
            }
        };
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
