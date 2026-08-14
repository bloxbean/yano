package com.bloxbean.cardano.yano.api.plugin.domain;

import java.util.Objects;

/**
 * Reviewed, non-custodial L1 transaction-building seam for domain plugins.
 * It can select one public UTxO and build one unsigned payment with change;
 * it cannot sign, submit, evaluate scripts, or access node stores directly.
 */
public interface L1TransactionBuilderService {
    int MAX_ADDRESS_LENGTH = 256;
    int MAX_INLINE_DATUM_BYTES = 64 * 1024;
    int MAX_TRANSACTION_BYTES = 1024 * 1024;

    record SpendableInput(
            String transactionId,
            int outputIndex,
            long lovelace
    ) {
        public SpendableInput {
            if (transactionId == null || !transactionId.matches("[0-9a-f]{64}")
                    || outputIndex < 0 || lovelace < 1) {
                throw new IllegalArgumentException("invalid spendable L1 input");
            }
        }
    }

    record PaymentPlan(
            String sourceAddress,
            SpendableInput input,
            String destinationAddress,
            long lovelace,
            byte[] inlineDatum,
            long ttlSlot
    ) {
        public PaymentPlan {
            sourceAddress = address(sourceAddress, "sourceAddress");
            input = Objects.requireNonNull(input, "input");
            destinationAddress = address(destinationAddress, "destinationAddress");
            Objects.requireNonNull(inlineDatum, "inlineDatum");
            if (lovelace < 1 || lovelace > input.lovelace()
                    || inlineDatum.length > MAX_INLINE_DATUM_BYTES
                    || ttlSlot < 1) {
                throw new IllegalArgumentException("invalid unsigned L1 payment plan");
            }
            inlineDatum = inlineDatum.clone();
        }

        @Override public byte[] inlineDatum() { return inlineDatum.clone(); }
    }

    record UnsignedTransaction(
            byte[] cbor,
            String transactionId,
            long fee,
            long ttlSlot
    ) {
        public UnsignedTransaction {
            Objects.requireNonNull(cbor, "cbor");
            if (cbor.length == 0 || cbor.length > MAX_TRANSACTION_BYTES
                    || transactionId == null
                    || !transactionId.matches("[0-9a-f]{64}")
                    || fee < 0 || ttlSlot < 1) {
                throw new IllegalArgumentException("invalid unsigned L1 transaction");
            }
            cbor = cbor.clone();
        }

        @Override public byte[] cbor() { return cbor.clone(); }
    }

    long tipSlot();

    SpendableInput selectSpendableInput(String sourceAddress);

    UnsignedTransaction buildPayment(PaymentPlan plan);

    static L1TransactionBuilderService unavailable() {
        return new L1TransactionBuilderService() {
            @Override public long tipSlot() { throw unavailableFailure(); }
            @Override public SpendableInput selectSpendableInput(String sourceAddress) {
                throw unavailableFailure();
            }
            @Override public UnsignedTransaction buildPayment(PaymentPlan plan) {
                throw unavailableFailure();
            }
            private IllegalStateException unavailableFailure() {
                return new IllegalStateException(
                        "L1 transaction-building service is unavailable");
            }
        };
    }

    private static String address(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > MAX_ADDRESS_LENGTH
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }
}
