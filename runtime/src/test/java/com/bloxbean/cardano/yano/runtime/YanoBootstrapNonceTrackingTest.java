package com.bloxbean.cardano.yano.runtime;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YanoBootstrapNonceTrackingTest {

    @Test
    void relayNonceTrackingIsDisabledInBootstrapMode() {
        assertThat(Yano.shouldInitializeRelayNonceTracking(false, true)).isFalse();
    }

    @Test
    void relayNonceTrackingIsEnabledForNormalRelayMode() {
        assertThat(Yano.shouldInitializeRelayNonceTracking(false, false)).isTrue();
    }

    @Test
    void relayNonceTrackingIsNotInitializedByRelayHelperInBootstrapMode() {
        YanoConfig config = YanoConfig.builder()
                .enableBootstrap(true)
                .enableBlockProducer(false)
                .enableClient(false)
                .enableServer(false)
                .useRocksDB(false)
                .build();
        Yano yano = new Yano(config);

        yano.initRelayNonceTrackingIfRequired();

        assertThat(yano.getEpochNonceInfo()).isNull();
    }

    @Test
    void blockProducerModeDoesNotUseRelayNonceTrackingHelper() {
        assertThat(Yano.shouldInitializeRelayNonceTracking(true, false)).isFalse();
        assertThat(Yano.shouldInitializeRelayNonceTracking(true, true)).isFalse();
    }
}
