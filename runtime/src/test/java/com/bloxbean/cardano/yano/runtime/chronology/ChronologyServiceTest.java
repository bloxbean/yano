package com.bloxbean.cardano.yano.runtime.chronology;

import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChronologyServiceTest {
    @Test
    void returnsEmptyWhenNotInitialized() {
        ChronologyService service = new ChronologyService(new InMemoryChainState());

        assertThat(service.slotToUnixTime(10)).isEmpty();
        assertThat(service.isSlotTimeAvailable()).isFalse();
    }

    @Test
    void initializesFromResolvedGenesisTimestampWhenProvided() {
        ChronologyService service = new ChronologyService(new InMemoryChainState());
        GenesisConfig genesis = GenesisConfig.fromInMemory(shelleyGenesis(), null, null);

        assertThat(service.initialize(genesis, 1_700_000_000_000L)).isTrue();

        assertThat(service.isSlotTimeAvailable()).isTrue();
        assertThat(service.slotToUnixTime(5)).hasValue(1_700_000_100L);
    }

    private static ShelleyGenesisData shelleyGenesis() {
        return new ShelleyGenesisData(
                Map.of(),
                42,
                100,
                1.0,
                "2020-01-01T00:00:00Z",
                45_000_000_000_000_000L,
                1.0,
                10,
                62,
                100,
                5,
                8,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                1,
                0,
                0,
                0,
                BigDecimal.ZERO,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                0,
                null);
    }
}
