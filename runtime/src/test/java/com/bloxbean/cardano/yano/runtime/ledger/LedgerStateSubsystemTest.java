package com.bloxbean.cardano.yano.runtime.ledger;

import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.genesis.GenesisBootstrapData;
import com.bloxbean.cardano.yano.api.genesis.GenesisDelegation;
import com.bloxbean.cardano.yano.api.genesis.GenesisPool;
import com.bloxbean.cardano.yano.api.genesis.ShelleyGenesisBootstrap;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerStateSubsystemTest {

    @Test
    void disabledLedgerStateStartsWithoutStoresAndClosesCleanly() {
        LedgerStateSubsystem subsystem = new LedgerStateSubsystem(
                YanoConfig.serverOnly(0),
                options(false, false),
                new InMemoryChainState(),
                new NoopEventBus(),
                LoggerFactory.getLogger(LedgerStateSubsystemTest.class),
                null,
                null,
                null,
                null,
                () -> null,
                () -> null,
                () -> null,
                null);

        assertThat(subsystem.name()).isEqualTo("ledger-state");
        assertThat(subsystem.accountStateStore()).isNull();
        assertThat(subsystem.accountHistoryStore()).isNull();
        assertThat(subsystem.ledgerStateProvider()).isNull();
        assertThat(subsystem.accountHistoryProvider()).isNull();
        assertThat(subsystem.health().healthy()).isTrue();

        subsystem.close();

        assertThat(subsystem.health().status()).isEqualTo(SubsystemHealth.Status.DOWN);
    }

    @Test
    void accountHistoryCannotStartWithoutAccountState() {
        assertThatThrownBy(() -> new LedgerStateSubsystem(
                YanoConfig.serverOnly(0),
                options(false, true),
                new InMemoryChainState(),
                new NoopEventBus(),
                LoggerFactory.getLogger(LedgerStateSubsystemTest.class),
                null,
                null,
                null,
                null,
                () -> null,
                () -> null,
                () -> null,
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to initialize ledger-state subsystem")
                .hasRootCauseMessage("Account history requested but not initialized because account-state is disabled");
    }

    @Test
    void genesisBootstrapPublicationRuleLivesWithLedgerSubsystem() {
        assertThat(LedgerStateSubsystem.shouldFailClosedGenesisBootstrapPublication(GenesisBootstrapData.empty()))
                .isFalse();
        assertThat(LedgerStateSubsystem.shouldFailClosedGenesisBootstrapPublication(
                new GenesisBootstrapData("aa".repeat(32), ShelleyGenesisBootstrap.empty())))
                .isTrue();

        ShelleyGenesisBootstrap staking = new ShelleyGenesisBootstrap(
                Map.of(),
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                List.of(new GenesisPool("aa".repeat(28), "bb".repeat(32),
                        BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ONE,
                        "e0" + "cc".repeat(28), Set.of(), List.of(), null, null)),
                List.of(new GenesisDelegation("dd".repeat(28), "aa".repeat(28))));

        assertThat(LedgerStateSubsystem.shouldFailClosedGenesisBootstrapPublication(
                new GenesisBootstrapData(null, staking)))
                .isTrue();
    }

    @Test
    void failClosedGenesisBootstrapRequiresEraMetadata() {
        LedgerStateSubsystem.ensureGenesisBootstrapEraAvailable(
                GenesisBootstrapData.empty(), false, "missing");

        assertThatThrownBy(() -> LedgerStateSubsystem.ensureGenesisBootstrapEraAvailable(
                new GenesisBootstrapData("aa".repeat(32), ShelleyGenesisBootstrap.empty()),
                false,
                "era metadata service is unavailable"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("era metadata service is unavailable");

        ShelleyGenesisBootstrap staking = new ShelleyGenesisBootstrap(
                Map.of(),
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                List.of(new GenesisPool("aa".repeat(28), "bb".repeat(32),
                        BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ONE,
                        "e0" + "cc".repeat(28), Set.of(), List.of(), null, null)),
                List.of(new GenesisDelegation("dd".repeat(28), "aa".repeat(28))));

        assertThatThrownBy(() -> LedgerStateSubsystem.ensureGenesisBootstrapEraAvailable(
                new GenesisBootstrapData(null, staking),
                false,
                "earliest known era is unavailable"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("earliest known era is unavailable");
    }

    private static RuntimeOptions options(boolean accountStateEnabled, boolean accountHistoryEnabled) {
        return new RuntimeOptions(null, null, Map.of(
                "yano.account-state.enabled", accountStateEnabled,
                "yano.account-history.enabled", accountHistoryEnabled));
    }
}
