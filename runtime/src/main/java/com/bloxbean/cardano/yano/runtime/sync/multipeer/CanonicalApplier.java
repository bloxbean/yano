package com.bloxbean.cardano.yano.runtime.sync.multipeer;

/**
 * Single selected-chain writer boundary.
 */
public interface CanonicalApplier {
    void adoptHeader(CandidateHeader header);

    void rollbackTo(long slot, String blockHash);
}
