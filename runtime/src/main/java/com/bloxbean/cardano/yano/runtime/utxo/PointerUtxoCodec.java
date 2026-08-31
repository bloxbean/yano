package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yano.api.utxo.PointerAddressId;
import com.bloxbean.cardano.yano.api.utxo.PointerUtxo;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Compact fixed-field codec for the pointer UTXO index. */
final class PointerUtxoCodec {
    private static final byte VERSION = 1;
    private static final byte FLAG_RESOLVABLE = 1;
    private static final int FIXED_BYTES = 2 + Long.BYTES * 2 + Integer.BYTES * 2 + 1;

    private PointerUtxoCodec() {
    }

    static byte[] encode(PointerUtxo pointerUtxo) {
        byte[] lovelace = unsignedBytes(pointerUtxo.lovelace());
        if (lovelace.length > 255) {
            throw new IllegalArgumentException("Pointer UTXO lovelace is too large");
        }
        PointerAddressId pointer = pointerUtxo.pointer();
        boolean resolvable = pointer != null;
        return ByteBuffer.allocate(FIXED_BYTES + lovelace.length)
                .order(ByteOrder.BIG_ENDIAN)
                .put(VERSION)
                .put(resolvable ? FLAG_RESOLVABLE : (byte) 0)
                .putLong(pointerUtxo.creationSlot())
                .putLong(resolvable ? pointer.slot() : 0)
                .putInt(resolvable ? pointer.transactionIndex() : 0)
                .putInt(resolvable ? pointer.certificateIndex() : 0)
                .put((byte) lovelace.length)
                .put(lovelace)
                .array();
    }

    static PointerUtxo decode(byte[] value) {
        if (value == null || value.length < FIXED_BYTES) {
            throw new IllegalArgumentException("Malformed pointer UTXO value");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN);
        byte version = buffer.get();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported pointer UTXO value version: " + version);
        }
        byte flags = buffer.get();
        if ((flags & ~FLAG_RESOLVABLE) != 0) {
            throw new IllegalArgumentException("Unsupported pointer UTXO flags: " + flags);
        }
        long creationSlot = buffer.getLong();
        long pointerSlot = buffer.getLong();
        int transactionIndex = buffer.getInt();
        int certificateIndex = buffer.getInt();
        int lovelaceLength = buffer.get() & 0xFF;
        if (lovelaceLength != buffer.remaining()) {
            throw new IllegalArgumentException("Malformed pointer UTXO lovelace length");
        }
        byte[] lovelace = new byte[lovelaceLength];
        buffer.get(lovelace);
        return new PointerUtxo(
                creationSlot,
                lovelace.length == 0 ? BigInteger.ZERO : new BigInteger(1, lovelace),
                (flags & FLAG_RESOLVABLE) != 0
                        ? new PointerAddressId(pointerSlot, transactionIndex, certificateIndex)
                        : null);
    }

    private static byte[] unsignedBytes(BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Pointer UTXO lovelace must be non-negative");
        }
        if (value.signum() == 0) return new byte[0];
        byte[] signed = value.toByteArray();
        if (signed[0] != 0) return signed;
        byte[] unsigned = new byte[signed.length - 1];
        System.arraycopy(signed, 1, unsigned, 0, unsigned.length);
        return unsigned;
    }
}
