package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * Ordered public inputs for an L1-settleable bounded batch.
 *
 * <p>The ordering is the on-chain ABI: previous root, next root, transition
 * digest, owner commitment, batch size, settlement context, batch-data
 * commitment, and withdrawal commitment.</p>
 */
public record EutxoZkSettlementPublicInputs(
        BigInteger previousRoot,
        BigInteger nextRoot,
        BigInteger transitionDigest,
        BigInteger ownerCommitment,
        BigInteger batchSize,
        BigInteger settlementContext,
        BigInteger batchDataCommitment,
        BigInteger withdrawalCommitment
) {
    public static final int COUNT = 8;

    public EutxoZkSettlementPublicInputs {
        requireScalar(previousRoot, "previousRoot");
        requireScalar(nextRoot, "nextRoot");
        requireScalar(transitionDigest, "transitionDigest");
        requireScalar(ownerCommitment, "ownerCommitment");
        requireScalar(batchSize, "batchSize");
        requireScalar(settlementContext, "settlementContext");
        requireScalar(batchDataCommitment, "batchDataCommitment");
        requireScalar(withdrawalCommitment, "withdrawalCommitment");
        if (batchSize.signum() <= 0
                || batchSize.compareTo(BigInteger.valueOf(64)) > 0) {
            throw new IllegalArgumentException("batch size must be in 1-64");
        }
    }

    public List<BigInteger> ordered() {
        return List.of(
                previousRoot,
                nextRoot,
                transitionDigest,
                ownerCommitment,
                batchSize,
                settlementContext,
                batchDataCommitment,
                withdrawalCommitment);
    }

    public EutxoZkPublicInputs batchInputs() {
        return new EutxoZkPublicInputs(
                previousRoot, nextRoot, transitionDigest,
                ownerCommitment, batchSize);
    }

    private static void requireScalar(BigInteger value, String label) {
        Objects.requireNonNull(value, label);
        if (value.signum() < 0 || value.bitLength() > 255) {
            throw new IllegalArgumentException(
                    label + " is outside the scalar envelope");
        }
    }
}
