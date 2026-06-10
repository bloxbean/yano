package com.bloxbean.cardano.yano.api.events;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yano.api.genesis.GenesisBootstrapData;

/**
 * Published when the runtime needs listeners to initialize genesis-derived
 * state for the local chain. This normally happens after the first block of a
 * fresh chain; startup may publish it again only if direct-start bootstrap state
 * is missing.
 * <p>
 * This is a genesis/bootstrap hook, not a normal epoch-boundary event. Listeners
 * should initialize genesis-derived state only and must not run reward, snapshot,
 * pool-reap, or other regular epoch transition logic.
 */
public record GenesisBlockEvent(Era era, int epoch, long slot, long blockNumber,
                                String blockHash, GenesisBootstrapData bootstrapData,
                                String producerPoolHash)
        implements Event {
    public GenesisBlockEvent {
        bootstrapData = bootstrapData != null ? bootstrapData : GenesisBootstrapData.empty();
    }

    public GenesisBlockEvent(Era era, int epoch, long slot, long blockNumber,
                             String blockHash, GenesisBootstrapData bootstrapData) {
        this(era, epoch, slot, blockNumber, blockHash, bootstrapData, null);
    }

    public GenesisBlockEvent(Era era, int epoch, long slot, long blockNumber, String blockHash) {
        this(era, epoch, slot, blockNumber, blockHash, GenesisBootstrapData.empty(), null);
    }
}
