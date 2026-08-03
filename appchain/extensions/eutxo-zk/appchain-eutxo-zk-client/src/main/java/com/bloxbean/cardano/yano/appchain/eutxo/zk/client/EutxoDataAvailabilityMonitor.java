package com.bloxbean.cardano.yano.appchain.eutxo.zk.client;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;

import java.util.Arrays;
import java.util.Objects;

/**
 * Transport-neutral assessment of proof-bound L1 batch data.
 */
public final class EutxoDataAvailabilityMonitor {
    public Assessment assess(
            EutxoZkStatement statement,
            byte[] inlineDatum,
            long publicationBlock,
            long currentBlock,
            long declaredHorizonBlocks
    ) {
        Objects.requireNonNull(statement, "statement");
        if (publicationBlock < 0
                || currentBlock < publicationBlock
                || declaredHorizonBlocks < 1) {
            throw new IllegalArgumentException(
                    "invalid availability horizon");
        }
        if (inlineDatum == null || inlineDatum.length == 0) {
            return new Assessment(State.MISSING, 0,
                    "proof-bound L1 batch datum is unavailable");
        }
        EutxoZkBatchData batch;
        try {
            batch = EutxoZkBatchData.decode(inlineDatum);
        } catch (IllegalArgumentException exception) {
            return new Assessment(State.CORRUPT, inlineDatum.length,
                    "published batch datum is not canonical");
        }
        if (!Arrays.equals(
                batch.commitment(),
                statement.batchDataCommitment())
                || !batch.commitmentScalar().equals(
                statement.publicInputs().batchDataCommitment())) {
            return new Assessment(State.CORRUPT, inlineDatum.length,
                    "published batch commitment mismatch");
        }
        long age = currentBlock - publicationBlock;
        if (age > declaredHorizonBlocks) {
            return new Assessment(State.OUTSIDE_DECLARED_HORIZON,
                    inlineDatum.length,
                    "publication is older than the monitored horizon");
        }
        return new Assessment(State.AVAILABLE, inlineDatum.length, "");
    }

    public enum State {
        AVAILABLE,
        MISSING,
        CORRUPT,
        OUTSIDE_DECLARED_HORIZON
    }

    public record Assessment(State state, int bytes, String message) {
        public Assessment {
            Objects.requireNonNull(state, "state");
            if (bytes < 0) {
                throw new IllegalArgumentException(
                        "byte count cannot be negative");
            }
            message = message == null ? "" : message;
        }

        public boolean healthy() {
            return state == State.AVAILABLE;
        }
    }
}
