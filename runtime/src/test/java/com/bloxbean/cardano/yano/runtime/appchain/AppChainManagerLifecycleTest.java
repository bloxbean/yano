package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochStateProvider;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochBoundary;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochState;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AppChainManagerLifecycleTest {

    @Test
    void epochStateIsWiredAfterManagerAssemblyAndReachesEveryChain() {
        List<L1EpochStateProvider> firstWiring = new ArrayList<>();
        List<L1EpochStateProvider> secondWiring = new ArrayList<>();
        ScriptedChain first = chain("first", new ArrayList<>(), null, null, firstWiring);
        ScriptedChain second = chain("second", new ArrayList<>(), null, null, secondWiring);
        L1EpochStateProvider provider = inertEpochStateProvider();

        AppChainManager.wireL1EpochState(List.of(first, second), provider);

        assertThat(firstWiring).containsExactly(provider);
        assertThat(secondWiring).containsExactly(provider);
    }

    @Test
    void startupRollbackContinuesInReverseAndPromotesProcessFatal() {
        List<String> calls = new ArrayList<>();
        IllegalStateException startFailure =
                new IllegalStateException("startup secret");
        AssertionError containableCleanup =
                new AssertionError("cleanup secret");
        TestVirtualMachineError fatalCleanup =
                new TestVirtualMachineError("fatal cleanup secret");
        ScriptedChain first = chain("first", calls, null, null);
        ScriptedChain second = chain("second", calls, null, fatalCleanup);
        ScriptedChain third = chain("third", calls, null, containableCleanup);
        ScriptedChain failing = chain("failing", calls, startFailure, null);
        Logger logger = mock(Logger.class);

        assertThatThrownBy(() -> AppChainManager.startManagedChains(
                List.of(first, second, third, failing), logger)).isSameAs(fatalCleanup);

        assertThat(calls).containsExactly(
                "start:first", "start:second", "start:third", "start:failing",
                "stop:third", "stop:second", "stop:first");
        assertThat(fatalCleanup.getSuppressed()).containsExactly(containableCleanup);
        assertThat(containableCleanup.getSuppressed()).containsExactly(startFailure);
        verify(logger).warn("Error rolling back app chain {} (errorType={})",
                "third", AssertionError.class.getName());
        verify(logger).warn("Error rolling back app chain {} (errorType={})",
                "second", TestVirtualMachineError.class.getName());
    }

    @Test
    void stopAttemptsEveryChainAndPromotesLaterProcessFatal() {
        List<String> calls = new ArrayList<>();
        AssertionError containableCleanup = new AssertionError("cleanup assertion");
        TestVirtualMachineError fatalCleanup = new TestVirtualMachineError("fatal cleanup");
        ScriptedChain first = chain("first", calls, null, null);
        ScriptedChain second = chain("second", calls, null, fatalCleanup);
        ScriptedChain third = chain("third", calls, null, containableCleanup);

        assertThatThrownBy(() -> AppChainManager.stopManagedChains(
                List.of(first, second, third), mock(Logger.class))).isSameAs(fatalCleanup);

        assertThat(calls).containsExactly("stop:third", "stop:second", "stop:first");
        assertThat(fatalCleanup.getSuppressed()).containsExactly(containableCleanup);
    }

    private static ScriptedChain chain(
            String id,
            List<String> calls,
            Throwable startFailure,
            Throwable stopFailure
    ) {
        return chain(id, calls, startFailure, stopFailure, new ArrayList<>());
    }

    private static L1EpochStateProvider inertEpochStateProvider() {
        return new L1EpochStateProvider() {
            @Override
            public boolean persistent() {
                return false;
            }

            @Override
            public int snapshotRetentionEpochs() {
                return 0;
            }

            @Override
            public long epochAtSlot(long slot) {
                return 0;
            }

            @Override
            public List<L1EpochBoundary> completedBoundaries(long afterNewEpoch, int limit) {
                return List.of();
            }

            @Override
            public Optional<L1EpochState> open(L1EpochBoundary boundary) {
                return Optional.empty();
            }
        };
    }

    private static ScriptedChain chain(
            String id,
            List<String> calls,
            Throwable startFailure,
            Throwable stopFailure,
            List<L1EpochStateProvider> wiring
    ) {
        return new ScriptedChain(id, calls, startFailure, stopFailure, wiring);
    }

    private record ScriptedChain(
            String chainId,
            List<String> calls,
            Throwable startFailure,
            Throwable stopFailure,
            List<L1EpochStateProvider> wiring
    ) implements AppChainManager.ManagedChain {
        @Override
        public void start() {
            calls.add("start:" + chainId);
            throwUnchecked(startFailure);
        }

        @Override
        public void stop() {
            calls.add("stop:" + chainId);
            throwUnchecked(stopFailure);
        }

        @Override
        public void wireL1EpochState(L1EpochStateProvider provider) {
            wiring.add(provider);
        }

        private static void throwUnchecked(Throwable failure) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private TestVirtualMachineError(String message) {
            super(message);
        }
    }
}
