package com.bloxbean.cardano.yano.api.events;

import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.events.api.Event;

import java.util.Objects;

/**
 * Canonical application of a transaction-bearing Byron main block.
 * Epoch-boundary blocks deliberately have no equivalent UTXO event.
 */
public record ByronMainBlockAppliedEvent(
        long slot,
        long blockNumber,
        String blockHash,
        ByronMainBlock block
) implements Event {
    public ByronMainBlockAppliedEvent {
        Objects.requireNonNull(blockHash, "blockHash");
        Objects.requireNonNull(block, "block");
    }
}
