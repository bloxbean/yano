package com.bloxbean.cardano.yano.api.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardanoHexTest {

    @Test
    void validatesEvenLengthHexOnly() {
        assertTrue(CardanoHex.isHex("00aA"));
        assertFalse(CardanoHex.isHex("00xz"));
        assertFalse(CardanoHex.isHex("abc"));
        assertFalse(CardanoHex.isHex(null));
    }

    @Test
    void validatesCommonCardanoHashLengths() {
        assertTrue(CardanoHex.isHash28Bytes("11".repeat(28)));
        assertFalse(CardanoHex.isHash28Bytes("11".repeat(32)));

        assertTrue(CardanoHex.isTxHash("22".repeat(32)));
        assertFalse(CardanoHex.isTxHash("22".repeat(28)));
    }
}
