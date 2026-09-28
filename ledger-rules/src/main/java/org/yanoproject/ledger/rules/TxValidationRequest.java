package org.yanoproject.ledger.rules;

import org.yanoproject.ledger.rules.view.LedgerView;

import java.util.Arrays;
import java.util.Objects;

/**
 * One validation request for a {@link LedgerValidationEngine} (ADR-056 §2).
 *
 * <p>The caller does not choose full validation or re-application: the engine decides from
 * {@link #previous()} and {@link #env()} using the invalidation rules of §6.</p>
 *
 * @param txCbor   the transaction bytes exactly as received; copied on the way in and out
 * @param view     the state to validate against (a ticked base, possibly under an overlay)
 * @param env      the non-state environment
 * @param rule     {@link Rule#MEMPOOL} for admission and mempool rebuilds, {@link Rule#LEDGER} for
 *                 block selection and shadow sync
 * @param origin   where the transaction came from
 * @param previous the earlier successful validation of this transaction, or {@code null}
 */
public record TxValidationRequest(byte[] txCbor, LedgerView view, ValidationEnv env, Rule rule, Origin origin,
                                  ValidatedTx previous) {

    /** Haskell rule to run: admission ({@code MEMPOOL}, which runs {@code LEDGER}) or block ({@code LEDGER}). */
    public enum Rule {
        MEMPOOL,
        LEDGER
    }

    /** Where a transaction, and so a verdict, came from. */
    public enum Origin {
        LOCAL,
        PEER,
        BLOCK_BUILD,
        SYNC
    }

    public TxValidationRequest {
        txCbor = Objects.requireNonNull(txCbor, "txCbor").clone();
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(origin, "origin");
    }

    @Override
    public byte[] txCbor() {
        return txCbor.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TxValidationRequest other
                && Arrays.equals(txCbor, other.txCbor)
                && view.equals(other.view)
                && env.equals(other.env)
                && rule == other.rule
                && origin == other.origin
                && Objects.equals(previous, other.previous);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(txCbor), view, env, rule, origin, previous);
    }

    @Override
    public String toString() {
        return "TxValidationRequest[txCbor=" + txCbor.length + " bytes, rule=" + rule + ", origin=" + origin
                + ", env=" + env + ", previous=" + previous + "]";
    }
}
