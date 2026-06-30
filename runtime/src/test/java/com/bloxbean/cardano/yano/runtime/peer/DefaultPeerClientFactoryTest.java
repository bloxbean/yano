package com.bloxbean.cardano.yano.runtime.peer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPeerClientFactoryTest {

    @Test
    void supervisedConfigDoesNotBindSourcePortByDefault() {
        var config = DefaultPeerClientFactory.supervisedNodeClientConfig();

        assertThat(config.hasLocalBindAddress()).isFalse();
        assertThat(config.getLocalBindPort()).isZero();
    }

    @Test
    void supervisedConfigCanBindSourcePortForRelayReuse() {
        var config = DefaultPeerClientFactory.supervisedNodeClientConfig("", 13338);

        assertThat(config.hasLocalBindAddress()).isTrue();
        assertThat(config.getLocalBindHost()).isEmpty();
        assertThat(config.getLocalBindPort()).isEqualTo(13338);
        assertThat(config.isLocalBindFallbackToEphemeral()).isTrue();
    }
}
