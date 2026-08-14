package com.bloxbean.cardano.yano.runtime.internal;

import com.bloxbean.cardano.yano.runtime.kernel.KernelLifecycleException;
import com.bloxbean.cardano.yano.runtime.kernel.NodeKernel;
import com.bloxbean.cardano.yano.runtime.kernel.ServiceRegistry;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemContext;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
class RuntimeKernelStagesTest {
    @Test
    void prePublicationSubsystemStartsBeforeSuccessAndStopsInsideShutdownBoundary() {
        TestActions actions = new TestActions(null, null);
        Subsystem appChain = new Subsystem() {
            @Override public String name() { return "app-chain"; }
            @Override public void start() { actions.events.add("app-chain-start"); }
            @Override public void stop() { actions.events.add("app-chain-stop"); }
        };
        NodeKernel kernel = new NodeKernel(
                RuntimeKernelStages.create(actions, List.of(appChain)),
                new SubsystemContext(
                        new NoopEventBus(),
                        new com.bloxbean.cardano.yano.runtime.kernel.Schedulers(),
                        Map.of(),
                        new ServiceRegistry()));

        kernel.start();
        kernel.stop();

        assertThat(actions.events).containsSubsequence(
                "sync-start",
                "app-chain-start",
                "startup-publication",
                "startup-success");
        assertThat(actions.events).containsSubsequence(
                "shutdown-boundary",
                "app-chain-stop",
                "sync-stop");
        kernel.close();
    }

    @Test
    void prePublicationSubsystemFailurePreventsSuccessPublicationAndRollsBackRuntime() {
        TestActions actions = new TestActions(null, null);
        RuntimeException startupFailure = new RuntimeException("app-chain startup");
        AtomicInteger extensionCloseCalls = new AtomicInteger();
        Subsystem appChain = new Subsystem() {
            @Override public String name() { return "app-chain"; }
            @Override public void start() {
                actions.events.add("app-chain-start");
                throw startupFailure;
            }
            @Override public void stop() { actions.events.add("app-chain-stop"); }
            @Override public void close() { extensionCloseCalls.incrementAndGet(); }
        };
        NodeKernel kernel = new NodeKernel(
                RuntimeKernelStages.create(actions, List.of(appChain)),
                new SubsystemContext(
                        new NoopEventBus(),
                        new com.bloxbean.cardano.yano.runtime.kernel.Schedulers(),
                        Map.of(),
                        new ServiceRegistry()));

        Throwable observed = catchThrowable(kernel::start);

        assertThat(observed)
                .isInstanceOf(KernelLifecycleException.class)
                .hasCause(startupFailure);
        assertThat(actions.events).contains("sync-start", "app-chain-start", "sync-stop");
        assertThat(actions.events).doesNotContain("startup-publication", "startup-success");
        assertThat(actions.markStoppedCalls).hasValue(1);
        assertThat(actions.closeResourcesCalls).hasValue(1);
        assertThat(extensionCloseCalls).hasValue(1);
        kernel.close();
    }

    @Test
    void pairedShutdownRunsEveryActionAndPreservesProcessFatalWinner() {
        TestActions actions = new TestActions(null, null);
        AssertionError txStopFailure = new AssertionError("tx stop");
        TestVirtualMachineError serverFatal = new TestVirtualMachineError();
        IllegalStateException producerStopFailure =
                new IllegalStateException("producer stop");
        AssertionError nonceCloseFailure = new AssertionError("nonce close");
        actions.txStopFailure = txStopFailure;
        actions.serverStopFailure = serverFatal;
        actions.producerStopFailure = producerStopFailure;
        actions.nonceCloseFailure = nonceCloseFailure;
        NodeKernel kernel = new NodeKernel(
                RuntimeKernelStages.create(actions),
                new SubsystemContext(
                        new NoopEventBus(),
                        new com.bloxbean.cardano.yano.runtime.kernel.Schedulers(),
                        Map.of(),
                        new ServiceRegistry()));
        kernel.start();

        Throwable observed = catchThrowable(kernel::stop);

        assertThat(observed).isSameAs(serverFatal);
        // Deferred serve, early serve, and the tx subsystem each own a stop
        // edge; both serve stages must still reach their paired server stop.
        assertThat(actions.stopTxCalls).hasValue(3);
        assertThat(actions.stopServerCalls).hasValue(2);
        assertThat(actions.stopProducerCalls).hasValue(1);
        assertThat(actions.closeNonceCalls).hasValue(1);
        assertThat(serverFatal.getSuppressed()).contains(txStopFailure, nonceCloseFailure);
        assertThat(nonceCloseFailure.getSuppressed()).containsExactly(producerStopFailure);
        kernel.close();
    }

    @Test
    void startupCleanupPromotesProcessFatalAndStillReleasesRuntimeResources() {
        RuntimeException primary = new RuntimeException("startup");
        TestVirtualMachineError fatal = new TestVirtualMachineError();
        TestActions actions = new TestActions(primary, fatal);
        NodeKernel kernel = new NodeKernel(
                RuntimeKernelStages.create(actions),
                new SubsystemContext(
                        new NoopEventBus(),
                        new com.bloxbean.cardano.yano.runtime.kernel.Schedulers(),
                        Map.of(),
                        new ServiceRegistry()));

        Throwable observed = catchThrowable(kernel::start);

        assertThat(observed).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).hasSize(1);
        assertThat(fatal.getSuppressed()[0])
                .isInstanceOf(KernelLifecycleException.class)
                .hasCause(primary);
        assertThat(actions.markStoppedCalls).hasValue(1);
        assertThat(actions.closeResourcesCalls).hasValue(1);
        kernel.close();
    }

    private static final class TestActions implements RuntimeKernelStages.Actions {
        private final RuntimeException startupFailure;
        private final TestVirtualMachineError cleanupFailure;
        private final RuntimeMaintenanceGate maintenanceGate = new RuntimeMaintenanceGate();
        private final AtomicInteger markStoppedCalls = new AtomicInteger();
        private final AtomicInteger closeResourcesCalls = new AtomicInteger();
        private final AtomicInteger stopTxCalls = new AtomicInteger();
        private final AtomicInteger stopServerCalls = new AtomicInteger();
        private final AtomicInteger stopProducerCalls = new AtomicInteger();
        private final AtomicInteger closeNonceCalls = new AtomicInteger();
        private final List<String> events = new ArrayList<>();
        private Throwable txStopFailure;
        private Throwable serverStopFailure;
        private Throwable producerStopFailure;
        private Throwable nonceCloseFailure;

        private TestActions(
                RuntimeException startupFailure,
                TestVirtualMachineError cleanupFailure
        ) {
            this.startupFailure = startupFailure;
            this.cleanupFailure = cleanupFailure;
        }

        @Override public boolean isClosed() { return false; }
        @Override public boolean markRunningForStartup() { return true; }
        @Override public void markStoppedAfterStartupFailure() { markStoppedCalls.incrementAndGet(); }
        @Override public boolean markStoppingForShutdown() {
            events.add("shutdown-boundary");
            return true;
        }
        @Override public RuntimeMaintenanceGate maintenanceGate() { return maintenanceGate; }
        @Override public void logStarting() { }
        @Override public void logAlreadyRunning() { }
        @Override public void logStopping() { }
        @Override public void logStopped() { }
        @Override public void logStartupCleanupFailure(Throwable failure) { }
        @Override public void stopRuntimeServices() {
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
        @Override public void startPluginsAndInitializeFilters() { }
        @Override public void stopPluginsAfterRuntimeDrain() { }
        @Override public void closeRuntimeResourcesUnderMaintenance() {
            closeResourcesCalls.incrementAndGet();
        }
        @Override public SubsystemHealth runtimeHealth(String name) {
            return SubsystemHealth.up(name);
        }
        @Override public boolean isServerEnabled() { return false; }
        @Override public boolean deferServerStartUntilClientStateReady() { return false; }
        @Override public void startServer() { }
        @Override public void stopServer() {
            stopServerCalls.incrementAndGet();
            throwIfPresent(serverStopFailure);
        }
        @Override public SubsystemHealth serverHealth(String name) {
            return SubsystemHealth.up(name);
        }
        @Override public String txName() { return "tx"; }
        @Override public void startTx() {
            if (startupFailure != null) {
                throw startupFailure;
            }
        }
        @Override public void stopTx() {
            stopTxCalls.incrementAndGet();
            throwIfPresent(txStopFailure);
        }
        @Override public SubsystemHealth txHealth() { return SubsystemHealth.up("tx"); }
        @Override public void runBootstrapRecovery() { }
        @Override public String utxoName() { return "utxo"; }
        @Override public void startUtxo() { }
        @Override public void stopUtxo() { }
        @Override public SubsystemHealth utxoHealth() { return SubsystemHealth.up("utxo"); }
        @Override public String ledgerStateName() { return "ledger"; }
        @Override public void startLedgerState() { }
        @Override public void stopLedgerState() { }
        @Override public SubsystemHealth ledgerStateHealth() {
            return SubsystemHealth.up("ledger");
        }
        @Override public String chainStorageName() { return "chain-storage"; }
        @Override public void startChainPrune() { }
        @Override public void stopChainPrune() { }
        @Override public SubsystemHealth chainStorageHealth() {
            return SubsystemHealth.up("chain-storage");
        }
        @Override public String producerName() { return "producer"; }
        @Override public void startProducer() { }
        @Override public void stopProducer() {
            stopProducerCalls.incrementAndGet();
            throwIfPresent(producerStopFailure);
        }
        @Override public SubsystemHealth producerHealth() {
            return SubsystemHealth.up("producer");
        }
        @Override public void closeNonceListeners() {
            closeNonceCalls.incrementAndGet();
            throwIfPresent(nonceCloseFailure);
        }
        @Override public String chronologyName() { return "chronology"; }
        @Override public void startChronology() { }
        @Override public SubsystemHealth chronologyHealth() {
            return SubsystemHealth.up("chronology");
        }
        @Override public String syncName() { return "sync"; }
        @Override public void startSync() { events.add("sync-start"); }
        @Override public void stopSyncForShutdown() { events.add("sync-stop"); }
        @Override public SubsystemHealth syncHealth() { return SubsystemHealth.up("sync"); }
        @Override public void startPublication() { events.add("startup-publication"); }
        @Override public void finishSuccessfulStartup() { events.add("startup-success"); }

        private static void throwIfPresent(Throwable failure) {
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
    }
}
