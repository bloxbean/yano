package org.yanoproject.runtime.storage;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import org.yanoproject.api.BlockBodyRetentionBoundary;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.api.db.RocksDbAccess;
import org.yanoproject.runtime.chain.BlockPruner;
import org.yanoproject.runtime.chain.BootstrapChainStateWriter;
import org.yanoproject.runtime.chain.ByronGenesisUtxoMetadataStore;
import org.yanoproject.runtime.chain.ChainStateRecovery;
import org.yanoproject.runtime.chain.ChainStateSnapshots;
import org.yanoproject.runtime.chain.DirectRocksDBChainState;
import org.yanoproject.runtime.chain.EraMetadataStore;
import org.yanoproject.runtime.chain.InMemoryChainState;
import org.yanoproject.runtime.db.RocksDbSupplier;
import org.yanoproject.runtime.kernel.Subsystem;
import org.yanoproject.runtime.kernel.SubsystemHealth;
import org.yanoproject.runtime.maintenance.RuntimeMaintenanceGate;
import org.yanoproject.runtime.migration.StakeBalanceIndexStartupMigration;
import org.yanoproject.runtime.migration.StartupMigrationContext;
import org.yanoproject.runtime.migration.StartupMigrationRunner;
import org.yanoproject.runtime.utxo.PruneService;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Storage-owned runtime boundary for chain-state construction, storage
 * capabilities, block-body pruning, migrations, and maintenance locking.
 */
public final class ChainStorageSubsystem implements Subsystem {
    private final YanoConfig config;
    private final RuntimeOptions runtimeOptions;
    private final Logger log;
    private final ChainState chainState;
    private final RuntimeMaintenanceGate maintenanceGate = new RuntimeMaintenanceGate();

    private PruneService blockPruneService;
    private final MutableBlockBodyRetentionBoundary blockBodyRetentionBoundary =
            new MutableBlockBodyRetentionBoundary();
    private boolean closed;

    public ChainStorageSubsystem(YanoConfig config, RuntimeOptions runtimeOptions, Logger log) {
        this.config = Objects.requireNonNull(config, "config");
        this.runtimeOptions = runtimeOptions != null ? runtimeOptions : RuntimeOptions.defaults();
        this.log = Objects.requireNonNull(log, "log");
        this.chainState = config.isUseRocksDB()
                ? new DirectRocksDBChainState(config.getRocksDBPath())
                : new InMemoryChainState();
    }

    @Override
    public String name() {
        return "chain-storage";
    }

    public ChainState chainState() {
        return chainState;
    }

    public RuntimeMaintenanceGate maintenanceGate() {
        return maintenanceGate;
    }

    public RocksDbSupplier rocksDbSupplierOrNull() {
        return chainState instanceof RocksDbSupplier supplier ? supplier : null;
    }

    public RocksDbAccess rocksDbAccessOrNull() {
        return chainState instanceof RocksDbAccess access ? access : null;
    }

    public EraMetadataStore eraMetadataStoreOrNull() {
        return chainState instanceof EraMetadataStore store ? store : null;
    }

    public ByronGenesisUtxoMetadataStore byronGenesisUtxoMetadataStoreOrNull() {
        return chainState instanceof ByronGenesisUtxoMetadataStore store ? store : null;
    }

    public ChainStateSnapshots snapshotsOrThrow() {
        if (chainState instanceof ChainStateSnapshots snapshots) {
            return snapshots;
        }
        throw new IllegalStateException("Chain state does not support snapshots: "
                + chainState.getClass().getName());
    }

    public BootstrapChainStateWriter bootstrapWriterOrNull() {
        return chainState instanceof BootstrapChainStateWriter writer ? writer : null;
    }

    public ChainStateRecovery recoveryOrNull() {
        return chainState instanceof ChainStateRecovery recovery ? recovery : null;
    }

    public void runStartupMigrations() {
        RocksDbSupplier rocks = rocksDbSupplierOrNull();
        if (rocks == null) {
            return;
        }

        StartupMigrationContext context = new StartupMigrationContext(
                rocks,
                runtimeOptions.globals(),
                config.getRocksDBPath());
        new StartupMigrationRunner().run(context, List.of(new StakeBalanceIndexStartupMigration()));
    }

    @Override
    public void start() {
        startBlockPruneService();
    }

    public void startBlockPruneService() {
        if (blockPruneService != null) {
            return;
        }

        int blockPruneDepth = (int) parseLong(
                runtimeOptions.globals().get(YanoPropertyKeys.Chain.BLOCK_BODY_PRUNE_DEPTH),
                0);
        RocksDbSupplier rocks = rocksDbSupplierOrNull();
        if (blockPruneDepth <= 0 || rocks == null) {
            log.info("Block body pruning disabled (block-body-prune-depth=0)");
            return;
        }

        int pruneBatch = (int) parseLong(
                runtimeOptions.globals().get(YanoPropertyKeys.Chain.BLOCK_PRUNE_BATCH_SIZE),
                200);
        long pruneIntervalSec = parseLong(
                runtimeOptions.globals().get(YanoPropertyKeys.Chain.BLOCK_PRUNE_INTERVAL_SECONDS),
                120L);
        blockPruneService = new PruneService(
                new BlockPruner(chainState, rocks, blockPruneDepth, pruneBatch, blockBodyRetentionBoundary),
                Math.max(1L, pruneIntervalSec) * 1000L);
        blockPruneService.start();
        log.info("Block body prune service started (retention={} blocks, batch={}, interval={}s)",
                blockPruneDepth, pruneBatch, pruneIntervalSec);
    }

    public void setBlockBodyRetentionBoundary(BlockBodyRetentionBoundary boundary) {
        blockBodyRetentionBoundary.setDelegate(boundary);
    }

    @Override
    public void stop() {
        stopBlockPruneService();
    }

    public void stopBlockPruneService() {
        stopBlockPruneServiceAndAwait(Duration.ofSeconds(5));
    }

    public boolean stopBlockPruneServiceAndAwait(Duration timeout) {
        if (blockPruneService == null) {
            return true;
        }
        boolean stopped = true;
        try {
            stopped = blockPruneService.closeAndAwait(timeout);
            if (!stopped) {
                log.warn("Block body prune service did not stop within timeout");
            }
        } catch (Exception e) {
            log.warn("Error stopping block body prune service", e);
            stopped = false;
        } finally {
            if (stopped) {
                blockPruneService = null;
            }
        }
        return stopped;
    }

    public boolean isBlockPruneServiceRunning() {
        return blockPruneService != null;
    }

    public void closeAfterRuntimeDrain(boolean unsafeLedgerApplyWorker) {
        if (closed) {
            return;
        }
        stopBlockPruneService();
        if (unsafeLedgerApplyWorker) {
            log.error("Skipping ChainState close because an apply worker did not stop or drain; "
                    + "process restart will recover from durable state");
            closed = true;
            return;
        }
        close();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        stopBlockPruneService();
        if (chainState instanceof AutoCloseable closeableChainState) {
            try {
                closeableChainState.close();
            } catch (Exception ignored) {
            }
        }
        closed = true;
    }

    @Override
    public SubsystemHealth health() {
        if (closed) {
            return SubsystemHealth.down(name(), "closed");
        }
        RuntimeMaintenanceGate.Degradation degradation = maintenanceGate.degradation();
        if (degradation != null) {
            return SubsystemHealth.degraded(name(), degradation.message());
        }
        return SubsystemHealth.up(name());
    }

    private static long parseLong(Object obj, long def) {
        if (obj instanceof Number n) {
            return n.longValue();
        }
        if (obj != null) {
            try {
                return Long.parseLong(String.valueOf(obj));
            } catch (Exception ignored) {
            }
        }
        return def;
    }

    /** Stable boundary captured by the pruner with safe optional-consumer detach. */
    private static final class MutableBlockBodyRetentionBoundary implements BlockBodyRetentionBoundary {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private BlockBodyRetentionBoundary delegate = BlockBodyRetentionBoundary.NONE;

        @Override
        public OptionalLong oldestRequiredBlockNumber() {
            lock.readLock().lock();
            try {
                return delegate.oldestRequiredBlockNumber();
            } finally {
                lock.readLock().unlock();
            }
        }

        void setDelegate(BlockBodyRetentionBoundary boundary) {
            lock.writeLock().lock();
            try {
                delegate = boundary == null ? BlockBodyRetentionBoundary.NONE : boundary;
            } finally {
                lock.writeLock().unlock();
            }
        }
    }
}
