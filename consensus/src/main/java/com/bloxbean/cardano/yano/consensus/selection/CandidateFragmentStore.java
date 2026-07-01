package com.bloxbean.cardano.yano.consensus.selection;

import java.util.List;
import java.util.Optional;

/**
 * Bounded per-peer candidate-fragment store.
 */
public interface CandidateFragmentStore {
    void put(CandidateHeader header);

    Optional<CandidateFragment> fragmentEndingAt(String peerId, String blockHash);

    List<CandidateFragment> fragmentsAfter(long blockNumber);

    void pruneBeforeSlot(long slot);
}
