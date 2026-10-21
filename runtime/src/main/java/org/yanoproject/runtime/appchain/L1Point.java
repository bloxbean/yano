package org.yanoproject.runtime.appchain;

import org.yanoproject.api.CanonicalBlockReference;

import java.util.Arrays;
import java.util.Objects;

/**
 * One L1 block coordinate recorded by the delivery loop (app-layer ADR-038, D2). {@link #ORIGIN} stands before block
 * 0: it is always canonical, and rolling back to it removes all L1-derived state.
 */
record L1Point(long blockNumber, long slot, byte[] blockHash) {
    static final L1Point ORIGIN = new L1Point(-1, -1, new byte[0]);

    L1Point {
        Objects.requireNonNull(blockHash, "blockHash");
        if (blockNumber < -1 || slot < -1 || (blockNumber >= 0) != (blockHash.length > 0)) {
            throw new IllegalArgumentException("Invalid L1 point");
        }
        blockHash = blockHash.clone();
    }

    static L1Point of(CanonicalBlockReference reference) {
        return new L1Point(reference.blockNumber(), reference.slot(), reference.blockHash());
    }

    @Override
    public byte[] blockHash() {
        return blockHash.clone();
    }

    boolean isOrigin() {
        return blockNumber < 0;
    }

    /** Equal to the canonical reference for the same block number (ORIGIN has none). */
    boolean matches(CanonicalBlockReference reference) {
        return reference != null && reference.blockNumber() == blockNumber && reference.slot() == slot
                && Arrays.equals(reference.blockHash(), blockHash);
    }

    /** The engine-facing reference, or null for ORIGIN. */
    AppChainEngine.L1Ref toRef() {
        return isOrigin() ? null : new AppChainEngine.L1Ref(slot, blockHash.clone());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof L1Point point && point.blockNumber == blockNumber && point.slot == slot
                && Arrays.equals(point.blockHash, blockHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blockNumber, slot) * 31 + Arrays.hashCode(blockHash);
    }

    @Override
    public String toString() {
        return isOrigin() ? "ORIGIN" : "L1Point[#" + blockNumber + " slot " + slot + "]";
    }
}
