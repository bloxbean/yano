package com.bloxbean.cardano.yano.runtime.sync.multipeer;

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
