package org.yanoproject.ledger.scripteval.phase2;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.tx.RawScript;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.conway.utxow.UtxowSubject;
import org.yanoproject.ledger.rules.conway.utxow.WitnessNeeds;
import org.yanoproject.ledger.rules.phase2.ForecastHorizon;
import org.yanoproject.ledger.rules.phase2.ScriptCollection;
import org.yanoproject.ledger.rules.phase2.ScriptCollection.NeededScript;
import org.yanoproject.ledger.rules.phase2.ScriptOutcome;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.ledger.scripteval.phase2.ConwayTxInfoTranslator.Redeemer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The julc {@link ScriptPhaseEvaluator} (ADR-056 Phase 7c): the phase-2 contract of {@link ScriptCollection}, with the
 * script contexts built by Yano from the transaction's original bytes ({@link ConwayTxInfoTranslator}) and the scripts
 * run on julc's CEK machine ({@link JulcMachine}). The phase 2 of engine {@code java-julc}; Scalus's is engine
 * {@code java-scalus}. The context builder shares nothing with Scalus's {@code LedgerToPlutusTranslation}, the machine
 * nothing with Scalus's CEK.
 *
 * <p>julc's own {@code JulcTransactionEvaluator} is not used: it estimates ExUnits (the transaction's maximum is the
 * budget) and its context builder differs from cardano-ledger in ways a validator cannot accept (ADR-056 Phase 7c,
 * "Phase 2 evaluator: Julc"); only julc's machine, cost models and script decoder are.</p>
 *
 * <p>Every needed Plutus script runs, in {@code getConwayScriptsNeeded} order, with its redeemer's declared ExUnits as
 * the budget; the first failure makes the result {@link ScriptPhaseResult.Failed}. Comparing with {@code is_valid} is
 * the engine's job. Integers are {@link java.math.BigInteger}s throughout, so a Word64 above 2^63 needs no special
 * case. Thread-safe.</p>
 */
public final class JulcScriptPhaseEvaluator implements ScriptPhaseEvaluator {

    private final ForecastHorizon horizon;
    private final JulcMachine machine = new JulcMachine();

    /** An evaluator without a forecast horizon: {@code TimeTranslationPastHorizon} is not checked. */
    public JulcScriptPhaseEvaluator() {
        this(null);
    }

    /** @param horizon the network's forecast horizon, or {@code null} to skip that check */
    public JulcScriptPhaseEvaluator(ForecastHorizon horizon) {
        this.horizon = horizon;
    }

    @Override
    public ScriptPhaseResult evaluate(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                      ProtocolParams params, SlotConfig slotConfig) {
        return evaluate(txCbor, tx, resolvedInputs, params, slotConfig, -1);
    }

    @Override
    public ScriptPhaseResult evaluate(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                      ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
        return evaluate(RawTransaction.parse(txCbor, tx), resolvedInputs, params, slotConfig, validationSlot);
    }

    @Override
    public ScriptPhaseResult evaluate(RawTransaction raw, Map<Outpoint, UtxoEntry> resolvedInputs,
                                      ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
        Objects.requireNonNull(slotConfig, "slotConfig");
        Preparation preparation = prepare(raw, resolvedInputs, params, validationSlot, true);
        if (!preparation.failures().isEmpty()) {
            return new ScriptPhaseResult.Rejected(preparation.failures());
        }
        int major = ScriptCollection.protocolMajor(params);
        int minor = ScriptCollection.protocolMinor(params);
        List<ScriptOutcome> unavailable = ScriptCollection.plutusCoreVersionFailures(preparation.plutus(), major);
        if (!unavailable.isEmpty()) {
            return new ScriptPhaseResult.Failed(unavailable);
        }
        ConwayTxInfoTranslator translator = new ConwayTxInfoTranslator(raw, resolvedInputs, major, slotConfig);
        List<ScriptOutcome> outcomes = new ArrayList<>();
        for (Needed needed : preparation.needed()) {
            RawScript script = needed.script();
            Redeemer redeemer = translator.redeemer(needed.tag(), needed.index()).orElseThrow();
            JulcMachine.Outcome outcome = machine.run(script.hashHex(), script.language(), script.bytes(), major, minor,
                    ScriptCollection.costModel(params, script.language()),
                    translator.arguments(script.language(), redeemer), ScriptCollection.budget(redeemer.mem()),
                    ScriptCollection.budget(redeemer.steps()));
            String purpose = ScriptCollection.purposeName(needed.tag());
            outcomes.add(new ScriptOutcome(purpose, (int) needed.index(), outcome.success(), outcome.mem(),
                    outcome.steps(), outcome.logs(), outcome.success() ? null : "script " + script.hashHex()
                    + " (" + script.languageName() + ", " + purpose + "[" + needed.index() + "]): " + outcome.error()));
            if (!outcome.success()) {
                return new ScriptPhaseResult.Failed(outcomes);
            }
        }
        return new ScriptPhaseResult.Passed(outcomes);
    }

    /** The {@code CollectErrors} {@link #evaluate} finds before running any script, without running one. */
    @Override
    public List<LedgerFailure> collect(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                       ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
        return collect(RawTransaction.parse(txCbor, tx), resolvedInputs, params, slotConfig, validationSlot);
    }

    @Override
    public List<LedgerFailure> collect(RawTransaction raw, Map<Outpoint, UtxoEntry> resolvedInputs,
                                       ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
        return prepare(raw, resolvedInputs, params, validationSlot, false).failures();
    }

    /** Haskell {@code isValidPlutusScript}, with julc's script decoder and builtin availability. */
    @Override
    public boolean isWellFormed(int language, byte[] script, int protocolMajor) {
        return machine.isWellFormed(language, script, protocolMajor);
    }

    /**
     * True: the PlutusV3 context of a protocol version 9 transaction leaves out the deposit of {@code reg_cert} and the
     * refund of {@code unreg_cert} ({@code transTxCert}, Conway/TxInfo.hs:572-581), in the certificates, the redeemer
     * map and the certifying {@code ScriptInfo}.
     */
    @Override
    public boolean translatesBootstrapPhaseCertificateDeposits() {
        return true;
    }

    /** A needed, provided Plutus script with a redeemer, to run. */
    private record Needed(int tag, long index, RawScript script) {
    }

    private record Preparation(List<LedgerFailure> failures, List<Needed> needed, List<NeededScript> plutus) {
    }

    private Preparation prepare(RawTransaction raw, Map<Outpoint, UtxoEntry> resolvedInputs, ProtocolParams params,
                                long validationSlot, boolean checkWellFormed) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(resolvedInputs, "resolvedInputs");
        Objects.requireNonNull(params, "params");
        int major = ScriptCollection.protocolMajor(params);

        if (checkWellFormed) {
            List<LedgerFailure> malformed = malformedScripts(raw, major);
            if (!malformed.isEmpty()) {
                return new Preparation(malformed, List.of(), List.of());
            }
        }

        Function<TxInRef, Optional<UtxoEntry>> utxo = in -> Optional.ofNullable(
                resolvedInputs.get(Outpoints.normalize(in.outpoint())));
        SortedMap<String, RawScript> provided = UtxowSubject.scriptsProvided(raw, utxo);
        List<NeededScript> neededPlutus = new ArrayList<>();
        List<Needed> needed = new ArrayList<>();
        for (WitnessNeeds.NeededScript script : WitnessNeeds.scriptsNeeded(raw, utxo)) {
            RawScript plutus = provided.get(script.hash());
            if (plutus == null || !plutus.isPlutus()) {
                continue; // native, or not provided (UTXOW's MissingScriptWitnessesUTXOW)
            }
            boolean hasRedeemer = raw.redeemers().stream()
                    .anyMatch(r -> r.tag() == script.tag() && r.index() == script.index());
            neededPlutus.add(new NeededScript(ScriptCollection.purposeName(script.tag()), script.index(),
                    script.hash(), plutus.language(), hasRedeemer, plutus.bytes()));
            needed.add(new Needed(script.tag(), script.index(), plutus));
        }
        List<String> errors = ScriptCollection.collectErrors(raw.decoded(), resolvedInputs, params, major, neededPlutus,
                ScriptCollection.horizonSlot(horizon, validationSlot));
        if (!errors.isEmpty()) {
            return new Preparation(List.of(ScriptCollection.collectErrorsFailure(errors)), needed, neededPlutus);
        }
        return new Preparation(List.of(), needed, neededPlutus);
    }

    /** The malformed Plutus witness scripts and reference scripts of the transaction's own outputs. */
    private List<LedgerFailure> malformedScripts(RawTransaction raw, int major) {
        TreeSet<String> witnesses = new TreeSet<>();
        for (RawScript script : raw.witnessScripts()) {
            if (script.isPlutus() && !machine.isWellFormed(script.language(), script.bytes(), major)) {
                witnesses.add(script.hashHex());
            }
        }
        TreeSet<String> references = new TreeSet<>();
        for (RawOutput output : raw.allOutputs()) {
            RawScript script = output.scriptRef();
            if (script != null && script.isPlutus() && !machine.isWellFormed(script.language(), script.bytes(), major)) {
                references.add(script.hashHex());
            }
        }
        return ScriptCollection.malformedScripts(witnesses, references);
    }
}
