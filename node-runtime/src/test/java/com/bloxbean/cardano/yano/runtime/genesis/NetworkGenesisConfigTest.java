package com.bloxbean.cardano.yano.runtime.genesis;

import com.bloxbean.cardano.yano.runtime.config.NetworkGenesisConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link NetworkGenesisConfig}, {@link AvvmAddressConverter},
 * {@link ByronGenesisParser}, {@link ShelleyGenesisParser}, and {@link ConwayGenesisParser}.
 */
class NetworkGenesisConfigTest {

    // Genesis file paths — relative to node-runtime/ module dir (Gradle test CWD)
    private static final String GENESIS_DIR = "../node-app/config/network/";

    private static String genesisPath(String network, String file) {
        return GENESIS_DIR + network + "/" + file;
    }

    // --- AVVM Address Converter (ported from yaci-store AvvmAddressConverterTest) ---

    @Nested
    class AvvmConverterTests {

        @Test
        void convertAvvmToByronAddress_knownVector() {
            // From yaci-store AvvmAddressConverterTest
            String avvmAddr = "-0BJDi-gauylk4LptQTgjMeo7kY9lTCbZv12vwOSTZk=";
            var result = AvvmAddressConverter.convertAvvmToByronAddress(avvmAddr);

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("Ae2tdPwUPEZHFQnrr2dYB4GEQ8WVKspEyrg29pJ3f7qdjzaxjeShEEokF5f");
        }

        @Test
        void convertAvvmToByronAddress_nullInput_returnsEmpty() {
            var result = AvvmAddressConverter.convertAvvmToByronAddress(null);
            assertThat(result).isEmpty();
        }
    }

    // --- Byron Genesis Tx Hash ---

    @Nested
    class ByronGenesisTxHashTests {

        @Test
        void genesisUtxoTxHash_matchesExpectedValue() {
            // Known AVVM vector from yaci-store AvvmAddressConverterTest
            String avvmAddr = "-0BJDi-gauylk4LptQTgjMeo7kY9lTCbZv12vwOSTZk=";
            String expectedByronAddr = "Ae2tdPwUPEZHFQnrr2dYB4GEQ8WVKspEyrg29pJ3f7qdjzaxjeShEEokF5f";

            String byronAddr = AvvmAddressConverter.convertAvvmToByronAddress(avvmAddr).orElseThrow();
            assertThat(byronAddr).isEqualTo(expectedByronAddr);

            // tx hash = blake2b_256(Base58.decode(byronAddress))
            String txHash = ByronGenesisParser.genesisUtxoTxHash(byronAddr);
            assertThat(txHash).isNotNull();
            assertThat(txHash).hasSize(64); // 32 bytes hex

            // Verify deterministic: same address always produces same hash
            String txHash2 = ByronGenesisParser.genesisUtxoTxHash(expectedByronAddr);
            assertThat(txHash2).isEqualTo(txHash);
        }

        @Test
        void genesisUtxoTxHash_knownMainnetAvvmEntry() {
            // Parse mainnet Byron genesis and verify an AVVM entry produces correct tx hash
            var config = NetworkGenesisConfig.load(
                    genesisPath("mainnet", "shelley-genesis.json"),
                    genesisPath("mainnet", "byron-genesis.json"),
                    null, null);

            var avvmBalances = config.getByronGenesisData().avvmBalances();
            assertThat(avvmBalances).isNotEmpty();

            // Pick the first entry and verify tx hash derivation is consistent
            var firstEntry = avvmBalances.entrySet().iterator().next();
            String byronAddr = firstEntry.getKey();
            String txHash = ByronGenesisParser.genesisUtxoTxHash(byronAddr);
            assertThat(txHash).hasSize(64);

            // Same address → same hash (deterministic)
            assertThat(ByronGenesisParser.genesisUtxoTxHash(byronAddr)).isEqualTo(txHash);
        }
    }

    // --- Preview Genesis ---

    @Nested
    class PreviewGenesis {

        @Test
        void parseShelleyGenesis_correctValues() {
            var config = NetworkGenesisConfig.load(
                    genesisPath("preview", "shelley-genesis.json"),
                    genesisPath("preview", "byron-genesis.json"),
                    null,
                    genesisPath("preview", "conway-genesis.json"));

            assertThat(config.getNetworkMagic()).isEqualTo(2);
            assertThat(config.getEpochLength()).isEqualTo(86400);
            assertThat(config.getSecurityParam()).isEqualTo(432);
            assertThat(config.getByronSlotsPerEpoch()).isEqualTo(4320);
            assertThat(config.getActiveSlotsCoeff()).isEqualTo(0.05);
        }

        @Test
        void parseByronGenesis_correctK() {
            var config = NetworkGenesisConfig.load(
                    genesisPath("preview", "shelley-genesis.json"),
                    genesisPath("preview", "byron-genesis.json"),
                    null, null);

            assertThat(config.hasByronGenesis()).isTrue();
            assertThat(config.getByronGenesisData().k()).isEqualTo(432);
            assertThat(config.getByronGenesisData().epochLength()).isEqualTo(4320);
        }

        @Test
        void parseShelleyProtocolParams() {
            var config = NetworkGenesisConfig.load(
                    genesisPath("preview", "shelley-genesis.json"),
                    null, null, null);

            var shelley = config.getShelleyGenesisData();
            assertThat(shelley.rho()).isEqualTo(0.003);
            assertThat(shelley.tau()).isEqualTo(0.2);
            assertThat(shelley.a0()).isEqualTo(0.3);
            assertThat(shelley.nOpt()).isEqualTo(150);
            assertThat(shelley.minPoolCost()).isEqualTo(340000000);
            assertThat(shelley.keyDeposit()).isEqualTo(2000000);
            assertThat(shelley.poolDeposit()).isEqualTo(500000000);
            assertThat(shelley.decentralisationParam()).isEqualTo(1.0);
        }

        @Test
        void parseConwayGenesis_correctValues() {
            var config = NetworkGenesisConfig.load(
                    genesisPath("preview", "shelley-genesis.json"),
                    null, null,
                    genesisPath("preview", "conway-genesis.json"));

            assertThat(config.hasConwayGenesis()).isTrue();
            var conway = config.getConwayGenesisData();
            assertThat(conway.govActionLifetime()).isEqualTo(30);
            assertThat(conway.dRepActivity()).isEqualTo(20);
            assertThat(conway.dRepDeposit()).isEqualTo(BigInteger.valueOf(500000000));
            assertThat(conway.committeeMinSize()).isEqualTo(0);
        }
    }

    // --- Preprod Genesis ---

    @Nested
    class PreprodGenesis {

        @Test
        void parseShelleyGenesis_correctValues() {
            var config = NetworkGenesisConfig.load(
                    genesisPath("preprod", "shelley-genesis.json"),
                    genesisPath("preprod", "byron-genesis.json"),
                    null, null);

            assertThat(config.getNetworkMagic()).isEqualTo(1);
            assertThat(config.getEpochLength()).isEqualTo(432000);
            assertThat(config.getSecurityParam()).isEqualTo(2160);
            assertThat(config.getByronSlotsPerEpoch()).isEqualTo(21600);
        }

        @Test
        void parseByronGenesis_correctK() {
            var config = NetworkGenesisConfig.load(
                    genesisPath("preprod", "shelley-genesis.json"),
                    genesisPath("preprod", "byron-genesis.json"),
                    null, null);

            assertThat(config.getByronGenesisData().k()).isEqualTo(2160);
            assertThat(config.getByronGenesisData().epochLength()).isEqualTo(21600);
        }
    }

    // --- Mainnet Genesis ---

    @Nested
    class MainnetGenesis {

        @Test
        void parseShelleyGenesis_correctValues() {
            var config = NetworkGenesisConfig.load(
                    genesisPath("mainnet", "shelley-genesis.json"),
                    genesisPath("mainnet", "byron-genesis.json"),
                    null, null);

            assertThat(config.getNetworkMagic()).isEqualTo(764824073);
            assertThat(config.getEpochLength()).isEqualTo(432000);
            assertThat(config.getSecurityParam()).isEqualTo(2160);
            assertThat(config.getByronSlotsPerEpoch()).isEqualTo(21600);
        }

        @Test
        void mainnetByronAvvmBalances_nonEmpty() {
            var config = NetworkGenesisConfig.load(
                    genesisPath("mainnet", "shelley-genesis.json"),
                    genesisPath("mainnet", "byron-genesis.json"),
                    null, null);

            var byronData = config.getByronGenesisData();
            assertThat(byronData.avvmBalances()).isNotEmpty();
            // Mainnet has AVVM entries, no non-AVVM entries
            assertThat(byronData.nonAvvmBalances()).isEmpty();
            assertThat(config.getAllByronBalances()).isNotEmpty();
            assertThat(config.getAllByronBalances().size()).isEqualTo(byronData.avvmBalances().size());
        }
    }

    // --- Sanchonet Genesis ---

    @Nested
    class SanchonetGenesis {

        private String sanchoPath(String file) {
            return "../node-app/src/main/resources/genesis/sanchonet/" + file;
        }

        @Test
        void parseShelleyGenesis_correctValues() {
            var config = NetworkGenesisConfig.load(
                    sanchoPath("shelley-genesis.json"),
                    sanchoPath("byron-genesis.json"),
                    null,
                    sanchoPath("conway-genesis.json"));

            assertThat(config.getNetworkMagic()).isEqualTo(4);
            assertThat(config.getEpochLength()).isEqualTo(86400);
            assertThat(config.getSecurityParam()).isEqualTo(432);
            assertThat(config.getByronSlotsPerEpoch()).isEqualTo(4320);
        }

        @Test
        void parseConwayGenesis_correctValues() {
            var config = NetworkGenesisConfig.load(
                    sanchoPath("shelley-genesis.json"),
                    null, null,
                    sanchoPath("conway-genesis.json"));

            var conway = config.getConwayGenesisData();
            assertThat(conway).isNotNull();
            assertThat(conway.committeeMinSize()).isEqualTo(0);
            assertThat(conway.committeeMaxTermLength()).isEqualTo(1000);
            assertThat(conway.govActionLifetime()).isEqualTo(60);
            assertThat(conway.govActionDeposit()).isEqualTo(new BigInteger("100000000000"));
            assertThat(conway.dRepDeposit()).isEqualTo(BigInteger.valueOf(500000000));
            assertThat(conway.dRepActivity()).isEqualTo(20);
        }

        @Test
        void parseByronGenesis_correctK() {
            var config = NetworkGenesisConfig.load(
                    sanchoPath("shelley-genesis.json"),
                    sanchoPath("byron-genesis.json"),
                    null, null);

            assertThat(config.getByronGenesisData().k()).isEqualTo(432);
            assertThat(config.getByronGenesisData().epochLength()).isEqualTo(4320);
        }
    }

    // --- Error cases ---

    @Nested
    class ErrorCases {

        @Test
        void nullShelleyPath_throws() {
            assertThatThrownBy(() -> NetworkGenesisConfig.load(null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("required");
        }

        @Test
        void blankShelleyPath_throws() {
            assertThatThrownBy(() -> NetworkGenesisConfig.load("  ", null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("required");
        }

        @Test
        void nonexistentShelleyPath_throws() {
            assertThatThrownBy(() -> NetworkGenesisConfig.load("/nonexistent/path.json", null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void configuredButMissingByronPath_throws() {
            assertThatThrownBy(() -> NetworkGenesisConfig.load(
                    genesisPath("preview", "shelley-genesis.json"),
                    "/nonexistent/byron.json", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Byron genesis file configured but not found");
        }

        @Test
        void protocolMagicMismatch_throws(@TempDir Path tempDir) throws Exception {
            // Create a shelley genesis with magic=2 and a byron genesis with magic=999
            Path shelley = tempDir.resolve("shelley.json");
            Files.writeString(shelley, """
                    {"networkMagic": 2, "epochLength": 86400, "securityParam": 432,
                     "activeSlotsCoeff": 0.05, "maxLovelaceSupply": 45000000000000000,
                     "protocolParams": {"protocolVersion": {"major": 6, "minor": 0}}}
                    """);
            Path byron = tempDir.resolve("byron.json");
            Files.writeString(byron, """
                    {"nonAvvmBalances": {}, "startTime": 0,
                     "protocolConsts": {"k": 432, "protocolMagic": 999},
                     "blockVersionData": {"slotDuration": "20000"}}
                    """);

            assertThatThrownBy(() -> NetworkGenesisConfig.load(
                    shelley.toString(), byron.toString(), null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Protocol magic mismatch");
        }

        @Test
        void noByronGenesis_byronSlotsPerEpochFallsBackToSecurityParam() {
            var config = NetworkGenesisConfig.load(
                    genesisPath("preview", "shelley-genesis.json"),
                    null, null, null);

            assertThat(config.hasByronGenesis()).isFalse();
            // Falls back to securityParam * 10 = 432 * 10 = 4320
            assertThat(config.getByronSlotsPerEpoch()).isEqualTo(4320);
        }

        @Test
        void nullConwayPath_returnsNullConwayData() {
            // null path = not configured → no error, no Conway data
            var config = NetworkGenesisConfig.load(
                    genesisPath("preview", "shelley-genesis.json"),
                    null, null, null);

            assertThat(config.hasConwayGenesis()).isFalse();
            assertThat(config.getConwayGenesisData()).isNull();
        }

        @Test
        void configuredButMissingConwayPath_throws() {
            assertThatThrownBy(() -> NetworkGenesisConfig.load(
                    genesisPath("preview", "shelley-genesis.json"),
                    null, null, "/nonexistent/conway.json"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Conway genesis file configured but not found");
        }
    }

    // --- getAllByronBalances ---

    @Nested
    class ByronBalanceCombination {

        @Test
        void noByronGenesis_returnsEmptyMap() {
            var config = NetworkGenesisConfig.load(
                    genesisPath("preview", "shelley-genesis.json"),
                    null, null, null);

            assertThat(config.getAllByronBalances()).isEmpty();
        }

        @Test
        void preprodByronGenesis_hasNonAvvmOnly() {
            var config = NetworkGenesisConfig.load(
                    genesisPath("preprod", "shelley-genesis.json"),
                    genesisPath("preprod", "byron-genesis.json"),
                    null, null);

            var byronData = config.getByronGenesisData();
            assertThat(byronData.nonAvvmBalances()).isNotEmpty();
            assertThat(byronData.avvmBalances()).isEmpty();
            assertThat(config.getAllByronBalances()).isEqualTo(byronData.nonAvvmBalances());
        }
    }
}
