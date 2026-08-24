package com.bloxbean.cardano.yano.archive.api;

import java.util.Arrays;
import java.util.HexFormat;

/** Canonical anchors rechecked immediately before commit. */
public record ArchiveRangeAnchor(long startSlot, byte[] startHash, long endSlot, byte[] endHash) {
    public ArchiveRangeAnchor {
        if (startSlot < 0 || endSlot < startSlot || startHash == null || startHash.length == 0
                || endHash == null || endHash.length == 0) {
            throw new IllegalArgumentException("invalid archive range anchor");
        }
        startHash = Arrays.copyOf(startHash, startHash.length);
        endHash = Arrays.copyOf(endHash, endHash.length);
    }

    @Override public byte[] startHash() { return Arrays.copyOf(startHash, startHash.length); }
    @Override public byte[] endHash() { return Arrays.copyOf(endHash, endHash.length); }

    public String canonicalForm() {
        return startSlot + ":" + HexFormat.of().formatHex(startHash) + ':'
                + endSlot + ":" + HexFormat.of().formatHex(endHash);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ArchiveRangeAnchor anchor
                && startSlot == anchor.startSlot && endSlot == anchor.endSlot
                && Arrays.equals(startHash, anchor.startHash) && Arrays.equals(endHash, anchor.endHash);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(startSlot);
        result = 31 * result + Arrays.hashCode(startHash);
        result = 31 * result + Long.hashCode(endSlot);
        return 31 * result + Arrays.hashCode(endHash);
    }
}
