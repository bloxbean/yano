package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserver;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserverProvider;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;

import java.util.Map;

/** Plugin provider for the out-of-box epoch-stake observer. */
public final class EpochStakeObserverProvider implements L1EpochObserverProvider {
    @Override public String type() { return EpochStakeContract.OBSERVER_TYPE; }

    @Override
    public L1EpochObserver create(String observerId, Map<String, String> settings) {
        int chunkEntries;
        try {
            chunkEntries = Integer.parseInt(settings.getOrDefault(
                    "chunk-entries", Integer.toString(EpochStakeContract.DEFAULT_CHUNK_ENTRIES)));
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("epoch-stake chunk-entries must be an integer", malformed);
        }
        return new EpochStakeObserver(observerId, chunkEntries);
    }
}
