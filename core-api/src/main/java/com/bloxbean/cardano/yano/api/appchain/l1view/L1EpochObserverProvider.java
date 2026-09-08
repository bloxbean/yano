package com.bloxbean.cardano.yano.api.appchain.l1view;

import java.util.Map;

/** Plugin factory selected by {@code observers.<id>.type}. */
public interface L1EpochObserverProvider {
    String type();

    default L1ObserverConsensusIdentity consensusIdentity(
            String observerId, Map<String, String> settings) {
        throw new UnsupportedOperationException(
                "Custom L1 epoch observer providers must declare a consensus identity");
    }

    L1EpochObserver create(String observerId, Map<String, String> settings);
}
