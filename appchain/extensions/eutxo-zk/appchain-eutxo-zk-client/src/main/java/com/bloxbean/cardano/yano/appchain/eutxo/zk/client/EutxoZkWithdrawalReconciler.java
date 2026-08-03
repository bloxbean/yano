package com.bloxbean.cardano.yano.appchain.eutxo.zk.client;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Strict proof-finalized to L1-stable aggregate-withdrawal reconciliation.
 *
 * <p>This state is node-local operational metadata. It cannot authorize a
 * withdrawal; the L1 vault validator remains authoritative.</p>
 */
public final class EutxoZkWithdrawalReconciler {
    private final Map<Key, Status> statuses = new LinkedHashMap<>();

    public synchronized Status proofFinalized(
            EutxoZkProofArtifact proof,
            long sequence
    ) {
        Objects.requireNonNull(proof, "proof");
        if (sequence < 0) {
            throw new IllegalArgumentException(
                    "withdrawal sequence cannot be negative");
        }
        BigInteger amount =
                proof.statement().publicInputs().withdrawalCommitment();
        if (amount.signum() <= 0 || amount.bitLength() > 63) {
            throw new IllegalArgumentException(
                    "aggregate withdrawal is outside the L1 lovelace envelope");
        }
        Key key = new Key(proof.statementDigest(), sequence);
        Status next = new Status(
                key, Stage.PROOF_FINALIZED, amount.longValueExact(),
                "", "");
        Status existing = statuses.putIfAbsent(key, next);
        if (existing != null && existing.lovelace() != next.lovelace()) {
            throw new IllegalStateException(
                    "withdrawal identity was reused with another amount");
        }
        return existing == null ? next : existing;
    }

    public synchronized Status submitted(
            Key key,
            String transactionHash
    ) {
        Status current = require(key);
        if (current.stage() == Stage.L1_STABLE) {
            return current;
        }
        requireHash(transactionHash, "transactionHash");
        if (current.stage() == Stage.L1_SUBMITTED
                && !current.transactionHash().equals(transactionHash)) {
            throw new IllegalStateException(
                    "withdrawal already has another submitted transaction");
        }
        Status next = new Status(
                key, Stage.L1_SUBMITTED, current.lovelace(),
                transactionHash, "");
        statuses.put(key, next);
        return next;
    }

    public synchronized Status stable(
            Key key,
            String transactionHash,
            String stableBlockHash
    ) {
        Status current = require(key);
        requireHash(transactionHash, "transactionHash");
        requireHash(stableBlockHash, "stableBlockHash");
        if (current.stage() != Stage.L1_SUBMITTED
                || !current.transactionHash().equals(transactionHash)) {
            throw new IllegalStateException(
                    "only the submitted transaction can become L1 stable");
        }
        Status next = new Status(
                key, Stage.L1_STABLE, current.lovelace(),
                transactionHash, stableBlockHash);
        statuses.put(key, next);
        return next;
    }

    public synchronized Status rolledBack(Key key) {
        Status current = require(key);
        if (current.stage() != Stage.L1_STABLE) {
            return current;
        }
        Status next = new Status(
                key, Stage.L1_SUBMITTED, current.lovelace(),
                current.transactionHash(), "");
        statuses.put(key, next);
        return next;
    }

    public synchronized Optional<Status> find(Key key) {
        return Optional.ofNullable(statuses.get(key));
    }

    private Status require(Key key) {
        Status status = statuses.get(Objects.requireNonNull(key, "key"));
        if (status == null) {
            throw new IllegalArgumentException("unknown withdrawal");
        }
        return status;
    }

    private static void requireHash(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    label + " must be a lowercase SHA-256 hash");
        }
    }

    public enum Stage {
        PROOF_FINALIZED,
        L1_SUBMITTED,
        L1_STABLE
    }

    public record Key(String statementDigest, long sequence) {
        public Key {
            requireHash(statementDigest, "statementDigest");
            if (sequence < 0) {
                throw new IllegalArgumentException(
                        "withdrawal sequence cannot be negative");
            }
        }
    }

    public record Status(
            Key key,
            Stage stage,
            long lovelace,
            String transactionHash,
            String stableBlockHash
    ) {
        public Status {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(stage, "stage");
            if (lovelace <= 0) {
                throw new IllegalArgumentException(
                        "withdrawal lovelace must be positive");
            }
            transactionHash = transactionHash == null
                    ? "" : transactionHash;
            stableBlockHash = stableBlockHash == null
                    ? "" : stableBlockHash;
        }
    }
}
