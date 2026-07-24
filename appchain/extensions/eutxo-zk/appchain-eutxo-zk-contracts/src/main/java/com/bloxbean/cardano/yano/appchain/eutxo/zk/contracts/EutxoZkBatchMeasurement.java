package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.util.Objects;

/** Reproducible measurement record; zero means the gate was not exercised. */
public record EutxoZkBatchMeasurement(
        String profileDigest,
        String environment,
        int constraints,
        long setupMillis,
        long proofMillis,
        long peakMemoryBytes,
        long provingKeyBytes,
        int proofBytes,
        int canonicalBatchBytes,
        long julcExecutionUnits,
        Gate gate
) {
    public enum Gate {
        EXERCISED,
        NOT_EXERCISED
    }

    public EutxoZkBatchMeasurement {
        if (profileDigest == null
                || !profileDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "measurement requires a batch-profile digest");
        }
        environment = Objects.requireNonNull(environment, "environment").trim();
        Objects.requireNonNull(gate, "gate");
        if (environment.isEmpty() || constraints < 0 || setupMillis < 0
                || proofMillis < 0 || peakMemoryBytes < 0
                || provingKeyBytes < 0 || proofBytes < 0
                || canonicalBatchBytes < 0 || julcExecutionUnits < 0) {
            throw new IllegalArgumentException("invalid batch measurement");
        }
        if (gate == Gate.NOT_EXERCISED
                && (constraints != 0 || setupMillis != 0 || proofMillis != 0
                || peakMemoryBytes != 0 || provingKeyBytes != 0
                || proofBytes != 0 || canonicalBatchBytes != 0
                || julcExecutionUnits != 0)) {
            throw new IllegalArgumentException(
                    "unexercised gate cannot contain invented measurements");
        }
    }
}
