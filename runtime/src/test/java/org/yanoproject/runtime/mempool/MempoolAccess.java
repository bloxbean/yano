package org.yanoproject.runtime.mempool;

import org.yanoproject.ledger.rules.view.LedgerView;

/** Test access to a {@link LedgerMempool}'s published state from other packages. */
public final class MempoolAccess {

    /** A frozen view of the published state and the release of the base reference it retains. */
    public record State(LedgerView view, Runnable release) {
    }

    /** Retains the published base, as a shadow task does, and returns the published overlay with its release. */
    public static State published(LedgerMempool mempool) {
        MempoolLedgerState state = mempool.published();
        MempoolBase base = state.base().retain();
        return new State(state.overlay(), base::release);
    }

    private MempoolAccess() {
    }
}
