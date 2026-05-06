package com.bloxbean.cardano.yano.scalusbridge;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.yano.ledgerrules.EpochProtocolParamsSupplier;
import com.bloxbean.cardano.yano.ledgerrules.TransactionEvaluator;
import scalus.bloxbean.EvaluatorMode;
import scalus.bloxbean.ScalusTransactionEvaluator;
import scalus.bloxbean.ScriptSupplier;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Scalus-based implementation of {@link TransactionEvaluator}.
 * Delegates to {@link ScalusTransactionEvaluator} for Plutus script evaluation and ExUnits computation.
 */
public class ScalusBasedTransactionEvaluator implements TransactionEvaluator {

    private final EpochProtocolParamsSupplier protocolParamsSupplier;
    private final ScriptSupplier scriptSupplier;
    private final SlotConfig slotConfig;
    private final int networkId;
    private final LongSupplier currentSlotSupplier;

    ScalusBasedTransactionEvaluator(ProtocolParams protocolParams, 
                                    com.bloxbean.cardano.client.api.ScriptSupplier scriptSupplier,
                                    SlotConfig slotConfig, int networkId) {
        this(slot -> protocolParams, scriptSupplier, slotConfig, networkId, null);
    }

    ScalusBasedTransactionEvaluator(EpochProtocolParamsSupplier protocolParamsSupplier,
                                    com.bloxbean.cardano.client.api.ScriptSupplier scriptSupplier,
                                    SlotConfig slotConfig, int networkId,
                                    LongSupplier currentSlotSupplier) {
        this.protocolParamsSupplier = protocolParamsSupplier;
        if (scriptSupplier != null)
            this.scriptSupplier = new ScalusScriptSupplier(scriptSupplier);
        else
            this.scriptSupplier = null;
        this.slotConfig = slotConfig;
        this.networkId = networkId;
        this.currentSlotSupplier = currentSlotSupplier;
    }

    @Override
    public List<EvaluationResult> evaluate(byte[] txCbor, Set<Utxo> inputUtxos) throws Exception {
        // Build a lightweight UtxoSupplier backed by the pre-resolved inputUtxos
        UtxoSupplier utxoSupplier = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                return List.of(); // Not used by ScalusTransactionEvaluator with pre-resolved UTxOs
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int index) {
                return inputUtxos.stream()
                        .filter(u -> u.getTxHash().equals(txHash) && u.getOutputIndex() == index)
                        .findFirst();
            }
        };

        var scalusSlotConfig = new scalus.cardano.ledger.SlotConfig(slotConfig.getZeroTime(), slotConfig.getZeroSlot(), slotConfig.getSlotLength());
        ProtocolParams protocolParams = protocolParamsSupplier.getProtocolParams(resolveCurrentSlot());
        // Create ScalusTransactionEvaluator and evaluate
        var evaluator = new ScalusTransactionEvaluator(
                scalusSlotConfig, protocolParams, utxoSupplier, scriptSupplier,
                EvaluatorMode.EVALUATE_AND_COMPUTE_COST, false);

        Result<List<com.bloxbean.cardano.client.api.model.EvaluationResult>> result =
                evaluator.evaluateTx(txCbor, inputUtxos);

        if (!result.isSuccessful()) {
            throw new Exception("Script evaluation failed: " + result.getResponse());
        }
        
        // Map CCL EvaluationResult to yaci EvaluationResult
        return result.getValue().stream()
                .map(er -> new EvaluationResult(
                        er.getRedeemerTag().name().toLowerCase(),
                        er.getIndex(),
                        er.getExUnits().getMem().longValueExact(),
                        er.getExUnits().getSteps().longValueExact()))
                .collect(Collectors.toList());
    }

    private long resolveCurrentSlot() {
        if (currentSlotSupplier == null) return 0;
        try {
            long slot = currentSlotSupplier.getAsLong();
            if (slot >= 0) return slot;
            throw new IllegalStateException("current slot supplier returned " + slot);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve current slot from runtime", e);
        }
    }
}
