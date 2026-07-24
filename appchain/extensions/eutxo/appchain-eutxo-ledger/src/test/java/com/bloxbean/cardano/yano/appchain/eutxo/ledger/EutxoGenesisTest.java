package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoGenesisTest {
    private static final String MNEMONIC =
            "wrist approve ethics forest knife treat noise great three simple "
                    + "prize happy toe dynamic number hunt trigger install "
                    + "wrong change decorate vendor glow erosion";

    @Test
    void registersAnL2KeyWithoutCreatingVirtualFunds() {
        String address = new Account(
                Networks.testnet(), MNEMONIC).enterpriseAddress();
        Map<String, String> settings = new HashMap<>();
        settings.put(
                "machines.eutxo.genesis.l2-address", address);
        settings.put(
                "machines.eutxo.genesis.l2-public-key",
                "11".repeat(32));
        settings.put(
                "machines.eutxo.genesis.l2-key-epoch", "1");
        settings.put(
                "machines.eutxo.validity.authorization-profile",
                "zeroj-jubjub-dev-v1");

        EutxoGenesis genesis = EutxoGenesis.from(settings);

        assertThat(genesis.records()).isEmpty();
        assertThat(genesis.l2KeyRegistrations())
                .singleElement()
                .satisfies(registration -> {
                    assertThat(registration.publicKey())
                            .isEqualTo(filled(0x11, 32));
                    assertThat(registration.authorizationProfile())
                            .isEqualTo("zeroj-jubjub-dev-v1");
                });
    }

    @Test
    void fundlessRegistrationRequiresAnAddress() {
        assertThatThrownBy(() -> EutxoGenesis.from(Map.of(
                "machines.eutxo.genesis.l2-public-key",
                "11".repeat(32),
                "machines.eutxo.validity.authorization-profile",
                "zeroj-jubjub-dev-v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("l2-address");
    }

    private static byte[] filled(int value, int length) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
