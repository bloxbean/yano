package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yano.runtime.blockproducer.EpochNonceState;
import com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolVersionSupplier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotLeaderSigningComponentsTest {
    private static final Path DEVNET_FIXTURE = Path.of("src/test/resources/devnet");

    @Test
    void createsSignedBuilderAndSlotLeaderCheckWithSharedSigner() throws Exception {
        SlotLeaderKeyMaterial keyMaterial = SlotLeaderKeyMaterial.load(
                DEVNET_FIXTURE.resolve("vrf.skey"),
                DEVNET_FIXTURE.resolve("kes.skey"),
                DEVNET_FIXTURE.resolve("opcert.cert"));
        EpochNonceState nonceState = new EpochNonceState(
                1200,
                100,
                1.0,
                Constants.BYRON_SLOTS_PER_EPOCH);

        SlotLeaderSigningComponents components = SlotLeaderSigningComponents.create(
                keyMaterial,
                129600,
                60,
                nonceState,
                null,
                ProtocolVersionSupplier.fixed(11, 0),
                1.0);

        assertThat(components.signedBlockBuilder()).isNotNull();
        assertThat(components.slotLeaderCheck()).isNotNull();
        assertThat(components.signedBlockBuilder().getIssuerPoolHashHex()).isEqualTo(keyMaterial.poolHash());
        assertThat(components.slotLeaderCheck().checkAndProve(0, new byte[32], BigDecimal.ZERO)).isNull();
    }

    @Test
    void requiresProtocolVersionSupplier() throws Exception {
        SlotLeaderKeyMaterial keyMaterial = SlotLeaderKeyMaterial.load(
                DEVNET_FIXTURE.resolve("vrf.skey"),
                DEVNET_FIXTURE.resolve("kes.skey"),
                DEVNET_FIXTURE.resolve("opcert.cert"));
        EpochNonceState nonceState = new EpochNonceState(
                1200,
                100,
                1.0,
                Constants.BYRON_SLOTS_PER_EPOCH);

        assertThatThrownBy(() -> SlotLeaderSigningComponents.create(
                keyMaterial,
                129600,
                60,
                nonceState,
                null,
                null,
                1.0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("protocolVersionSupplier");
    }
}
