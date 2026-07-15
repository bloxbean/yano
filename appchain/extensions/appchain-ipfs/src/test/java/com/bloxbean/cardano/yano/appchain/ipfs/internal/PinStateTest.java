package com.bloxbean.cardano.yano.appchain.ipfs.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PinStateTest {
    @Test
    void requiresAnExplicitPinAndTreatsRecursiveAsStrongerThanDirect() {
        assertThat(PinState.ABSENT.satisfies(false)).isFalse();
        assertThat(PinState.ABSENT.satisfies(true)).isFalse();
        assertThat(PinState.INDIRECT.satisfies(false)).isFalse();
        assertThat(PinState.INDIRECT.satisfies(true)).isFalse();
        assertThat(PinState.DIRECT.satisfies(false)).isTrue();
        assertThat(PinState.DIRECT.satisfies(true)).isFalse();
        assertThat(PinState.RECURSIVE.satisfies(false)).isTrue();
        assertThat(PinState.RECURSIVE.satisfies(true)).isTrue();
    }
}
