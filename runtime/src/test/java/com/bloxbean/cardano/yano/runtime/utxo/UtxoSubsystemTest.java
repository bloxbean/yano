package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.api.plugin.StorageFilter;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class UtxoSubsystemTest {

    @TempDir
    Path tempDir;

    @Test
    void startsAndPausesBackgroundServicesIdempotently() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        FakeUtxoStore store = new FakeUtxoStore();
        UtxoSubsystem subsystem = testSubsystem(store, scheduler);

        try {
            subsystem.startBackgroundServices();
            subsystem.startBackgroundServices();

            assertThat(subsystem.isPruneServiceRunning()).isTrue();

            subsystem.pauseBackgroundServices();
            subsystem.pauseBackgroundServices();

            assertThat(subsystem.isPruneServiceRunning()).isFalse();
        } finally {
            subsystem.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void productionConstructorDefersBackgroundServicesUntilStart() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RuntimeOptions options = new RuntimeOptions(null, null, Map.of(
                "yano.utxo.enabled", true,
                "yano.utxo.prune.schedule.seconds", 60,
                "yano.utxo.metrics.lag.logSeconds", 60));

        try (DirectRocksDBChainState chain = new DirectRocksDBChainState(
                tempDir.resolve("chainstate").toString())) {
            UtxoSubsystem subsystem = new UtxoSubsystem(
                    YanoConfig.serverOnly(0),
                    options,
                    chain,
                    chain,
                    new NoopEventBus(),
                    scheduler,
                    LoggerFactory.getLogger(UtxoSubsystemTest.class));

            try {
                assertThat(subsystem.isPruneServiceRunning()).isFalse();

                subsystem.start();

                assertThat(subsystem.isPruneServiceRunning()).isTrue();
            } finally {
                subsystem.close();
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void emptyFilterSnapshotClearsStartCycleFilterFromPreviousRun() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RuntimeOptions options = new RuntimeOptions(null, null, Map.of(
                "yano.utxo.enabled", true,
                YanoPropertyKeys.UtxoFilter.ENABLED, true,
                "yano.utxo.prune.schedule.seconds", 60,
                "yano.utxo.metrics.lag.logSeconds", 60));

        try (DirectRocksDBChainState chain = new DirectRocksDBChainState(
                tempDir.resolve("filter-chainstate").toString())) {
            UtxoSubsystem subsystem = new UtxoSubsystem(
                    YanoConfig.serverOnly(0),
                    options,
                    chain,
                    chain,
                    new NoopEventBus(),
                    scheduler,
                    LoggerFactory.getLogger(UtxoSubsystemTest.class));

            try {
                DefaultUtxoStore store = (DefaultUtxoStore) subsystem.store();

                subsystem.initializeFilterChain(List.of(new StorageFilter() { }));
                assertThat(store.activeStorageFilterCount()).isOne();

                subsystem.initializeFilterChain(List.of());
                assertThat(store.activeStorageFilterCount()).isZero();
            } finally {
                subsystem.close();
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void reinitializesAndReconcilesAfterSnapshotRestore() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        FakeUtxoStore store = new FakeUtxoStore();
        UtxoSubsystem subsystem = testSubsystem(store, scheduler);

        try {
            subsystem.reinitializeAndReconcileAfterSnapshotRestore();

            assertThat(store.reinitializeCalls).isEqualTo(1);
            assertThat(store.reconcileCalls).isEqualTo(1);
        } finally {
            subsystem.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void pausePruneReturnsTrueWhenServiceStops() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        UtxoSubsystem subsystem = testSubsystem(new FakeUtxoStore(), scheduler);

        try {
            subsystem.startBackgroundServices();

            assertThat(subsystem.pausePruneServiceAndAwait(Duration.ofSeconds(1))).isTrue();
            assertThat(subsystem.isPruneServiceRunning()).isFalse();
        } finally {
            subsystem.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void terminalCloseReleasesStoreResources() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        FakeUtxoStore store = new FakeUtxoStore();
        UtxoSubsystem subsystem = testSubsystem(store, scheduler);

        try {
            subsystem.startBackgroundServices();

            subsystem.close();
            subsystem.close();

            assertThat(subsystem.isPruneServiceRunning()).isFalse();
            assertThat(store.closeCalls).isEqualTo(1);
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static UtxoSubsystem testSubsystem(FakeUtxoStore store, ScheduledExecutorService scheduler) {
        RuntimeOptions options = new RuntimeOptions(null, null, Map.of(
                "yano.utxo.prune.schedule.seconds", 60,
                "yano.utxo.metrics.lag.logSeconds", 60));
        ChainState chainState = new InMemoryChainState();
        return new UtxoSubsystem(
                YanoConfig.serverOnly(0),
                options,
                chainState,
                store,
                new NoopEventBus(),
                scheduler,
                LoggerFactory.getLogger(UtxoSubsystemTest.class));
    }

    private static final class FakeUtxoStore implements UtxoStoreWriter, Prunable, AutoCloseable {
        private int reinitializeCalls;
        private int reconcileCalls;
        private int closeCalls;

        @Override
        public void applyBlock(BlockAppliedEvent e) {
        }

        @Override
        public void rollbackTo(RollbackEvent e) {
        }

        @Override
        public void reconcile(ChainState chainState) {
            reconcileCalls++;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void reinitialize() {
            reinitializeCalls++;
        }

        @Override
        public void pruneOnce() {
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
