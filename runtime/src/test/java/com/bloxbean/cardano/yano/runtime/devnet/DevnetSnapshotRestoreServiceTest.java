package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yano.runtime.chain.ChainStateSnapshots;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevnetSnapshotRestoreServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void restoreStopsRestoresReinitializesAndResumesRuntimeServicesInOrder() throws Exception {
        List<String> calls = new ArrayList<>();
        FakeSnapshotChainState chainState = new FakeSnapshotChainState(tempDir.resolve("chainstate"), calls);
        FakeActions actions = FakeActions.allRunning(calls);
        createCheckpoint(chainState, "snap");

        var result = restore(chainState, actions, "snap");

        assertEquals(42, result.slot());
        assertEquals(7, result.blockNumber());
        assertEquals(List.of(
                "pauseTxAdmission",
                "stopBlockProducer",
                "stopServer:PT10S",
                "pauseAsyncUtxo:PT30S",
                "pauseUtxoPrune:PT5S",
                "pauseUtxoMetrics:PT5S",
                "pauseAccountHistoryPrune:PT5S",
                "stopBlockPrune:PT5S",
                "restore",
                "reinitializeUtxo",
                "reinitializeLedger",
                "clearPendingTransactions",
                "resetBlockProducerToChainTip",
                "notifyServerNewDataAvailable",
                "invalidateSlotTimeCache",
                "resumeUtxo:true,true,true",
                "resumeLedger:true",
                "startBlockPrune",
                "startTxAdmission",
                "startServer",
                "startBlockProducer"
        ), calls);
        assertTrue(actions.blockProducerRunning);
        assertTrue(actions.serverRunning);
        assertTrue(actions.txAdmissionAccepting);
        assertTrue(actions.asyncUtxoHandlerRunning);
        assertTrue(actions.utxoPruneRunning);
        assertTrue(actions.utxoMetricsRunning);
        assertTrue(actions.accountHistoryPruneRunning);
        assertTrue(actions.blockPruneRunning);
    }

    @Test
    void missingSnapshotFailsBeforeRuntimeServicesArePaused() {
        List<String> calls = new ArrayList<>();
        FakeSnapshotChainState chainState = new FakeSnapshotChainState(tempDir.resolve("chainstate"), calls);
        FakeActions actions = FakeActions.allRunning(calls);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> restore(chainState, actions, "missing"));

        assertEquals("Snapshot 'missing' does not exist", error.getMessage());
        assertEquals(List.of(), calls);
    }

    @Test
    void preparationFailureMarksRuntimeDegradedWithoutStartingRestore() throws Exception {
        List<String> calls = new ArrayList<>();
        FakeSnapshotChainState chainState = new FakeSnapshotChainState(tempDir.resolve("chainstate"), calls);
        FakeActions actions = new FakeActions(calls);
        actions.asyncUtxoHandlerRunning = true;
        actions.asyncUtxoPauseSucceeds = false;
        createCheckpoint(chainState, "snap");
        RuntimeMaintenanceGate gate = new RuntimeMaintenanceGate();

        IllegalStateException error;
        try (var maintenance = gate.enterMaintenance("restore")) {
            error = assertThrows(IllegalStateException.class,
                    () -> service(chainState, actions).restoreAndGetTip("snap", maintenance));
        }

        assertEquals("Cannot restore snapshot because async UTXO handler did not drain", error.getMessage());
        assertEquals(List.of("pauseAsyncUtxo:PT30S"), calls);
        assertFalse(chainState.restoreCalled);
        assertTrue(gate.isDegraded());
        assertEquals("restore", gate.degradation().operation());
        assertEquals("Snapshot 'snap' restore preparation failed after a service did not drain; runtime remains "
                + "stopped/degraded and should be restarted before continuing", gate.degradation().message());
    }

    @Test
    void restoreFailureAfterStorageReplacementStartsMarksRuntimeDegraded() throws Exception {
        List<String> calls = new ArrayList<>();
        FakeSnapshotChainState chainState = new FakeSnapshotChainState(tempDir.resolve("chainstate"), calls);
        chainState.failRestore = true;
        FakeActions actions = new FakeActions(calls);
        createCheckpoint(chainState, "snap");
        RuntimeMaintenanceGate gate = new RuntimeMaintenanceGate();

        RuntimeException error;
        try (var maintenance = gate.enterMaintenance("restore")) {
            error = assertThrows(RuntimeException.class,
                    () -> service(chainState, actions).restoreAndGetTip("snap", maintenance));
        }

        assertEquals("restore failed", error.getMessage());
        assertEquals(List.of("restore"), calls);
        assertTrue(gate.isDegraded());
        assertEquals("restore", gate.degradation().operation());
        assertEquals("Snapshot 'snap' failed after RocksDB replacement started; runtime remains paused "
                + "and should be restarted before continuing", gate.degradation().message());
    }

    @Test
    void successfulRestoreWithResumeFailureThrowsAndMarksRuntimeDegraded() throws Exception {
        List<String> calls = new ArrayList<>();
        FakeSnapshotChainState chainState = new FakeSnapshotChainState(tempDir.resolve("chainstate"), calls);
        FakeActions actions = new FakeActions(calls);
        actions.failResumeUtxo = true;
        createCheckpoint(chainState, "snap");
        RuntimeMaintenanceGate gate = new RuntimeMaintenanceGate();

        IllegalStateException error;
        try (var maintenance = gate.enterMaintenance("restore")) {
            error = assertThrows(IllegalStateException.class,
                    () -> service(chainState, actions).restoreAndGetTip("snap", maintenance));
        }

        assertEquals("Snapshot restored but runtime services did not resume; restart required", error.getMessage());
        assertEquals(List.of(
                "restore",
                "reinitializeUtxo",
                "reinitializeLedger",
                "clearPendingTransactions",
                "resetBlockProducerToChainTip",
                "notifyServerNewDataAvailable",
                "invalidateSlotTimeCache",
                "resumeUtxo:false,false,false"
        ), calls);
        assertTrue(gate.isDegraded());
        assertEquals("restore", gate.degradation().operation());
        assertEquals("Snapshot restored but runtime services did not resume; restart required",
                gate.degradation().message());
    }

    private void createCheckpoint(FakeSnapshotChainState chainState, String name) throws Exception {
        DevnetSnapshotStore store = new DevnetSnapshotStore(chainState, chainState, null);
        Files.createDirectories(store.checkpointDir(name));
    }

    private static com.bloxbean.cardano.yano.api.model.DevnetRestoreResult restore(
            FakeSnapshotChainState chainState,
            FakeActions actions,
            String name) {
        RuntimeMaintenanceGate gate = new RuntimeMaintenanceGate();
        try (var maintenance = gate.enterMaintenance("restore")) {
            return service(chainState, actions).restoreAndGetTip(name, maintenance);
        }
    }

    private static DevnetSnapshotRestoreService service(FakeSnapshotChainState chainState,
                                                        FakeActions actions) {
        return new DevnetSnapshotRestoreService(chainState, chainState, () -> null, actions);
    }

    private static byte[] hash(long value) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) value);
        return hash;
    }

    private static final class FakeSnapshotChainState extends InMemoryChainState implements ChainStateSnapshots {
        private final Path dbPath;
        private final List<String> calls;
        private boolean restoreCalled;
        private boolean failRestore;

        private FakeSnapshotChainState(Path dbPath, List<String> calls) {
            this.dbPath = dbPath;
            this.calls = calls;
        }

        @Override
        public void createSnapshot(String snapshotPath) {
        }

        @Override
        public void restoreFromSnapshot(String snapshotPath) {
            restoreCalled = true;
            calls.add("restore");
            if (failRestore) {
                throw new RuntimeException("restore failed");
            }
            storeBlock(hash(42), 7L, 42L, new byte[]{1});
        }

        @Override
        public String getDbPath() {
            return dbPath.toString();
        }
    }

    private static final class FakeActions implements DevnetSnapshotRestoreService.Actions {
        private final List<String> calls;
        private boolean blockProducerRunning;
        private boolean serverRunning;
        private boolean txAdmissionAccepting;
        private boolean asyncUtxoHandlerRunning;
        private boolean utxoPruneRunning;
        private boolean utxoMetricsRunning;
        private boolean accountHistoryPruneRunning;
        private boolean blockPruneRunning;
        private boolean asyncUtxoPauseSucceeds = true;
        private boolean failResumeUtxo;

        private FakeActions(List<String> calls) {
            this.calls = calls;
        }

        private static FakeActions allRunning(List<String> calls) {
            FakeActions actions = new FakeActions(calls);
            actions.blockProducerRunning = true;
            actions.serverRunning = true;
            actions.txAdmissionAccepting = true;
            actions.asyncUtxoHandlerRunning = true;
            actions.utxoPruneRunning = true;
            actions.utxoMetricsRunning = true;
            actions.accountHistoryPruneRunning = true;
            actions.blockPruneRunning = true;
            return actions;
        }

        @Override
        public boolean isBlockProducerRunning() {
            return blockProducerRunning;
        }

        @Override
        public void stopBlockProducer() {
            calls.add("stopBlockProducer");
            blockProducerRunning = false;
        }

        @Override
        public void resetBlockProducerToChainTip() {
            calls.add("resetBlockProducerToChainTip");
        }

        @Override
        public void startBlockProducer() {
            calls.add("startBlockProducer");
            blockProducerRunning = true;
        }

        @Override
        public boolean isServerRunning() {
            return serverRunning;
        }

        @Override
        public boolean stopServerAndAwait(Duration timeout) {
            calls.add("stopServer:" + timeout);
            serverRunning = false;
            return true;
        }

        @Override
        public void startServer() {
            calls.add("startServer");
            serverRunning = true;
        }

        @Override
        public void notifyServerNewDataAvailable() {
            calls.add("notifyServerNewDataAvailable");
        }

        @Override
        public boolean isTxAdmissionAccepting() {
            return txAdmissionAccepting;
        }

        @Override
        public void pauseTxAdmissionAndAwait() {
            calls.add("pauseTxAdmission");
            txAdmissionAccepting = false;
        }

        @Override
        public void startTxAdmission() {
            calls.add("startTxAdmission");
            txAdmissionAccepting = true;
        }

        @Override
        public void stopTxAdmission() {
            calls.add("stopTxAdmission");
            txAdmissionAccepting = false;
        }

        @Override
        public void clearPendingTransactions() {
            calls.add("clearPendingTransactions");
        }

        @Override
        public boolean isAsyncUtxoHandlerRunning() {
            return asyncUtxoHandlerRunning;
        }

        @Override
        public boolean pauseAsyncUtxoHandlerAndAwait(Duration timeout) {
            calls.add("pauseAsyncUtxo:" + timeout);
            asyncUtxoHandlerRunning = false;
            return asyncUtxoPauseSucceeds;
        }

        @Override
        public boolean isUtxoPruneServiceRunning() {
            return utxoPruneRunning;
        }

        @Override
        public boolean pauseUtxoPruneServiceAndAwait(Duration timeout) {
            calls.add("pauseUtxoPrune:" + timeout);
            utxoPruneRunning = false;
            return true;
        }

        @Override
        public boolean isUtxoMetricsSamplerRunning() {
            return utxoMetricsRunning;
        }

        @Override
        public boolean pauseUtxoMetricsSamplerAndAwait(Duration timeout) {
            calls.add("pauseUtxoMetrics:" + timeout);
            utxoMetricsRunning = false;
            return true;
        }

        @Override
        public void reinitializeUtxoAndReconcileAfterSnapshotRestore() {
            calls.add("reinitializeUtxo");
        }

        @Override
        public void resumeUtxoAfterSnapshotRestore(boolean asyncUtxoHandlerPaused,
                                                   boolean utxoPrunePaused,
                                                   boolean utxoMetricsSamplerPaused) {
            calls.add("resumeUtxo:" + asyncUtxoHandlerPaused + "," + utxoPrunePaused + ","
                    + utxoMetricsSamplerPaused);
            if (failResumeUtxo) {
                throw new IllegalStateException("resume failed");
            }
            if (asyncUtxoHandlerPaused) {
                asyncUtxoHandlerRunning = true;
            }
            if (utxoPrunePaused) {
                utxoPruneRunning = true;
            }
            if (utxoMetricsSamplerPaused) {
                utxoMetricsRunning = true;
            }
        }

        @Override
        public boolean isAccountHistoryPruneServiceRunning() {
            return accountHistoryPruneRunning;
        }

        @Override
        public boolean pauseAccountHistoryPruneServiceAndAwait(Duration timeout) {
            calls.add("pauseAccountHistoryPrune:" + timeout);
            accountHistoryPruneRunning = false;
            return true;
        }

        @Override
        public void reinitializeLedgerAndReconcileAfterSnapshotRestore() {
            calls.add("reinitializeLedger");
        }

        @Override
        public void resumeLedgerAfterSnapshotRestore(boolean accountHistoryPrunePaused) {
            calls.add("resumeLedger:" + accountHistoryPrunePaused);
            if (accountHistoryPrunePaused) {
                accountHistoryPruneRunning = true;
            }
        }

        @Override
        public boolean isBlockPruneServiceRunning() {
            return blockPruneRunning;
        }

        @Override
        public boolean stopBlockPruneServiceAndAwait(Duration timeout) {
            calls.add("stopBlockPrune:" + timeout);
            blockPruneRunning = false;
            return true;
        }

        @Override
        public void startBlockPruneService() {
            calls.add("startBlockPrune");
            blockPruneRunning = true;
        }

        @Override
        public void invalidateSlotTimeCache() {
            calls.add("invalidateSlotTimeCache");
        }
    }
}
