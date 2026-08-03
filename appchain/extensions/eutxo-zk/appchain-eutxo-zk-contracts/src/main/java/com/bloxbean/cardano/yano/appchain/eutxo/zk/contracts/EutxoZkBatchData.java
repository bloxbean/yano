package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Canonical public data needed to reconstruct a Z1 batch.
 *
 * <p>The owner secret is deliberately excluded. It belongs only in the
 * prover witness store.</p>
 */
public record EutxoZkBatchData(
        List<EutxoKeyPaymentBatch.Payment> payments
) {
    private static final int VERSION = 2;
    public static final int CANONICAL_BYTES = 4 + 1 + (4 * 3 * 8);
    private static final BigInteger BLS12_381_SCALAR_FIELD =
            new BigInteger(
                    "52435875175126190479447740508185965837690552500527637822603658699938581184513");

    public EutxoZkBatchData {
        payments = List.copyOf(Objects.requireNonNull(payments, "payments"));
        if (payments.isEmpty()
                || payments.size()
                > EutxoZkProfile.Z1_BOUNDED_KEY_PAYMENTS.maximumBatchSize()) {
            throw new IllegalArgumentException("batch data must contain 1-4 payments");
        }
    }

    public byte[] canonicalBytes() {
        return EutxoZkCodec.encode(output -> {
            output.writeInt(VERSION);
            output.writeByte(payments.size());
            for (int index = 0;
                 index < EutxoZkProfile.Z1_BOUNDED_KEY_PAYMENTS
                         .maximumBatchSize();
                 index++) {
                if (index < payments.size()) {
                    EutxoKeyPaymentBatch.Payment payment =
                            payments.get(index);
                    output.writeLong(
                            payment.inputLovelace().longValue());
                    output.writeLong(
                            payment.firstOutputLovelace().longValue());
                    output.writeLong(
                            payment.secondOutputLovelace().longValue());
                } else {
                    output.writeLong(0);
                    output.writeLong(0);
                    output.writeLong(0);
                }
            }
        });
    }

    public byte[] commitment() {
        return Blake2bUtil.blake2bHash256(canonicalBytes());
    }

    public BigInteger commitmentScalar() {
        return new BigInteger(1, commitment())
                .mod(BLS12_381_SCALAR_FIELD);
    }

    public static EutxoZkBatchData decode(byte[] encoded) {
        try (DataInputStream input = EutxoZkCodec.input(encoded)) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException("unsupported batch-data version");
            }
            int count = input.readUnsignedByte();
            if (count < 1
                    || count > EutxoZkProfile.Z1_BOUNDED_KEY_PAYMENTS.maximumBatchSize()) {
                throw new IllegalArgumentException("invalid batch-data count");
            }
            List<EutxoKeyPaymentBatch.Payment> payments =
                    new ArrayList<>(count);
            for (int index = 0;
                 index < EutxoZkProfile.Z1_BOUNDED_KEY_PAYMENTS
                         .maximumBatchSize();
                 index++) {
                BigInteger inputAmount = unsigned(input.readLong());
                BigInteger first = unsigned(input.readLong());
                BigInteger second = unsigned(input.readLong());
                if (index < count) {
                    payments.add(new EutxoKeyPaymentBatch.Payment(
                            inputAmount, first, second));
                } else if (inputAmount.signum() != 0
                        || first.signum() != 0
                        || second.signum() != 0) {
                    throw new IllegalArgumentException(
                            "non-zero canonical batch padding");
                }
            }
            EutxoZkCodec.requireEnd(input);
            return new EutxoZkBatchData(payments);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid EUTxO ZK batch data", exception);
        }
    }

    private static BigInteger unsigned(long value) {
        return value >= 0
                ? BigInteger.valueOf(value)
                : BigInteger.valueOf(value & Long.MAX_VALUE).setBit(63);
    }
}
