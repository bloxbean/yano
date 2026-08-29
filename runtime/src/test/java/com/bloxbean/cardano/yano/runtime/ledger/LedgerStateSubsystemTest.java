package com.bloxbean.cardano.yano.runtime.ledger;

import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.genesis.GenesisBootstrapData;
import com.bloxbean.cardano.yano.api.genesis.GenesisDelegation;
import com.bloxbean.cardano.yano.api.genesis.GenesisPool;
import com.bloxbean.cardano.yano.api.genesis.ShelleyGenesisBootstrap;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerStateSubsystemTest {

    @Test
    void disabledLedgerStateStartsWithoutStoresAndClosesCleanly() {
        LedgerStateSubsystem subsystem = new LedgerStateSubsystem(
                YanoConfig.serverOnly(0),
                options(false),
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
        assertThat(subsystem.ledgerStateProvider()).isNull();
        assertThat(subsystem.health().healthy()).isTrue();

        subsystem.close();

        assertThat(subsystem.health().status()).isEqualTo(SubsystemHealth.Status.DOWN);
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

    @Test
    void pointerIndexVersionGateSkipsStoresWhereIndexIsNotApplicable() {
        AtomicReference<Boolean> completion = new AtomicReference<>();

        LedgerStateSubsystem.completePointerIndexVersionGateIfApplicable(
                pointerState(false, false), completion::set,
                LoggerFactory.getLogger(LedgerStateSubsystemTest.class));

        assertThat(completion.get()).isNull();

        LedgerStateSubsystem.completePointerIndexVersionGateIfApplicable(
                null, completion::set,
                LoggerFactory.getLogger(LedgerStateSubsystemTest.class));

        assertThat(completion.get()).isNull();
    }

    @Test
    void pointerIndexVersionGatePassesApplicableStoreReadinessToAccountStore() {
        AtomicReference<Boolean> completion = new AtomicReference<>();

        LedgerStateSubsystem.completePointerIndexVersionGateIfApplicable(
                pointerState(true, false), completion::set,
                LoggerFactory.getLogger(LedgerStateSubsystemTest.class));

        assertThat(completion.get()).isFalse();

        LedgerStateSubsystem.completePointerIndexVersionGateIfApplicable(
                pointerState(true, true), completion::set,
                LoggerFactory.getLogger(LedgerStateSubsystemTest.class));

        assertThat(completion.get()).isTrue();
    }

    private static UtxoState pointerState(boolean applicable, boolean ready) {
        return new UtxoState() {
            @Override
            public List<Utxo> getUtxosByAddress(String address, int page, int pageSize) {
                return List.of();
            }

            @Override
            public List<Utxo> getUtxosByPaymentCredential(String credential, int page, int pageSize) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getUtxo(Outpoint outpoint) {
                return Optional.empty();
            }

            @Override
            public boolean isEnabled() {
                return applicable;
            }

            @Override
            public boolean isPointerIndexApplicable() {
                return applicable;
            }

            @Override
            public boolean isPointerIndexReadyAtCurrentCoordinate() {
                return ready;
            }
        };
    }

    private static RuntimeOptions options(boolean accountStateEnabled) {
        return new RuntimeOptions(null, null, Map.of(
                "yano.account-state.enabled", accountStateEnabled));
    }
}
