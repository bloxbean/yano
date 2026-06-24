package com.bloxbean.cardano.yano.runtime.internal;

import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate.MaintenanceLease;

import java.util.List;
import java.util.Objects;

/**
 * Ordered runtime lifecycle stages installed into the {@code NodeKernel}.
 *
 * <p>The stages keep kernel ordering, startup cleanup, and shutdown maintenance
 * ownership outside {@link RuntimeNode}. RuntimeNode remains the provider of
 * concrete storage, sync, server, ledger, and producer operations through the
 * narrow {@link Actions} contract.</p>
 */
final class RuntimeKernelStages {
    private final Actions actions;
    private boolean startupSkippedAlreadyRunning;
    private boolean startupMutationStarted;
    private Throwable startupFailure;
    private MaintenanceLease startupMaintenanceLease;
    private MaintenanceLease shutdownMaintenanceLease;

    private RuntimeKernelStages(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    static List<Subsystem> create(Actions actions) {
        return new RuntimeKernelStages(actions).subsystems();
    }

    private List<Subsystem> subsystems() {
        return List.of(
                new RuntimeResourceCloseSubsystem(),
                new RuntimeStartupBoundarySubsystem(),
                new RuntimeTxSubsystem(),
                new RuntimeEarlyServeSubsystem(),
                new RuntimeBootstrapRecoverySubsystem(),
                new RuntimeUtxoSubsystem(),
                new RuntimeLedgerStateSubsystem(),
                new RuntimeChainPruneSubsystem(),
                new RuntimeProducerSubsystem(),
                new RuntimeChronologySubsystem(),
                new RuntimeDeferredServeSubsystem(),
                new RuntimeSyncSubsystem(),
                new RuntimeStartupPublicationSubsystem(),
                new RuntimeShutdownBoundarySubsystem());
    }

    private boolean startupActive() {
        return startupMaintenanceLease != null && !startupSkippedAlreadyRunning;
    }

    private void runStartupStage(Runnable action) {
        if (!startupActive()) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException | Error e) {
            startupFailure = e;
            throw e;
        }
    }

    private void finishSuccessfulStartup() {
        if (!startupActive()) {
            return;
        }
        actions.finishSuccessfulStartup();
        closeStartupMaintenanceLease();
    }

    private void handleFailedStartupCleanup() {
        Throwable failure = startupFailure;
        try {
            try {
                actions.stopRuntimeServices();
            } catch (RuntimeException stopFailure) {
                if (failure != null) {
                    failure.addSuppressed(stopFailure);
                }
                actions.logStartupCleanupFailure(stopFailure);
            } finally {
                actions.markStoppedAfterStartupFailure();
            }
            if (startupMutationStarted && startupMaintenanceLease != null) {
                startupMaintenanceLease.markDegraded(
                        "Node startup failed during storage/bootstrap/recovery; restart required",
                        failure);
            }
        } finally {
            closeStartupMaintenanceLease();
            startupMutationStarted = false;
            startupFailure = null;
            startupSkippedAlreadyRunning = false;
        }
    }

    private void closeStartupMaintenanceLease() {
        MaintenanceLease lease = startupMaintenanceLease;
        startupMaintenanceLease = null;
        if (lease != null) {
            lease.close();
        }
    }

    private void closeShutdownMaintenanceLease() {
        MaintenanceLease lease = shutdownMaintenanceLease;
        shutdownMaintenanceLease = null;
        if (lease != null) {
            lease.close();
        }
    }

    interface Actions {
        boolean isClosed();

        boolean markRunningForStartup();

        void markStoppedAfterStartupFailure();

        boolean markStoppingForShutdown();

        RuntimeMaintenanceGate maintenanceGate();

        void logStarting();

        void logAlreadyRunning();

        void logStopping();

        void logStopped();

        void logStartupCleanupFailure(RuntimeException failure);

        void stopRuntimeServices();

        void closeRuntimeResourcesUnderMaintenance();

        SubsystemHealth runtimeHealth(String name);

        boolean isServerEnabled();

        boolean deferServerStartUntilClientStateReady();

        void startServer();

        void stopServer();

        SubsystemHealth serverHealth(String stageName);

        String txName();

        void startTx();

        void stopTx();

        SubsystemHealth txHealth();

        void runBootstrapRecovery();

        String utxoName();

        void startUtxo();

        void stopUtxo();

        SubsystemHealth utxoHealth();

        String ledgerStateName();

        void startLedgerState();

        void stopLedgerState();

        SubsystemHealth ledgerStateHealth();

        String chainStorageName();

        void startChainPrune();

        void stopChainPrune();

        SubsystemHealth chainStorageHealth();

        String producerName();

        void startProducer();

        void stopProducer();

        SubsystemHealth producerHealth();

        void closeNonceListeners();

        String chronologyName();

        void startChronology();

        SubsystemHealth chronologyHealth();

        String syncName();

        void startSync();

        void stopSyncForShutdown();

        SubsystemHealth syncHealth();

        void startPublication();

        void finishSuccessfulStartup();
    }

    private final class RuntimeResourceCloseSubsystem implements Subsystem {
        @Override
        public String name() {
            return "runtime-resources";
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
            actions.closeRuntimeResourcesUnderMaintenance();
        }

        @Override
        public SubsystemHealth health() {
            return actions.runtimeHealth(name());
        }
    }

    private final class RuntimeStartupBoundarySubsystem implements Subsystem {
        @Override
        public String name() {
            return "runtime-startup-boundary";
        }

        @Override
        public void start() {
            if (actions.isClosed()) {
                throw new IllegalStateException("Cannot start a closed Yano instance");
            }
            startupFailure = null;
            startupMutationStarted = false;
            startupSkippedAlreadyRunning = false;
            if (!actions.markRunningForStartup()) {
                startupSkippedAlreadyRunning = true;
                actions.logAlreadyRunning();
                return;
            }
            startupMaintenanceLease = actions.maintenanceGate().enterMaintenance("node startup");
            actions.logStarting();
        }

        @Override
        public void stop() {
            if (shutdownMaintenanceLease != null) {
                closeShutdownMaintenanceLease();
                actions.logStopped();
            }
        }

        @Override
        public void close() {
            if (startupMaintenanceLease != null) {
                handleFailedStartupCleanup();
            }
        }

        @Override
        public SubsystemHealth health() {
            return actions.runtimeHealth(name());
        }
    }

    private final class RuntimeShutdownBoundarySubsystem implements Subsystem {
        @Override
        public String name() {
            return "runtime-shutdown-boundary";
        }

        @Override
        public void start() {
            runStartupStage(RuntimeKernelStages.this::finishSuccessfulStartup);
        }

        @Override
        public void stop() {
            if (actions.markStoppingForShutdown()) {
                shutdownMaintenanceLease = actions.maintenanceGate().enterMaintenance("node stop");
                actions.logStopping();
            }
        }

        @Override
        public SubsystemHealth health() {
            return actions.runtimeHealth(name());
        }
    }

    private final class RuntimeTxSubsystem implements Subsystem {
        @Override
        public String name() {
            return actions.txName();
        }

        @Override
        public void start() {
            runStartupStage(actions::startTx);
        }

        @Override
        public void stop() {
            actions.stopTx();
        }

        @Override
        public SubsystemHealth health() {
            return actions.txHealth();
        }
    }

    private final class RuntimeEarlyServeSubsystem implements Subsystem {
        @Override
        public String name() {
            return "serve";
        }

        @Override
        public void start() {
            runStartupStage(() -> {
                if (actions.isServerEnabled() && !actions.deferServerStartUntilClientStateReady()) {
                    actions.startServer();
                }
            });
        }

        @Override
        public void stop() {
            actions.stopTx();
            actions.stopServer();
        }

        @Override
        public SubsystemHealth health() {
            return actions.serverHealth(name());
        }
    }

    private final class RuntimeBootstrapRecoverySubsystem implements Subsystem {
        @Override
        public String name() {
            return "runtime-bootstrap-recovery";
        }

        @Override
        public void start() {
            runStartupStage(() -> {
                startupMutationStarted = true;
                actions.runBootstrapRecovery();
            });
        }

        @Override
        public void stop() {
        }

        @Override
        public SubsystemHealth health() {
            return actions.runtimeHealth(name());
        }
    }

    private final class RuntimeUtxoSubsystem implements Subsystem {
        @Override
        public String name() {
            return actions.utxoName();
        }

        @Override
        public void start() {
            runStartupStage(actions::startUtxo);
        }

        @Override
        public void stop() {
            actions.stopUtxo();
        }

        @Override
        public SubsystemHealth health() {
            return actions.utxoHealth();
        }
    }

    private final class RuntimeLedgerStateSubsystem implements Subsystem {
        @Override
        public String name() {
            return actions.ledgerStateName();
        }

        @Override
        public void start() {
            runStartupStage(actions::startLedgerState);
        }

        @Override
        public void stop() {
            actions.stopLedgerState();
        }

        @Override
        public SubsystemHealth health() {
            return actions.ledgerStateHealth();
        }
    }

    private final class RuntimeChainPruneSubsystem implements Subsystem {
        @Override
        public String name() {
            return actions.chainStorageName();
        }

        @Override
        public void start() {
            runStartupStage(actions::startChainPrune);
        }

        @Override
        public void stop() {
            actions.stopChainPrune();
        }

        @Override
        public SubsystemHealth health() {
            return actions.chainStorageHealth();
        }
    }

    private final class RuntimeProducerSubsystem implements Subsystem {
        @Override
        public String name() {
            return actions.producerName();
        }

        @Override
        public void start() {
            runStartupStage(actions::startProducer);
        }

        @Override
        public void stop() {
            actions.stopProducer();
            actions.closeNonceListeners();
        }

        @Override
        public SubsystemHealth health() {
            return actions.producerHealth();
        }
    }

    private final class RuntimeChronologySubsystem implements Subsystem {
        @Override
        public String name() {
            return actions.chronologyName();
        }

        @Override
        public void start() {
            runStartupStage(actions::startChronology);
        }

        @Override
        public void stop() {
        }

        @Override
        public SubsystemHealth health() {
            return actions.chronologyHealth();
        }
    }

    private final class RuntimeDeferredServeSubsystem implements Subsystem {
        @Override
        public String name() {
            return "serve-deferred";
        }

        @Override
        public void start() {
            runStartupStage(() -> {
                if (actions.deferServerStartUntilClientStateReady()) {
                    actions.startServer();
                }
            });
        }

        @Override
        public void stop() {
            actions.stopTx();
            actions.stopServer();
        }

        @Override
        public SubsystemHealth health() {
            return SubsystemHealth.up(name());
        }
    }

    private final class RuntimeSyncSubsystem implements Subsystem {
        @Override
        public String name() {
            return actions.syncName();
        }

        @Override
        public void start() {
            runStartupStage(actions::startSync);
        }

        @Override
        public void stop() {
            actions.stopSyncForShutdown();
        }

        @Override
        public SubsystemHealth health() {
            return actions.syncHealth();
        }
    }

    private final class RuntimeStartupPublicationSubsystem implements Subsystem {
        @Override
        public String name() {
            return "runtime-startup-publication";
        }

        @Override
        public void start() {
            runStartupStage(actions::startPublication);
        }

        @Override
        public void stop() {
        }

        @Override
        public SubsystemHealth health() {
            return actions.runtimeHealth(name());
        }
    }
}
