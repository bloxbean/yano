package org.yanoproject.ledger.rules.conway.utxos;

import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayScopes;

/**
 * The Conway {@code UTXOS} rule ({@code utxosTransition}, Conway/Rules/Utxos.hs:207-242), which Haskell runs for
 * both validity flags: the {@link ConwayScopes#UTXOS} units of the protocol version's rule set ({@link UtxosChecks}) —
 * {@code CollectErrors}, then Plutus execution inside {@code when2Phase $ whenFailureFree}.
 */
public final class UtxosRule {

    /** Engine constructor: a transaction needs Plutus execution and the node has no evaluator. */
    public static final String PHASE_TWO_UNAVAILABLE = "PhaseTwoEvaluatorUnavailable";
    /**
     * Engine constructor: the evaluator cannot build the script context Haskell builds for this transaction at this
     * protocol version ({@link UtxosChecks.BootstrapPhasePlutusExecution}); the engine fails closed instead of running
     * the scripts over another context.
     */
    public static final String PHASE_TWO_CONTEXT_UNSUPPORTED = "PhaseTwoContextUnsupported";

    private UtxosRule() {
    }

    /** Runs {@code UTXOS} as a sub-rule of {@code UTXO}. */
    public static void apply(RuleFrame utxo) {
        RuleFrame utxos = utxo.child(LedgerRuleName.UTXOS);
        utxos.run(ConwayScopes.UTXOS, utxos.context());
        utxo.subRule(utxos);
    }
}
