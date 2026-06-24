package com.bloxbean.cardano.yano.runtime;

import com.bloxbean.cardano.yano.api.genesis.GenesisBootstrapData;
import com.bloxbean.cardano.yano.api.genesis.GenesisDelegation;
import com.bloxbean.cardano.yano.api.genesis.GenesisPool;
import com.bloxbean.cardano.yano.api.genesis.ShelleyGenesisBootstrap;
import com.bloxbean.cardano.yano.runtime.ledger.LedgerStateSubsystem;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class YanoGenesisBootstrapPublicationTest {

    @Test
    void directStartGenesisPublicationFailsClosedForHashOnlyPayloads() {
        assertThat(LedgerStateSubsystem.shouldFailClosedGenesisBootstrapPublication(GenesisBootstrapData.empty()))
                .isFalse();
        assertThat(LedgerStateSubsystem.shouldFailClosedGenesisBootstrapPublication(
                new GenesisBootstrapData("aa".repeat(32), ShelleyGenesisBootstrap.empty())))
                .isTrue();
    }

    @Test
    void directStartGenesisPublicationFailsClosedForStakingPayloads() {
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
}
