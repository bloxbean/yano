package org.yanoproject.runtime.mempool;

import org.yanoproject.ledger.rules.view.OverlayLedgerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The mempool's ledger-facing state (ADR-056 §6, "One immutable published state"): the ordered transactions with
 * their {@code ValidatedTx} provenance, the UTxO indexes and dependency edges, an {@link OverlayLedgerView} with one
 * layer per transaction (in list order) over the canonical {@link MempoolBase}, a monotonic mempool generation and
 * a mutation log.
 *
 * <p>Immutable. Under the mempool's mutation lane every change builds a new state and swaps the published
 * reference, so the list, provenance, indexes and overlay are always published together and consistent with each
 * other. The state does not manage its base's reference count: the published slot owns one reference for the
 * base of the published state (see {@code LedgerMempool}).</p>
 *
 * <h2>Mutation log</h2>
 * <p>Each state records the global sequence number of the mutation that produced it ({@link #logSeq()}) and a
 * persistent list of the mutations since its lineage started (a rebuild publication or a fresh state starts a new
 * lineage). A rebuild records the state it folded and later checks, with {@link #appendsSince}, that the published
 * state descends from it by appends only. While no rebuild holds a position the log is compacted to the last
 * mutation; compaction never makes a descent check pass wrongly, only fail (which restarts the rebuild).</p>
 */
final class MempoolLedgerState {

    enum MutationKind { APPEND, REMOVE }

    /** One entry of the mutation log, newest first. */
    record Mutation(long seq, MutationKind kind, MempoolEntry appended, Mutation previous) {
    }

    private final MempoolBase base;
    private final OverlayLedgerView overlay;
    private final List<MempoolEntry> entries;
    private final MempoolIndexes indexes;
    private final long generation;
    private final long lineage;
    private final long logSeq;
    private final long logStartSeq;
    private final Mutation log;

    private MempoolLedgerState(MempoolBase base, OverlayLedgerView overlay, List<MempoolEntry> entries,
                               MempoolIndexes indexes, long generation, long lineage, long logSeq, long logStartSeq,
                               Mutation log) {
        this.base = Objects.requireNonNull(base, "base");
        this.overlay = Objects.requireNonNull(overlay, "overlay");
        this.entries = entries;
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.generation = generation;
        this.lineage = lineage;
        this.logSeq = logSeq;
        this.logStartSeq = logStartSeq;
        this.log = log;
        if (overlay.layerCount() != entries.size()) {
            throw new IllegalStateException("overlay has " + overlay.layerCount() + " layers for " + entries.size()
                    + " transactions");
        }
    }

    /** A state that starts a new lineage (initial state, rebuild publication). */
    static MempoolLedgerState fresh(MempoolBase base, OverlayLedgerView overlay, List<MempoolEntry> entries,
                                    MempoolIndexes indexes, long generation, long seq) {
        return new MempoolLedgerState(base, overlay, List.copyOf(entries), indexes, generation, seq, seq, seq, null);
    }

    /** @return this state with {@code entry} (validated against this state's overlay) appended */
    MempoolLedgerState append(MempoolEntry entry, OverlayLedgerView newOverlay, MempoolIndexes newIndexes,
                              long newGeneration, long seq, boolean keepLog) {
        List<MempoolEntry> list = new ArrayList<>(entries.size() + 1);
        list.addAll(entries);
        list.add(entry);
        return new MempoolLedgerState(base, newOverlay, Collections.unmodifiableList(list), newIndexes, newGeneration,
                lineage, seq, keepLog ? logStartSeq : logSeq,
                new Mutation(seq, MutationKind.APPEND, entry, keepLog ? log : null));
    }

    /** @return a state of the same base and lineage after a removal (truncate-and-reapply, clear) */
    MempoolLedgerState removal(List<MempoolEntry> newEntries, OverlayLedgerView newOverlay,
                               MempoolIndexes newIndexes, long newGeneration, long seq, boolean keepLog) {
        return new MempoolLedgerState(base, newOverlay, List.copyOf(newEntries), newIndexes, newGeneration, lineage,
                seq, keepLog ? logStartSeq : logSeq, new Mutation(seq, MutationKind.REMOVE, null, keepLog ? log : null));
    }

    /**
     * Mempool descent (rebuild step 4.1).
     *
     * @return the transactions appended between {@code from} and {@code to}, oldest first, when {@code to} descends
     *         from {@code from} by appends only; {@code null} when anything else happened (a removal, eviction,
     *         clear, another lineage, or a compacted log)
     */
    static List<MempoolEntry> appendsSince(MempoolLedgerState from, MempoolLedgerState to) {
        if (to == from) {
            return List.of();
        }
        if (to.lineage != from.lineage) {
            return null;
        }
        List<MempoolEntry> appended = new ArrayList<>();
        Mutation m = to.log;
        while (m != null && m.seq() > from.logSeq) {
            if (m.kind() != MutationKind.APPEND) {
                return null;
            }
            appended.add(m.appended());
            m = m.previous();
        }
        long reached = m != null ? m.seq() : to.logStartSeq;
        if (reached != from.logSeq) {
            return null;
        }
        return appended.reversed();
    }

    MempoolBase base() {
        return base;
    }

    CanonicalMark mark() {
        return base.mark();
    }

    OverlayLedgerView overlay() {
        return overlay;
    }

    /** @return the transactions in admission order (immutable) */
    List<MempoolEntry> entries() {
        return entries;
    }

    MempoolIndexes indexes() {
        return indexes;
    }

    long generation() {
        return generation;
    }

    long logSeq() {
        return logSeq;
    }

    int size() {
        return entries.size();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /** @return the position of {@code txHash}, or -1 */
    int indexOf(String txHash) {
        if (!indexes.byId().containsKey(txHash)) {
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).txHash().equals(txHash)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Checks that the list, overlay layers and indexes describe the same transactions (tests and assertions).
     *
     * @throws IllegalStateException when they do not
     */
    void checkConsistent() {
        List<String> ids = entries.stream().map(MempoolEntry::txHash).toList();
        if (!overlay.layerTxIds().equals(ids)) {
            throw new IllegalStateException("overlay layers " + overlay.layerTxIds() + " != transactions " + ids);
        }
        if (indexes.byId().size() != ids.size()) {
            throw new IllegalStateException("index holds " + indexes.byId().size() + " transactions, list " + ids.size());
        }
        long bytes = 0;
        int spent = 0;
        int produced = 0;
        for (MempoolEntry e : entries) {
            if (indexes.byId().get(e.txHash()) == null) {
                throw new IllegalStateException("transaction " + e.txHash() + " missing from the index");
            }
            bytes += e.size();
            spent += e.projection().regularInputs().size();
            produced += e.projection().outputs().size();
        }
        if (bytes != indexes.byteSize() || spent != indexes.spentBy().size()
                || produced != indexes.producedBy().size()) {
            throw new IllegalStateException("index totals disagree with the transactions");
        }
    }

    @Override
    public String toString() {
        return "MempoolLedgerState[generation=" + generation + ", txs=" + entries.size() + ", base=" + base + "]";
    }
}
