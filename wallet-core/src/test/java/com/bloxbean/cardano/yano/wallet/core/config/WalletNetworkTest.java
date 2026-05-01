package com.bloxbean.cardano.yano.wallet.core.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletNetworkTest {
    @Test
    void resolvesSupportedNetworkProfilesById() {
        assertThat(WalletNetwork.fromId("devnet")).isEqualTo(WalletNetwork.DEVNET);
        assertThat(WalletNetwork.fromId("preview")).isEqualTo(WalletNetwork.PREVIEW);
        assertThat(WalletNetwork.fromId("preprod")).isEqualTo(WalletNetwork.PREPROD);
        assertThat(WalletNetwork.fromId("mainnet")).isEqualTo(WalletNetwork.MAINNET);
    }

    @Test
    void marksOnlyMainnetAsProduction() {
        assertThat(WalletNetwork.MAINNET.production()).isTrue();
        assertThat(WalletNetwork.DEVNET.production()).isFalse();
        assertThat(WalletNetwork.PREVIEW.production()).isFalse();
        assertThat(WalletNetwork.PREPROD.production()).isFalse();
    }

    @Test
    void rejectsUnsupportedNetworkProfile() {
        assertThatThrownBy(() -> WalletNetwork.fromId("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported wallet network");
    }
}
