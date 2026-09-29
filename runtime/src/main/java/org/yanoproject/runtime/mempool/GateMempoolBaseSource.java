package org.yanoproject.runtime.mempool;

import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.runtime.ledger.canonical.CanonicalLedgerView;
import org.yanoproject.runtime.ledger.canonical.CanonicalSnapshot;
import org.yanoproject.runtime.ledger.canonical.CanonicalStateGate;
import org.yanoproject.runtime.ledger.canonical.CanonicalTip;
import org.yanoproject.runtime.ledger.canonical.SnapshotPurpose;
import org.yanoproject.runtime.ledger.canonical.TickedLedgerView;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Mempool bases from the {@link CanonicalStateGate} (ADR-056 §3, §6): one {@link SnapshotPurpose#REBUILD} snapshot
 * per base, ticked to {@link TickedLedgerView#admissionSlot(CanonicalSnapshot) the admission slot}; a boundary dry
 * run is computed here, before the mempool lane, so admissions on the base never compute it under the lane.
 */
public final class GateMempoolBaseSource implements MempoolBaseSource {

    private final Supplier<CanonicalStateGate> gate;

    public GateMempoolBaseSource(Supplier<CanonicalStateGate> gate) {
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    @Override
    public MempoolBase acquire() {
        CanonicalStateGate g = gate.get();
        if (g == null) {
            return MempoolBase.unavailable("no canonical state gate is installed", CanonicalMark.UNKNOWN);
        }
        Lookup<CanonicalSnapshot> acquired = g.acquireSnapshot(SnapshotPurpose.REBUILD);
        if (!(acquired instanceof Lookup.Present<CanonicalSnapshot> present)) {
            String reason = acquired instanceof Lookup.Unavailable<CanonicalSnapshot> u ? u.reason()
                    : "no canonical snapshot";
            return MempoolBase.unavailable(reason, mark(g, g.withEpochs(g.tip())));
        }
        CanonicalSnapshot snapshot = present.value();
        try (CanonicalLedgerView canonical = CanonicalLedgerView.over(snapshot)) {
            long target = TickedLedgerView.admissionSlot(snapshot);
            TickedLedgerView view = TickedLedgerView.of(canonical, target);
            if (view.mode() == TickedLedgerView.Mode.TICKED) {
                view.boundaryPreview();
            }
            CanonicalTip tip = snapshot.tip();
            long basis = tip.slot() >= 0 ? tip.slot() + 1 : 0;
            return MempoolBase.of(view, mark(g, tip), target, basis,
                    view::close);
        } finally {
            snapshot.release();
        }
    }

    @Override
    public CanonicalMark current() {
        CanonicalStateGate g = gate.get();
        // The same epoch computation as snapshot acquisition, so the marks compare equal for one generation.
        return g == null ? CanonicalMark.UNKNOWN : mark(g, g.withEpochs(g.tip()));
    }

    private static CanonicalMark mark(CanonicalStateGate g, CanonicalTip tip) {
        return new CanonicalMark(tip.generation(), g.admissionEpoch(tip));
    }

    @Override
    public boolean isHeldByCurrentThread() {
        CanonicalStateGate g = gate.get();
        return g != null && g.isHeldByCurrentThread();
    }

    @Override
    public AutoCloseable onPublication(Consumer<CanonicalMark> listener) {
        CanonicalStateGate g = gate.get();
        if (g == null) {
            return () -> { };
        }
        Consumer<CanonicalTip> adapter = tip -> listener.accept(mark(g, tip));
        g.addPublicationListener(adapter);
        return () -> g.removePublicationListener(adapter);
    }
}
