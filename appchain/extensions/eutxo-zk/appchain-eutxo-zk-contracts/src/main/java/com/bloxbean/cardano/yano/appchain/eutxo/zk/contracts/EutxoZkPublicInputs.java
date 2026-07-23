package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * Ordered public inputs for the first validity circuit.
 *
 * <p>The ordering is consensus and on-chain ABI:
 * previous root, next root, transition digest, owner commitment, batch size.</p>
 */
public record EutxoZkPublicInputs(
        BigInteger previousRoot,
        BigInteger nextRoot,
        BigInteger transitionDigest,
        BigInteger ownerCommitment,
        BigInteger batchSize
) {
    public EutxoZkPublicInputs {
        requireScalar(previousRoot, "previousRoot");
        requireScalar(nextRoot, "nextRoot");
        requireScalar(transitionDigest, "transitionDigest");
        requireScalar(ownerCommitment, "ownerCommitment");
        requireScalar(batchSize, "batchSize");
        if (!BigInteger.ONE.equals(batchSize)) {
            throw new IllegalArgumentException(
                    "Z0 supports exactly one transition per proof");
        }
    }

    public List<BigInteger> ordered() {
        return List.of(previousRoot, nextRoot, transitionDigest,
                ownerCommitment, batchSize);
    }

    private static void requireScalar(BigInteger value, String label) {
        Objects.requireNonNull(value, label);
        if (value.signum() < 0 || value.bitLength() > 255) {
            throw new IllegalArgumentException(label + " is outside the scalar envelope");
        }
    }
}
