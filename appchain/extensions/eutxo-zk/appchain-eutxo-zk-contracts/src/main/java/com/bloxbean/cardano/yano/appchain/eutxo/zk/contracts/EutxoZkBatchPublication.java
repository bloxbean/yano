package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.util.Arrays;
import java.util.Objects;

/**
 * Portable view of one proof-bound batch published by an L1 root-advance
 * transaction.
 *
 * <p>The L1 inline datum is exactly {@link #canonicalDatum()}; no JSON,
 * compression, or transport wrapper participates in the commitment.</p>
 */
public record EutxoZkBatchPublication(
        long l1BlockHeight,
        String l1TransactionId,
        int outputIndex,
        EutxoZkStatement statement,
        EutxoZkBatchData batchData
) {
    public EutxoZkBatchPublication {
        if (l1BlockHeight < 0) {
            throw new IllegalArgumentException(
                    "L1 block height cannot be negative");
        }
        if (l1TransactionId == null
                || !l1TransactionId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "L1 transaction id must be lowercase hex");
        }
        if (outputIndex < 0) {
            throw new IllegalArgumentException(
                    "L1 output index cannot be negative");
        }
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(batchData, "batchData");
        if (!Arrays.equals(
                statement.batchDataCommitment(),
                batchData.commitment())
                || !statement.publicInputs()
                .batchDataCommitment()
                .equals(batchData.commitmentScalar())) {
            throw new IllegalArgumentException(
                    "published batch does not match the proof statement");
        }
    }

    public byte[] canonicalDatum() {
        return batchData.canonicalBytes();
    }

    public String identity() {
        return l1TransactionId + "#" + outputIndex;
    }
}
