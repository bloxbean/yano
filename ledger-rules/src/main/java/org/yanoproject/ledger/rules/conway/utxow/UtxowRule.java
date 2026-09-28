package org.yanoproject.ledger.rules.conway.utxow;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.conway.utxo.MinFee;
import org.yanoproject.ledger.rules.conway.utxo.UtxoRule;
import org.yanoproject.ledger.rules.conway.utxos.UtxosRule;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Conway {@code UTXOW} rule ({@code babbageUtxowTransition}, Babbage/Rules/Utxow.hs:308-391, used by Conway,
 * Conway/Rules/Utxow.hs:198): the witness checks, then the {@code UTXO} sub-rule (:391).
 *
 * <p><b>Phase 3a.</b> Only the script preparation runs here: the phase-2 evaluator's preparation step reports
 * {@code MalformedScriptWitnesses} / {@code MalformedReferenceScripts} (Babbage/Rules/Utxow.hs:264-273, 379,
 * static) and the {@code CollectErrors} that {@code UTXOS} reports later. The vkey, native-script, script,
 * datum, redeemer, metadata and script-integrity checks are Phase 3b; they go before
 * {@link UtxoRule#apply(RuleFrame)} in Haskell's order (:338-389).</p>
 */
public final class UtxowRule {

    private UtxowRule() {
    }

    /** Runs {@code UTXOW} (and {@code UTXO}, {@code UTXOS} under it) as a sub-rule of {@code LEDGER}. */
    public static void apply(RuleFrame ledger) {
        RuleFrame utxow = ledger.child(LedgerRuleName.UTXOW);
        prepareScripts(utxow);
        UtxoRule.apply(utxow);
        ledger.subRule(utxow);
    }

    /**
     * Asks the evaluator to prepare the scripts (without running them): its {@code UTXOW} failures are recorded
     * here, its {@code CollectErrors} are kept for {@code UTXOS}.
     */
    static void prepareScripts(RuleFrame utxow) {
        TransitionContext ctx = utxow.context();
        RawTransaction raw = ctx.raw();
        if (!involvesPlutus(ctx)) {
            return;
        }
        ScriptPhaseEvaluator evaluator = ctx.evaluator();
        if (evaluator == null) {
            utxow.fail(new LedgerFailure(LedgerRuleName.ENGINE, UtxosRule.PHASE_TWO_UNAVAILABLE,
                    LedgerFailure.Phase.PHASE_1, "the transaction involves Plutus scripts and the node has no "
                    + "phase-2 evaluator"));
            return;
        }
        List<LedgerFailure> prepared = evaluator.collect(raw.txCbor(), ctx.tx(), ctx.resolvedInputs(), ctx.params(),
                ctx.env().slotConfig(), ctx.forecastBasisSlot());
        List<LedgerFailure> witnessFailures = new ArrayList<>();
        List<LedgerFailure> collectFailures = new ArrayList<>();
        for (LedgerFailure failure : prepared) {
            (failure.rule() == LedgerRuleName.UTXOW ? witnessFailures : collectFailures).add(failure);
        }
        // MalformedScriptWitnesses / MalformedReferenceScripts are static (validateScriptsWellFormed, :379).
        if (ctx.mode() == TransitionContext.Mode.FULL) {
            utxow.predicate(witnessFailures);
        }
        ctx.collectFailures(collectFailures);
    }

    /**
     * Whether the transaction can need Plutus script preparation: it has redeemers, or a Plutus script is
     * provided, as a witness (keys 3, 6, 7) or as the reference script of a resolved spending or reference input
     * ({@code getBabbageScriptsProvided}), or an own output carries a reference script (well-formedness). A
     * needed Plutus script without a redeemer is then {@code CollectErrors [NoRedeemer …]}
     * ({@code scriptsWithContextFromLedgerTxInfoWithResult}, Alonzo/Plutus/Evaluate.hs:137-175), so the
     * preparation must also run for transactions without redeemers.
     */
    static boolean involvesPlutus(TransitionContext ctx) {
        RawTransaction raw = ctx.raw();
        if (raw.hasRedeemers()
                || raw.witnessFields().containsKey(RawTransaction.WITNESS_PLUTUS_V1)
                || raw.witnessFields().containsKey(RawTransaction.WITNESS_PLUTUS_V2)
                || raw.witnessFields().containsKey(RawTransaction.WITNESS_PLUTUS_V3)
                || raw.allOutputs().stream().anyMatch(RawOutput::hasScriptRef)) {
            return true;
        }
        Set<TxInRef> providing = new TreeSet<>(raw.inputSet());
        providing.addAll(raw.referenceSet());
        for (TxInRef in : providing) {
            Optional<UtxoEntry> entry = ctx.utxo(in);
            if (entry.isPresent() && entry.get().output().getScriptRef() != null
                    && MinFee.isPlutusScript(entry.get().output().getScriptRef())) {
                return true;
            }
        }
        return false;
    }
}
