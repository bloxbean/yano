package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.AppLedgerOperatingProfile;
import org.yanoproject.api.appchain.l1view.L1EpochBoundary;
import org.yanoproject.api.appchain.l1view.L1EpochState;
import org.yanoproject.api.appchain.l1view.L1EpochStateProvider;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AppChainManagerLifecycleTest {
    @TempDir Path directory;

    @Test
    void epochStateIsWiredAfterManagerAssemblyAndReachesEveryChain() throws Exception {
        AppChainSubsystem first = cardanoChain("first");
        AppChainSubsystem second = cardanoChain("second");
        L1EpochStateProvider provider = inertEpochStateProvider();
        try (AppChainManager manager = new AppChainManager(List.of(first, second),
                LoggerFactory.getLogger(getClass()))) {
            manager.wireL1EpochState(provider);
            var field = AppChainSubsystem.class.getDeclaredField("epochStateProvider");
            field.setAccessible(true);
            assertThat(field.get(first)).isSameAs(provider);
            assertThat(field.get(second)).isSameAs(provider);
        }
    }

    @Test
    void genericManagerHostsStandaloneRuntimesAndPreservesLifecycleOrder() {
        List<String> calls = new ArrayList<>();
        AppLedgerRuntime first = new TrackedRuntime(config("first", true), directory, calls);
        AppLedgerRuntime second = new TrackedRuntime(config("second", true), directory, calls);
        try (AppLedgerManager manager = new AppLedgerManager(List.of(first, second),
                LoggerFactory.getLogger(getClass()))) {
            assertThat(manager.byId("first")).contains(first);
            assertThat(manager.byId("missing")).isEmpty();
            assertThat(manager.all()).containsExactly(first, second);
            manager.start();
            manager.stop();
            assertThat(calls).containsExactly("start:first", "start:second", "stop:second", "stop:first");
        }
        assertThat(calls.stream().filter(call -> call.startsWith("close:")))
                .containsExactly("close:second", "close:first");
    }

    private AppChainSubsystem cardanoChain(String chainId) {
        return new AppChainSubsystem(config(chainId, false), 42, null, null, directory.toString(),
                LoggerFactory.getLogger(getClass()));
    }

    private static AppChainConfig config(String chainId, boolean standalone) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 37);
        String member = HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
        return AppChainConfig.builder(chainId)
                .signingKeyHex(HexFormat.of().formatHex(seed))
                .memberKeysHex(Set.of(member))
                .proposerKeyHex(member)
                .threshold(1)
                .blockIntervalMs(60_000)
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .pluginSettings(standalone ? Map.of(AppLedgerOperatingProfile.SETTING,
                        AppLedgerOperatingProfile.STANDALONE.id()) : Map.of())
                .build();
    }

    private static final class TrackedRuntime extends AppLedgerRuntime {
        private final List<String> calls;

        private TrackedRuntime(AppChainConfig config, Path directory, List<String> calls) {
            super(config, 42, null, null, directory.toString(), PluginProviderRegistry.empty(),
                    LoggerFactory.getLogger(TrackedRuntime.class));
            this.calls = calls;
        }

        @Override public void start() { super.start(); calls.add("start:" + chainId()); }
        @Override public void stop() { super.stop(); calls.add("stop:" + chainId()); }
        @Override public void close() { super.close(); calls.add("close:" + chainId()); }
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

        assertThatThrownBy(() -> AppLedgerManager.startManagedChains(
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

        assertThatThrownBy(() -> AppLedgerManager.stopManagedChains(
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
        return new ScriptedChain(id, calls, startFailure, stopFailure);
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

    private record ScriptedChain(
            String chainId,
            List<String> calls,
            Throwable startFailure,
            Throwable stopFailure
    ) implements AppLedgerManager.ManagedChain {
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
