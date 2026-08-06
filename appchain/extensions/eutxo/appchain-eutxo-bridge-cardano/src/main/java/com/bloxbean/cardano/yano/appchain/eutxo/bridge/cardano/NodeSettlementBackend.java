package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.yano.api.utxo.UtxoState;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * ADR-UTXO-009 SP-M6: {@link CardanoSettlementBackend} over the node's own
 * L1 — submission through the node's tx path, status by asking the node's
 * UTxO view whether the transaction's outputs exist yet.
 */
final class NodeSettlementBackend implements CardanoSettlementBackend {
    private final Function<byte[], String> submitTx;
    private final Supplier<UtxoState> utxoView;

    NodeSettlementBackend(Function<byte[], String> submitTx,
                          Supplier<UtxoState> utxoView) {
        this.submitTx = Objects.requireNonNull(submitTx, "submitTx");
        this.utxoView = Objects.requireNonNull(utxoView, "utxoView");
    }

    @Override
    public Submission submit(byte[] signedTransactionCbor) {
        try {
            String transactionId = submitTx.apply(signedTransactionCbor);
            return new Submission(transactionId, Status.PENDING, "submitted");
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "L1 settlement submission failed", failure);
        }
    }

    @Override
    public Status status(String transactionId) {
        UtxoState view = utxoView.get();
        if (view == null) {
            return Status.UNKNOWN;
        }
        try {
            return view.getOutputsByTxHash(transactionId).isEmpty()
                    ? Status.UNKNOWN
                    : Status.CONFIRMED;
        } catch (RuntimeException failure) {
            return Status.UNKNOWN;
        }
    }
}
