package org.yanoproject.api;

import java.util.List;
import java.util.Optional;

/** Host source of completed ledger epoch boundaries and close-scoped {@link EpochSnapshot}s. */
public interface EpochSnapshotReader {
    /** Whether ledger state is persisted; {@code false} when the account-state store is disabled. */
    boolean persistent();

    int snapshotRetentionEpochs();

    /** Pure slot-to-epoch calculation; never touches the store. */
    long epochAtSlot(long slot);

    /**
     * First epoch transition for which snapshots can be exposed.
     *
     * <p>The default matches networks whose ledger-state datasets start at genesis. Hosts with a Byron era
     * override this with the first post-Byron epoch. The value is derived from deterministic network
     * configuration, never from local dataset availability.</p>
     */
    default long firstObservableEpoch() {
        return 1;
    }

    /** Completed boundaries after {@code afterNewEpoch}, ascending, at most {@code limit}. */
    List<EpochBoundary> completedBoundaries(long afterNewEpoch, int limit);

    /** Opens a snapshot of a completed boundary; empty when the boundary is not yet complete. */
    Optional<EpochSnapshot> open(EpochBoundary boundary);
}
