package com.bloxbean.cardano.yano.api.appchain.l1view;

/** Host-owned bounded sink used by the second observer pass. */
@FunctionalInterface
public interface L1EpochObservationSink {
    /**
     * Write the canonical claim for {@code observationIndex}. Index zero is
     * the dataset manifest; subsequent indexes are data chunks.
     */
    void write(int observationIndex, byte[] claim);
}
