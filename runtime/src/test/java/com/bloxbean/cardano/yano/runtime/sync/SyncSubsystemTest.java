package com.bloxbean.cardano.yano.runtime.sync;

import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yano.api.SyncPhase;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.ledger.LedgerStateSubsystem;
import com.bloxbean.cardano.yano.runtime.peer.PeerRecoveryReason;
import com.bloxbean.cardano.yano.runtime.server.ServeSubsystem;
import com.bloxbean.cardano.yano.runtime.storage.ChainStorageSubsystem;
import com.bloxbean.cardano.yano.runtime.tx.TransactionAdmission;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class SyncSubsystemTest {

    @Test
    void ownsProgressCountersAndCloseHealth() {
        YanoConfig config = YanoConfig.builder()
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(42L)
                .serverPort(0)
                .enableServer(false)
                .enableClient(false)
                .useRocksDB(false)
                .fullSyncThreshold(1_800)
                .enablePipelinedSync(true)
                .headerPipelineDepth(10)
                .bodyBatchSize(5)
                .maxParallelBodies(2)
                .build();
        RuntimeOptions options = new RuntimeOptions(null, null, Map.of(
                "yano.account-state.enabled", false,
                "yano.account-history.enabled", false));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ChainStorageSubsystem chainStorage = new ChainStorageSubsystem(
                config,
                options,
                LoggerFactory.getLogger(SyncSubsystemTest.class));
        LedgerStateSubsystem ledgerState = new LedgerStateSubsystem(
                config,
                options,
                chainStorage.chainState(),
                new NoopEventBus(),
                LoggerFactory.getLogger(SyncSubsystemTest.class),
                null,
                null,
                null,
                null,
                () -> null,
                () -> null,
                () -> null,
                null);
        ServeSubsystem serve = new ServeSubsystem(
                0,
                config.getProtocolMagic(),
                chainStorage.chainState(),
                noopTransactionAdmission(),
                false,
                LoggerFactory.getLogger(SyncSubsystemTest.class));
        SyncSubsystem sync = new SyncSubsystem(
                config,
                chainStorage.chainState(),
                new NoopEventBus(),
                scheduler,
                serve,
                ledgerState,
                chainStorage,
                () -> false,
                ledgerState::epochParamProvider,
                ledgerState::currentGenesisBootstrapData,
                config.getRemoteHost(),
                config.getRemotePort(),
                config.getProtocolMagic(),
                LoggerFactory.getLogger(SyncSubsystemTest.class));

        try {
            assertThat(sync.name()).isEqualTo("sync");
            assertThat(sync.health().healthy()).isTrue();
            assertThat(sync.isSyncing()).isFalse();
            assertThat(sync.isInitialSyncComplete()).isFalse();
            assertThat(sync.syncPhase()).isEqualTo(SyncPhase.INITIAL_SYNC);
            assertThat(sync.stopForShutdown()).isFalse();

            sync.updateSyncProgress(42L, 7L);

            assertThat(sync.blocksProcessed()).isEqualTo(1L);
            assertThat(sync.lastProcessedSlot()).isEqualTo(42L);

            sync.close();

            assertThat(sync.health().status()).isEqualTo(SubsystemHealth.Status.DOWN);
        } finally {
            sync.close();
            ledgerState.close();
            serve.close();
            chainStorage.closeAfterRuntimeDrain(false);
            scheduler.shutdownNow();
        }
    }

    @Test
    void serverOnlyModeDoesNotRequireRemoteHost() {
        YanoConfig config = serverOnlyConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> false, null);

        try {
            SyncSubsystem sync = runtime.sync(null);

            assertThat(sync.health().healthy()).isTrue();
            assertThat(sync.isSyncing()).isFalse();
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void stopCancelsPendingIntersectionTransition() {
        YanoConfig config = serverOnlyConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> false, null);

        try {
            SyncSubsystem sync = runtime.sync(null);
            sync.setRollbackClassificationTimeoutMillis(60_000L);

            sync.onIntersectionFound();
            assertThat(sync.syncPhase()).isEqualTo(SyncPhase.INTERSECT_PHASE);
            assertThat(sync.hasPendingIntersectionTransition()).isTrue();

            sync.stopForShutdown();

            assertThat(sync.hasPendingIntersectionTransition()).isFalse();
            assertThat(sync.syncPhase()).isEqualTo(SyncPhase.INTERSECT_PHASE);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void clientStartupCancelsPendingIntersectionTransition() {
        YanoConfig config = serverOnlyConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> false, null);

        try {
            SyncSubsystem sync = runtime.sync(null);
            sync.setRollbackClassificationTimeoutMillis(60_000L);

            sync.onIntersectionFound();
            assertThat(sync.hasPendingIntersectionTransition()).isTrue();

            sync.startClientSync();

            assertThat(sync.hasPendingIntersectionTransition()).isFalse();
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void recoverableStartupRecoveryFailureDoesNotMarkPeerTerminal() {
        YanoConfig config = clientConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true,
                (endpoint, point) -> {
                    throw new RuntimeException("temporary");
                });

        try {
            SyncSubsystem sync = runtime.sync(config.getRemoteHost());

            sync.startClientSync();

            assertThat(sync.peerRecoverySnapshot().terminal()).isFalse();
            assertThat(sync.health().status()).isEqualTo(SubsystemHealth.Status.UP);
            assertThat(sync.currentPeerSessionStatus().terminalFailureMessage()).isNull();
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    void terminalStartupRecoveryFailureStopsSyncAndReportsDownHealth() {
        YanoConfig config = clientConfig();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TestRuntime runtime = new TestRuntime(config, scheduler, () -> true,
                (endpoint, point) -> {
                    throw new RuntimeException("boom");
                });

        try {
            SyncSubsystem sync = runtime.sync(config.getRemoteHost());

            for (int i = 0; i < 10; i++) {
                sync.startClientSync();
            }

            assertThat(sync.isSyncing()).isFalse();
            assertThat(sync.peerRecoverySnapshot().terminal()).isTrue();
            assertThat(sync.health().status()).isEqualTo(SubsystemHealth.Status.DOWN);
            assertThat(sync.currentPeerSessionStatus().lastRecoveryReason())
                    .isEqualTo(PeerRecoveryReason.STARTUP_FAILED);
        } finally {
            runtime.close();
            scheduler.shutdownNow();
        }
    }

    private static YanoConfig serverOnlyConfig() {
        return YanoConfig.builder()
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(42L)
                .serverPort(0)
                .enableServer(false)
                .enableClient(false)
                .useRocksDB(false)
                .fullSyncThreshold(1_800)
                .enablePipelinedSync(true)
                .headerPipelineDepth(10)
                .bodyBatchSize(5)
                .maxParallelBodies(2)
                .build();
    }

    private static YanoConfig clientConfig() {
        return YanoConfig.builder()
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(42L)
                .serverPort(0)
                .enableServer(false)
                .enableClient(true)
                .useRocksDB(false)
                .fullSyncThreshold(1_800)
                .enablePipelinedSync(true)
                .headerPipelineDepth(10)
                .bodyBatchSize(5)
                .maxParallelBodies(2)
                .build();
    }

    private static final class TestRuntime implements AutoCloseable {
        private final YanoConfig config;
        private final ScheduledExecutorService scheduler;
        private final java.util.function.BooleanSupplier running;
        private final com.bloxbean.cardano.yano.runtime.peer.PeerClientFactory peerClientFactory;
        private Owned owned;

        private TestRuntime(YanoConfig config,
                            ScheduledExecutorService scheduler,
                            java.util.function.BooleanSupplier running,
                            com.bloxbean.cardano.yano.runtime.peer.PeerClientFactory peerClientFactory) {
            this.config = config;
            this.scheduler = scheduler;
            this.running = running;
            this.peerClientFactory = peerClientFactory;
        }

        private SyncSubsystem sync(String remoteHost) {
            ChainStorageSubsystem chainStorage = new ChainStorageSubsystem(
                    config,
                    options(),
                    LoggerFactory.getLogger(SyncSubsystemTest.class));
            LedgerStateSubsystem ledgerState = new LedgerStateSubsystem(
                    config,
                    options(),
                    chainStorage.chainState(),
                    new NoopEventBus(),
                    LoggerFactory.getLogger(SyncSubsystemTest.class),
                    null,
                    null,
                    null,
                    null,
                    () -> null,
                    () -> null,
                    () -> null,
                    null);
            ServeSubsystem serve = new ServeSubsystem(
                    0,
                    config.getProtocolMagic(),
                    chainStorage.chainState(),
                    noopTransactionAdmission(),
                    false,
                    LoggerFactory.getLogger(SyncSubsystemTest.class));
            SyncSubsystem sync = new SyncSubsystem(
                    config,
                    chainStorage.chainState(),
                    new NoopEventBus(),
                    scheduler,
                    serve,
                    ledgerState,
                    chainStorage,
                    running,
                    ledgerState::epochParamProvider,
                    ledgerState::currentGenesisBootstrapData,
                    remoteHost,
                    config.getRemotePort(),
                    config.getProtocolMagic(),
                    LoggerFactory.getLogger(SyncSubsystemTest.class),
                    peerClientFactory != null ? peerClientFactory
                            : (endpoint, point) -> new com.bloxbean.cardano.yaci.helper.PeerClient(
                            endpoint.host(), endpoint.port(), endpoint.protocolMagic(), point));
            owned = new Owned(sync, ledgerState, serve, chainStorage);
            return sync;
        }

        @Override
        public void close() {
            if (owned != null) {
                owned.close();
                owned = null;
            }
        }
    }

    private record Owned(SyncSubsystem sync,
                         LedgerStateSubsystem ledgerState,
                         ServeSubsystem serve,
                         ChainStorageSubsystem chainStorage) implements AutoCloseable {
        @Override
        public void close() {
            sync.close();
            ledgerState.close();
            serve.close();
            chainStorage.closeAfterRuntimeDrain(false);
        }
    }

    private static RuntimeOptions options() {
        return new RuntimeOptions(null, null, Map.of(
                "yano.account-state.enabled", false,
                "yano.account-history.enabled", false));
    }

    private static TransactionAdmission noopTransactionAdmission() {
        return new TransactionAdmission() {
            @Override
            public String admitTransaction(byte[] txCbor, String origin) {
                return "tx";
            }

            @Override
            public int mempoolSize() {
                return 0;
            }
        };
    }
}
