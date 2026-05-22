package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable persistence envelope for {@link EpochNonceState}.
 * <p>
 * {@link EpochNonceState#serialize()} intentionally contains only the nonce
 * variables. This envelope adds the ChainState body cursor that those variables
 * represent, so startup and rollback repair can prove whether the nonce state is
 * aligned with the durable body tip.
 */
public record NonceStateSnapshot(
        long slot,
        long blockNumber,
        byte[] blockHash,
        byte[] nonceState) {

    private static final int MAGIC = 0x594e5350; // "YNSP"
    private static final byte VERSION = 1;

    public NonceStateSnapshot {
        Objects.requireNonNull(nonceState, "nonceState must not be null");
        blockHash = blockHash != null ? blockHash.clone() : null;
        boolean origin = slot < 0 && blockNumber < 0 && blockHash == null;
        if (!origin && (slot < 0 || blockNumber < 0 || blockHash == null || blockHash.length == 0)) {
            throw new IllegalArgumentException(
                    "Nonce snapshot cursor must be origin or a complete body-tip cursor");
        }
        nonceState = nonceState.clone();
    }

    public static NonceStateSnapshot of(long slot, long blockNumber, String blockHashHex, byte[] nonceState) {
        byte[] hash = blockHashHex != null && !blockHashHex.isBlank()
                ? HexUtil.decodeHexString(blockHashHex)
                : null;
        return new NonceStateSnapshot(slot, blockNumber, hash, nonceState);
    }

    public static NonceStateSnapshot origin(byte[] nonceState) {
        return new NonceStateSnapshot(-1L, -1L, null, nonceState);
    }

    @Override
    public byte[] blockHash() {
        return blockHash != null ? blockHash.clone() : null;
    }

    @Override
    public byte[] nonceState() {
        return nonceState.clone();
    }

    public String blockHashHex() {
        return blockHash != null ? HexUtil.encodeHexString(blockHash) : null;
    }

    public boolean isOrigin() {
        return slot < 0 && blockNumber < 0 && blockHash == null;
    }

    public byte[] serialize() {
        int hashLength = blockHash != null ? blockHash.length : 0;
        int capacity = Integer.BYTES + 1 + Long.BYTES + Long.BYTES
                + Integer.BYTES + hashLength
                + Integer.BYTES + nonceState.length;
        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        buffer.putInt(MAGIC);
        buffer.put(VERSION);
        buffer.putLong(slot);
        buffer.putLong(blockNumber);
        buffer.putInt(hashLength);
        if (hashLength > 0) {
            buffer.put(blockHash);
        }
        buffer.putInt(nonceState.length);
        buffer.put(nonceState);
        return buffer.array();
    }

    public static Optional<NonceStateSnapshot> tryDeserialize(byte[] bytes) {
        if (bytes == null || bytes.length < Integer.BYTES + 1) {
            return Optional.empty();
        }
        ByteBuffer probe = ByteBuffer.wrap(bytes);
        int magic = probe.getInt();
        if (magic != MAGIC) {
            return Optional.empty();
        }
        return Optional.of(deserialize(bytes));
    }

    public static NonceStateSnapshot deserialize(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("Nonce snapshot bytes are null");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("Unsupported nonce snapshot magic: 0x"
                    + Integer.toHexString(magic));
        }
        byte version = buffer.get();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported nonce snapshot version: " + version);
        }

        long slot = buffer.getLong();
        long blockNumber = buffer.getLong();
        int hashLength = buffer.getInt();
        if (hashLength < 0 || hashLength > buffer.remaining()) {
            throw new IllegalArgumentException("Malformed nonce snapshot block hash length: " + hashLength);
        }
        byte[] hash = null;
        if (hashLength > 0) {
            hash = new byte[hashLength];
            buffer.get(hash);
        }

        if (buffer.remaining() < Integer.BYTES) {
            throw new IllegalArgumentException("Malformed nonce snapshot: missing nonce state length");
        }
        int stateLength = buffer.getInt();
        if (stateLength <= 0 || stateLength > buffer.remaining()) {
            throw new IllegalArgumentException("Malformed nonce snapshot state length: " + stateLength);
        }
        byte[] state = new byte[stateLength];
        buffer.get(state);
        if (buffer.hasRemaining()) {
            throw new IllegalArgumentException("Malformed nonce snapshot: trailing bytes=" + buffer.remaining());
        }

        return new NonceStateSnapshot(slot, blockNumber, hash, state);
    }

    public boolean sameCursor(long otherSlot, long otherBlockNumber, byte[] otherHash) {
        return slot == otherSlot
                && blockNumber == otherBlockNumber
                && Arrays.equals(blockHash, otherHash);
    }
}
