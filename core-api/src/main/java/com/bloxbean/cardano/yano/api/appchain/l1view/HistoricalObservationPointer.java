package com.bloxbean.cardano.yano.api.appchain.l1view;

import java.util.Objects;
import java.util.Optional;

/**
 * Operator-confirmed pointer for independently recomputing a missed historical
 * observation. Claim bytes are deliberately absent and cannot be supplied by
 * the caller.
 */
public record HistoricalObservationPointer(String chainId,
                                           String observerId,
                                           String l1Network,
                                           L1Observation.Anchor anchor,
                                           Optional<Long> slot,
                                           Optional<byte[]> blockHash,
                                           long eventOrdinal,
                                           boolean operatorConfirmed) {
    public HistoricalObservationPointer {
        if (chainId == null || chainId.isBlank()
                || observerId == null || observerId.isBlank()
                || l1Network == null || l1Network.isBlank()) {
            throw new IllegalArgumentException("Historical pointer identity must not be blank");
        }
        anchor = Objects.requireNonNull(anchor, "anchor");
        slot = Objects.requireNonNull(slot, "slot");
        blockHash = Objects.requireNonNull(blockHash, "blockHash")
                .map(value -> value.clone());
        if (eventOrdinal < 0 || slot.filter(value -> value < 0).isPresent()
                || blockHash.filter(value -> value.length != 32).isPresent()
                || slot.isPresent() != blockHash.isPresent()) {
            throw new IllegalArgumentException("Invalid historical observation pointer");
        }
    }

    @Override
    public Optional<byte[]> blockHash() {
        return blockHash.map(byte[]::clone);
    }
}
