package com.bloxbean.cardano.yano.runtime.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link NetworkGenesisValuesFactory} — override and failure modes.
 */
class NetworkGenesisValuesFactoryTest {

    private static final String GENESIS_DIR = "../node-app/config/network/";

    @Test
    void knownNetwork_ignoresOverride() {
        // Preview is a known network — override should be ignored
        var ngc = NetworkGenesisConfig.load(
                GENESIS_DIR + "preview/shelley-genesis.json",
                GENESIS_DIR + "preview/byron-genesis.json",
                null, null);

        var values = NetworkGenesisValuesFactory.build(ngc, new BigInteger("99999"));

        // Should use CF constant, not override
        assertThat(values.shelleyInitialUtxo()).isEqualTo(new BigInteger("30009000000000000"));
    }

    @Test
    void customDevnet_noByron_usesGenesisSum() {
        var ngc = NetworkGenesisConfig.load(
                GENESIS_DIR + "devnet/shelley-genesis.json",
                GENESIS_DIR + "devnet/byron-genesis.json",
                null, null);

        // Devnet has magic=42, Byron balances are empty, so no-Byron path
        var values = NetworkGenesisValuesFactory.build(ngc);

        assertThat(values.shelleyInitialUtxo()).isEqualTo(new BigInteger("9010500000000000"));
    }

    @Test
    void unknownByronNetwork_withOverride_usesOverride(@TempDir Path tempDir) throws Exception {
        // Create synthetic unknown+Byron genesis with non-empty Byron balances
        Path shelley = tempDir.resolve("shelley.json");
        Files.writeString(shelley, """
                {"networkMagic": 99999, "epochLength": 600, "securityParam": 100,
                 "activeSlotsCoeff": 1.0, "maxLovelaceSupply": 45000000000000000,
                 "initialFunds": {}, "protocolParams": {"protocolVersion": {"major": 6, "minor": 0},
                 "rho": 0.003, "tau": 0.2, "a0": 0.0, "nOpt": 100, "minPoolCost": 0,
                 "keyDeposit": 2000000, "poolDeposit": 500000000, "decentralisationParam": 0}}
                """);
        Path byron = tempDir.resolve("byron.json");
        Files.writeString(byron, """
                {"nonAvvmBalances": {"addr1": "1000000000"}, "startTime": 0,
                 "protocolConsts": {"k": 100, "protocolMagic": 99999},
                 "blockVersionData": {"slotDuration": "1000"}}
                """);

        var ngc = NetworkGenesisConfig.load(shelley.toString(), byron.toString(), null, null);
        BigInteger override = new BigInteger("12345678900000000");
        var overrides = new NetworkGenesisValuesFactory.Overrides(override, 5, 10, 20);
        var values = NetworkGenesisValuesFactory.build(ngc, overrides);

        // Unknown+Byron: uses override for initial UTXO
        assertThat(values.shelleyInitialUtxo()).isEqualTo(override);
        // Uses override hardfork epochs (not defaults)
        assertThat(values.shelleyStartEpoch()).isEqualTo(5);
        assertThat(values.allegraHardforkEpoch()).isEqualTo(10);
        assertThat(values.vasilHardforkEpoch()).isEqualTo(20);
    }

    @Test
    void unknownByronNetwork_noOverride_throws(@TempDir Path tempDir) throws Exception {
        // Synthetic unknown+Byron genesis with non-empty Byron balances
        Path shelley = tempDir.resolve("shelley.json");
        Files.writeString(shelley, """
                {"networkMagic": 99999, "epochLength": 600, "securityParam": 100,
                 "activeSlotsCoeff": 1.0, "maxLovelaceSupply": 45000000000000000,
                 "initialFunds": {}, "protocolParams": {"protocolVersion": {"major": 6, "minor": 0},
                 "rho": 0.003, "tau": 0.2, "a0": 0.0, "nOpt": 100, "minPoolCost": 0,
                 "keyDeposit": 2000000, "poolDeposit": 500000000, "decentralisationParam": 0}}
                """);
        Path byron = tempDir.resolve("byron.json");
        Files.writeString(byron, """
                {"nonAvvmBalances": {"addr1": "1000000000"}, "startTime": 0,
                 "protocolConsts": {"k": 100, "protocolMagic": 99999},
                 "blockVersionData": {"slotDuration": "1000"}}
                """);

        var ngc = NetworkGenesisConfig.load(shelley.toString(), byron.toString(), null, null);

        // Unknown+Byron without override → throws
        assertThatThrownBy(() -> NetworkGenesisValuesFactory.build(ngc, NetworkGenesisValuesFactory.Overrides.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Custom network")
                .hasMessageContaining("Byron history");
    }

    @Test
    void unknownByronNetwork_hardforkEpochsDefaultToMaxValue(@TempDir Path tempDir) throws Exception {
        // Unknown+Byron with initial UTXO override but no hardfork epoch overrides
        Path shelley = tempDir.resolve("shelley.json");
        Files.writeString(shelley, """
                {"networkMagic": 99999, "epochLength": 600, "securityParam": 100,
                 "activeSlotsCoeff": 1.0, "maxLovelaceSupply": 45000000000000000,
                 "initialFunds": {}, "protocolParams": {"protocolVersion": {"major": 6, "minor": 0},
                 "rho": 0.003, "tau": 0.2, "a0": 0.0, "nOpt": 100, "minPoolCost": 0,
                 "keyDeposit": 2000000, "poolDeposit": 500000000, "decentralisationParam": 0}}
                """);
        Path byron = tempDir.resolve("byron.json");
        Files.writeString(byron, """
                {"nonAvvmBalances": {"addr1": "1000000000"}, "startTime": 0,
                 "protocolConsts": {"k": 100, "protocolMagic": 99999},
                 "blockVersionData": {"slotDuration": "1000"}}
                """);

        var ngc = NetworkGenesisConfig.load(shelley.toString(), byron.toString(), null, null);
        // Override initial UTXO but NOT hardfork epochs
        var overrides = new NetworkGenesisValuesFactory.Overrides(
                new BigInteger("1000000000"), null, null, null);
        var values = NetworkGenesisValuesFactory.build(ngc, overrides);

        // Hardfork epochs default to MAX_VALUE for unknown networks ("not reached")
        assertThat(values.allegraHardforkEpoch()).isEqualTo(Integer.MAX_VALUE);
        assertThat(values.vasilHardforkEpoch()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void unknownByronNetwork_shelleyStartEpochDefaultsToMaxValue(@TempDir Path tempDir) throws Exception {
        Path shelley = tempDir.resolve("shelley.json");
        Files.writeString(shelley, """
                {"networkMagic": 99999, "epochLength": 600, "securityParam": 100,
                 "activeSlotsCoeff": 1.0, "maxLovelaceSupply": 45000000000000000,
                 "initialFunds": {}, "protocolParams": {"protocolVersion": {"major": 6, "minor": 0},
                 "rho": 0.003, "tau": 0.2, "a0": 0.0, "nOpt": 100, "minPoolCost": 0,
                 "keyDeposit": 2000000, "poolDeposit": 500000000, "decentralisationParam": 0}}
                """);
        Path byron = tempDir.resolve("byron.json");
        Files.writeString(byron, """
                {"nonAvvmBalances": {"addr1": "1000000000"}, "startTime": 0,
                 "protocolConsts": {"k": 100, "protocolMagic": 99999},
                 "blockVersionData": {"slotDuration": "1000"}}
                """);

        var ngc = NetworkGenesisConfig.load(shelley.toString(), byron.toString(), null, null);
        // Provide initial UTXO override but NOT shelley epoch override
        var overrides = new NetworkGenesisValuesFactory.Overrides(
                new BigInteger("1000000000"), null, null, null);
        var values = NetworkGenesisValuesFactory.build(ngc, overrides);

        // shelleyStartEpoch = ERA_NOT_REACHED for unknown+Byron without override
        assertThat(values.shelleyStartEpoch()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void knownNetwork_noOverride_succeeds() {
        var ngc = NetworkGenesisConfig.load(
                GENESIS_DIR + "mainnet/shelley-genesis.json",
                GENESIS_DIR + "mainnet/byron-genesis.json",
                null, null);

        // Known mainnet: build succeeds without overrides (uses CF constants)
        assertThatCode(() -> NetworkGenesisValuesFactory.build(ngc, NetworkGenesisValuesFactory.Overrides.NONE))
                .doesNotThrowAnyException();
    }
}
