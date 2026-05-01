package com.bloxbean.cardano.yano.wallet.yano;

import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransactionStore;

import java.util.Objects;

public class YanoPendingTransactionReconciler {
    private final PendingTransactionStore pendingTransactions;
    private final String networkId;

    public YanoPendingTransactionReconciler(PendingTransactionStore pendingTransactions, String networkId) {
        this.pendingTransactions = Objects.requireNonNull(pendingTransactions, "pendingTransactions is required");
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId is required");
        }
        this.networkId = networkId;
    }

    public int reconcile(UtxoState utxoState) {
        if (utxoState == null || !utxoState.isEnabled()) {
            return 0;
        }

        int confirmed = 0;
        for (var transaction : pendingTransactions.listByNetwork(networkId)) {
            if (!transaction.awaitsConfirmation()) {
                continue;
            }

            var outputs = utxoState.getOutputsByTxHash(transaction.txHash());
            if (outputs == null || outputs.isEmpty()) {
                continue;
            }

            var firstOutput = outputs.get(0);
            pendingTransactions.save(transaction.markConfirmed(
                    firstOutput.slot(),
                    firstOutput.blockNumber(),
                    firstOutput.blockHash()));
            confirmed++;
        }
        return confirmed;
    }
}
