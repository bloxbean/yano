package com.bloxbean.cardano.yano.runtime.config;

import com.bloxbean.cardano.yano.runtime.genesis.ByronGenesisData;
import com.bloxbean.cardano.yano.runtime.genesis.ConwayGenesisData;
import com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisData;

/**
 * In-memory genesis data for devnet/block-producer mode.
 * Allows devnet startup without genesis files on disk.
 * <p>
 * Only valid when {@code devMode=true} and {@code enableBlockProducer=true}.
 * Immutable after construction — create a new instance for different values.
 *
 * @param shelley              Shelley genesis data (required)
 * @param byron                Byron genesis data (nullable — no Byron era for most devnets)
 * @param conway               Conway genesis data (nullable — uses defaults if absent)
 * @param protocolParametersJson raw protocol params JSON string (nullable)
 */
public record InMemoryDevnetGenesis(
        ShelleyGenesisData shelley,
        ByronGenesisData byron,
        ConwayGenesisData conway,
        String protocolParametersJson
) {
    public InMemoryDevnetGenesis {
        if (shelley == null) {
            throw new IllegalArgumentException("ShelleyGenesisData is required for in-memory devnet genesis");
        }
    }
}
