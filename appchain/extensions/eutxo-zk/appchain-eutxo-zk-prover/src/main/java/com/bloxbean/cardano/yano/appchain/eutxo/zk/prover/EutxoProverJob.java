package com.bloxbean.cardano.yano.appchain.eutxo.zk.prover;

import java.time.Instant;

/** Public, secret-free metadata for one durable proving job. */
public record EutxoProverJob(
        String id,
        Status status,
        int attempts,
        Instant createdAt,
        Instant updatedAt,
        String proofDigest,
        String lastError
) {
    public EutxoProverJob {
        if (id == null || !id.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("job id must be a lowercase SHA-256 digest");
        }
        if (status == null || attempts < 0 || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("invalid prover job metadata");
        }
        proofDigest = proofDigest == null ? "" : proofDigest;
        lastError = lastError == null ? "" : lastError;
        if (!proofDigest.isEmpty() && !proofDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid proof digest");
        }
        if (lastError.length() > 512) {
            lastError = lastError.substring(0, 512);
        }
    }

    public enum Status {
        QUEUED,
        RUNNING,
        PROVED,
        FAILED,
        CANCELLED
    }
}
