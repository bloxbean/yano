package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.model.DevnetRestoreResult;
import com.bloxbean.cardano.yano.runtime.blockproducer.BlockProducerService;
import com.bloxbean.cardano.yano.runtime.chain.ChainStateSnapshots;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Coordinates devnet snapshot restore across storage and runtime services.
 */
@Slf4j
public final class DevnetSnapshotRestoreService {
    /**
     * Runtime callbacks used to pause, replace, and resume mutable services
     * around snapshot restore.
     */
    public interface Actions {
        boolean isBlockProducerRunning();

        void stopBlockProducer();

        void resetBlockProducerToChainTip();

        void startBlockProducer();

        boolean isServerRunning();

        boolean stopServerAndAwait(Duration timeout);

        void startServer();

        void notifyServerNewDataAvailable();

        boolean isTxAdmissionAccepting();

        void pauseTxAdmissionAndAwait();

        void startTxAdmission();

        void stopTxAdmission();

        void clearPendingTransactions();

        boolean isAsyncUtxoHandlerRunning();

        boolean pauseAsyncUtxoHandlerAndAwait(Duration timeout);

        boolean isUtxoPruneServiceRunning();

        boolean pauseUtxoPruneServiceAndAwait(Duration timeout);

        boolean isUtxoMetricsSamplerRunning();

        boolean pauseUtxoMetricsSamplerAndAwait(Duration timeout);

        void reinitializeUtxoAndReconcileAfterSnapshotRestore();

        void resumeUtxoAfterSnapshotRestore(boolean asyncUtxoHandlerPaused,
                                            boolean utxoPrunePaused,
                                            boolean utxoMetricsSamplerPaused);

        boolean isAccountHistoryPruneServiceRunning();

        boolean pauseAccountHistoryPruneServiceAndAwait(Duration timeout);

        void reinitializeLedgerAndReconcileAfterSnapshotRestore();

        void resumeLedgerAfterSnapshotRestore(boolean accountHistoryPrunePaused);

        boolean isBlockPruneServiceRunning();

        boolean stopBlockPruneServiceAndAwait(Duration timeout);

        void startBlockPruneService();

        void invalidateSlotTimeCache();
    }

    private final ChainState chainState;
    private final ChainStateSnapshots snapshots;
    private final Supplier<BlockProducerService> blockProducerService;
    private final Actions actions;

    public DevnetSnapshotRestoreService(ChainState chainState,
                                        ChainStateSnapshots snapshots,
                                        Supplier<BlockProducerService> blockProducerService,
                                        Actions actions) {
        this.chainState = Objects.requireNonNull(chainState, "chainState");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.blockProducerService = Objects.requireNonNull(blockProducerService, "blockProducerService");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public DevnetRestoreResult restoreAndGetTip(String name,
                                                RuntimeMaintenanceGate.MaintenanceLease maintenance) {
        Objects.requireNonNull(maintenance, "maintenance");

        DevnetSnapshotStore snapshotStore = new DevnetSnapshotStore(
                chainState, snapshots, blockProducerService.get());
        Path checkpointDir = snapshotStore.checkpointDir(name);

        if (!Files.isDirectory(checkpointDir)) {
            throw new IllegalArgumentException("Snapshot '" + name + "' does not exist");
        }

        log.info("Restoring snapshot '{}'...", name);

        boolean wasRunning = actions.isBlockProducerRunning();
        boolean serverWasRunning = actions.isServerRunning();
        boolean txAdmissionWasAccepting = actions.isTxAdmissionAccepting();
        boolean asyncUtxoHandlerPaused = actions.isAsyncUtxoHandlerRunning();
        boolean utxoPrunePaused = actions.isUtxoPruneServiceRunning();
        boolean utxoMetricsSamplerPaused = actions.isUtxoMetricsSamplerRunning();
        boolean accountHistoryPrunePaused = actions.isAccountHistoryPruneServiceRunning();
        boolean blockPrunePaused = actions.isBlockPruneServiceRunning();
        boolean restoreStarted = false;
        boolean preRestoreResumeAllowed = true;
        boolean restored = false;
        try {
            if (txAdmissionWasAccepting) {
                actions.pauseTxAdmissionAndAwait();
                log.info("Transaction admission paused for snapshot restore");
            }
            if (wasRunning) {
                actions.stopBlockProducer();
            }
            if (serverWasRunning) {
                if (!actions.stopServerAndAwait(Duration.ofSeconds(10))) {
                    preRestoreResumeAllowed = false;
                    throw new IllegalStateException("Cannot restore snapshot because NodeServer did not stop within 10s");
                }
                log.info("NodeServer paused for snapshot restore");
            }
            if (asyncUtxoHandlerPaused) {
                if (!actions.pauseAsyncUtxoHandlerAndAwait(Duration.ofSeconds(30))) {
                    preRestoreResumeAllowed = false;
                    throw new IllegalStateException("Cannot restore snapshot because async UTXO handler did not drain");
                }
            }
            if (utxoPrunePaused) {
                if (!actions.pauseUtxoPruneServiceAndAwait(Duration.ofSeconds(5))) {
                    preRestoreResumeAllowed = false;
                    throw new IllegalStateException("Cannot restore snapshot because UTXO prune service did not stop");
                }
            }
            if (utxoMetricsSamplerPaused) {
                if (!actions.pauseUtxoMetricsSamplerAndAwait(Duration.ofSeconds(5))) {
                    preRestoreResumeAllowed = false;
                    throw new IllegalStateException("Cannot restore snapshot because UTXO metrics sampler did not stop");
                }
            }
            if (accountHistoryPrunePaused) {
                if (!actions.pauseAccountHistoryPruneServiceAndAwait(Duration.ofSeconds(5))) {
                    preRestoreResumeAllowed = false;
                    throw new IllegalStateException(
                            "Cannot restore snapshot because account-history prune service did not stop");
                }
            }
            if (blockPrunePaused) {
                if (!actions.stopBlockPruneServiceAndAwait(Duration.ofSeconds(5))) {
                    preRestoreResumeAllowed = false;
                    throw new IllegalStateException("Cannot restore snapshot because block-body prune service did not stop");
                }
                log.info("block-body prune service paused for snapshot restore");
            }

            restoreStarted = true;
            snapshots.restoreFromSnapshot(checkpointDir.toString());

            actions.reinitializeUtxoAndReconcileAfterSnapshotRestore();
            actions.reinitializeLedgerAndReconcileAfterSnapshotRestore();
            actions.clearPendingTransactions();
            actions.resetBlockProducerToChainTip();
            actions.notifyServerNewDataAvailable();
            actions.invalidateSlotTimeCache();

            ChainTip newTip = chainState.getTip();
            log.info("Snapshot '{}' restored: new tip slot={}, block={}",
                    name,
                    newTip != null ? newTip.getSlot() : "null",
                    newTip != null ? newTip.getBlockNumber() : "null");
            restored = true;
            return new DevnetRestoreResult(
                    newTip != null ? newTip.getSlot() : 0,
                    newTip != null ? newTip.getBlockNumber() : 0);
        } finally {
            if (restored) {
                boolean resumed = resumeAfterPause(
                        asyncUtxoHandlerPaused,
                        utxoPrunePaused,
                        utxoMetricsSamplerPaused,
                        accountHistoryPrunePaused,
                        blockPrunePaused,
                        txAdmissionWasAccepting,
                        serverWasRunning,
                        wasRunning,
                        "snapshot restore");
                if (!resumed) {
                    maintenance.markDegraded(
                            "Snapshot restored but runtime services did not resume; restart required",
                            null);
                    throw new IllegalStateException(
                            "Snapshot restored but runtime services did not resume; restart required");
                }
                maintenance.clearDegraded();
            } else if (!restoreStarted && preRestoreResumeAllowed) {
                boolean resumed = resumeAfterPause(
                        asyncUtxoHandlerPaused,
                        utxoPrunePaused,
                        utxoMetricsSamplerPaused,
                        accountHistoryPrunePaused,
                        blockPrunePaused,
                        txAdmissionWasAccepting,
                        serverWasRunning,
                        wasRunning,
                        "failed snapshot restore preparation");
                if (!resumed) {
                    maintenance.markDegraded(
                            "Failed to resume runtime services after snapshot restore preparation failed; restart required",
                            null);
                }
            } else if (!restoreStarted) {
                String message = "Snapshot '" + name
                        + "' restore preparation failed after a service did not drain; runtime remains "
                        + "stopped/degraded and should be restarted before continuing";
                log.error(message);
                maintenance.markDegraded(message, null);
            } else {
                String message = "Snapshot '" + name
                        + "' failed after RocksDB replacement started; runtime remains paused "
                        + "and should be restarted before continuing";
                log.error(message);
                maintenance.markDegraded(message, null);
            }
        }
    }

    private boolean resumeAfterPause(boolean asyncUtxoHandlerPaused,
                                     boolean utxoPrunePaused,
                                     boolean utxoMetricsSamplerPaused,
                                     boolean accountHistoryPrunePaused,
                                     boolean blockPrunePaused,
                                     boolean txAdmissionWasAccepting,
                                     boolean serverWasRunning,
                                     boolean blockProducerWasRunning,
                                     String reason) {
        try {
            actions.resumeUtxoAfterSnapshotRestore(
                    asyncUtxoHandlerPaused,
                    utxoPrunePaused,
                    utxoMetricsSamplerPaused);
            actions.resumeLedgerAfterSnapshotRestore(accountHistoryPrunePaused);
            if (blockPrunePaused) {
                actions.startBlockPruneService();
            }
            if (txAdmissionWasAccepting && !actions.isTxAdmissionAccepting()) {
                actions.startTxAdmission();
            }
            if (serverWasRunning && !actions.isServerRunning()) {
                actions.startServer();
            }
            if (blockProducerWasRunning && !actions.isBlockProducerRunning()) {
                actions.startBlockProducer();
            }
            return true;
        } catch (Exception e) {
            if (txAdmissionWasAccepting && actions.isTxAdmissionAccepting()) {
                actions.stopTxAdmission();
            }
            log.error("Failed to resume runtime services after {}", reason, e);
            return false;
        }
    }
}
