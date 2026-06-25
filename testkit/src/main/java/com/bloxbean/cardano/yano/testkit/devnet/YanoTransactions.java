package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.TxGateway;
import com.bloxbean.cardano.yano.api.model.TxEvaluationResult;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Low-level transaction helpers backed by public submission and evaluation
 * roles.
 */
public final class YanoTransactions {
    private final TxGateway txGateway;
    private final TxEvaluationGateway txEvaluationGateway;
    private final YanoAwait await;

    YanoTransactions(TxGateway txGateway, TxEvaluationGateway txEvaluationGateway, YanoAwait await) {
        this.txGateway = Objects.requireNonNull(txGateway, "txGateway");
        this.txEvaluationGateway = Objects.requireNonNull(txEvaluationGateway, "txEvaluationGateway");
        this.await = Objects.requireNonNull(await, "await");
    }

    /**
     * Submits serialized transaction CBOR.
     *
     * @param txCbor transaction CBOR bytes
     * @return transaction hash
     */
    public String submit(byte[] txCbor) {
        return txGateway.submitTransaction(copyRequiredTxCbor(txCbor));
    }

    /**
     * Submits serialized transaction CBOR and waits until outputs are visible
     * through the public UTXO query surface.
     *
     * @param txCbor transaction CBOR bytes
     * @return transaction hash
     */
    public String submitAndAwait(byte[] txCbor) {
        String txHash = submit(txCbor);
        await.untilTxVisible(txHash);
        return txHash;
    }

    /**
     * Whether transaction evaluation is configured for this devnet.
     *
     * @return true when evaluation is available
     */
    public boolean evaluationAvailable() {
        return txEvaluationGateway.isTransactionEvaluationAvailable();
    }

    /**
     * Evaluates serialized transaction CBOR.
     *
     * @param txCbor transaction CBOR bytes
     * @return evaluation results
     * @throws Exception if evaluation fails
     */
    public List<TxEvaluationResult> evaluate(byte[] txCbor) throws Exception {
        byte[] payload = copyRequiredTxCbor(txCbor);
        if (!txEvaluationGateway.isTransactionEvaluationAvailable()) {
            throw new UnsupportedOperationException("Transaction evaluation is not available");
        }
        return List.copyOf(txEvaluationGateway.evaluateTransaction(payload));
    }

    private static byte[] copyRequiredTxCbor(byte[] txCbor) {
        if (txCbor == null || txCbor.length == 0) {
            throw new IllegalArgumentException("txCbor must not be empty");
        }
        return Arrays.copyOf(txCbor, txCbor.length);
    }
}
