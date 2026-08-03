package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import org.junit.jupiter.api.Test;

import java.util.Map;

class EutxoZ6CircuitHardeningTest {

    @Test
    void aProductionManifestCannotDescribeSinglePartyDevelopmentSetup() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new EutxoCeremonyManifest(
                        "invalid-production",
                        EutxoCeremonyManifest.Kind.PRODUCTION,
                        "zeroj-single-development-setup",
                        1,
                        "11".repeat(32),
                        "22".repeat(32),
                        "circuit",
                        "33".repeat(32),
                        Map.of("pk.bin", "44".repeat(32))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multi-party");
    }

}
