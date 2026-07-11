package com.bloxbean.cardano.yano.consensus.selection;

import java.util.List;
import java.util.Optional;

/**
 * Non-canonical candidate header store.
 */
public interface CandidateHeaderStore {
    void put(CandidateHeader header);

    Optional<CandidateHeader> get(String blockHash);

    List<CandidateHeader> all();

    void pruneBeforeSlot(long slot);
}
