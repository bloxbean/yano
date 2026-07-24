package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Bounded witness values for the Z1 key-payment batch circuit. */
public record EutxoKeyPaymentBatch(
        List<Payment> payments,
        BigInteger ownerSecret
) {
    public EutxoKeyPaymentBatch {
        payments = List.copyOf(Objects.requireNonNull(payments, "payments"));
        Objects.requireNonNull(ownerSecret, "ownerSecret");
        if (payments.isEmpty()
                || payments.size()
                > EutxoZkProfile.Z1_BOUNDED_KEY_PAYMENTS.maximumBatchSize()) {
            throw new IllegalArgumentException("key-payment batch must contain 1-4 payments");
        }
        if (ownerSecret.signum() <= 0 || ownerSecret.bitLength() > 252) {
            throw new IllegalArgumentException(
                    "owner secret must be a positive 252-bit scalar");
        }
    }

    public record Payment(
            BigInteger inputLovelace,
            BigInteger firstOutputLovelace,
            BigInteger secondOutputLovelace
    ) {
        private static final BigInteger MAX = BigInteger.ONE.shiftLeft(64);

        public Payment {
            requireAmount(inputLovelace, "inputLovelace");
            requireAmount(firstOutputLovelace, "firstOutputLovelace");
            requireAmount(secondOutputLovelace, "secondOutputLovelace");
            if (inputLovelace.signum() <= 0
                    || !inputLovelace.equals(
                    firstOutputLovelace.add(secondOutputLovelace))) {
                throw new IllegalArgumentException(
                        "key payment must conserve a positive input value");
            }
        }

        private static void requireAmount(BigInteger value, String label) {
            Objects.requireNonNull(value, label);
            if (value.signum() < 0 || value.compareTo(MAX) >= 0) {
                throw new IllegalArgumentException(
                        label + " must be an unsigned 64-bit value");
            }
        }
    }
}
