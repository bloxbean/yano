package org.yanoproject.ledger.rules.conway;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.mempool.MempoolRule;
import org.yanoproject.ledger.rules.conway.utxow.UtxowRule;

import java.util.List;
import java.util.Objects;

/**
 * The Conway transaction transition (ADR-056 §4), rooted where Haskell roots it: {@code MEMPOOL} for admission
 * and mempool rebuilds, {@code LEDGER} for block selection and shadow sync.
 *
 * <ol start="0">
 *   <li>{@code MEMPOOL} (rule {@code MEMPOOL} only): {@link MempoolRule} against the incoming state; the
 *       all-inputs-spent failure stops everything ({@code whenFailureFreeDefault}), the unelected-voter failure
 *       is recorded and {@code LEDGER} still runs (Conway/Rules/Mempool.hs:103-138).</li>
 *   <li>{@code LEDGER} ({@code conwayLedgerTransitionTRC}, Conway/Rules/Ledger.hs:350-440): when
 *       {@code isValid = True}, the pre-checks (:364-392), then {@code CERTS} (:394-400), then {@code GOV}
 *       (:409-421); for every transaction {@code UTXOW} (:428-439) with the pre-certificate state.</li>
 *   <li>{@code UTXOW} → {@code UTXO} → {@code UTXOS}, nested as in Haskell.</li>
 * </ol>
 *
 * <p>Each family is a {@link SubRule}. Failures accumulate with Haskell's STS semantics ({@link RuleFrame}); only
 * {@code whenFailureFree} blocks are skipped ({@code UTXOS}' script execution). Families that later phases
 * implement are plugged in through the constructor; until then they are {@link SubRule#NOT_YET_IMPLEMENTED}
 * (Phase 3a: {@code LEDGER} pre-checks and {@code GOV} are Phase 5, {@code CERTS} Phase 4, the {@code UTXOW}
 * witness checks Phase 3b).</p>
 *
 * <p>Stateless and thread-safe; each run gets its own {@link TransitionContext}.</p>
 */
public final class ConwayLedgerTransition {

    /** One rule family, run against its parent rule's frame. */
    @FunctionalInterface
    public interface SubRule {

        /** A family no phase has implemented yet: it records nothing. */
        SubRule NOT_YET_IMPLEMENTED = parent -> {
        };

        /**
         * Runs the family. A sub-rule opens its own frame with {@link RuleFrame#child(LedgerRuleName)} and folds it
         * back with {@link RuleFrame#subRule(RuleFrame)}; the {@code LEDGER} pre-checks record into
         * {@code parent} directly, as they are predicates of {@code LEDGER} itself.
         */
        void apply(RuleFrame parent);
    }

    private final SubRule ledgerPreChecks;
    private final SubRule certs;
    private final SubRule gov;
    private final SubRule utxow;

    /**
     * @param ledgerPreChecks the {@code LEDGER} predicates before {@code CERTS} (treasury value, reference-script
     *                        size, DRep-delegated withdrawals, the PV11 withdrawal checks)
     * @param certs           {@code CERTS} (with {@code DELEG}, {@code POOL}, {@code GOVCERT})
     * @param gov             {@code GOV}
     * @param utxow           {@code UTXOW}, which runs {@code UTXO} and {@code UTXOS}
     */
    public ConwayLedgerTransition(SubRule ledgerPreChecks, SubRule certs, SubRule gov, SubRule utxow) {
        this.ledgerPreChecks = Objects.requireNonNull(ledgerPreChecks, "ledgerPreChecks");
        this.certs = Objects.requireNonNull(certs, "certs");
        this.gov = Objects.requireNonNull(gov, "gov");
        this.utxow = Objects.requireNonNull(utxow, "utxow");
    }

    /** @return the families implemented so far (Phase 3a: {@code UTXOW} script preparation, {@code UTXO}, {@code UTXOS}) */
    public static ConwayLedgerTransition standard() {
        return new ConwayLedgerTransition(SubRule.NOT_YET_IMPLEMENTED, SubRule.NOT_YET_IMPLEMENTED,
                SubRule.NOT_YET_IMPLEMENTED, UtxowRule::apply);
    }

    /**
     * Runs the transition.
     *
     * @return the failures in the order Haskell reports them for the context's root rule; empty when valid
     */
    public List<LedgerFailure> apply(TransitionContext ctx) {
        if (ctx.rule() == TxValidationRequest.Rule.MEMPOOL) {
            MempoolRule.Result mempool = MempoolRule.apply(ctx.tx().getBody(), ctx.preState(), ctx.protocolMajor());
            if (!mempool.continueToLedger()) {
                return mempool.failures();
            }
            RuleFrame mempoolFrame = new RuleFrame(LedgerRuleName.MEMPOOL, ctx);
            mempoolFrame.predicate(mempool.failures());
            RuleFrame ledger = mempoolFrame.child(LedgerRuleName.LEDGER);
            ledger(ledger);
            mempoolFrame.subRule(ledger);
            return mempoolFrame.failures();
        }
        RuleFrame ledger = new RuleFrame(LedgerRuleName.LEDGER, ctx);
        ledger(ledger);
        return ledger.failures();
    }

    /** {@code conwayLedgerTransitionTRC} (Conway/Rules/Ledger.hs:350-440). */
    private void ledger(RuleFrame ledger) {
        if (ledger.context().raw().isValid()) {
            ledgerPreChecks.apply(ledger);
            certs.apply(ledger);
            gov.apply(ledger);
        }
        utxow.apply(ledger);
    }
}
