package com.bloxbean.cardano.yano.consensus.selection;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Receives peer-scoped headers without mutating canonical chain state.
 */
public final class HeaderFanIn {
    private final CandidateHeaderStore store;

    public HeaderFanIn(CandidateHeaderStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public void onCandidateHeader(CandidateHeader header) {
        store.put(header);
    }

    public List<CandidateHeader> candidatesAfter(long blockNumber) {
        return store.all().stream()
                .filter(header -> header.blockNumber() > blockNumber)
                .sorted(Comparator
                        .comparingLong(CandidateHeader::blockNumber).reversed()
                        .thenComparing(CandidateHeader::blockHash))
                .toList();
    }
}
