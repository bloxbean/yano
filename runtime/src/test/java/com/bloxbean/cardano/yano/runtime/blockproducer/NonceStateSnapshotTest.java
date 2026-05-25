package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NonceStateSnapshotTest {

    @Test
    void roundTripsCursorAndNonceState() {
        byte[] state = bytes(24, 3);
        byte[] hash = bytes(32, 9);
        NonceStateSnapshot snapshot = new NonceStateSnapshot(100L, 7L, hash, state);

        NonceStateSnapshot restored = NonceStateSnapshot.deserialize(snapshot.serialize());

        assertThat(restored.slot()).isEqualTo(100L);
        assertThat(restored.blockNumber()).isEqualTo(7L);
        assertThat(restored.blockHash()).isEqualTo(hash);
        assertThat(restored.blockHashHex()).isEqualTo(HexUtil.encodeHexString(hash));
        assertThat(restored.nonceState()).isEqualTo(state);
    }

    @Test
    void legacyRawNonceBytesAreNotTreatedAsSnapshot() {
        assertThat(NonceStateSnapshot.tryDeserialize(new byte[]{1, 2, 3, 4, 5})).isEmpty();
    }

    @Test
    void malformedSnapshotWithMagicFailsClosed() {
        byte[] malformed = new byte[]{'Y', 'N', 'S', 'P', 1, 0};

        assertThatThrownBy(() -> NonceStateSnapshot.deserialize(malformed))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void partialCursorIsRejected() {
        byte[] state = bytes(24, 3);

        assertThat(NonceStateSnapshot.origin(state).isOrigin()).isTrue();
        assertThatThrownBy(() -> new NonceStateSnapshot(10L, -1L, bytes(32, 1), state))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NonceStateSnapshot(10L, 1L, null, state))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }
}
