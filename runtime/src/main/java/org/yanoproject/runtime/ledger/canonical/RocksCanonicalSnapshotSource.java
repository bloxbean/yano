package org.yanoproject.runtime.ledger.canonical;

import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.Snapshot;
import org.yanoproject.api.model.ProtocolParamsSnapshot;
import org.yanoproject.ledgerstate.DefaultAccountStateStore;
import org.yanoproject.ledgerstate.EpochBoundaryProcessor;
import org.yanoproject.ledgerstate.LedgerStateSnapshotReader;
import org.yanoproject.runtime.utxo.DefaultUtxoStore;
import org.yanoproject.runtime.utxo.UtxoSnapshotReader;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Production {@link CanonicalSnapshotSource}: one RocksDB snapshot of the node's single shared
 * database, readers of the account/governance store and the UTxO store bound to it, and a copy of
 * the ledger epoch's effective protocol parameters.
 *
 * <p>Storage facts (ADR-056 Phase 1): UTxO ({@code utxo_unspent}, {@code script_ref}), accounts,
 * pools, DReps, committee, proposals, constitution, enacted roots, dormant epochs and AdaPot
 * ({@code acct_state}) and epoch parameters ({@code epoch_params}) all live in the one RocksDB
 * instance opened by {@code DirectRocksDBChainState}. The only validation-visible state outside it
 * is the {@code EpochParamTracker}'s in-memory parameter maps, which are mutated inside the same
 * write section as the block that changes them; {@link #capture} runs under the gate's read lock, so
 * the parameter copy it takes belongs to the same tip as the RocksDB snapshot.
 * ({@code ProtocolParamsSnapshot} is built fresh by the store on every call, with its own
 * cost-model maps, so the snapshot's copy is not shared with the tracker.)</p>
 */
public final class RocksCanonicalSnapshotSource implements CanonicalSnapshotSource {

    /** Reason reported when {@code yano.utxo.applyAsync=true}. */
    public static final String ASYNC_UTXO_UNAVAILABLE =
            "unavailable: async UTxO apply enabled (yano.utxo.applyAsync=true); the UTxO store is written "
                    + "outside the canonical write section, so no snapshot can be guaranteed to hold one tip";

    /**
     * @return the epoch of the last <em>completed</em> epoch boundary recorded by the account store
     *         ({@code meta.boundary_step} at {@code STEP_COMPLETE}), or -1 when none or the store is
     *         disabled. Rollbacks below that epoch clear the marker, so it never outlives its state.
     */
    public static int completedBoundaryEpoch(DefaultAccountStateStore store) {
        if (store == null || !store.isEnabled()) {
            return -1;
        }
        int[] state = store.getLastBoundaryState();
        return state != null && state[1] == EpochBoundaryProcessor.STEP_COMPLETE ? state[0] : -1;
    }

    private final Supplier<RocksDB> database;
    private final Supplier<DefaultAccountStateStore> accountStore;
    private final Supplier<DefaultUtxoStore> utxoStore;
    private final IntFunction<Optional<ProtocolParamsSnapshot>> protocolParams;
    private final BooleanSupplier asyncUtxoApply;

    /**
     * @param database       the shared RocksDB instance (re-read on every capture: restores reopen it)
     * @param accountStore   account/governance store, or a supplier of {@code null} when disabled
     * @param utxoStore      UTxO store, or a supplier of {@code null} when disabled
     * @param protocolParams effective protocol parameters for an epoch (the in-memory tracker)
     * @param asyncUtxoApply true when UTxO apply runs off the canonical write path
     */
    public RocksCanonicalSnapshotSource(Supplier<RocksDB> database,
                                        Supplier<DefaultAccountStateStore> accountStore,
                                        Supplier<DefaultUtxoStore> utxoStore,
                                        IntFunction<Optional<ProtocolParamsSnapshot>> protocolParams,
                                        BooleanSupplier asyncUtxoApply) {
        this.database = Objects.requireNonNull(database, "database");
        this.accountStore = Objects.requireNonNull(accountStore, "accountStore");
        this.utxoStore = Objects.requireNonNull(utxoStore, "utxoStore");
        this.protocolParams = Objects.requireNonNull(protocolParams, "protocolParams");
        this.asyncUtxoApply = Objects.requireNonNull(asyncUtxoApply, "asyncUtxoApply");
    }

    @Override
    public String unavailableReason() {
        return asyncUtxoApply.getAsBoolean() ? ASYNC_UTXO_UNAVAILABLE : null;
    }

    @Override
    public Captured capture(CanonicalTip tip) {
        String unavailable = unavailableReason();
        if (unavailable != null) {
            throw new IllegalStateException(unavailable);
        }
        RocksDB db = database.get();
        if (db == null) {
            throw new IllegalStateException("RocksDB is not open");
        }
        Snapshot snapshot = db.getSnapshot();
        ReadOptions reads = null;
        try {
            reads = new ReadOptions().setSnapshot(snapshot).setFillCache(false);
            DefaultAccountStateStore accounts = accountStore.get();
            LedgerStateSnapshotReader ledger = accounts != null && accounts.isEnabled()
                    ? accounts.snapshotReader(db, reads)
                    : null;
            DefaultUtxoStore utxos = utxoStore.get();
            UtxoSnapshotReader utxo = utxos != null && utxos.isEnabled() ? utxos.snapshotReader(db, reads) : null;
            ProtocolParamsSnapshot params = tip.ledgerEpoch() >= 0
                    ? protocolParams.apply(tip.ledgerEpoch()).orElse(null)
                    : null;
            ReadOptions ownedReads = reads;
            return new Captured(ledger, utxo, params, () -> {
                ownedReads.close();
                db.releaseSnapshot(snapshot);
            });
        } catch (RuntimeException | Error e) {
            if (reads != null) {
                reads.close();
            }
            db.releaseSnapshot(snapshot);
            throw e;
        }
    }
}
