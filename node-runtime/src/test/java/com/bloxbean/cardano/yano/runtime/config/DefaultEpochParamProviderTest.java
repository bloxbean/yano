package com.bloxbean.cardano.yano.runtime.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link DefaultEpochParamProvider} factory and firstNonByronSlot resolution.
 */
class DefaultEpochParamProviderTest {

    private static final String GENESIS_DIR = "../node-app/config/network/";

    private static String genesis(String network, String file) {
        return GENESIS_DIR + network + "/" + file;
    }

    // --- Factory from NetworkGenesisConfig ---

    @Nested
    class FromNetworkGenesisConfig {

        @Test
        void preview_correctEpochLength() {
            var config = NetworkGenesisConfig.load(
                    genesis("preview", "shelley-genesis.json"),
                    genesis("preview", "byron-genesis.json"),
                    null,
                    genesis("preview", "conway-genesis.json"));

            long firstNonByronSlot = DefaultEpochParamProvider.resolveFirstNonByronSlot(
                    config.getNetworkMagic(), config.hasByronGenesis());

            var provider = DefaultEpochParamProvider.fromNetworkGenesisConfig(config, firstNonByronSlot);

            assertThat(provider.getEpochLength()).isEqualTo(86400);
            assertThat(provider.getByronSlotsPerEpoch()).isEqualTo(4320);
            assertThat(provider.getShelleyStartSlot()).isEqualTo(0);
            assertThat(provider.getSecurityParam()).isEqualTo(432);
        }

        @Test
        void preview_correctProtocolParams() {
            var config = NetworkGenesisConfig.load(
                    genesis("preview", "shelley-genesis.json"),
                    null, null, null);

            var provider = DefaultEpochParamProvider.fromNetworkGenesisConfig(config, 0);

            assertThat(provider.getRho(0)).isEqualByComparingTo(new BigDecimal("0.003"));
            assertThat(provider.getTau(0)).isEqualByComparingTo(new BigDecimal("0.2"));
            assertThat(provider.getA0(0)).isEqualByComparingTo(new BigDecimal("0.3"));
            assertThat(provider.getNOpt(0)).isEqualTo(150);
            assertThat(provider.getMinPoolCost(0)).isEqualTo(BigInteger.valueOf(340000000));
            assertThat(provider.getKeyDeposit(0)).isEqualTo(BigInteger.valueOf(2000000));
            assertThat(provider.getPoolDeposit(0)).isEqualTo(BigInteger.valueOf(500000000));
        }

        @Test
        void preview_conwayParams() {
            var config = NetworkGenesisConfig.load(
                    genesis("preview", "shelley-genesis.json"),
                    null, null,
                    genesis("preview", "conway-genesis.json"));

            var provider = DefaultEpochParamProvider.fromNetworkGenesisConfig(config, 0);

            assertThat(provider.getGovActionLifetime(0)).isEqualTo(30);
            assertThat(provider.getDRepActivity(0)).isEqualTo(20);
            assertThat(provider.getDRepDeposit(0)).isEqualTo(BigInteger.valueOf(500000000));
            assertThat(provider.getCommitteeMinSize(0)).isEqualTo(0);
        }

        @Test
        void preprod_correctEpochLength() {
            var config = NetworkGenesisConfig.load(
                    genesis("preprod", "shelley-genesis.json"),
                    genesis("preprod", "byron-genesis.json"),
                    null, null);

            long firstNonByronSlot = DefaultEpochParamProvider.resolveFirstNonByronSlot(
                    config.getNetworkMagic(), config.hasByronGenesis());

            var provider = DefaultEpochParamProvider.fromNetworkGenesisConfig(config, firstNonByronSlot);

            assertThat(provider.getEpochLength()).isEqualTo(432000);
            assertThat(provider.getByronSlotsPerEpoch()).isEqualTo(21600);
            assertThat(provider.getShelleyStartSlot()).isEqualTo(86400);
            assertThat(provider.getSecurityParam()).isEqualTo(2160);
        }

        @Test
        void mainnet_correctEpochLength() {
            var config = NetworkGenesisConfig.load(
                    genesis("mainnet", "shelley-genesis.json"),
                    genesis("mainnet", "byron-genesis.json"),
                    null, null);

            long firstNonByronSlot = DefaultEpochParamProvider.resolveFirstNonByronSlot(
                    config.getNetworkMagic(), config.hasByronGenesis());

            var provider = DefaultEpochParamProvider.fromNetworkGenesisConfig(config, firstNonByronSlot);

            assertThat(provider.getEpochLength()).isEqualTo(432000);
            assertThat(provider.getByronSlotsPerEpoch()).isEqualTo(21600);
            assertThat(provider.getShelleyStartSlot()).isEqualTo(4492800);
            assertThat(provider.getSecurityParam()).isEqualTo(2160);
        }

        @Test
        void noConwayGenesis_usesDefaults() {
            var config = NetworkGenesisConfig.load(
                    genesis("preview", "shelley-genesis.json"),
                    null, null, null);

            var provider = DefaultEpochParamProvider.fromNetworkGenesisConfig(config, 0);

            // Interface defaults for Conway
            assertThat(provider.getGovActionLifetime(0)).isEqualTo(6);
            assertThat(provider.getCommitteeMinSize(0)).isEqualTo(7);
            assertThat(provider.getCommitteeMaxTermLength(0)).isEqualTo(146);
        }

        @Test
        void getEpochSlotCalc_consistent() {
            var config = NetworkGenesisConfig.load(
                    genesis("preview", "shelley-genesis.json"),
                    null, null, null);

            var provider = DefaultEpochParamProvider.fromNetworkGenesisConfig(config, 0);
            var calc = provider.getEpochSlotCalc();

            assertThat(calc.shelleyEpochLength()).isEqualTo(86400);
            assertThat(calc.slotToEpoch(864004)).isEqualTo(10); // THE preview bug guard
        }
    }

    // --- firstNonByronSlot resolution ---

    @Nested
    class ResolveFirstNonByronSlot {

        @Test
        void mainnet_returns4492800() {
            assertThat(DefaultEpochParamProvider.resolveFirstNonByronSlot(764824073, true))
                    .isEqualTo(4492800);
        }

        @Test
        void preprod_returns86400() {
            assertThat(DefaultEpochParamProvider.resolveFirstNonByronSlot(1, true))
                    .isEqualTo(86400);
        }

        @Test
        void preview_returns0() {
            assertThat(DefaultEpochParamProvider.resolveFirstNonByronSlot(2, true))
                    .isEqualTo(0);
        }

        @Test
        void sanchonet_returns0() {
            assertThat(DefaultEpochParamProvider.resolveFirstNonByronSlot(4, true))
                    .isEqualTo(0);
        }

        @Test
        void unknownNetwork_noByronGenesis_returns0() {
            assertThat(DefaultEpochParamProvider.resolveFirstNonByronSlot(99999, false))
                    .isEqualTo(0);
        }

        @Test
        void unknownNetwork_withByronGenesis_throws() {
            assertThatThrownBy(() ->
                    DefaultEpochParamProvider.resolveFirstNonByronSlot(99999, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot determine first non-Byron slot");
        }
    }

    // --- Sanchonet Conway params ---

    @Nested
    class SanchonetConwayParams {

        @Test
        void correctConwayValues() {
            var config = NetworkGenesisConfig.load(
                    "../node-app/src/main/resources/genesis/sanchonet/shelley-genesis.json",
                    null, null,
                    "../node-app/src/main/resources/genesis/sanchonet/conway-genesis.json");

            var provider = DefaultEpochParamProvider.fromNetworkGenesisConfig(config, 0);

            assertThat(provider.getCommitteeMinSize(0)).isEqualTo(0);
            assertThat(provider.getCommitteeMaxTermLength(0)).isEqualTo(1000);
            assertThat(provider.getGovActionLifetime(0)).isEqualTo(60);
            assertThat(provider.getGovActionDeposit(0)).isEqualTo(new BigInteger("100000000000"));
            assertThat(provider.getDRepDeposit(0)).isEqualTo(BigInteger.valueOf(500000000));
            assertThat(provider.getDRepActivity(0)).isEqualTo(20);
        }
    }
}
