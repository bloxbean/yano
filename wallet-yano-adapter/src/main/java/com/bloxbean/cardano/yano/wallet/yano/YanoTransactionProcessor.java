package com.bloxbean.cardano.yano.wallet.yano;

import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yano.api.NodeAPI;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public class YanoTransactionProcessor implements TransactionProcessor {
    private final TransactionSubmitter transactionSubmitter;

    public YanoTransactionProcessor(NodeAPI nodeAPI) {
        Objects.requireNonNull(nodeAPI, "nodeAPI is required");
        this.transactionSubmitter = nodeAPI::submitTransaction;
    }

    YanoTransactionProcessor(TransactionSubmitter transactionSubmitter) {
        this.transactionSubmitter = Objects.requireNonNull(transactionSubmitter, "transactionSubmitter is required");
    }

    @Override
    public Result<String> submitTransaction(byte[] cborData) throws ApiException {
        try {
            return new SubmissionResult(transactionSubmitter.submitTransaction(cborData));
        } catch (RuntimeException e) {
            throw new ApiException("Unable to submit transaction through Yano", e);
        }
    }

    @Override
    public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) {
        return new UnsupportedEvaluationResult("Yano transaction evaluation is not available in the MVP adapter");
    }

    @FunctionalInterface
    interface TransactionSubmitter {
        String submitTransaction(byte[] cborData);
    }

    private static final class SubmissionResult extends Result<String> {
        private SubmissionResult(String response) {
            super(true, response);
        }
    }

    private static final class UnsupportedEvaluationResult extends Result<List<EvaluationResult>> {
        private UnsupportedEvaluationResult(String response) {
            super(false, response);
        }
    }
}
