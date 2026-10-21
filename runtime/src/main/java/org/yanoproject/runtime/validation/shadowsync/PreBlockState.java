package org.yanoproject.runtime.validation.shadowsync;

import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.runtime.ledger.canonical.CanonicalLedgerView;
import org.yanoproject.runtime.ledger.canonical.CanonicalSnapshot;
import org.yanoproject.runtime.ledger.canonical.CanonicalStateGate;
import org.yanoproject.runtime.ledger.canonical.SnapshotPurpose;
import org.yanoproject.runtime.ledger.canonical.TickedLedgerView;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The pre-block ledger state of one applied block, owned by one shadow-sync job (ADR-056 Phase 7a). Captured on the
 * apply thread, read and closed on a validation thread; {@link #close()} releases what it holds exactly once.
 */
public interface PreBlockState extends AutoCloseable {

    /**
     * @return the pre-block view at the block's slot
     * @throws IllegalStateException when the captured state is not at the block's epoch (the reason is the message)
     */
    LedgerView view();

    /** @return {@code next(parent)}: the forecast-horizon basis of the block's transactions */
    long forecastBasisSlot();

    /**
     * @return the epoch of the parent block's slot, or -1 when unknown; a block in a later epoch is the first of its
     *         epoch, and its pre-block state includes that epoch's boundary
     */
    default int parentEpoch() {
        return -1;
    }

    @Override
    void close();

    /** Captures the pre-block state on the apply thread. */
    @FunctionalInterface
    interface Source {
        /**
         * Called from the first {@code BlockAppliedEvent} listener, inside the block's canonical write section.
         *
         * @param blockSlot the block's slot
         */
        Lookup<PreBlockState> capture(long blockSlot);
    }

    /**
     * The production source: {@link CanonicalStateGate#captureInWriteSection} with purpose
     * {@link SnapshotPurpose#SHADOW_SYNC}, viewed as {@link TickedLedgerView#of(CanonicalLedgerView, long)} at the
     * block's slot. The capture's ledger epoch is the block's (the boundary, if any, is already applied), so the view
     * must be {@link TickedLedgerView.Mode#CANONICAL}; anything else is reported, never dry-run.
     */
    static Source ofGate(Supplier<CanonicalStateGate> gate) {
        Objects.requireNonNull(gate, "gate");
        return blockSlot -> {
            CanonicalStateGate g = gate.get();
            if (g == null) {
                return Lookup.unavailable("no canonical state gate");
            }
            Lookup<CanonicalSnapshot> captured = g.captureInWriteSection(SnapshotPurpose.SHADOW_SYNC);
            if (captured instanceof Lookup.Present<CanonicalSnapshot> present) {
                return Lookup.present(new SnapshotState(present.value(), blockSlot));
            }
            return Lookup.unavailable(captured instanceof Lookup.Unavailable<CanonicalSnapshot> u ? u.reason()
                    : "no snapshot");
        };
    }

    /** A {@link CanonicalSnapshot} taken over by the job (it owns the capture's first reference). */
    final class SnapshotState implements PreBlockState {
        private final CanonicalSnapshot snapshot;
        private final long blockSlot;
        private final AtomicBoolean closed = new AtomicBoolean();
        private CanonicalLedgerView canonical;
        private TickedLedgerView ticked;

        SnapshotState(CanonicalSnapshot snapshot, long blockSlot) {
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
            this.blockSlot = blockSlot;
        }

        @Override
        public synchronized LedgerView view() {
            if (closed.get()) {
                throw new IllegalStateException("the pre-block state is closed");
            }
            if (ticked == null) {
                canonical = CanonicalLedgerView.over(snapshot);
                ticked = TickedLedgerView.of(canonical, blockSlot);
            }
            if (ticked.mode() != TickedLedgerView.Mode.CANONICAL) {
                throw new IllegalStateException("the pre-block state is at ledger epoch "
                        + snapshot.tip().ledgerEpoch() + ", not the epoch of block slot " + blockSlot
                        + " (view mode " + ticked.mode() + "): the block's epoch boundary was not applied before it");
            }
            return ticked;
        }

        @Override
        public long forecastBasisSlot() {
            long parent = snapshot.tip().slot();
            return parent >= 0 ? parent + 1 : 0;
        }

        @Override
        public int parentEpoch() {
            return snapshot.tip().tipSlotEpoch();
        }

        /** @return the captured snapshot (tests) */
        CanonicalSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public synchronized void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                if (ticked != null) {
                    ticked.close();
                }
                if (canonical != null) {
                    canonical.close();
                }
            } finally {
                snapshot.release();
            }
        }
    }
}
