package org.yanoproject.runtime.sync.multipeer;

import org.yanoproject.consensus.selection.CandidateHeader;

/**
 * Single selected-chain writer boundary.
 */
public interface CanonicalApplier {
    void adoptHeader(CandidateHeader header);

    void rollbackTo(long slot, String blockHash);
}
