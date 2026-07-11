package com.bloxbean.cardano.yano.consensus.selection;

import java.util.Objects;

/**
 * Read-only canonical chain point used for candidate intersection checks.
 */
public record CanonicalHeaderPoint(long slot, long blockNumber, String blockHash) {
    public CanonicalHeaderPoint {
        Objects.requireNonNull(blockHash, "blockHash");
        if (blockHash.isBlank()) {
            throw new IllegalArgumentException("blockHash must not be blank");
        }
        if (slot < 0 || blockNumber < 0) {
            throw new IllegalArgumentException("slot and blockNumber must be non-negative");
        }
    }
}
