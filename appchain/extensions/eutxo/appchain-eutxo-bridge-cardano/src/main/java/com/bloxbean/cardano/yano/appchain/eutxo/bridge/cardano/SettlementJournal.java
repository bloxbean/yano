package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import java.util.Objects;
import java.util.Optional;

/**
 * Durable node-local write-ahead journal. Implementations must fsync SIGNED
 * before the backend submission returns.
 */
public interface SettlementJournal {
    Optional<Entry> find(String claimId);

    void save(Entry entry);

    enum Stage {
        SIGNED,
        SUBMITTED,
        CONFIRMED,
        PARKED
    }

    record Entry(
            String claimId,
            String transactionId,
            byte[] signedTransactionCbor,
            Stage stage,
        String detail
    ) {
        public Entry {
            claimId = canonicalHash(claimId, "claimId");
            transactionId = canonicalHash(transactionId, "transactionId");
            signedTransactionCbor = Objects.requireNonNull(
                    signedTransactionCbor, "signedTransactionCbor").clone();
            Objects.requireNonNull(stage, "stage");
            detail = Objects.requireNonNullElse(detail, "");
        }

        @Override
        public byte[] signedTransactionCbor() {
            return signedTransactionCbor.clone();
        }

        public Entry advance(Stage next, String nextDetail) {
            Objects.requireNonNull(next, "next");
            boolean allowed = next == stage
                    || stage == Stage.SIGNED
                    || (stage == Stage.SUBMITTED
                    && (next == Stage.CONFIRMED || next == Stage.PARKED))
                    || (stage == Stage.PARKED && next == Stage.CONFIRMED);
            if (!allowed || stage == Stage.CONFIRMED && next != Stage.CONFIRMED) {
                throw new IllegalArgumentException("settlement journal cannot move backwards");
            }
            return new Entry(
                    claimId, transactionId, signedTransactionCbor,
                    next, nextDetail);
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value.trim();
        }

        private static String canonicalHash(String value, String field) {
            String hash = required(value, field);
            if (hash.length() != 64
                    || !hash.equals(hash.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException(
                        field + " must be 32-byte lowercase hex");
            }
            try {
                java.util.HexFormat.of().parseHex(hash);
                return hash;
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(
                        field + " must be 32-byte lowercase hex", failure);
            }
        }
    }
}
