package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Versioned completeness marker for the pointer UTXO index. */
record PointerIndexMarker(int version, long blockNumber, long slot, byte[] blockHash) {
    static final int CURRENT_VERSION = 1;
    static final byte[] KEY = "meta.utxo_pointer.ready.v1".getBytes(StandardCharsets.UTF_8);
    private static final int ENCODED_BYTES = 1 + Long.BYTES + Long.BYTES + 32;

    PointerIndexMarker {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported pointer index version: " + version);
        }
        if (blockNumber < 0 || slot < 0) {
            throw new IllegalArgumentException("Pointer index marker coordinate must be non-negative");
        }
        if (blockHash == null || blockHash.length != 32) {
            throw new IllegalArgumentException("Pointer index marker hash must be 32 bytes");
        }
        blockHash = Arrays.copyOf(blockHash, blockHash.length);
    }

    static PointerIndexMarker at(CanonicalBlockReference coordinate) {
        return new PointerIndexMarker(CURRENT_VERSION, coordinate.blockNumber(),
                coordinate.slot(), coordinate.blockHash());
    }

    static byte[] encode(PointerIndexMarker marker) {
        return ByteBuffer.allocate(ENCODED_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .put((byte) marker.version)
                .putLong(marker.blockNumber)
                .putLong(marker.slot)
                .put(marker.blockHash)
                .array();
    }

    static PointerIndexMarker decode(byte[] value) {
        if (value == null || value.length != ENCODED_BYTES) return null;
        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN);
        int version = buffer.get() & 0xFF;
        if (version != CURRENT_VERSION) return null;
        long blockNumber = buffer.getLong();
        long slot = buffer.getLong();
        byte[] hash = new byte[32];
        buffer.get(hash);
        try {
            return new PointerIndexMarker(version, blockNumber, slot, hash);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    boolean isUsableAt(CanonicalBlockReference boundary) {
        if (blockNumber > boundary.blockNumber() || slot > boundary.slot()) return false;
        if (blockNumber == boundary.blockNumber()) {
            return slot == boundary.slot() && Arrays.equals(blockHash, boundary.blockHash());
        }
        return true;
    }

    boolean isAfter(CanonicalBlockReference coordinate) {
        if (blockNumber != coordinate.blockNumber()) return blockNumber > coordinate.blockNumber();
        if (slot != coordinate.slot()) return slot > coordinate.slot();
        return !Arrays.equals(blockHash, coordinate.blockHash());
    }

    @Override
    public byte[] blockHash() {
        return Arrays.copyOf(blockHash, blockHash.length);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PointerIndexMarker marker
                && version == marker.version
                && blockNumber == marker.blockNumber
                && slot == marker.slot
                && Arrays.equals(blockHash, marker.blockHash);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(version);
        result = 31 * result + Long.hashCode(blockNumber);
        result = 31 * result + Long.hashCode(slot);
        result = 31 * result + Arrays.hashCode(blockHash);
        return result;
    }
}
