package org.yanoproject.api;

import org.yanoproject.api.model.TxEvaluationResult;

import java.util.List;

/**
 * Optional transaction script evaluation surface.
 */
public interface TxEvaluationGateway {
    default boolean isTransactionEvaluationAvailable() {
        return false;
    }

    default List<TxEvaluationResult> evaluateTransaction(byte[] txCbor) throws Exception {
        throw new UnsupportedOperationException("Transaction evaluation is not available");
    }
}
