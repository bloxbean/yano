package org.yanoproject.scalusbridge;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.phase2.ForecastHorizon;
import org.yanoproject.ledger.rules.phase2.ScriptCollection;
import org.yanoproject.ledger.rules.phase2.ScriptCollection.NeededScript;
import org.yanoproject.ledger.rules.phase2.ScriptOutcome;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import scala.util.Either;

/**
 * The Scalus {@link ScriptPhaseEvaluator} (ADR-056 §5, step 1d): runs every needed Plutus script on the
 * Scalus CEK machine with its redeemer's declared ExUnits as budget.
 *
 * <p>Before any script runs it reports, as phase-1 {@link ScriptPhaseResult.Rejected} failures, the checks
 * Haskell makes while preparing script contexts ({@link ScriptCollection}: {@code MalformedScriptWitnesses},
 * {@code MalformedReferenceScripts} and {@code CollectErrors}), which Amaru's {@code phase_one} mode does not (ADR-057
 * Phase B deviation 9). The Java engine's {@code UTXOW} owns the two malformed-script checks (ADR-056 Phase 3b): it
 * asks {@link #isWellFormed} per script, and {@link #collect} reports only the {@code CollectErrors}; {@link #evaluate}
 * still refuses to run a malformed script. A malformed reference script of a resolved input fails when it is decoded
 * to run ({@code decodePlutusRunnable}): a phase-2 failure, {@link ScriptPhaseResult.Failed}.</p>
 *
 * <p>Then every script runs; the first failure (Scalus stops there) makes the result
 * {@link ScriptPhaseResult.Failed}. Comparing with {@code is_valid} is the engine's job. Anything else Scalus
 * throws (a missing datum or script that phase one should have caught, BLS builtins unavailable) propagates,
 * and the engine fails closed. Thread-safe and stateless.</p>
 */
public final class ScalusScriptPhaseEvaluator implements ScriptPhaseEvaluator {

    /**
     * {@code ENGINE} constructor (not a ledger verdict): the transaction holds an integer in {@code [2^63, 2^64)} that
     * the bridge cannot carry through Scalus's decoder into the script contexts ({@link WideIntegers}: a validity
     * slot, a wide {@code Constr} alternative in a witness datum, a wide negative body integer), so the scripts cannot
     * be evaluated. The evaluator fails closed. Wide coins, quantities, other body amounts, datum and redeemer
     * constructor alternatives and metadata integers are evaluated exactly (ADR-056 Phase 7c).
     */
    public static final String INTEGER_OUT_OF_EVALUATOR_RANGE = "IntegerOutOfEvaluatorRange";

    private final ForecastHorizon horizon;

    /** An evaluator without a forecast horizon: {@code TimeTranslationPastHorizon} is not checked. */
    public ScalusScriptPhaseEvaluator() {
        this(null);
    }

    /** @param horizon the network's forecast horizon, or {@code null} to skip that check */
    public ScalusScriptPhaseEvaluator(ForecastHorizon horizon) {
        this.horizon = horizon;
    }

    @Override
    public ScriptPhaseResult evaluate(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                      ProtocolParams params, SlotConfig slotConfig) {
        return evaluate(txCbor, tx, resolvedInputs, params, slotConfig, -1);
    }

    /**
     * @param validationSlot the slot after the ledger tip, for the forecast-horizon check; negative skips it
     */
    @Override
    public ScriptPhaseResult evaluate(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                      ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
        Preparation preparation = prepare(txCbor, tx, resolvedInputs, params, slotConfig, validationSlot, true);
        if (!preparation.failures().isEmpty()) {
            return new ScriptPhaseResult.Rejected(preparation.failures());
        }
        if (preparation.needed().isEmpty()) {
            return new ScriptPhaseResult.Passed(List.of());
        }
        // Scalus runs a program of any Plutus Core version; the ledger does not (ScriptCollection).
        List<ScriptOutcome> unavailable = ScriptCollection.plutusCoreVersionFailures(preparation.needed(),
                ScriptCollection.protocolMajor(params));
        if (!unavailable.isEmpty()) {
            return new ScriptPhaseResult.Failed(unavailable);
        }
        ScalusPhaseTwo.Evaluation evaluation = ScalusPhaseTwo.evaluate(preparation.prepared(), params, slotConfig);
        return evaluation.passed()
                ? new ScriptPhaseResult.Passed(evaluation.scripts())
                : new ScriptPhaseResult.Failed(evaluation.scripts());
    }

    /**
     * The {@code CollectErrors} {@link #evaluate} finds before running any script, without running one. The
     * malformed-script checks are left out: the Java engine's {@code UTXOW} makes them itself
     * ({@link #isWellFormed}, ADR-056 Phase 3b).
     */
    @Override
    public List<LedgerFailure> collect(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                       ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
        return prepare(txCbor, tx, resolvedInputs, params, slotConfig, validationSlot, false).failures();
    }

    /** Haskell {@code isValidPlutusScript}, with Scalus's Plutus decoder ({@code PlutusScript.isWellFormed}). */
    @Override
    public boolean isWellFormed(int language, byte[] script, int protocolMajor) {
        return ScalusPhaseTwo.isWellFormed(language, script, protocolMajor);
    }

    /**
     * True: at protocol version 9 a transaction with a {@code reg_cert} or {@code unreg_cert} runs over the
     * bootstrap-phase PlutusV3 context ({@code BootstrapPhaseContexts}, Conway/TxInfo.hs:572-581), not Scalus's.
     */
    @Override
    public boolean translatesBootstrapPhaseCertificateDeposits() {
        return true;
    }

    /**
     * What the preparation found: failures (malformed scripts, or CollectErrors), the scripts to run, and the
     * transaction decoded for Scalus ({@code null} after an out-of-range failure).
     */
    private record Preparation(List<LedgerFailure> failures, List<NeededScript> needed,
                               ScalusPhaseTwo.Prepared prepared) {
    }

    private Preparation prepare(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                ProtocolParams params, SlotConfig slotConfig, long validationSlot,
                                boolean checkWellFormed) {
        Objects.requireNonNull(txCbor, "txCbor");
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(resolvedInputs, "resolvedInputs");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(slotConfig, "slotConfig");
        int major = ScriptCollection.protocolMajor(params);
        int minor = ScriptCollection.protocolMinor(params);

        // Scalus's decoder reads Word64 coins and quantities, tag-102 Constr alternatives and metadatum integers as a
        // signed long: WideIntegers narrows them for decoding and the evaluation restores them in every context.
        Either<String, ScalusPhaseTwo.Prepared> decoded = ScalusPhaseTwo.prepare(txCbor, resolvedInputs.values(),
                major, minor);
        if (decoded.isLeft()) {
            return new Preparation(List.of(new LedgerFailure(LedgerRuleName.ENGINE, INTEGER_OUT_OF_EVALUATOR_RANGE,
                    LedgerFailure.Phase.PHASE_1, decoded.left().get() + ": Scalus decodes it as a signed long, so the "
                    + "scripts cannot be evaluated")), List.of(), null);
        }
        ScalusPhaseTwo.Prepared prepared = decoded.toOption().get();

        List<LedgerFailure> malformed = checkWellFormed
                ? ScriptCollection.malformedScripts(ScalusPhaseTwo.malformedWitnessScripts(prepared, major),
                ScalusPhaseTwo.malformedOutputReferenceScripts(prepared, major))
                : List.of();
        if (!malformed.isEmpty()) {
            return new Preparation(malformed, List.of(), prepared);
        }

        List<NeededScript> needed = ScalusPhaseTwo.neededPlutusScripts(prepared);
        List<String> collectErrors = ScriptCollection.collectErrors(tx, resolvedInputs, params, major, needed,
                ScriptCollection.horizonSlot(horizon, validationSlot));
        if (!collectErrors.isEmpty()) {
            return new Preparation(List.of(ScriptCollection.collectErrorsFailure(collectErrors)), needed, prepared);
        }
        return new Preparation(List.of(), needed, prepared);
    }
}
