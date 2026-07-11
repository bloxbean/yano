package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import com.bloxbean.cardano.yano.consensus.selection.CandidateHeader;

/**
 * Single selected-chain writer boundary.
 */
public interface CanonicalApplier {
    void adoptHeader(CandidateHeader header);

    void rollbackTo(long slot, String blockHash);
}
