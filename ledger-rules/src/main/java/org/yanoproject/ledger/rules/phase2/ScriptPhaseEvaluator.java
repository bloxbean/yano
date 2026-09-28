package org.yanoproject.ledger.rules.phase2;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

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
 *       script witness or reference script that does not deserialise;</li>
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
}
