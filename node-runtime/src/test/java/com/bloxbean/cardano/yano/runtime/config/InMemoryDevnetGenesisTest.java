package com.bloxbean.cardano.yano.runtime.config;

import com.bloxbean.cardano.yano.api.config.YaciNodeConfig;
import com.bloxbean.cardano.yano.runtime.YaciNode;
import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yano.runtime.genesis.ByronGenesisData;
import com.bloxbean.cardano.yano.runtime.genesis.ConwayGenesisData;
import com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class InMemoryDevnetGenesisTest {

    private static ShelleyGenesisData testShelley() {
        return new ShelleyGenesisData(
                Map.of("addr1", BigInteger.valueOf(1000_000_000)),
                42, 600, 1.0, "2026-01-01T00:00:00Z",
                45_000_000_000_000_000L, 1.0, 100,
                62, 129600, 5, 10, 0,
                new BigDecimal("0.003"), new BigDecimal("0.2"), BigDecimal.ZERO,
                100, 0, 2_000_000, 500_000_000, BigDecimal.ZERO,
                44, 155381, 65536, 16384, 1100, 18, null, 1_000_000);
    }

    private static ByronGenesisData testByron() {
        return new ByronGenesisData(
                Map.of("byronAddr1", BigInteger.valueOf(500_000_000)),
                Collections.emptyMap(), 0, 42, 1, 100);
    }

    private static ConwayGenesisData testConway() {
        return new ConwayGenesisData(30, BigInteger.valueOf(100_000_000_000L),
                BigInteger.valueOf(500_000_000), 20, 0, 365, null, null, null, null, null);
    }

    @Test
    void record_requiresShelley() {
        assertThatThrownBy(() -> new InMemoryDevnetGenesis(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void genesisConfig_fromInMemory_matchesFields() {
        var shelley = testShelley();
        var byron = testByron();
        var gc = GenesisConfig.fromInMemory(shelley, byron, "{\"test\":true}");

        assertThat(gc.hasInitialFunds()).isTrue();
        assertThat(gc.getInitialFunds()).containsKey("addr1");
        assertThat(gc.hasByronBalances()).isTrue();
        assertThat(gc.getByronBalances()).containsKey("byronAddr1");
        assertThat(gc.getShelleyGenesisData()).isSameAs(shelley);
        assertThat(gc.getByronGenesisData()).isSameAs(byron);
        assertThat(gc.hasProtocolParameters()).isTrue();
    }

    @Test
    void genesisConfig_fromInMemory_nullByron() {
        var gc = GenesisConfig.fromInMemory(testShelley(), null, null);

        assertThat(gc.hasInitialFunds()).isTrue();
        assertThat(gc.hasByronBalances()).isFalse();
        assertThat(gc.getByronGenesisData()).isNull();
        assertThat(gc.hasProtocolParameters()).isFalse();
    }

    @Test
    void networkGenesisConfig_fromInMemory_matchesFields() {
        var shelley = testShelley();
        var conway = testConway();
        var ngc = NetworkGenesisConfig.fromInMemory(shelley, null, conway);

        assertThat(ngc.getNetworkMagic()).isEqualTo(42);
        assertThat(ngc.getEpochLength()).isEqualTo(600);
        assertThat(ngc.getSecurityParam()).isEqualTo(100);
        assertThat(ngc.getActiveSlotsCoeff()).isEqualTo(1.0);
        assertThat(ngc.getMaxLovelaceSupply()).isEqualTo(45_000_000_000_000_000L);
        assertThat(ngc.hasByronGenesis()).isFalse();
        assertThat(ngc.hasConwayGenesis()).isTrue();
        assertThat(ngc.getConwayGenesisData().govActionLifetime()).isEqualTo(30);
    }

    @Test
    void networkGenesisConfig_fromInMemory_requiresShelley() {
        assertThatThrownBy(() -> NetworkGenesisConfig.fromInMemory(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void networkGenesisConfig_fromInMemory_magicMismatch_throws() {
        var shelley = testShelley(); // magic=42
        var byron = new ByronGenesisData(
                Collections.emptyMap(), Collections.emptyMap(), 0, 99, 1, 100); // magic=99
        assertThatThrownBy(() -> NetworkGenesisConfig.fromInMemory(shelley, byron, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Protocol magic mismatch");
    }

    @Test
    void epochParamProvider_fromInMemoryGenesis() {
        var ngc = NetworkGenesisConfig.fromInMemory(testShelley(), null, testConway());
        var provider = DefaultEpochParamProvider.fromNetworkGenesisConfig(ngc, 0);

        assertThat(provider.getEpochLength()).isEqualTo(600);
        assertThat(provider.getSecurityParam()).isEqualTo(100);
        assertThat(provider.getGovActionLifetime(0)).isEqualTo(30);
        assertThat(provider.getDRepActivity(0)).isEqualTo(20);

        // EpochSlotCalc should work
        var calc = provider.getEpochSlotCalc();
        assertThat(calc.slotToEpoch(1200)).isEqualTo(2);
    }

    // --- YaciNode constructor tests ---

    @Test
    void yaciNode_constructor_rejectsNonDevnetConfig() {
        // preprodDefault has devMode=false, enableBlockProducer=false
        var config = YaciNodeConfig.preprodDefault();
        var genesis = new InMemoryDevnetGenesis(testShelley(), null, null, null);

        assertThatThrownBy(() -> new YaciNode(config, null, genesis))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("devMode=true");
    }

    @Test
    void yaciNode_constructor_acceptsDevnetConfig() {
        var config = YaciNodeConfig.devnetDefault(0); // port 0 = OS assigns random available port
        var genesis = new InMemoryDevnetGenesis(testShelley(), null, testConway(), null);

        // Should not throw — devMode=true and enableBlockProducer=true
        // Constructor initializes account-state/epoch-params from in-memory genesis
        assertThatCode(() -> new YaciNode(config, null, genesis))
                .doesNotThrowAnyException();
    }

    @Test
    void yaciNode_constructor_nullGenesis_acceptedForDevnet() {
        var config = YaciNodeConfig.devnetDefault(0); // port 0 = OS assigns random available port

        // null in-memory genesis is fine — uses file-based path
        assertThatCode(() -> new YaciNode(config, null, null))
                .doesNotThrowAnyException();
    }
}
