package com.bloxbean.cardano.yano.p2p.peer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalBindAddressResolverTest {

    @Test
    void resolvesLoopbackRoute() {
        var localHost = LocalBindAddressResolver.resolveForRemote("127.0.0.1", 9);

        assertThat(localHost).contains("127.0.0.1");
    }

    @Test
    void rejectsInvalidTarget() {
        assertThat(LocalBindAddressResolver.resolveForRemote("", 9)).isEmpty();
        assertThat(LocalBindAddressResolver.resolveForRemote("127.0.0.1", 0)).isEmpty();
    }
}
