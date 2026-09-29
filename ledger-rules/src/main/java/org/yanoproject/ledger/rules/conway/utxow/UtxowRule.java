package org.yanoproject.ledger.rules.conway.utxow;

import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.RuleFrame;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayScopes;
import org.yanoproject.ledger.rules.conway.utxo.UtxoRule;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;

/**
 * The Conway {@code UTXOW} rule ({@code babbageUtxowTransition}, Babbage/Rules/Utxow.hs:328-391, used by Conway,
 * Conway/Rules/Utxow.hs:198): the {@link ConwayScopes#UTXOW} units of the protocol version's rule set
 * ({@link UtxowChecks}) in Haskell's order, then the {@code UTXO} sub-rule (:391).
 *
 * <ol>
 *   <li>the engine's script preparation ({@code UTXOW.prepareScripts});</li>
 *   <li>{@code validateFailedBabbageScripts} (:349, dynamic): {@code ScriptWitnessNotValidatingUTXOW};</li>
 *   <li>{@code babbageMissingScripts} (:354, dynamic): {@code ExtraneousScriptWitnessesUTXOW},
 *       {@code MissingScriptWitnessesUTXOW};</li>
 *   <li>{@code missingRequiredDatums} (:357, dynamic): {@code UnspendableUTxONoDatumHash},
 *       {@code MissingRequiredDatums}, {@code NotAllowedSupplementalDatums};</li>
 *   <li>{@code hasExactSetOfRedeemers} (:361, dynamic): {@code ExtraRedeemers}, {@code MissingRedeemers};</li>
 *   <li>{@code validateVerifiedWits} (:366, static): {@code InvalidWitnessesUTXOW};</li>
 *   <li>{@code validateNeededWitnesses} (:369, dynamic): {@code MissingVKeyWitnessesUTXOW};</li>
 *   <li>{@code validateMetadata} (:374, static): {@code MissingTxMetadata}, {@code MissingTxBodyMetadataHash},
 *       {@code ConflictingMetadataHash}, {@code InvalidMetadata};</li>
 *   <li>{@code validateScriptsWellFormed} (:379, static): {@code MalformedScriptWitnesses},
 *       {@code MalformedReferenceScripts};</li>
 *   <li>{@code checkScriptIntegrityHash} (:389, dynamic): {@code PPViewHashesDontMatch} before protocol version
 *       11, {@code ScriptIntegrityHashMismatch} from 11 (the protocol version 11 delta supersedes the unit).</li>
 * </ol>
 *
 * <p>The rule reads the state before the transaction (invariant 5); in Conway no witness check reads the certificate
 * state ({@link WitnessNeeds}).</p>
 *
 * <p><b>Scripts.</b> Native scripts are evaluated here. Plutus scripts are judged well formed by
 * {@link PlutusScriptDecoder} (plutus-ledger-api's {@code deserialiseScript}) and, when the node has one, also by the
 * phase-2 evaluator ({@link ScriptPhaseEvaluator#isWellFormed}); their contexts are collected by the evaluator
 * ({@link ScriptPhaseEvaluator#collect}, the {@code CollectErrors} that {@code UTXOS} reports) when the transaction
 * needs a Plutus script it provides; without an evaluator such a transaction fails closed.</p>
 */
public final class UtxowRule {

    private UtxowRule() {
    }

    /** Runs {@code UTXOW} (and {@code UTXO}, {@code UTXOS} under it) as a sub-rule of {@code LEDGER}. */
    public static void apply(RuleFrame ledger) {
        RuleFrame utxow = ledger.child(LedgerRuleName.UTXOW);
        utxow.run(ConwayScopes.UTXOW, UtxowSubject.of(utxow.context()));
        UtxoRule.apply(utxow);
        ledger.subRule(utxow);
    }
}
