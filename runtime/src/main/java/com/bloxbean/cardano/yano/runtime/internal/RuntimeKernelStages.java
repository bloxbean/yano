package com.bloxbean.cardano.yano.runtime.internal;

import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemContext;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate.MaintenanceLease;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;

import java.util.ArrayList;
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
    private final List<Subsystem> prePublicationSubsystems;
    private boolean startupSkippedAlreadyRunning;
    private boolean startupMutationStarted;
    private Throwable startupFailure;
    private MaintenanceLease startupMaintenanceLease;
    private MaintenanceLease shutdownMaintenanceLease;

    private RuntimeKernelStages(
            Actions actions,
            List<? extends Subsystem> prePublicationSubsystems
    ) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.prePublicationSubsystems = List.copyOf(
                Objects.requireNonNull(prePublicationSubsystems, "prePublicationSubsystems"));
    }

    static List<Subsystem> create(Actions actions) {
        return create(actions, List.of());
    }

    /**
     * Adds runtime-owned extensions after sync is available but before startup
     * is published as successful. Reverse kernel teardown therefore enters the
     * shutdown boundary before stopping extensions, and stops extensions before
     * sync/storage/plugin teardown.
     */
    static List<Subsystem> create(
            Actions actions,
            List<? extends Subsystem> prePublicationSubsystems
    ) {
        return new RuntimeKernelStages(actions, prePublicationSubsystems).subsystems();
    }

    private List<Subsystem> subsystems() {
        List<Subsystem> stages = new ArrayList<>();
        stages.add(new RuntimeResourceCloseSubsystem());
        stages.add(new RuntimeStartupBoundarySubsystem());
        stages.add(new RuntimePluginSubsystem());
        stages.add(new RuntimeTxSubsystem());
        stages.add(new RuntimeEarlyServeSubsystem());
        stages.add(new RuntimeBootstrapRecoverySubsystem());
        stages.add(new RuntimeUtxoSubsystem());
        stages.add(new RuntimeLedgerStateSubsystem());
        stages.add(new RuntimeChainPruneSubsystem());
        stages.add(new RuntimeProducerSubsystem());
        stages.add(new RuntimeChronologySubsystem());
        stages.add(new RuntimeDeferredServeSubsystem());
        stages.add(new RuntimeSyncSubsystem());
        prePublicationSubsystems.stream()
                .map(RuntimePrePublicationSubsystem::new)
                .forEach(stages::add);
        stages.add(new RuntimeStartupPublicationSubsystem());
        stages.add(new RuntimeShutdownBoundarySubsystem());
        return List.copyOf(stages);
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
        } catch (Throwable e) {
            startupFailure = e;
            rethrow(e);
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
        Throwable cleanupOutcome = null;
        try {
            actions.stopRuntimeServices();
        } catch (Throwable stopFailure) {
            cleanupOutcome = recordStartupCleanupFailure(cleanupOutcome, stopFailure);
        }
        try {
            actions.markStoppedAfterStartupFailure();
        } catch (Throwable markFailure) {
            cleanupOutcome = recordStartupCleanupFailure(cleanupOutcome, markFailure);
        }
        try {
            if (startupMutationStarted && startupMaintenanceLease != null) {
                startupMaintenanceLease.markDegraded(
                        "Node startup failed during storage/bootstrap/recovery; restart required",
                        failure);
            }
        } catch (Throwable degradedFailure) {
            cleanupOutcome = recordStartupCleanupFailure(cleanupOutcome, degradedFailure);
        }
        MaintenanceLease failedLease = startupMaintenanceLease;
        startupMaintenanceLease = null;
        if (failedLease != null) {
            try {
                failedLease.close();
            } catch (Throwable leaseFailure) {
                cleanupOutcome = recordStartupCleanupFailure(cleanupOutcome, leaseFailure);
            }
        }
        // Reset every transition field before a stronger cleanup failure
        // escapes. A failed diagnostic or lease close must not strand the
        // stage object in a synthetic in-progress startup.
        startupMutationStarted = false;
        startupFailure = null;
        startupSkippedAlreadyRunning = false;
        // The original startup failure is already owned by NodeKernel. Only
        // rethrow when cleanup produced a stronger winner (for example OOME
        // after an AssertionError) or when no startup failure existed.
        if (cleanupOutcome == null) {
            return;
        }
        if (LifecycleFailures.outranks(cleanupOutcome, failure)) {
            // NodeKernel still owns the original startup failure and will add
            // its lifecycle wrapper when it merges this stronger cleanup
            // signal. Do not pre-suppress the raw primary here as well.
            rethrow(cleanupOutcome);
        }
        LifecycleFailures.merge(failure, cleanupOutcome);
    }

    private Throwable recordStartupCleanupFailure(
            Throwable current,
            Throwable cleanupFailure
    ) {
        Throwable outcome = LifecycleFailures.merge(current, cleanupFailure);
        try {
            actions.logStartupCleanupFailure(cleanupFailure);
        } catch (Throwable diagnosticFailure) {
            outcome = LifecycleFailures.merge(outcome, diagnosticFailure);
        }
        return outcome;
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

        void logStartupCleanupFailure(Throwable failure);

        void stopRuntimeServices();

        void startPluginsAndInitializeFilters();

        void stopPluginsAfterRuntimeDrain();

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

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException("Runtime startup cleanup failed", failure);
    }

    /** Run every paired teardown action, then surface the strongest failure. */
    private static void runBestEffortCleanup(Runnable... cleanupActions) {
        Throwable failure = null;
        for (Runnable cleanup : cleanupActions) {
            try {
                cleanup.run();
            } catch (Throwable cleanupFailure) {
                failure = LifecycleFailures.merge(failure, cleanupFailure);
            }
        }
        if (failure != null) {
            rethrow(failure);
        }
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

    /**
     * Activates plugins and freezes the UTXO filter chain before any bootstrap,
     * producer, or sync stage can publish/apply chain data. Reverse kernel stop
     * then drains all producers before stopping plugins while maintenance is
     * still held by the startup/shutdown boundaries.
     */
    private final class RuntimePluginSubsystem implements Subsystem {
        @Override
        public String name() {
            return "plugins";
        }

        @Override
        public void start() {
            runStartupStage(actions::startPluginsAndInitializeFilters);
        }

        @Override
        public void stop() {
            actions.stopPluginsAfterRuntimeDrain();
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
            runBestEffortCleanup(actions::stopTx, actions::stopServer);
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
            runBestEffortCleanup(actions::stopProducer, actions::closeNonceListeners);
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
            runBestEffortCleanup(actions::stopTx, actions::stopServer);
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

    /**
     * Lifecycle adapter for app-layer/runtime extensions whose successful
     * activation is a prerequisite for publishing node startup. The adapter
     * keeps the delegate's name/health and full init/close ownership while
     * routing start through the startup-failure boundary.
     */
    private final class RuntimePrePublicationSubsystem implements Subsystem {
        private final Subsystem delegate;

        private RuntimePrePublicationSubsystem(Subsystem delegate) {
            this.delegate = Objects.requireNonNull(delegate, "pre-publication subsystem");
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public void init(SubsystemContext context) {
            delegate.init(context);
        }

        @Override
        public void start() {
            runStartupStage(delegate::start);
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public SubsystemHealth health() {
            return delegate.health();
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
