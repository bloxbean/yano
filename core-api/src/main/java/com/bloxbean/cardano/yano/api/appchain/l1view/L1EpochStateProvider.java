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

    /**
     * First epoch transition for which this source can expose observer datasets.
     *
     * <p>The default matches networks whose ledger-state datasets start at genesis. Hosts with
     * a Byron era override this with the first post-Byron epoch. Every member must derive
     * the same value from deterministic network configuration, never local dataset availability.</p>
     */
    default long firstObservableEpoch() {
        return 1;
    }

    /** Return completed boundaries after {@code afterNewEpoch}, ascending. */
    List<L1EpochBoundary> completedBoundaries(long afterNewEpoch, int limit);

    Optional<L1EpochState> open(L1EpochBoundary boundary);
}
