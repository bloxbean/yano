package org.yanoproject.scalusbridge;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.SlotConfigSupplier;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.conway.ReapplyPolicy;
import org.yanoproject.ledger.rules.conway.mempool.MempoolRule;
import org.yanoproject.ledger.rules.conway.tx.TxDecodingException;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.effects.TxEffectsDeriver;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code scalus} engine behind the {@link LedgerValidationEngine} SPI (ADR-056 §6 "Scalus under
 * overlays", §7): Scalus's Conway rules ({@code YanoCardanoMutator}) run against the request's
 * {@link LedgerView} instead of the canonical stores.
 *
 * <ul>
 *   <li><b>UTxOs</b>: spending, reference and collateral inputs are resolved through the view. Absent inputs
 *       are left out, so Scalus reports {@code BadInputsUTxO}; an unavailable read rejects with
 *       {@code ENGINE.LedgerStateUnavailable} before Scalus runs.</li>
 *   <li><b>Account, pool and DRep state</b> come from {@link LedgerViewStateProvider}, which fails closed on
 *       unavailable reads.</li>
 *   <li><b>Protocol parameters</b> are the view's (so ticking changes them); the slot is the request's.</li>
 *   <li><b>Rule {@code MEMPOOL}</b> first runs the engine-neutral {@link MempoolRule}, as the Amaru engine
 *       does, so failure precedence is the same across engines.</li>
 *   <li><b>Failures</b> are Haskell-named by {@link ScalusFailureMapping}: only Plutus evaluation outcomes are
 *       phase 2 (the legacy validator's class-name heuristic labelled every failure whose class mentions
 *       "Script" as phase 2).</li>
 *   <li><b>Effects</b> come from {@link TxEffectsDeriver}. Scalus does not model governance, so proposals and
 *       votes enter an overlay without the GOV checks (ADR-056 invariant 4).</li>
 *   <li><b>Origin policy</b>: an {@code is_valid = false} transaction is rejected
 *       ({@code ENGINE.Phase2InvalidTxNotSupported}) from every origin except {@code SYNC} (ADR-056 §6).</li>
 * </ul>
 *
 * <p>The legacy {@link ScalusBasedTransactionValidator} is untouched and stays the default admission path;
 * this adapter is used only when {@code yano.validation.engine} or {@code shadow-engines} select the engine
 * API.</p>
 *
 * <p><b>Re-application</b> (ADR-056 §6, Phase 6a): the adapter records the resolved-inputs digest in its
 * {@code ValidatedTx}, and when {@link ReapplyPolicy} allows re-applying {@code previous} it runs Scalus's
 * dynamic rules only ({@code YanoCardanoMutator.reapply}: no signature, metadata, well-formedness, network or size
 * checks, and no Plutus execution).</p>
 */
public final class ScalusLedgerValidationEngine implements LedgerValidationEngine {

    private static final Logger log = LoggerFactory.getLogger(ScalusLedgerValidationEngine.class);

    public static final String NAME = "scalus";
    /** Engine constructor: Scalus failed without a ledger verdict (same as {@code ScalusFailureMapping}). */
    public static final String ENGINE_FAILURE = "ScalusEngineFailure";
    /** Engine constructor: the transaction does not decode. */
    public static final String DECODING_FAILURE = "DecodingFailure";

    private final SlotConfigSupplier epochGeometry;
    private final TxEffectsDeriver effectsDeriver = new TxEffectsDeriver();

    /**
     * @param epochGeometry supplies the epoch geometry Scalus's slot config carries next to the request's slot
     *                      timing; {@code null} uses the three-field slot config alone
     */
    public ScalusLedgerValidationEngine(SlotConfigSupplier epochGeometry) {
        this.epochGeometry = epochGeometry;
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
        } catch (TransactionDecodingException e) {
            return engine(DECODING_FAILURE, e.getMessage());
        } catch (LinkageError e) {
            if (ScalusNativeFailures.isBlsUnavailable(e)) {
                return engine(ScalusNativeFailures.BLS_UNAVAILABLE_RULE, ScalusNativeFailures.BLS_UNAVAILABLE_MESSAGE);
            }
            throw e;
        } catch (RuntimeException e) {
            if (ScalusNativeFailures.isBlsUnavailable(e)) {
                return engine(ScalusNativeFailures.BLS_UNAVAILABLE_RULE, ScalusNativeFailures.BLS_UNAVAILABLE_MESSAGE);
            }
            log.debug("Scalus engine failed closed", e);
            return engine(ENGINE_FAILURE, e.toString());
        }
    }

    private TxValidationOutcome run(TxValidationRequest request) {
        byte[] txCbor = request.txCbor();
        LedgerView view = request.view();
        ValidationEnv env = request.env();
        ProtocolParams params = view.protocolParams().require("protocol parameters");

        Transaction tx;
        try {
            tx = Transaction.deserialize(txCbor);
        } catch (Exception e) {
            return engine(DECODING_FAILURE, "the transaction does not decode: " + e.getMessage());
        }
        TransactionBody body = tx.getBody();

        List<LedgerFailure> failures = new ArrayList<>();
        if (request.rule() == TxValidationRequest.Rule.MEMPOOL) {
            int major = params.getProtocolMajorVer() != null ? params.getProtocolMajorVer() : env.protocolMajor();
            MempoolRule.Result mempool = MempoolRule.apply(body, view, major);
            if (!mempool.continueToLedger()) {
                return new TxValidationOutcome.Invalid(mempool.failures());
            }
            failures.addAll(mempool.failures());
        }

        Map<Outpoint, UtxoEntry> resolved = new LinkedHashMap<>();
        resolve(view, body.getInputs(), resolved);
        resolve(view, body.getReferenceInputs(), resolved);
        resolve(view, body.getCollateral(), resolved);

        int major = params.getProtocolMajorVer() != null ? params.getProtocolMajorVer() : env.protocolMajor();
        byte[] txId = TxIdentity.txId(txCbor);
        byte[] resolvedDigest = ReapplyPolicy.resolvedInputsDigest(ReapplyPolicy.allInputs(body), resolved);
        ValidatedTx previous = request.previous();
        boolean reapply = ReapplyPolicy.decide(previous, txId, tx.isValid(), major, env, request.origin(),
                resolvedDigest).reapply();

        LedgerFailure scalusFailure = LedgerBridge.validateAgainstView(txCbor, params, resolved.values(),
                env.currentSlot(), scalusSlotConfig(env.slotConfig()), env.networkId() == NetworkId.MAINNET ? 1 : 0,
                new LedgerViewStateProvider(view), reapply);
        if (scalusFailure != null) {
            failures.add(scalusFailure);
        }
        if (!failures.isEmpty()) {
            return new TxValidationOutcome.Invalid(failures);
        }

        // Scalus accepted: a transaction flagged is_valid = false had failing scripts (collateral only).
        boolean phase2Valid = tx.isValid();
        if (!phase2Valid && request.origin() != TxValidationRequest.Origin.SYNC) {
            return TxValidationOutcome.Invalid.of(LedgerFailure.phase2InvalidTxNotSupported(
                    "is_valid = false transactions are not admitted (ADR-056 §6)"));
        }
        TxEffects effects;
        try {
            effects = effectsDeriver.derive(txCbor, tx, TxIdentity.txIdHex(txCbor), view, env, phase2Valid);
        } catch (TxDecodingException e) {
            return engine(DECODING_FAILURE, "Scalus accepted the transaction but Haskell's decoder rejects it: "
                    + e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return engine(ENGINE_FAILURE,
                    "Scalus accepted the transaction but its effects cannot be derived: " + e.getMessage());
        }
        ValidatedTx validated = reapply ? previous : new ValidatedTx(txCbor, txId, major, env.currentEpoch(),
                env.phase2EnvDigest(), phase2Valid, request.origin(), resolvedDigest);
        return new TxValidationOutcome.Valid(effects, validated, reapply);
    }

    private static void resolve(LedgerView view, List<TransactionInput> inputs, Map<Outpoint, UtxoEntry> resolved) {
        if (inputs == null) {
            return;
        }
        for (TransactionInput input : inputs) {
            Outpoint outpoint = Outpoints.normalize(new Outpoint(input.getTransactionId(), input.getIndex()));
            if (resolved.containsKey(outpoint)) {
                continue;
            }
            switch (view.utxo(outpoint)) {
                case Lookup.Present<UtxoEntry> p -> resolved.put(outpoint, p.value());
                case Lookup.Absent<UtxoEntry> a -> {
                    // Scalus reports BadInputsUTxO for it.
                }
                case Lookup.Unavailable<UtxoEntry> u -> throw new LedgerStateUnavailableException(
                        "utxo " + outpoint.txHash() + "#" + outpoint.index() + ": " + u.reason());
            }
        }
    }

    private scalus.cardano.ledger.SlotConfig scalusSlotConfig(SlotConfig slotConfig) {
        EpochSlotCalc calc = null;
        if (epochGeometry != null) {
            try {
                calc = epochGeometry.getEpochSlotCalc();
            } catch (IllegalStateException e) {
                calc = null;
            }
        }
        if (calc == null) {
            return new scalus.cardano.ledger.SlotConfig(slotConfig.getZeroTime(), slotConfig.getZeroSlot(),
                    slotConfig.getSlotLength());
        }
        return new scalus.cardano.ledger.SlotConfig(slotConfig.getZeroTime(), slotConfig.getZeroSlot(),
                slotConfig.getSlotLength(), calc.shelleyEpochLength(), calc.firstNonByronEpoch());
    }

    private static TxValidationOutcome engine(String constructor, String detail) {
        return TxValidationOutcome.Invalid.of(new LedgerFailure(LedgerRuleName.ENGINE, constructor,
                LedgerFailure.Phase.PHASE_1, detail));
    }
}
