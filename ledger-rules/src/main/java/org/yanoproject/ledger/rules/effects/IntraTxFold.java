package org.yanoproject.ledger.rules.effects;

import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.OverlayLedgerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The in-transaction state fold (ADR-056 invariant 5): the pre-certificate step, then one step per
 * certificate in body order, each applied on top of the state the previous steps left.
 *
 * <p>{@link TxEffectsDeriver} derives effects with this fold, and the CERTS/GOV rules must validate
 * each certificate against {@link #current()} of the same fold, stepping with the same
 * {@link TxEffectsDeriver#preCertificateChanges} and {@link TxEffectsDeriver#certificateChanges}. That
 * way validation and effects cannot disagree about the intermediate state. UTXOW/UTXO read
 * {@link #preState()}, never the fold.</p>
 *
 * <p>Immutable: {@link #step(List)} returns a new fold.</p>
 */
public final class IntraTxFold {

    private final String txId;
    private final LedgerView preState;
    private final OverlayLedgerView current;
    private final List<LedgerChange> changes;
    private final int steps;

    private IntraTxFold(String txId, LedgerView preState, OverlayLedgerView current, List<LedgerChange> changes,
                        int steps) {
        this.txId = txId;
        this.preState = preState;
        this.current = current;
        this.changes = changes;
        this.steps = steps;
    }

    /**
     * @param txId     the transaction id (lowercase hex)
     * @param preState the state the transaction is validated against
     * @return a fold with no steps
     */
    public static IntraTxFold start(String txId, LedgerView preState) {
        Objects.requireNonNull(txId, "txId");
        Objects.requireNonNull(preState, "preState");
        return new IntraTxFold(txId, preState, OverlayLedgerView.over(preState), List.of(), 0);
    }

    /**
     * Applies one step's changes.
     *
     * @param stepChanges the changes of one step, in order (may be empty)
     * @return a new fold whose {@link #current()} includes them
     */
    public IntraTxFold step(List<LedgerChange> stepChanges) {
        Objects.requireNonNull(stepChanges, "stepChanges");
        if (stepChanges.isEmpty()) {
            return new IntraTxFold(txId, preState, current, changes, steps + 1);
        }
        List<LedgerChange> all = new ArrayList<>(changes.size() + stepChanges.size());
        all.addAll(changes);
        all.addAll(stepChanges);
        OverlayLedgerView next = current.apply(TxEffects.ofChanges(txId, stepChanges));
        return new IntraTxFold(txId, preState, next, List.copyOf(all), steps + 1);
    }

    public String txId() {
        return txId;
    }

    /** @return the state before the transaction (what UTXOW/UTXO read) */
    public LedgerView preState() {
        return preState;
    }

    /** @return the state after the steps applied so far */
    public OverlayLedgerView current() {
        return current;
    }

    /** @return every change applied so far, in order */
    public List<LedgerChange> changes() {
        return changes;
    }

    /** @return the number of steps applied, including empty ones */
    public int steps() {
        return steps;
    }
}
