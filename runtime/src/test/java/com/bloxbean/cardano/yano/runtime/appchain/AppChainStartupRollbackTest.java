package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@Timeout(30)
class AppChainStartupRollbackTest {

    private static final String CHAIN_ID = "startup-rollback";
    private static final byte[] SIGNING_KEY = signingKey();
    private static final String PUBLIC_KEY = HexUtil.encodeHexString(
            KeyGenUtil.getPublicKeyFromPrivateKey(SIGNING_KEY));

    @TempDir
    Path tempDir;

    @Test
    void fatalSinkRollbackCloseWinsAfterRemainingResourcesAreScheduled() throws Exception {
        TestVirtualMachineError fatalCleanup = new TestVirtualMachineError();
        AtomicInteger neighborCloseCalls = new AtomicInteger();
        FinalizedStreamSink fatalSink = new FinalizedStreamSink() {
            @Override public String id() { return "fatal-rollback-close"; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() { throw fatalCleanup; }
        };
        FinalizedStreamSink neighborSink = new FinalizedStreamSink() {
            @Override public String id() { return "neighbor-rollback-close"; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() { neighborCloseCalls.incrementAndGet(); }
        };
        FinalizedStreamSinkFactory factory = new FinalizedStreamSinkFactory() {
            @Override public String scheme() { return "fatal"; }

            @Override
            public List<FinalizedStreamSink> create(String chainId, Map<String, String> config) {
                return List.of(fatalSink, neighborSink);
            }
        };
        PluginProviderRegistry registry = registry(factory);
        Path ledgerBase = tempDir.resolve("fatal-rollback-ledger");
        AppChainSubsystem failed = subsystem(ledgerBase, Map.of(
                "sinks.fatal.enabled", "true",
                "effects.executor.enabled", "true",
                "effects.enabled", "invalid"), registry);

        Throwable observed = catchThrowable(failed::start);

        assertThat(observed).isSameAs(fatalCleanup);
        assertThat(fatalCleanup.getSuppressed()).hasSize(1);
        assertThat(fatalCleanup.getSuppressed()[0])
                .isInstanceOf(com.bloxbean.cardano.yano.api.plugin.PluginActivationException.class);
        assertThat(neighborCloseCalls).hasValue(1);
        // The fatal is rethrown only after the engine/ledger lifetime barriers
        // are installed; the deferred ledger close must therefore still run.
        awaitLedgerReopen(ledgerBase.resolve(CHAIN_ID));
    }

    @Test
    void failedStartReleasesLedgerSoSamePathCanReopenImmediately() {
        Path ledgerBase = tempDir.resolve("ledger");
        AppChainSubsystem failed = subsystem(ledgerBase, Map.of(
                "observers.deposits.type", "address-deposit",
                "observers.deposits.address", "addr_test1_startup_rollback"));

        // Observer construction occurs after RocksDB and AppChainEngine are
        // created; stability-depth=0 then produces a deterministic start error.
        assertThatThrownBy(failed::start)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("L1 observers require l1.stability-depth > 0");
        assertThat(Files.isDirectory(ledgerBase.resolve(CHAIN_ID))).isTrue();
        assertThat(failed.health().status()).isEqualTo(SubsystemHealth.Status.DOWN);

        // A leaked RocksDB handle would fail here with a LOCK acquisition error.
        AppChainSubsystem replacement = subsystem(ledgerBase, Map.of());
        try {
            replacement.start();
            assertThat(replacement.health().status()).isEqualTo(SubsystemHealth.Status.UP);
            assertThat(replacement.tipHeight()).isZero();
        } finally {
            replacement.stop();
        }
    }

    @Test
    void normalStopAllowsSameSubsystemToRestart() {
        AppChainSubsystem subsystem = subsystem(tempDir.resolve("restart-ledger"), Map.of());
        try {
            subsystem.start();
            assertThat(subsystem.health().status()).isEqualTo(SubsystemHealth.Status.UP);

            subsystem.stop();
            assertThat(subsystem.health().status()).isEqualTo(SubsystemHealth.Status.DOWN);

            subsystem.start();
            assertThat(subsystem.health().status()).isEqualTo(SubsystemHealth.Status.UP);
            assertThat(subsystem.tipHeight()).isZero();
        } finally {
            subsystem.stop();
        }
    }

    private static AppChainSubsystem subsystem(Path ledgerBase, Map<String, String> settings) {
        AppChainConfig config = config(settings);
        return new AppChainSubsystem(config, 42, null, null,
                ledgerBase.toString(), null,
                LoggerFactory.getLogger(AppChainStartupRollbackTest.class));
    }

    private static AppChainSubsystem subsystem(
            Path ledgerBase,
            Map<String, String> settings,
            PluginProviderRegistry registry
    ) {
        AppChainConfig config = config(settings);
        return new AppChainSubsystem(config, 42, null, null,
                ledgerBase.toString(), null, registry,
                LoggerFactory.getLogger(AppChainStartupRollbackTest.class));
    }

    private static AppChainConfig config(Map<String, String> settings) {
        return AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(HexUtil.encodeHexString(SIGNING_KEY))
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .proposerKeyHex(PUBLIC_KEY)
                .threshold(1)
                .pluginSettings(settings)
                .build();
    }

    private static PluginProviderRegistry registry(FinalizedStreamSinkFactory factory) {
        return new PluginProviderRegistry() {
            @Override
            public <P> Optional<P> find(Class<P> providerType, String selector) {
                if (providerType == FinalizedStreamSinkFactory.class
                        && factory.scheme().equals(selector)) {
                    return Optional.of(providerType.cast(factory));
                }
                return Optional.empty();
            }

            @Override
            public <P> List<String> names(Class<P> providerType) {
                return providerType == FinalizedStreamSinkFactory.class
                        ? List.of(factory.scheme()) : List.of();
            }
        };
    }

    private static void awaitLedgerReopen(Path ledgerPath) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try (AppLedgerStore ignored = new AppLedgerStore(
                    ledgerPath.toString(),
                    LoggerFactory.getLogger("fatal-rollback-ledger-probe"))) {
                return;
            } catch (RuntimeException failure) {
                lastFailure = failure;
                Thread.sleep(25);
            }
        }
        throw new AssertionError("Ledger remained locked after fatal rollback cleanup", lastFailure);
    }

    private static byte[] signingKey() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 93);
        return key;
    }

    private static final class TestVirtualMachineError extends VirtualMachineError { }
}
