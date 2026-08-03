package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import java.util.Objects;

/** Durable projection checkpoint advanced atomically with one source block. */
public record IndexCheckpoint(
        String identityDigest,
        SourcePoint source,
        long transactionSequence,
        long depositSequence,
        long withdrawalSequence,
        IndexCoverage coverage
) {
    public IndexCheckpoint {
        identityDigest = Objects.requireNonNull(identityDigest, "identityDigest");
        source = Objects.requireNonNull(source, "source");
        coverage = Objects.requireNonNull(coverage, "coverage");
        if (!identityDigest.matches("[0-9a-f]{64}")
                || transactionSequence < 0
                || depositSequence < 0
                || withdrawalSequence < 0) {
            throw new IllegalArgumentException("invalid index checkpoint");
        }
    }

    public static IndexCheckpoint origin(IndexIdentity identity) {
        return new IndexCheckpoint(
                identity.digest(), SourcePoint.ORIGIN, 0, 0, 0,
                IndexCoverage.FULL);
    }
}
