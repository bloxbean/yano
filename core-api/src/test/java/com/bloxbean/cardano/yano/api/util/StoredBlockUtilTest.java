package com.bloxbean.cardano.yano.api.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredBlockUtilTest {
    @Test
    void classifiesByronEnvelopeDiscriminatorsWithoutInvokingBodyDecoders() {
        assertThat(StoredBlockUtil.requireByronEnvelopeKind(envelope(0)))
                .isEqualTo(StoredBlockUtil.ByronEnvelopeKind.EBB);
        assertThat(StoredBlockUtil.requireByronEnvelopeKind(envelope(1)))
                .isEqualTo(StoredBlockUtil.ByronEnvelopeKind.MAIN);
    }

    @Test
    void rejectsUnknownOrMalformedEnvelopeDiscriminators() {
        assertThatThrownBy(() -> StoredBlockUtil.requireByronEnvelopeKind(envelope(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported stored Byron envelope tag: 2");
        assertThatThrownBy(() -> StoredBlockUtil.requireByronEnvelopeKind(new byte[]{(byte) 0x80}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty CBOR array");
    }

    private static byte[] envelope(int discriminator) {
        return new byte[]{(byte) 0x82, (byte) discriminator, (byte) 0x80};
    }
}
