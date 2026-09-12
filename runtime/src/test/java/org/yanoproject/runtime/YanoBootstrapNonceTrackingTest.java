package org.yanoproject.runtime;

import org.yanoproject.runtime.internal.RuntimeNode;

import org.yanoproject.api.config.YanoConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YanoBootstrapNonceTrackingTest {

    @Test
    void relayNonceTrackingIsDisabledInBootstrapMode() {
        assertThat(RuntimeNode.shouldInitializeRelayNonceTracking(false, true)).isFalse();
    }

    @Test
    void relayNonceTrackingIsEnabledForNormalRelayMode() {
        assertThat(RuntimeNode.shouldInitializeRelayNonceTracking(false, false)).isTrue();
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
        RuntimeNode yano = new RuntimeNode(config);

        yano.initRelayNonceTrackingIfRequired();

        assertThat(yano.getEpochNonceInfo()).isNull();
    }

    @Test
    void blockProducerModeDoesNotUseRelayNonceTrackingHelper() {
        assertThat(RuntimeNode.shouldInitializeRelayNonceTracking(true, false)).isFalse();
        assertThat(RuntimeNode.shouldInitializeRelayNonceTracking(true, true)).isFalse();
    }
}
