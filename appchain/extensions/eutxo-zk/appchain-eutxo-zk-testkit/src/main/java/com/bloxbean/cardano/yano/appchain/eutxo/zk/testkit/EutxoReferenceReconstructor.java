package com.bloxbean.cardano.yano.appchain.eutxo.zk.testkit;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchPublication;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkSettlementPublicInputs;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonHash;
import com.bloxbean.cardano.zeroj.circuit.lib.poseidon.PoseidonParamsBLS12_381T3;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Small reconstruction oracle deliberately independent from the production
 * circuit host helpers and prover pipeline.
 */
public final class EutxoReferenceReconstructor {
    private static final BigInteger FIELD = new BigInteger(
            "52435875175126190479447740508185965837690552500527637822603658699938581184513");
    private static final BigInteger BATCH_DOMAIN =
            scalar(sha256("yano:eutxo:key-payment-batch:v1"
                    .getBytes(StandardCharsets.UTF_8)));

    public Snapshot reconstruct(
            Snapshot start,
            List<EutxoZkBatchPublication> publications
    ) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(publications, "publications");
        Snapshot current = start;
        for (EutxoZkBatchPublication publication : publications) {
            current = apply(current, publication);
        }
        return current;
    }

    public Snapshot apply(
            Snapshot current,
            EutxoZkBatchPublication publication
    ) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(publication, "publication");
        var statement = publication.statement();
        var batch = publication.batchData();
        EutxoZkSettlementPublicInputs inputs = statement.publicInputs();
        if (!statement.chainId().equals(current.chainId())
                || statement.bridgeEpoch() != current.bridgeEpoch()
                || statement.finalizedHeight() <= current.height()
                || !statement.profile().equals(
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT)
                || !inputs.previousRoot().equals(current.root())
                || !inputs.settlementContext().equals(
                current.settlementContext())
                || !Arrays.equals(
                batch.commitment(), statement.batchDataCommitment())
                || !batch.commitmentScalar().equals(
                inputs.batchDataCommitment())
                || !BigInteger.valueOf(batch.payments().size()).equals(
                inputs.batchSize())) {
            throw new IllegalArgumentException(
                    "publication does not continue the verified snapshot");
        }

        BigInteger root = current.root();
        BigInteger digest = BATCH_DOMAIN;
        BigInteger withdrawal = BigInteger.ZERO;
        for (EutxoKeyPaymentBatch.Payment payment : batch.payments()) {
            if (!payment.inputLovelace().equals(
                    payment.firstOutputLovelace().add(
                            payment.secondOutputLovelace()))) {
                throw new IllegalArgumentException(
                        "published payment does not conserve lovelace");
            }
            BigInteger transition = poseidon(
                    poseidon(payment.inputLovelace(),
                            payment.firstOutputLovelace()),
                    payment.secondOutputLovelace());
            root = poseidon(root, transition);
            digest = poseidon(digest, transition);
            withdrawal = withdrawal.add(
                    payment.secondOutputLovelace());
        }
        if (!root.equals(inputs.nextRoot())
                || !digest.equals(inputs.transitionDigest())
                || !withdrawal.equals(
                inputs.withdrawalCommitment())) {
            throw new IllegalArgumentException(
                    "published batch does not reconstruct the accepted root");
        }
        return new Snapshot(
                current.chainId(), current.bridgeEpoch(),
                statement.finalizedHeight(), root,
                current.settlementContext());
    }

    private static BigInteger poseidon(
            BigInteger left,
            BigInteger right
    ) {
        return PoseidonHash.hash(
                PoseidonParamsBLS12_381T3.INSTANCE, left, right);
    }

    private static BigInteger scalar(byte[] bytes) {
        return new BigInteger(1, bytes).mod(FIELD);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible);
        }
    }

    public record Snapshot(
            String chainId,
            long bridgeEpoch,
            long height,
            BigInteger root,
            BigInteger settlementContext
    ) {
        public Snapshot {
            if (chainId == null || chainId.isBlank()
                    || bridgeEpoch < 0 || height < 0) {
                throw new IllegalArgumentException(
                        "invalid reconstruction snapshot identity");
            }
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(
                    settlementContext, "settlementContext");
            if (root.signum() < 0
                    || settlementContext.signum() < 0) {
                throw new IllegalArgumentException(
                        "snapshot scalars cannot be negative");
            }
        }
    }
}
