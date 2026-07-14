package com.bloxbean.cardano.yano.runtime.internal;

import com.bloxbean.cardano.yano.runtime.kernel.NodeKernel;
import com.bloxbean.cardano.yano.runtime.kernel.Schedulers;
import com.bloxbean.cardano.yano.runtime.kernel.ServiceRegistry;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class RuntimeNodeShutdownSafetyTest {

    @Test
    void arbitrarySyncStopThrowableMarksUnsafeBeforeReverseTeardownReachesPlugins() {
        AssertionError stopFailure = new AssertionError("sync stop failed");
        AtomicBoolean unsafe = new AtomicBoolean();
        List<String> calls = new ArrayList<>();
        Schedulers schedulers = new Schedulers();
        NodeKernel kernel = new NodeKernel(
                List.of(pluginStopProbe(unsafe, calls), failingSyncStop(stopFailure, unsafe, calls)),
                new SubsystemContext(null, schedulers, Map.of(), new ServiceRegistry()));

        try {
            kernel.start();

            assertThatThrownBy(kernel::stop).isSameAs(stopFailure);

            assertThat(unsafe).isTrue();
            assertThat(calls).containsExactly("sync-stop", "unsafe", "plugin-stop:true");
            assertUnsafeRestartRejected(unsafe.get());
        } finally {
            kernel.close();
        }
    }

    @Test
    void unsafeMarkerFailureAfterSuccessfulStopIsInvokedOnlyOnce() {
        AssertionError markerFailure = new AssertionError("unsafe marker failed");
        AtomicInteger markerCalls = new AtomicInteger();

        assertThatThrownBy(() -> RuntimeNode.stopSyncForShutdownSafely(
                () -> true,
                () -> {
                    markerCalls.incrementAndGet();
                    throw markerFailure;
                }))
                .isSameAs(markerFailure);
        assertThat(markerCalls).hasValue(1);
    }

    @Test
    void unsafeMarkerFailureDuringFailedStopRetainsTheStopFailure() {
        IllegalStateException stopFailure = new IllegalStateException("sync stop failed");
        AssertionError markerFailure = new AssertionError("unsafe marker failed");
        AtomicInteger markerCalls = new AtomicInteger();

        Throwable thrown = catchThrowable(() -> RuntimeNode.stopSyncForShutdownSafely(
                () -> {
                    throw stopFailure;
                },
                () -> {
                    markerCalls.incrementAndGet();
                    throw markerFailure;
                }));

        assertThat(thrown).isSameAs(markerFailure);
        assertThat(markerFailure.getSuppressed()).containsExactly(stopFailure);
        assertThat(markerCalls).hasValue(1);
    }

    @Test
    void syncCloseThrowableSkipsApplyOwnedResourcesButClosesSafeOwners() {
        AssertionError syncFailure = new AssertionError("sync close failed");
        RecordingRuntimeCloseActions actions = new RecordingRuntimeCloseActions(
                syncFailure, true);

        RuntimeNode.RuntimeCloseOutcome outcome = RuntimeNode.closeApplyOwnedResources(
                false, null, actions);

        assertThat(outcome.unsafeLedgerApplyWorker()).isTrue();
        assertThat(outcome.failure()).isSameAs(syncFailure);
        assertThat(actions.calls).containsExactly(
                "sync", "unsafe", "schedulers", "chain-storage:true");
        assertUnsafeRestartRejected(outcome.unsafeLedgerApplyWorker());
    }

    @Test
    void utxoDrainFalseSkipsApplyOwnedResourcesButClosesSafeOwners() {
        RecordingRuntimeCloseActions actions = new RecordingRuntimeCloseActions(
                null, false);

        RuntimeNode.RuntimeCloseOutcome outcome = RuntimeNode.closeApplyOwnedResources(
                false, null, actions);

        assertThat(outcome.unsafeLedgerApplyWorker()).isTrue();
        assertThat(outcome.failure()).isNull();
        assertThat(actions.calls).containsExactly(
                "sync", "drain", "unsafe", "schedulers", "chain-storage:true");
        assertUnsafeRestartRejected(outcome.unsafeLedgerApplyWorker());
    }

    @Test
    void successfulDrainClosesApplyOwnedResourcesBeforeSchedulersAndStorage() {
        RecordingRuntimeCloseActions actions = new RecordingRuntimeCloseActions(
                null, true);

        RuntimeNode.RuntimeCloseOutcome outcome = RuntimeNode.closeApplyOwnedResources(
                false, null, actions);

        assertThat(outcome.unsafeLedgerApplyWorker()).isFalse();
        assertThat(outcome.failure()).isNull();
        assertThat(actions.calls).containsExactly(
                "sync", "drain", "domain-apis", "plugin-manager", "plugin-environment",
                "utxo-event-handlers", "ledger-event-handlers", "event-bus",
                "utxo", "ledger", "schedulers", "chain-storage:false");
    }

    private static Subsystem pluginStopProbe(
            AtomicBoolean unsafe,
            List<String> calls
    ) {
        return new Subsystem() {
            @Override
            public String name() {
                return "plugin-probe";
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
                calls.add("plugin-stop:" + unsafe.get());
            }
        };
    }

    private static Subsystem failingSyncStop(
            AssertionError stopFailure,
            AtomicBoolean unsafe,
            List<String> calls
    ) {
        return new Subsystem() {
            @Override
            public String name() {
                return "sync-probe";
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
                RuntimeNode.stopSyncForShutdownSafely(
                        () -> {
                            calls.add("sync-stop");
                            throw stopFailure;
                        },
                        () -> {
                            unsafe.set(true);
                            calls.add("unsafe");
                        });
            }
        };
    }

    private static void assertUnsafeRestartRejected(boolean unsafe) {
        assertThatThrownBy(() -> RuntimeNode.requireSafeRestart(unsafe))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot restart after an unsafe ledger-apply shutdown");
    }

    private static final class RecordingRuntimeCloseActions
            implements RuntimeNode.RuntimeCloseActions {
        private final List<String> calls = new ArrayList<>();
        private final AssertionError syncFailure;
        private final boolean drainResult;

        private RecordingRuntimeCloseActions(
                AssertionError syncFailure,
                boolean drainResult
        ) {
            this.syncFailure = syncFailure;
            this.drainResult = drainResult;
        }

        @Override
        public void closeSync() {
            calls.add("sync");
            if (syncFailure != null) {
                throw syncFailure;
            }
        }

        @Override
        public boolean drainUtxo() {
            calls.add("drain");
            return drainResult;
        }

        @Override
        public void markUnsafe() {
            calls.add("unsafe");
        }

        @Override
        public void closePluginManager() {
            calls.add("plugin-manager");
        }

        @Override
        public void closeDomainApis() {
            calls.add("domain-apis");
        }

        @Override
        public void closePluginEnvironment() {
            calls.add("plugin-environment");
        }

        @Override
        public void closeUtxoEventHandlers() {
            calls.add("utxo-event-handlers");
        }

        @Override
        public void closeLedgerEventHandlers() {
            calls.add("ledger-event-handlers");
        }

        @Override
        public void closeEventBus() {
            calls.add("event-bus");
        }

        @Override
        public void closeUtxo() {
            calls.add("utxo");
        }

        @Override
        public void closeLedger() {
            calls.add("ledger");
        }

        @Override
        public void closeSchedulers() {
            calls.add("schedulers");
        }

        @Override
        public void closeChainStorage(boolean unsafeLedgerApplyWorker) {
            calls.add("chain-storage:" + unsafeLedgerApplyWorker);
        }
    }
}
