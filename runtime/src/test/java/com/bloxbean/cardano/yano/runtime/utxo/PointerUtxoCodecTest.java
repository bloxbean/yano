package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.utxo.PointerAddressId;
import com.bloxbean.cardano.yano.api.utxo.PointerUtxo;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointerUtxoCodecTest {
    @Test
    void pointerUtxoRoundTripsCompactValue() {
        PointerUtxo expected = new PointerUtxo(
                42, new BigInteger("45000000000000000"),
                new PointerAddressId(41, 7, 3));

        byte[] encoded = PointerUtxoCodec.encode(expected);

        assertEquals(expected, PointerUtxoCodec.decode(encoded));
        assertTrue(encoded.length < 40);
    }

    @Test
    void malformedPointerValueFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> PointerUtxoCodec.decode(new byte[]{1, 2, 3}));
    }

    @Test
    void unresolvablePointerRowRoundTripsExplicitFlag() {
        PointerUtxo expected = new PointerUtxo(42, BigInteger.TEN, null);

        PointerUtxo decoded = PointerUtxoCodec.decode(PointerUtxoCodec.encode(expected));

        assertEquals(expected, decoded);
        assertFalse(decoded.resolvable());
    }

    @Test
    void markerIsVersionedAndCoordinateBound() {
        CanonicalBlockReference coordinate = new CanonicalBlockReference(
                100, 1_000, bytes(1));
        PointerIndexMarker marker = PointerIndexMarker.at(coordinate);

        PointerIndexMarker decoded = PointerIndexMarker.decode(
                PointerIndexMarker.encode(marker));

        assertEquals(marker, decoded);
        assertTrue(decoded.isUsableAt(coordinate));
        assertTrue(decoded.isUsableAt(new CanonicalBlockReference(101, 1_001, bytes(2))));
        assertFalse(decoded.isUsableAt(new CanonicalBlockReference(99, 999, bytes(3))));
        assertFalse(decoded.isUsableAt(new CanonicalBlockReference(100, 1_000, bytes(4))));
        assertNull(PointerIndexMarker.decode(new byte[]{2}));
    }

    private static byte[] bytes(int value) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) value;
        return bytes;
    }
}
