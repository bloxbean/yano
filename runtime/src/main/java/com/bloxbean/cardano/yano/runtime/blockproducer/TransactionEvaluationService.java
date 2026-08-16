package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.ledgerrules.ScriptReferenceResolverScope;
import com.bloxbean.cardano.yano.ledgerrules.TransactionEvaluator;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Service that resolves UTxOs and delegates to {@link TransactionEvaluator}
 * for Plutus script evaluation (ExUnits computation).
 */
@Slf4j
public class TransactionEvaluationService {

    @FunctionalInterface
    public interface UtxoView {
        Map<Outpoint, com.bloxbean.cardano.yano.api.utxo.model.Utxo> resolve(
                Collection<Outpoint> outpoints);
    }

    private final TransactionEvaluator evaluator;
    private final UtxoState utxoState;

    public TransactionEvaluationService(TransactionEvaluator evaluator, UtxoState utxoState) {
        this.evaluator = evaluator;
        this.utxoState = utxoState;
    }

    /**
     * Evaluate Plutus scripts in the given transaction.
     * Resolves all inputs (regular + reference + collateral) from UtxoState.
     *
     * @param txCbor raw CBOR bytes of the transaction
     * @return evaluation results per redeemer
     * @throws Exception on deserialization, UTxO resolution, or evaluation failure
     */
    public List<TransactionEvaluator.EvaluationResult> evaluate(byte[] txCbor) throws Exception {
        return evaluate(txCbor, outpoints -> {
            Map<Outpoint, com.bloxbean.cardano.yano.api.utxo.model.Utxo> resolved =
                    new LinkedHashMap<>();
            for (Outpoint outpoint : outpoints) {
                utxoState.getUtxo(outpoint).ifPresent(utxo -> resolved.put(outpoint, utxo));
            }
            return resolved;
        });
    }

    /**
     * Evaluate against a caller-provided stable UTXO view. The view is resolved
     * before phase-2 evaluation so its synchronization does not span evaluator
     * execution.
     */
    public List<TransactionEvaluator.EvaluationResult> evaluate(
            byte[] txCbor, UtxoView utxoView) throws Exception {
        Transaction transaction = Transaction.deserialize(txCbor);

        // Collect all inputs: regular + reference + collateral
        Set<Utxo> inputUtxos = new HashSet<>();
        List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> resolvedInputUtxos =
                new ArrayList<>();
        List<TransactionInput> allInputs = new ArrayList<>();

        if (transaction.getBody().getInputs() != null) {
            allInputs.addAll(transaction.getBody().getInputs());
        }
        if (transaction.getBody().getReferenceInputs() != null) {
            allInputs.addAll(transaction.getBody().getReferenceInputs());
        }
        if (transaction.getBody().getCollateral() != null) {
            allInputs.addAll(transaction.getBody().getCollateral());
        }

        Set<Outpoint> requiredOutpoints = new LinkedHashSet<>();
        for (TransactionInput input : allInputs) {
            requiredOutpoints.add(new Outpoint(input.getTransactionId(), input.getIndex()));
        }

        Map<Outpoint, com.bloxbean.cardano.yano.api.utxo.model.Utxo> resolved =
                Objects.requireNonNull(utxoView, "utxoView").resolve(requiredOutpoints);
        if (resolved == null) resolved = Map.of();

        for (Outpoint op : requiredOutpoints) {
            var yaciUtxo = resolved.get(op);
            if (yaciUtxo == null) {
                throw new IllegalArgumentException("UTXO not found: " + op.txHash() + "#" + op.index());
            }

            resolvedInputUtxos.add(yaciUtxo);
            inputUtxos.add(UtxoMapper.toCclUtxo(yaciUtxo));
        }

        try (var ignored = ScriptReferenceResolverScope.open(resolvedInputUtxos)) {
            return evaluator.evaluate(txCbor, inputUtxos);
        }
    }
}
