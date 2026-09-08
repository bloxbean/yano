package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsensusCodecStrictTest {

    @Test
    void certificateNoticeRoundTripRequiresBoundedCanonicalWrapper() {
        byte[] hash = fill(32, 0x33);
        byte[] certificate = new byte[]{(byte) 0x80};
        byte[] encoded = ConsensusCodec.encodeCertNotice(9, hash, certificate);

        ConsensusCodec.CertNotice notice = ConsensusCodec.decodeCertNotice(encoded);

        assertThat(notice.height()).isEqualTo(9);
        assertThat(notice.blockHash()).isEqualTo(hash);
        assertThat(notice.certBytes()).isEqualTo(certificate);
        assertThatThrownBy(() -> ConsensusCodec.decodeCertNotice(
                ConsensusCodec.encodeCertNotice(9, new byte[31], certificate)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsensusCodec.decodeCertNotice(
                ConsensusCodec.encodeCertNotice(9, hash, new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsensusCodec.decodeCertNotice(
                ConsensusCodec.encodeCertNotice(9, hash,
                        new byte[AppChainConfig.MAX_FINALITY_CERT_HEADROOM_BYTES + 1])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsensusCodec.decodeCertNotice(
                withNonMinimalFirstUnsigned(encoded)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hostileNestingIsRejectedBeforeRecursiveDecode() {
        byte[] nested = new byte[66];
        Arrays.fill(nested, 0, 33, (byte) 0x9f);
        Arrays.fill(nested, 33, 66, (byte) 0xff);

        assertThatThrownBy(() -> ConsensusCodec.decodeCertNotice(nested))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] fill(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    /** Re-encodes the array's small first unsigned item using non-minimal uint8. */
    private static byte[] withNonMinimalFirstUnsigned(byte[] canonical) {
        assertThat(canonical[0]).isEqualTo((byte) 0x83);
        assertThat(canonical[1] & 0xff).isLessThan(24);
        byte[] nonCanonical = new byte[canonical.length + 1];
        nonCanonical[0] = canonical[0];
        nonCanonical[1] = 0x18;
        nonCanonical[2] = canonical[1];
        System.arraycopy(canonical, 2, nonCanonical, 3, canonical.length - 2);
        return nonCanonical;
    }
}
