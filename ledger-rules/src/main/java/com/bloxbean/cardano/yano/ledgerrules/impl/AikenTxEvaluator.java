package com.bloxbean.cardano.yano.ledgerrules.impl;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.yano.ledgerrules.SlotConfigSupplier;
import com.bloxbean.cardano.yano.ledgerrules.TransactionEvaluator;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class AikenTxEvaluator implements TransactionEvaluator {
    private final ProtocolParamsSupplier protocolParamsSupplier;
    private final ScriptSupplier scriptSupplier;
    private final SlotConfigSupplier slotConfigSupplier;

    public AikenTxEvaluator(ProtocolParamsSupplier protocolParamsSupplier,
                            ScriptSupplier scriptSupplier, SlotConfig slotConfig) {
        this(protocolParamsSupplier, scriptSupplier, () -> slotConfig);
    }

    public AikenTxEvaluator(ProtocolParamsSupplier protocolParamsSupplier,
                            ScriptSupplier scriptSupplier, SlotConfigSupplier slotConfigSupplier) {
        this.protocolParamsSupplier = protocolParamsSupplier;
        this.scriptSupplier = scriptSupplier;
        this.slotConfigSupplier = slotConfigSupplier;
    }

    @Override
    public List<EvaluationResult> evaluate(byte[] txCbor, Set<Utxo> inputUtxos) throws Exception {
        UtxoSupplier utxoSupplier = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int index) {
                return inputUtxos.stream()
                        .filter(u -> u.getTxHash().equals(txHash) && u.getOutputIndex() == index)
                        .findFirst();
            }
        };

        var aikenTxEvaluator = new AikenTransactionEvaluator(
                utxoSupplier, protocolParamsSupplier, scriptSupplier, slotConfigSupplier.getSlotConfig());

        var aikenEvaluationResult =  aikenTxEvaluator.evaluateTx(txCbor, inputUtxos);

        if (!aikenEvaluationResult.isSuccessful()) {
            throw new Exception("Script evaluation failed: " + aikenEvaluationResult.getResponse());
        }

        return aikenEvaluationResult.getValue().stream()
                .map(er -> new EvaluationResult(
                        er.getRedeemerTag().name().toLowerCase(),
                        er.getIndex(),
                        er.getExUnits().getMem().longValueExact(),
                        er.getExUnits().getSteps().longValueExact()))
                .collect(Collectors.toList());
    }
}
