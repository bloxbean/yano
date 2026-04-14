package com.bloxbean.cardano.yano.runtime.config;

import com.bloxbean.cardano.yano.api.NetworkGenesisValues;
import com.bloxbean.cardano.yano.ledgerstate.NetworkConfigBuilder;
import org.cardanofoundation.rewards.calculation.config.NetworkConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Validates that dynamically built CF NetworkConfig from genesis matches
 * the CF library's built-in configs for known networks.
 * <p>
 * These tests FAIL on any field mismatch — they are the safety net for Phase 6.
 */
class NetworkConfigBuilderValidationTest {

    private static final String GENESIS_DIR = "../node-app/config/network/";

    private static NetworkConfig buildFromGenesis(String network) {
        String shelley = GENESIS_DIR + network + "/shelley-genesis.json";
        String byron = GENESIS_DIR + network + "/byron-genesis.json";
        String conway = GENESIS_DIR + network + "/conway-genesis.json";

        var genesisConfig = NetworkGenesisConfig.load(shelley, byron, null, conway);
        NetworkGenesisValues values = NetworkGenesisValuesFactory.build(genesisConfig);
        return NetworkConfigBuilder.build(values);
    }

    private static void assertAllFieldsMatch(NetworkConfig actual, NetworkConfig expected, String network) {
        assertThat(actual.getNetworkMagic())
                .as("%s networkMagic", network).isEqualTo(expected.getNetworkMagic());
        assertThat(actual.getTotalLovelace())
                .as("%s totalLovelace", network).isEqualTo(expected.getTotalLovelace());
        assertThat(actual.getPoolDepositInLovelace())
                .as("%s poolDepositInLovelace", network).isEqualTo(expected.getPoolDepositInLovelace());
        assertThat(actual.getExpectedSlotsPerEpoch())
                .as("%s expectedSlotsPerEpoch", network).isEqualTo(expected.getExpectedSlotsPerEpoch());
        assertThat(actual.getShelleyInitialReserves())
                .as("%s shelleyInitialReserves", network).isEqualTo(expected.getShelleyInitialReserves());
        assertThat(actual.getShelleyInitialTreasury())
                .as("%s shelleyInitialTreasury", network).isEqualTo(expected.getShelleyInitialTreasury());
        assertThat(actual.getShelleyInitialUtxo())
                .as("%s shelleyInitialUtxo", network).isEqualTo(expected.getShelleyInitialUtxo());
        assertThat(actual.getGenesisConfigSecurityParameter())
                .as("%s genesisConfigSecurityParameter", network).isEqualTo(expected.getGenesisConfigSecurityParameter());
        assertThat(actual.getShelleyStartEpoch())
                .as("%s shelleyStartEpoch", network).isEqualTo(expected.getShelleyStartEpoch());
        assertThat(actual.getAllegraHardforkEpoch())
                .as("%s allegraHardforkEpoch", network).isEqualTo(expected.getAllegraHardforkEpoch());
        assertThat(actual.getVasilHardforkEpoch())
                .as("%s vasilHardforkEpoch", network).isEqualTo(expected.getVasilHardforkEpoch());
        assertThat(actual.getBootstrapAddressAmount())
                .as("%s bootstrapAddressAmount", network).isEqualTo(expected.getBootstrapAddressAmount());
        assertThat(actual.getActiveSlotCoefficient())
                .as("%s activeSlotCoefficient", network).isEqualTo(expected.getActiveSlotCoefficient());
        assertThat(actual.getRandomnessStabilisationWindow())
                .as("%s randomnessStabilisationWindow", network).isEqualTo(expected.getRandomnessStabilisationWindow());
        assertThat(actual.getShelleyStartDecentralisation())
                .as("%s shelleyStartDecentralisation", network).isEqualByComparingTo(expected.getShelleyStartDecentralisation());
        assertThat(actual.getShelleyStartTreasuryGrowRate())
                .as("%s shelleyStartTreasuryGrowRate", network).isEqualByComparingTo(expected.getShelleyStartTreasuryGrowRate());
        assertThat(actual.getShelleyStartMonetaryExpandRate())
                .as("%s shelleyStartMonetaryExpandRate", network).isEqualByComparingTo(expected.getShelleyStartMonetaryExpandRate());
        assertThat(actual.getShelleyStartOptimalPoolCount())
                .as("%s shelleyStartOptimalPoolCount", network).isEqualTo(expected.getShelleyStartOptimalPoolCount());
        assertThat(actual.getShelleyStartPoolOwnerInfluence())
                .as("%s shelleyStartPoolOwnerInfluence", network).isEqualByComparingTo(expected.getShelleyStartPoolOwnerInfluence());
    }

    @Nested
    class MainnetValidation {
        @Test
        void allFieldsMatchCfBuiltIn() {
            NetworkConfig built = buildFromGenesis("mainnet");
            NetworkConfig expected = NetworkConfig.getMainnetConfig();
            assertAllFieldsMatch(built, expected, "mainnet");
        }
    }

    @Nested
    class PreprodValidation {
        @Test
        void allFieldsMatchCfBuiltIn() {
            NetworkConfig built = buildFromGenesis("preprod");
            NetworkConfig expected = NetworkConfig.getPreprodConfig();
            assertAllFieldsMatch(built, expected, "preprod");
        }
    }

    @Nested
    class PreviewValidation {
        @Test
        void allFieldsMatchCfBuiltIn() {
            NetworkConfig built = buildFromGenesis("preview");
            NetworkConfig expected = NetworkConfig.getPreviewConfig();
            assertAllFieldsMatch(built, expected, "preview");
        }
    }

    // Sanchonet CF validation deliberately SKIPPED — CF library has wrong networkMagic (2 instead of 4)

    @Nested
    class CustomDevnet {

        private static final String DEVNET_DIR = GENESIS_DIR + "devnet/";

        @Test
        void customMagicAndEpochParams() {
            // Uses real devnet genesis: magic=42, epochLength=600, securityParam=100,
            // activeSlotsCoeff=1.0, a0=0.0, nOpt=100, 25 initial funds, no Byron balances
            var genesisConfig = NetworkGenesisConfig.load(
                    DEVNET_DIR + "shelley-genesis.json",
                    DEVNET_DIR + "byron-genesis.json",
                    null, null);
            NetworkGenesisValues values = NetworkGenesisValuesFactory.build(genesisConfig);

            assertThat(values.networkMagic()).isEqualTo(42);
            assertThat(values.expectedSlotsPerEpoch()).isEqualTo(600);
            assertThat(values.securityParam()).isEqualTo(100);
            assertThat(values.activeSlotsCoeff()).isEqualTo(1.0);
            assertThat(values.optimalPoolCount()).isEqualTo(100);
            // Custom devnet: shelleyStartEpoch defaults to 0
            assertThat(values.shelleyStartEpoch()).isEqualTo(0);
        }

        @Test
        void initialUtxoFromGenesisDistributions() {
            // Devnet has 25 initial funds (9010500000000000 total), no Byron balances
            var genesisConfig = NetworkGenesisConfig.load(
                    DEVNET_DIR + "shelley-genesis.json",
                    DEVNET_DIR + "byron-genesis.json",
                    null, null);
            NetworkGenesisValues values = NetworkGenesisValuesFactory.build(genesisConfig);

            // Initial UTXO = sum of Shelley initial funds (no Byron balances)
            assertThat(values.shelleyInitialUtxo())
                    .isEqualTo(new java.math.BigInteger("9010500000000000"));
            // Reserves = maxLovelaceSupply - initialUtxo
            assertThat(values.shelleyInitialReserves())
                    .isEqualTo(new java.math.BigInteger("35989500000000000"));
            // Treasury = 0 for custom devnet
            assertThat(values.shelleyInitialTreasury()).isEqualTo(java.math.BigInteger.ZERO);
            // Bootstrap = 0 for non-mainnet
            assertThat(values.bootstrapAddressAmount()).isEqualTo(java.math.BigInteger.ZERO);
        }

        @Test
        void cfNetworkConfigBuildsFromCustomValues() {
            var genesisConfig = NetworkGenesisConfig.load(
                    DEVNET_DIR + "shelley-genesis.json",
                    DEVNET_DIR + "byron-genesis.json",
                    null, null);
            NetworkGenesisValues values = NetworkGenesisValuesFactory.build(genesisConfig);
            var cfConfig = NetworkConfigBuilder.build(values);

            assertThat(cfConfig.getNetworkMagic()).isEqualTo(42);
            assertThat(cfConfig.getExpectedSlotsPerEpoch()).isEqualTo(600);
            assertThat(cfConfig.getGenesisConfigSecurityParameter()).isEqualTo(100);
            assertThat(cfConfig.getShelleyStartEpoch()).isEqualTo(0);
            // Unknown/custom networks: hardfork epochs default to MAX_VALUE ("not reached")
            assertThat(cfConfig.getAllegraHardforkEpoch()).isEqualTo(Integer.MAX_VALUE);
            assertThat(cfConfig.getVasilHardforkEpoch()).isEqualTo(Integer.MAX_VALUE);
        }

        // NOTE: this devnet has empty Byron balances so it does NOT test the Byron-history
        // boundary-state derivation path (Fix 5). That requires a custom network where Byron
        // transactions change UTXO state before Shelley.
    }
}
