package com.bloxbean.cardano.yano.api.appchain.l1view;

import java.util.List;
import java.util.Optional;

/**
 * Host-side source for completed ledger boundaries and isolated epoch-pinned
 * read handles. This is runtime wiring, not a plugin contribution.
 */
public interface L1EpochStateProvider {
    boolean persistent();

    int snapshotRetentionEpochs();

    /** Pure slot-to-epoch calculation used by the non-blocking event callback. */
    long epochAtSlot(long slot);

    /** Return completed boundaries after {@code afterNewEpoch}, ascending. */
    List<L1EpochBoundary> completedBoundaries(long afterNewEpoch, int limit);

    Optional<L1EpochState> open(L1EpochBoundary boundary);
}
