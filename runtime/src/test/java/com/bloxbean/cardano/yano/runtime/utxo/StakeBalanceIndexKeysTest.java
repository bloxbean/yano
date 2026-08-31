package com.bloxbean.cardano.yano.runtime.utxo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StakeBalanceIndexKeysTest {
    @Test
    void extractorVersionInvalidatesTheLegacyReadyMarker() {
        assertThat(StakeBalanceIndexKeys.isCurrent(new byte[]{1})).isFalse();
        assertThat(StakeBalanceIndexKeys.isCurrent(StakeBalanceIndexKeys.READY_VERSION)).isTrue();
    }
}
