package com.bloxbean.cardano.yano.api.appchain.l1view;

import java.util.Map;

/** Pure, deterministic two-pass observer for one finalized L1 epoch boundary. */
public interface L1EpochObserver {
    String observerId();

    EpochObservationManifest prepare(L1EpochBoundary boundary, L1EpochState state);

    void writeObservations(EpochObservationManifest manifest,
                           L1EpochState state,
                           L1EpochObservationSink sink);

    default Map<String, Object> status() {
        return Map.of();
    }
}
