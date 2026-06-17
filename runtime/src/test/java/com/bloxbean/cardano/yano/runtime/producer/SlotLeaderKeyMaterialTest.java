package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.client.crypto.BlockProducerKeys;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderBlockProducer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotLeaderKeyMaterialTest {
    private static final Path DEVNET_FIXTURE = Path.of("src/test/resources/devnet");

    @Test
    void loadsKeysAndDerivesPoolHashFromPaths() throws Exception {
        SlotLeaderKeyMaterial material = SlotLeaderKeyMaterial.load(
                DEVNET_FIXTURE.resolve("vrf.skey"),
                DEVNET_FIXTURE.resolve("kes.skey"),
                DEVNET_FIXTURE.resolve("opcert.cert"));

        assertThat(material.keys()).isNotNull();
        assertThat(material.poolHash()).hasSize(56);
        assertThat(material.poolHash()).isEqualTo(
                SlotLeaderBlockProducer.derivePoolHash(material.keys().getOpCert()));
    }

    @Test
    void loadsKeysAndDerivesPoolHashFromConfig() throws Exception {
        YanoConfig config = YanoConfig.builder()
                .vrfSkeyFile(DEVNET_FIXTURE.resolve("vrf.skey").toString())
                .kesSkeyFile(DEVNET_FIXTURE.resolve("kes.skey").toString())
                .opCertFile(DEVNET_FIXTURE.resolve("opcert.cert").toString())
                .build();
        BlockProducerKeys expectedKeys = BlockProducerKeys.load(
                DEVNET_FIXTURE.resolve("vrf.skey"),
                DEVNET_FIXTURE.resolve("kes.skey"),
                DEVNET_FIXTURE.resolve("opcert.cert"));

        SlotLeaderKeyMaterial material = SlotLeaderKeyMaterial.load(config);

        assertThat(material.poolHash()).isEqualTo(
                SlotLeaderBlockProducer.derivePoolHash(expectedKeys.getOpCert()));
    }

    @Test
    void missingConfigPathFailsBeforeKeyLoading() {
        YanoConfig config = YanoConfig.builder()
                .kesSkeyFile(DEVNET_FIXTURE.resolve("kes.skey").toString())
                .opCertFile(DEVNET_FIXTURE.resolve("opcert.cert").toString())
                .build();

        assertThatThrownBy(() -> SlotLeaderKeyMaterial.load(config))
                .isInstanceOf(NullPointerException.class);
    }
}
