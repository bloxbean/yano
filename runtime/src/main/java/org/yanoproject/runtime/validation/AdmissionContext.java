package org.yanoproject.runtime.validation;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.runtime.ledger.canonical.CanonicalLedgerView;
import org.yanoproject.runtime.ledger.canonical.CanonicalSnapshot;
import org.yanoproject.runtime.ledger.canonical.CanonicalStateGate;
import org.yanoproject.runtime.ledger.canonical.SnapshotPurpose;
import org.yanoproject.runtime.ledger.canonical.TickedLedgerView;

import java.util.function.Function;

/**
 * The canonical state one engine-mode admission validates against (ADR-056 step 1d): one
 * {@link CanonicalSnapshot} ({@link SnapshotPurpose#ADMISSION}) ticked to the slot after the tip, as Haskell's
 * mempool ticks its ledger state.
 *
 * <p><b>Lock order.</b> The transaction subsystem opens the context <em>before</em> it enters the mempool's
 * mutation lane and closes it after leaving, so acquiring the snapshot (which briefly takes the canonical
 * gate's read lock) never happens while the lane is held (ADR-056 §6). The context is published to the
 * admitting thread only ({@link #current()}); the synchronous validation listener picks it up there.</p>
 *
 * <p>When no snapshot can be acquired (no RocksDB store, asynchronous UTxO apply, the database closing), the
 * context carries the reason and engine admission rejects with {@code ENGINE.LedgerStateUnavailable}.</p>
 */
public final class AdmissionContext implements AutoCloseable {

    private static final ThreadLocal<AdmissionContext> CURRENT = new ThreadLocal<>();

    private final TickedLedgerView view;
    private final AdmissionCanonicalResolver canonicalResolver;
    private final long targetSlot;
    private final String unavailableReason;
    private final AdmissionContext previous;
    private boolean closed;

    private AdmissionContext(TickedLedgerView view, long targetSlot, String unavailableReason,
                             AdmissionContext previous) {
        this.view = view;
        this.canonicalResolver = view != null ? new AdmissionCanonicalResolver(view) : null;
        this.targetSlot = targetSlot;
        this.unavailableReason = unavailableReason;
        this.previous = previous;
    }

    /**
     * Acquires the admission snapshot and publishes the context on the calling thread until {@link #close()}.
     */
    public static AdmissionContext open(CanonicalStateGate gate) {
        return open(gate, SnapshotPurpose.ADMISSION);
    }

    /**
     * @param purpose {@link SnapshotPurpose#ADMISSION} for engine admission (never refused), or
     *                {@link SnapshotPurpose#SHADOW} when only shadow engines need the snapshot (refused at the
     *                live-snapshot cap; the reason then says so)
     */
    public static AdmissionContext open(CanonicalStateGate gate, SnapshotPurpose purpose) {
        AdmissionContext previous = CURRENT.get();
        AdmissionContext context = create(gate, purpose, previous);
        CURRENT.set(context);
        return context;
    }

    private static AdmissionContext create(CanonicalStateGate gate, SnapshotPurpose purpose,
                                           AdmissionContext previous) {
        if (gate == null) {
            return new AdmissionContext(null, -1, "no canonical state gate is installed", previous);
        }
        Lookup<CanonicalSnapshot> acquired = gate.acquireSnapshot(purpose);
        if (!(acquired instanceof Lookup.Present<CanonicalSnapshot> present)) {
            String reason = acquired instanceof Lookup.Unavailable<CanonicalSnapshot> u ? u.reason()
                    : "no canonical snapshot";
            return new AdmissionContext(null, -1, reason, previous);
        }
        CanonicalSnapshot snapshot = present.value();
        try (CanonicalLedgerView canonical = CanonicalLedgerView.over(snapshot)) {
            long target = TickedLedgerView.admissionSlot(snapshot);
            TickedLedgerView view = TickedLedgerView.of(canonical, target);
            // ADR-056 step 1d (M5): a boundary dry run is computed here, before the mempool lane, and shared
            // by every admission on this generation.
            if (view.mode() == TickedLedgerView.Mode.TICKED) {
                view.boundaryPreview();
            }
            return new AdmissionContext(view, target, null, previous);
        } finally {
            snapshot.release();
        }
    }

    /** @return the context of the admission running on this thread, or {@code null} */
    public static AdmissionContext current() {
        return CURRENT.get();
    }

    /** @return the ticked view, or {@code null} when {@link #unavailableReason()} is set */
    public TickedLedgerView view() {
        return view;
    }

    /** @return the resolver the mempool uses for canonical UTxOs, answering from {@link #view()} */
    AdmissionCanonicalResolver canonicalResolver() {
        return canonicalResolver;
    }

    /** @return the canonical-UTxO resolver for the mempool, or {@code null} when unavailable */
    public Function<Outpoint, Utxo> mempoolCanonicalResolver() {
        return canonicalResolver;
    }

    /** @return the validation slot: the slot after the snapshot's tip */
    public long targetSlot() {
        return targetSlot;
    }

    /** @return why no snapshot is available, or {@code null} */
    public String unavailableReason() {
        return unavailableReason;
    }

    /** Releases the snapshot reference and unpublishes the context. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (CURRENT.get() == this) {
            if (previous != null) {
                CURRENT.set(previous);
            } else {
                CURRENT.remove();
            }
        }
        if (view != null) {
            view.close();
        }
    }

    @Override
    public String toString() {
        return unavailableReason != null ? "AdmissionContext[unavailable: " + unavailableReason + "]"
                : "AdmissionContext[generation=" + view.generation() + ", slot=" + targetSlot + ", mode="
                + view.mode() + "]";
    }
}
