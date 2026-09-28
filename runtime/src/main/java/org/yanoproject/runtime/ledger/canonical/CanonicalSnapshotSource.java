package org.yanoproject.runtime.ledger.canonical;

import org.yanoproject.api.model.ProtocolParamsSnapshot;
import org.yanoproject.ledgerstate.LedgerStateSnapshotReader;
import org.yanoproject.runtime.utxo.UtxoSnapshotReader;

import java.util.Objects;

/**
 * Captures the state behind one {@link CanonicalSnapshot}. {@link CanonicalStateGate} calls
 * {@link #capture(CanonicalTip)} while it holds the gate's read lock, so no canonical writer is
 * part-way through a block (ADR-056 §3, acquisition steps 3 and 4).
 */
@FunctionalInterface
public interface CanonicalSnapshotSource {

    /**
     * Takes one RocksDB snapshot of the shared database and copies the in-memory values validation
     * reads.
     *
     * @param tip the published tip; the capture belongs to its generation
     * @return the captured state
     * @throws Exception when the state cannot be captured; the message becomes the
     *                   {@code Unavailable} reason
     */
    Captured capture(CanonicalTip tip) throws Exception;

    /**
     * Cheap check made <em>before</em> the gate's read lock is taken.
     *
     * @return why this source cannot currently produce a consistent snapshot, or {@code null} when
     *         it can
     */
    default String unavailableReason() {
        return null;
    }

    /** @return true when {@link #unavailableReason()} is {@code null} */
    default boolean isAvailable() {
        return unavailableReason() == null;
    }

    /**
     * State captured for one snapshot.
     *
     * @param ledger         account, pool, DRep, committee, governance and pot reads bound to the
     *                       RocksDB snapshot, or {@code null} when account state is disabled
     * @param utxo           UTxO reads bound to the same RocksDB snapshot, or {@code null} when the
     *                       UTxO store is disabled
     * @param protocolParams immutable copy of the ledger epoch's effective protocol parameters (including
     *                       cost models), taken under the gate's read lock; {@code null} when they are
     *                       not available
     * @param release        frees the RocksDB snapshot and read options; called exactly once
     */
    record Captured(LedgerStateSnapshotReader ledger, UtxoSnapshotReader utxo,
                    ProtocolParamsSnapshot protocolParams, Runnable release) {
        public Captured {
            Objects.requireNonNull(release, "release");
        }
    }
}
