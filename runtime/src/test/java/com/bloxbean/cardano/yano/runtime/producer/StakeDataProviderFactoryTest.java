package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.client.crypto.BlockProducerKeys;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.blockproducer.FixedStakeDataProvider;
import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisStakeDataProvider;
import com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderBlockProducer;
import com.bloxbean.cardano.yano.runtime.blockproducer.StakeDataProvider;
import com.bloxbean.cardano.yano.runtime.blockproducer.YaciStoreStakeDataProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StakeDataProviderFactoryTest {
    private static final Path DEVNET_FIXTURE = Path.of("src/test/resources/devnet");

    @Test
    void liveSlotLeaderUsesYaciStoreProviderWhenUrlIsConfigured() throws Exception {
        StakeDataProvider provider = StakeDataProviderFactory.createLiveSlotLeaderProvider(
                YanoConfig.builder()
                        .stakeDataProviderUrl("http://localhost:8080/")
                        .build());

        try {
            assertThat(provider).isInstanceOf(YaciStoreStakeDataProvider.class);
            assertThat(StakeDataProviderFactory.hasStakeDataProviderUrl(
                    YanoConfig.builder().stakeDataProviderUrl("http://localhost:8080").build()))
                    .isTrue();
        } finally {
            ((YaciStoreStakeDataProvider) provider).close();
        }
    }

    @Test
    void liveSlotLeaderUsesFixedProviderWhenUrlIsBlank() {
        StakeDataProvider provider = StakeDataProviderFactory.createLiveSlotLeaderProvider(
                YanoConfig.builder()
                        .stakeDataProviderUrl(" ")
                        .build());

        assertThat(provider).isInstanceOf(FixedStakeDataProvider.class);
        assertThat(provider.getPoolStake("pool", 0))
                .isEqualTo(provider.getTotalStake(0));
        assertThat(StakeDataProviderFactory.hasStakeDataProviderUrl(
                YanoConfig.builder().stakeDataProviderUrl(" ").build()))
                .isFalse();
    }

    @Test
    void genesisTimeTravelProviderLoadsAndValidatesProducerStake() throws Exception {
        String poolHash = fixturePoolHash();

        StakeDataProvider provider = StakeDataProviderFactory.createGenesisTimeTravelProvider(
                DEVNET_FIXTURE.resolve("shelley-genesis.json"),
                poolHash);

        assertThat(provider).isInstanceOf(GenesisStakeDataProvider.class);
        assertThat(provider.getPoolStake(poolHash, 0)).isPositive();
        assertThat(provider.getTotalStake(0)).isPositive();
    }

    @Test
    void genesisTimeTravelProviderRejectsPoolWithoutActiveStake() {
        String missingPool = "0".repeat(56);

        assertThatThrownBy(() -> StakeDataProviderFactory.createGenesisTimeTravelProvider(
                DEVNET_FIXTURE.resolve("shelley-genesis.json"),
                missingPool))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no active genesis stake");
    }

    private static String fixturePoolHash() throws Exception {
        BlockProducerKeys keys = BlockProducerKeys.load(
                DEVNET_FIXTURE.resolve("vrf.skey"),
                DEVNET_FIXTURE.resolve("kes.skey"),
                DEVNET_FIXTURE.resolve("opcert.cert"));
        return SlotLeaderBlockProducer.derivePoolHash(keys.getOpCert());
    }
}
