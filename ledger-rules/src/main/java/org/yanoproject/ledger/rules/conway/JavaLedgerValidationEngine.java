package org.yanoproject.ledger.rules.conway;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.effects.TxEffectsDeriver;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.TxDecodingException;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code java} engine (ADR-056 §4, §7): Yano's own Conway rules, {@link ConwayLedgerTransition}, over the
 * request's {@link LedgerView}, with Plutus scripts run by the node's {@link ScriptPhaseEvaluator}.
 *
 * <ul>
 *   <li><b>Scope</b>: Conway at protocol major version 10 or 11 (invariant 7); anything else is
 *       {@code ENGINE.EraNotSupported}, never "accept".</li>
 *   <li><b>Decoding</b>: the transaction is read from its original bytes ({@link RawTransaction}); bytes Haskell
 *       would not decode are {@code ENGINE.DecodingFailure}.</li>
 *   <li><b>Mode</b>: full validation or re-application, decided from {@code previous} ({@link ReapplyPolicy}).</li>
 *   <li><b>Fail closed</b>: an unavailable read is {@code ENGINE.LedgerStateUnavailable}; any other exception is
 *       {@code ENGINE.JavaEngineFailure}.</li>
 *   <li><b>Origin policy</b> (§6): an {@code is_valid = false} transaction that the rules accept is rejected
 *       with {@code ENGINE.Phase2InvalidTxNotSupported} from every origin except {@code SYNC}.</li>
 *   <li><b>Effects</b> come from {@link TxEffectsDeriver} for the verdict (invariant 4).</li>
 * </ul>
 *
 * <p>Phase 3a implements {@code UTXO} and {@code UTXOS}; {@code UTXOW}, {@code CERTS}, {@code GOV} and the
 * {@code LEDGER} pre-checks follow in Phases 3b–5, so the engine is not selectable for production admission
 * yet ({@link JavaEngineFactory}). Thread-safe and stateless.</p>
 */
public final class JavaLedgerValidationEngine implements LedgerValidationEngine {

    public static final String NAME = "java";
    /** Engine constructor: the bytes do not decode as a Conway transaction. */
    public static final String DECODING_FAILURE = "DecodingFailure";
    /** Engine constructor: the engine failed without a ledger verdict. */
    public static final String ENGINE_FAILURE = "JavaEngineFailure";
    /** The protocol versions the engine validates (invariant 7): Conway after the bootstrap phase. */
    public static final PvRange SUPPORTED = PvRange.between(10, 11);

    private final ScriptPhaseEvaluator evaluator;
    private final ConwayLedgerConstants constants;
    private final ConwayLedgerTransition transition;
    private final TxEffectsDeriver effectsDeriver = new TxEffectsDeriver();

    /** @param evaluator the phase-2 evaluator, or null (transactions with redeemers then fail closed) */
    public JavaLedgerValidationEngine(ScriptPhaseEvaluator evaluator) {
        this(evaluator, ConwayLedgerConstants.HASKELL, ConwayLedgerTransition.standard());
    }

    public JavaLedgerValidationEngine(ScriptPhaseEvaluator evaluator, ConwayLedgerConstants constants,
                                      ConwayLedgerTransition transition) {
        this.evaluator = evaluator;
        this.constants = Objects.requireNonNull(constants, "constants");
        this.transition = Objects.requireNonNull(transition, "transition");
    }

    /** @return this engine with other hardcoded constants (conformance fixtures that move them) */
    public JavaLedgerValidationEngine withConstants(ConwayLedgerConstants other) {
        return new JavaLedgerValidationEngine(evaluator, other, transition);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public TxValidationOutcome validate(TxValidationRequest request) {
        try {
            return run(request);
        } catch (LedgerStateUnavailableException e) {
            return TxValidationOutcome.Invalid.of(LedgerFailure.ledgerStateUnavailable(e.getMessage()));
        } catch (TxDecodingException e) {
            return engine(DECODING_FAILURE, e.getMessage());
        } catch (RuntimeException | LinkageError e) {
            // LinkageError: a phase-2 evaluator whose native parts are missing (for example BLS builtins in a
            // native image) must not escape the engine; the transaction is rejected, never admitted.
            return engine(ENGINE_FAILURE, e.toString());
        }
    }

    private TxValidationOutcome run(TxValidationRequest request) {
        byte[] txCbor = request.txCbor();
        LedgerView view = request.view();
        ValidationEnv env = request.env();
        ProtocolParams params = view.protocolParams().require("protocol parameters");
        int major = params.getProtocolMajorVer() != null ? params.getProtocolMajorVer() : env.protocolMajor();
        if (!SUPPORTED.contains(major)) {
            return TxValidationOutcome.Invalid.of(LedgerFailure.eraNotSupported(
                    "the java engine validates Conway at protocol versions " + SUPPORTED + ", not " + major));
        }

        Transaction tx;
        try {
            tx = Transaction.deserialize(txCbor);
        } catch (Exception e) {
            return engine(DECODING_FAILURE, "the transaction does not decode: " + e.getMessage());
        }
        RawTransaction raw = RawTransaction.parse(txCbor, tx);
        Map<TxInRef, UtxoEntry> resolved = TransitionContext.resolve(raw, view);
        byte[] resolvedDigest = ReapplyPolicy.resolvedInputsDigest(raw.allInputs(), resolved);
        ValidatedTx previous = request.previous();
        ReapplyPolicy.Decision decision = ReapplyPolicy.decide(previous, raw.txId(), raw.isValid(), major, env,
                request.origin(), resolvedDigest);
        TransitionContext ctx = new TransitionContext(raw, view, env, params, major,
                decision.reapply() ? TransitionContext.Mode.REAPPLY : TransitionContext.Mode.FULL, request.rule(),
                constants, evaluator, resolved);

        List<LedgerFailure> failures = transition.apply(ctx);
        if (!failures.isEmpty()) {
            return new TxValidationOutcome.Invalid(failures);
        }
        boolean phase2Valid = raw.isValid();
        if (!phase2Valid && request.origin() != TxValidationRequest.Origin.SYNC) {
            return TxValidationOutcome.Invalid.of(LedgerFailure.phase2InvalidTxNotSupported(
                    "is_valid = false transactions are not admitted (ADR-056 §6)"));
        }
        TxEffects effects;
        try {
            effects = effectsDeriver.derive(txCbor, tx, raw.txIdHex(), view, env, phase2Valid);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return engine(ENGINE_FAILURE, "the rules accepted the transaction but its effects cannot be derived: "
                    + e.getMessage());
        }
        ValidatedTx validated = decision.reapply() ? previous
                : new ValidatedTx(txCbor, raw.txId(), major, env.currentEpoch(), env.phase2EnvDigest(), phase2Valid,
                request.origin(), resolvedDigest);
        return new TxValidationOutcome.Valid(effects, validated, decision.reapply());
    }

    private static TxValidationOutcome engine(String constructor, String detail) {
        return TxValidationOutcome.Invalid.of(new LedgerFailure(LedgerRuleName.ENGINE, constructor,
                LedgerFailure.Phase.PHASE_1, detail));
    }
}
