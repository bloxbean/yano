package org.yanoproject.ledger.rules.phase2;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.List;
import java.util.Map;

/**
 * Phase-2 (Plutus script) evaluation SPI (ADR-056 §5).
 *
 * <p>An engine calls it after phase one passed, for a transaction that has redeemers. The evaluator
 * runs every redeemer's script with the redeemer's declared ExUnits as budget and reports whether all
 * of them succeeded; comparing that with the transaction's {@code is_valid} flag
 * ({@code UTXOS.ValidationTagMismatch}) is the engine's job.</p>
 *
 * <p>Some checks that Haskell reports as phase-1 failures only surface while preparing the script
 * context. The evaluator must report them as {@link ScriptPhaseResult.Rejected}, before running any
 * script (ADR-057 {@code amaru-validator-wasm/INTERFACE.md}, "Modes"):</p>
 * <ul>
 *   <li>{@code UTXOW.MalformedScriptWitnesses} and {@code UTXOW.MalformedReferenceScripts}: a Plutus
 *       script witness or reference script that does not deserialise ({@link #evaluate} only: the Java
 *       engine's {@code UTXOW} makes these checks itself (its own decoder, then {@link #isWellFormed}), so
 *       {@link #collect} leaves them out);</li>
 *   <li>{@code UTXOS.CollectErrors} with {@code NoCostModel}: a script language without a cost model
 *       in {@code params};</li>
 *   <li>{@code UTXOS.CollectErrors} with {@code BadTranslation}: a {@code TxInfo} translation failure,
 *       including the Plutus V3 requirement that spending and reference inputs be disjoint.</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe and side-effect free.</p>
 *
 * <p><b>Signature.</b> ADR-056 §5 sketches {@code evaluate(Transaction, Map<Outpoint, Utxo>,
 * ProtocolParams, SlotConfig)}. This SPI takes the original transaction bytes and {@link UtxoEntry}
 * instead: CCL's {@code Utxo} keeps only a reference script's hash (not its bytes) and a re-encoded
 * inline datum, and re-serialising the {@link Transaction} can change the transaction id that the
 * script context carries (ADR-057 Phase B, recorded deviation).</p>
 */
public interface ScriptPhaseEvaluator {

    /**
     * @param txCbor         the transaction exactly as received
     * @param tx             the decoded transaction
     * @param resolvedInputs every spending, reference and collateral input the transaction names,
     *                       resolved from the same ledger view phase one used
     * @param params         the epoch-effective protocol parameters (cost models, prices, limits)
     * @param slotConfig     slot-to-POSIX-time conversion for the validity interval in the script context
     * @return the phase-2 result
     */
    ScriptPhaseResult evaluate(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                               ProtocolParams params, SlotConfig slotConfig);

    /**
     * As {@link #evaluate(byte[], Transaction, Map, ProtocolParams, SlotConfig)}, for a transaction validated
     * at {@code validationSlot} (the slot after the ledger tip). Evaluators that know the network's
     * {@link ForecastHorizon} use it to report {@code UTXOS.CollectErrors}
     * ({@code BadTranslation TimeTranslationPastHorizon}) for a validity bound past the horizon.
     */
    default ScriptPhaseResult evaluate(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                       ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
        return evaluate(txCbor, tx, resolvedInputs, params, slotConfig);
    }

    /**
     * As {@link #evaluate(byte[], Transaction, Map, ProtocolParams, SlotConfig, long)}, for a transaction the engine has
     * already read ({@link RawTransaction}), so that an evaluator reading the same structure need not parse it again.
     */
    default ScriptPhaseResult evaluate(RawTransaction raw, Map<Outpoint, UtxoEntry> resolvedInputs,
                                       ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
        return evaluate(raw.txCbor(), raw.decoded(), resolvedInputs, params, slotConfig, validationSlot);
    }

    /**
     * Only the preparation step: the {@code UTXOS.CollectErrors} of {@link ScriptPhaseResult.Rejected}, without
     * running any script. The Java engine calls it when the transaction needs a Plutus script it provides, and
     * ignores any {@code UTXOW} failure in the result (its {@code UTXOW} judges malformed scripts itself).
     *
     * <p>Haskell collects the script contexts ({@code ?!: CollectErrors}, unlabelled, so also on re-application
     * and after other failures) separately from running the scripts ({@code when2Phase $ whenFailureFree},
     * Babbage/Rules/Utxos.hs:139-157), so an engine needs the two steps apart. The default runs
     * {@link #evaluate(byte[], Transaction, Map, ProtocolParams, SlotConfig, long)} and keeps only a rejection;
     * evaluators that can prepare without running should override it.</p>
     *
     * @return the failures, empty when the scripts can be run
     */
    default List<LedgerFailure> collect(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                        ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
        return switch (evaluate(txCbor, tx, resolvedInputs, params, slotConfig, validationSlot)) {
            case ScriptPhaseResult.Rejected rejected -> rejected.failures();
            case ScriptPhaseResult.Passed passed -> List.of();
            case ScriptPhaseResult.Failed failed -> List.of();
        };
    }

    /** As {@link #collect(byte[], Transaction, Map, ProtocolParams, SlotConfig, long)}, for a transaction already read. */
    default List<LedgerFailure> collect(RawTransaction raw, Map<Outpoint, UtxoEntry> resolvedInputs,
                                        ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
        return collect(raw.txCbor(), raw.decoded(), resolvedInputs, params, slotConfig, validationSlot);
    }

    /**
     * Haskell {@code isValidPlutusScript} ({@code Alonzo/Scripts.hs:276-277}, plutus-ledger-api
     * {@code deserialiseScript}): whether a Plutus script decodes as a program of its language at the protocol
     * version. The Java engine's {@code UTXOW} judges this with its own decoder
     * ({@code conway.utxow.PlutusScriptDecoder}, ADR-056 Phase 3b) and additionally requires a script it accepts to
     * decode with the evaluator, so that no script reaches the evaluator that it cannot decode.
     *
     * <p>The default cannot judge and throws {@link UnsupportedOperationException}; the engine then relies on its own
     * decoder alone.</p>
     *
     * @param language      1 PlutusV1, 2 PlutusV2, 3 PlutusV3
     * @param script        the script's {@code PlutusBinary} (the contents of its CBOR byte string)
     * @param protocolMajor the protocol major version
     * @return true when the script is well formed
     * @throws UnsupportedOperationException when the evaluator has no Plutus decoder
     */
    default boolean isWellFormed(int language, byte[] script, int protocolMajor) {
        throw new UnsupportedOperationException(getClass().getSimpleName()
                + " cannot judge Plutus script well-formedness");
    }

    /**
     * Whether the evaluator builds a PlutusV3 {@code TxInfo} as Haskell does during the Conway bootstrap phase
     * (protocol version 9): {@code transTxCert} leaves out the deposit of {@code RegDepositTxCert} and the refund of
     * {@code UnRegDepositTxCert} ({@code TxCertRegStaking cred Nothing}, {@code TxCertUnRegStaking cred Nothing})
     * while {@code hardforkConwayBootstrapPhase} (Conway/TxInfo.hs:572-581; the certifying purpose too, :636-640).
     *
     * <p>The default is false, and the Java engine then refuses to run PlutusV3 scripts over such a context
     * ({@code UtxosRule}, ADR-056 Phase 5b) rather than judge them with the wrong one. The Scalus evaluator translates
     * it itself (Scalus 1.1.1's {@code LedgerToPlutusTranslation.getTxCertV3} always includes the deposit) and
     * returns true. Plutus V1/V2 contexts never carry these deposits ({@code transTxCertV1V2}, :383-397).</p>
     *
     * @return true when bootstrap-phase certificate deposits are translated as Haskell translates them
     */
    default boolean translatesBootstrapPhaseCertificateDeposits() {
        return false;
    }
}
