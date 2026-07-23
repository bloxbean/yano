package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

/** Node-local Cardano submission/reconciliation boundary. */
public interface CardanoSettlementBackend {
    Submission submit(byte[] signedTransactionCbor) throws Exception;

    Status status(String transactionId) throws Exception;

    enum Status {
        UNKNOWN,
        PENDING,
        CONFIRMED,
        REJECTED
    }

    record Submission(String transactionId, Status status, String detail) {
        public Submission {
            if (transactionId == null || transactionId.isBlank()) {
                throw new IllegalArgumentException("transactionId is required");
            }
            if (status == null) {
                throw new IllegalArgumentException("submission status is required");
            }
            detail = detail == null ? "" : detail;
        }
    }
}
