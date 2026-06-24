package com.bloxbean.cardano.yano.runtime.ledger;

import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AccountHistorySubsystemTest {
    @TempDir
    Path tempDir;

    @Test
    void initializesStoreAndControlsPruneLifecycle() {
        DirectRocksDBChainState chainState =
                new DirectRocksDBChainState(tempDir.resolve("chainstate").toString());
        AccountHistorySubsystem subsystem = new AccountHistorySubsystem(
                YanoConfig.serverOnly(0),
                options(),
                chainState,
                new NoopEventBus(),
                LoggerFactory.getLogger(AccountHistorySubsystemTest.class));

        try {
            subsystem.initialize(chainState, epochParamProvider());

            assertThat(subsystem.store()).isNotNull();
            assertThat(subsystem.isPruneServiceRunning()).isFalse();

            subsystem.start();

            assertThat(subsystem.isPruneServiceRunning()).isTrue();

            assertThat(subsystem.pausePruneServiceAndAwait(Duration.ofSeconds(1))).isTrue();
            assertThat(subsystem.isPruneServiceRunning()).isFalse();

            subsystem.resumeAfterSnapshotRestore(true);
            assertThat(subsystem.isPruneServiceRunning()).isTrue();
        } finally {
            subsystem.close();
            chainState.close();
        }
    }

    @Test
    void clientModeDefersPruneUntilStartupRecoveryCompletes() {
        DirectRocksDBChainState chainState =
                new DirectRocksDBChainState(tempDir.resolve("client-chainstate").toString());
        AccountHistorySubsystem subsystem = new AccountHistorySubsystem(
                clientConfig(),
                options(),
                chainState,
                new NoopEventBus(),
                LoggerFactory.getLogger(AccountHistorySubsystemTest.class));

        try {
            subsystem.initialize(chainState, epochParamProvider());

            assertThat(subsystem.store()).isNotNull();
            assertThat(subsystem.isPruneServiceRunning()).isFalse();

            subsystem.completeStartupRecovery();
            subsystem.start();

            assertThat(subsystem.isPruneServiceRunning()).isTrue();
        } finally {
            subsystem.close();
            chainState.close();
        }
    }

    private static RuntimeOptions options() {
        return new RuntimeOptions(null, null, Map.of(
                "yano.account-history.enabled", true,
                "yano.account-history.retention-epochs", 1,
                "yano.account-history.prune-interval-seconds", 60));
    }

    private static YanoConfig clientConfig() {
        return YanoConfig.builder()
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(42L)
                .serverPort(0)
                .enableServer(false)
                .enableClient(true)
                .useRocksDB(true)
                .build();
    }

    private static EpochParamProvider epochParamProvider() {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.ZERO;
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.ZERO;
            }
        };
    }
}
