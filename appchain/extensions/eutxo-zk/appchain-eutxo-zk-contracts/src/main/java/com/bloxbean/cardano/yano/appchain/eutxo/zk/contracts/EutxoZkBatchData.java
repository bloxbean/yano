package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

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
        List<EutxoKeyPaymentBatch.Payment> payments,
        BigInteger ownerCommitment
) {
    private static final int VERSION = 1;

    public EutxoZkBatchData {
        payments = List.copyOf(Objects.requireNonNull(payments, "payments"));
        if (payments.isEmpty()
                || payments.size()
                > EutxoZkProfile.Z1_BOUNDED_KEY_PAYMENTS.maximumBatchSize()) {
            throw new IllegalArgumentException("batch data must contain 1-4 payments");
        }
        Objects.requireNonNull(ownerCommitment, "ownerCommitment");
        if (ownerCommitment.signum() < 0 || ownerCommitment.bitLength() > 255) {
            throw new IllegalArgumentException("owner commitment is outside the scalar envelope");
        }
    }

    public byte[] canonicalBytes() {
        return EutxoZkCodec.encode(output -> {
            output.writeInt(VERSION);
            output.writeByte(payments.size());
            for (EutxoKeyPaymentBatch.Payment payment : payments) {
                output.writeLong(payment.inputLovelace().longValue());
                output.writeLong(payment.firstOutputLovelace().longValue());
                output.writeLong(payment.secondOutputLovelace().longValue());
            }
            EutxoZkCodec.writeScalar(output, ownerCommitment);
        });
    }

    public byte[] commitment() {
        return EutxoZkCodec.sha256(canonicalBytes());
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
            List<EutxoKeyPaymentBatch.Payment> payments = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                payments.add(new EutxoKeyPaymentBatch.Payment(
                        unsigned(input.readLong()),
                        unsigned(input.readLong()),
                        unsigned(input.readLong())));
            }
            BigInteger ownerCommitment = EutxoZkCodec.readScalar(input);
            EutxoZkCodec.requireEnd(input);
            return new EutxoZkBatchData(payments, ownerCommitment);
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
