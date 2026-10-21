package org.yanoproject.runtime.producer;

import com.bloxbean.cardano.client.crypto.BlockProducerKeys;
import org.yanoproject.api.account.AccountStateReadStore;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.runtime.blockproducer.FixedStakeDataProvider;
import org.yanoproject.runtime.blockproducer.GenesisStakeDataProvider;
import org.yanoproject.runtime.blockproducer.LedgerStakeDataProvider;
import org.yanoproject.runtime.blockproducer.SlotLeaderBlockProducer;
import org.yanoproject.runtime.blockproducer.StakeDataProvider;
import org.yanoproject.runtime.blockproducer.YaciStoreStakeDataProvider;
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
                        .build(), accountState());

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
    void liveSlotLeaderUsesFixedProviderInDevModeWhenUrlIsBlank() {
        StakeDataProvider provider = StakeDataProviderFactory.createLiveSlotLeaderProvider(
                YanoConfig.builder()
                        .devMode(true)
                        .stakeDataProviderUrl(" ")
                        .build(), accountState());

        assertThat(provider).isInstanceOf(FixedStakeDataProvider.class);
        assertThat(provider.getPoolStake("pool", 0))
                .isEqualTo(provider.getTotalStake(0));
        assertThat(StakeDataProviderFactory.hasStakeDataProviderUrl(
                YanoConfig.builder().stakeDataProviderUrl(" ").build()))
                .isFalse();
    }

    @Test
    void liveSlotLeaderDefaultsToAccountStateWithoutUrl() {
        StakeDataProvider provider = StakeDataProviderFactory.createLiveSlotLeaderProvider(
                YanoConfig.builder().build(), accountState());

        assertThat(provider).isInstanceOf(LedgerStakeDataProvider.class);
    }

    @Test
    void liveSlotLeaderWithoutAnyStakeSourceIsRejected() {
        assertThatThrownBy(() -> StakeDataProviderFactory.createLiveSlotLeaderProvider(
                YanoConfig.builder().build(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs a stake source");
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

    private static AccountStateReadStore accountState() {
        return new AccountStateReadStore() {
        };
    }

    private static String fixturePoolHash() throws Exception {
        BlockProducerKeys keys = BlockProducerKeys.load(
                DEVNET_FIXTURE.resolve("vrf.skey"),
                DEVNET_FIXTURE.resolve("kes.skey"),
                DEVNET_FIXTURE.resolve("opcert.cert"));
        return SlotLeaderBlockProducer.derivePoolHash(keys.getOpCert());
    }
}
