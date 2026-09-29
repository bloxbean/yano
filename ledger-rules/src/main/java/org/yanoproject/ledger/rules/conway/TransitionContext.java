package org.yanoproject.ledger.rules.conway;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.conway.failure.ConwayPredicate;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.effects.IntraTxFold;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Everything one run of {@link ConwayLedgerTransition} reads: the transaction (original bytes and CCL
 * structure), the pre-transaction state, the environment, the protocol version that selects PV gates, the
 * validation mode, and the STS "is failing" flag that {@code whenFailureFree} consults.
 *
 * <p>The UTxO entries of every spending, collateral and reference input are read once, up front: an
 * {@link Lookup.Unavailable} read rejects the transaction (invariant 2) before any rule runs, and every rule
 * then sees the same answer. {@code UTXOW}/{@code UTXO}/{@code UTXOS} read {@link #preState()}, the state
 * before the transaction's certificates (invariant 5); {@code CERTS} and {@code GOV} read and advance
 * {@link #certState()}, the intra-transaction state in body order.</p>
 */
public final class TransitionContext {

    /** Full validation, or re-application of a validated transaction (ADR-056 §6, Haskell {@code reapplyTx}). */
    public enum Mode {
        FULL,
        REAPPLY
    }

    private final RawTransaction raw;
    private final LedgerView preState;
    private final ValidationEnv env;
    private final ProtocolParams params;
    private final int protocolMajor;
    private final Mode mode;
    private final TxValidationRequest.Rule rule;
    private final ConwayLedgerConstants constants;
    private final ScriptPhaseEvaluator evaluator;
    private final Map<TxInRef, UtxoEntry> utxo;
    private boolean failing;
    private List<LedgerFailure> collectFailures = List.of();
    private IntraTxFold certState;

    /**
     * @param resolved the UTxO entries of the inputs, from {@link #resolve(RawTransaction, LedgerView)}
     */
    public TransitionContext(RawTransaction raw, LedgerView preState, ValidationEnv env, ProtocolParams params,
                             int protocolMajor, Mode mode, TxValidationRequest.Rule rule,
                             ConwayLedgerConstants constants, ScriptPhaseEvaluator evaluator,
                             Map<TxInRef, UtxoEntry> resolved) {
        this.raw = Objects.requireNonNull(raw, "raw");
        this.preState = Objects.requireNonNull(preState, "preState");
        this.env = Objects.requireNonNull(env, "env");
        this.params = Objects.requireNonNull(params, "params");
        this.protocolMajor = protocolMajor;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.rule = Objects.requireNonNull(rule, "rule");
        this.constants = Objects.requireNonNull(constants, "constants");
        this.evaluator = evaluator;
        this.utxo = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(resolved, "resolved")));
    }

    /**
     * Reads the UTxO entries of all inputs ({@code allInputsTxBodyF}).
     *
     * @throws LedgerStateUnavailableException when any read is unavailable
     */
    public static Map<TxInRef, UtxoEntry> resolve(RawTransaction raw, LedgerView view) {
        Map<TxInRef, UtxoEntry> found = new TreeMap<>();
        for (TxInRef in : raw.allInputs()) {
            switch (view.utxo(in.outpoint())) {
                case Lookup.Present<UtxoEntry> p -> found.put(in, p.value());
                case Lookup.Absent<UtxoEntry> a -> {
                    // Not in the UTxO: BadInputsUTxO, and it contributes nothing to any sum (txInsFilter).
                }
                case Lookup.Unavailable<UtxoEntry> u ->
                        throw new LedgerStateUnavailableException("utxo " + in + ": " + u.reason());
            }
        }
        return Collections.unmodifiableMap(found);
    }

    public RawTransaction raw() {
        return raw;
    }

    /** @return the decoded CCL transaction (structure only) */
    public Transaction tx() {
        return raw.decoded();
    }

    /** @return the state before the transaction (pre-certificate, invariant 5) */
    public LedgerView preState() {
        return preState;
    }

    public ValidationEnv env() {
        return env;
    }

    /** @return the epoch-effective protocol parameters (from the view) */
    public ProtocolParams params() {
        return params;
    }

    /** @return the protocol major version that selects PV gates */
    public int protocolMajor() {
        return protocolMajor;
    }

    public Mode mode() {
        return mode;
    }

    public TxValidationRequest.Rule rule() {
        return rule;
    }

    public ConwayLedgerConstants constants() {
        return constants;
    }

    /** @return the phase-2 evaluator, or null when the node has none */
    public ScriptPhaseEvaluator evaluator() {
        return evaluator;
    }

    /** @return the UTxO entry of an input, empty when it is not in the UTxO */
    public Optional<UtxoEntry> utxo(TxInRef in) {
        return Optional.ofNullable(utxo.get(in));
    }

    /** @return the resolved spending, collateral and reference inputs, for the phase-2 evaluator */
    public Map<Outpoint, UtxoEntry> resolvedInputs() {
        Map<Outpoint, UtxoEntry> result = new LinkedHashMap<>();
        utxo.forEach((in, entry) -> result.put(in.outpoint(), entry));
        return result;
    }

    /**
     * The slot the phase-2 evaluator's {@code ForecastHorizon} is computed from: Haskell's script context
     * translates slots with the hard-fork combinator's epoch info of the ledger state the transaction is applied
     * to, whose horizon is based on {@code next(tip)} of that state (ouroboros-consensus
     * {@code HardFork/History/Summary.hs:370-404}).
     *
     * <p>This is {@link ValidationEnv#currentSlot()}. For rule {@code MEMPOOL} (admission, mempool rebuilds) that is
     * exactly {@code next(tip)}. For block validation the basis should be the slot after the <em>previous</em>
     * block, while {@code currentSlot} is the block's own slot, which can only move the horizon later (more
     * lenient). No block path uses the Java engine yet: Phase 6 (block building) and Phase 7 (shadow sync) must
     * carry the tip explicitly before they do. The Amaru fixtures validate at their tip, so the conformance
     * harness is exact.</p>
     */
    public long forecastBasisSlot() {
        return env.currentSlot();
    }

    /** @return the resolved inputs keyed as the body names them */
    public Map<TxInRef, UtxoEntry> resolvedByInput() {
        return utxo;
    }

    /** Haskell {@code IsFailing}: some rule of this transition already recorded a failure. */
    public boolean failing() {
        return failing;
    }

    void markFailing() {
        failing = true;
    }

    /**
     * Whether a check runs: its PV gate must include the protocol version, and a {@code static} check is skipped
     * on re-application ({@code lblStatic}, ADR-056 §6).
     */
    public boolean runs(ConwayPredicate predicate) {
        return predicate.pvRange().contains(protocolMajor)
                && (mode == Mode.FULL || predicate.label() == CheckLabel.DYNAMIC);
    }

    /**
     * Runs one check ({@code runTest} / {@code runTestOnSignal}) when {@link #runs(ConwayPredicate)} allows it.
     *
     * @param check returns the failure's detail when the check fails, or null when it holds
     */
    public void check(RuleFrame frame, ConwayPredicate predicate, Supplier<String> check) {
        if (!runs(predicate)) {
            return;
        }
        String detail = check.get();
        if (detail != null) {
            frame.fail(predicate.failure(detail));
        }
    }

    /** @return the {@code CollectErrors} the evaluator found while {@code UTXOW} prepared the scripts */
    public List<LedgerFailure> collectFailures() {
        return collectFailures;
    }

    public void collectFailures(List<LedgerFailure> failures) {
        this.collectFailures = List.copyOf(failures);
    }

    /**
     * The certificate state as the {@code LEDGER} branch threads it (ADR-056 invariant 5): the pre-transaction
     * state, then — once {@code CERTS} ran — the state after the pre-certificate step (Haskell's {@code CERTS}
     * base case before protocol version 11, the {@code LEDGER} step from 11) and after each certificate, in body
     * order. {@code GOV} reads it after {@code CERTS} ({@code certStateAfterCERTS}, Conway/Rules/Ledger.hs:394-421).
     *
     * @return the fold; a fold with no steps over {@link #preState()} until a rule advances it
     */
    public IntraTxFold certState() {
        if (certState == null) {
            certState = IntraTxFold.start(raw.txIdHex(), preState);
        }
        return certState;
    }

    /** Replaces the certificate state with a fold that advanced {@link #certState()}. */
    public void certState(IntraTxFold advanced) {
        this.certState = Objects.requireNonNull(advanced, "advanced");
    }
}
