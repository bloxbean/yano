package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.runtime.chain.TransactionOutputProjector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Tracks UTXOs consumed during a single block-build cycle to detect intra-block double-spends.
 * Wraps the persistent {@link UtxoState} with an in-memory set of spent outpoints.
 */
public class BlockBuildUtxoOverlay {

    private final UtxoState utxoState;
    private final Set<Outpoint> spent = new HashSet<>();
    private final Map<Outpoint, Utxo> produced = new HashMap<>();

    public BlockBuildUtxoOverlay(UtxoState utxoState) {
        this.utxoState = utxoState;
    }

    /**
     * Returns a resolver function that checks spent-tracking before delegating to UtxoState.
     * Returns null for already-spent outpoints.
     */
    public Function<Outpoint, Utxo> resolver() {
        return outpoint -> {
            if (spent.contains(outpoint)) {
                return null; // Already consumed in this block
            }
            Utxo projected = produced.get(outpoint);
            if (projected != null) {
                return projected;
            }
            return utxoState.getUtxo(outpoint).orElse(null);
        };
    }

    /**
     * Mark the regular inputs of a transaction as spent for subsequent validations.
     */
    public void markSpent(byte[] txCbor) {
        try {
            applyTransaction(txCbor);
        } catch (Exception e) {
            // If deserialization fails here, the tx already passed validation,
            // so this shouldn't happen. Log and continue.
        }
    }

    /**
     * Apply a selected valid transaction to the block-local view. Regular
     * inputs are consumed and normal outputs become visible to later
     * candidates. Collateral-return outputs are deliberately excluded.
     */
    public void applyTransaction(byte[] txCbor) {
        try {
            Transaction tx = Transaction.deserialize(txCbor);
            if (tx.getBody().getInputs() != null) {
                for (TransactionInput input : tx.getBody().getInputs()) {
                    spent.add(new Outpoint(input.getTransactionId(), input.getIndex()));
                }
            }
            String txHash = TransactionUtil.getTxHash(txCbor);
            if (tx.getBody().getOutputs() != null) {
                for (int index = 0; index < tx.getBody().getOutputs().size(); index++) {
                    Utxo utxo = TransactionOutputProjector.project(
                            txHash, index, tx.getBody().getOutputs().get(index));
                    produced.put(utxo.outpoint(), utxo);
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to apply transaction to block UTXO overlay", e);
        }
    }

    /**
     * Reset the spent tracking for the next block cycle.
     */
    public void reset() {
        spent.clear();
        produced.clear();
    }
}
