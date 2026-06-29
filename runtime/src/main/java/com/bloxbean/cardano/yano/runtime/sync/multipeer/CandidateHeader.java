package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import java.util.Objects;

/**
 * Header advertised by one upstream peer before canonical adoption.
 */
public record CandidateHeader(
        String peerId,
        long slot,
        long blockNumber,
        String blockHash,
        String previousHash,
        boolean trusted,
        long receivedAtMillis
) {
    public CandidateHeader {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(blockHash, "blockHash");
        if (peerId.isBlank()) {
            throw new IllegalArgumentException("peerId must not be blank");
        }
        if (blockHash.isBlank()) {
            throw new IllegalArgumentException("blockHash must not be blank");
        }
        if (slot < 0 || blockNumber < 0) {
            throw new IllegalArgumentException("slot and blockNumber must be non-negative");
        }
    }
}
