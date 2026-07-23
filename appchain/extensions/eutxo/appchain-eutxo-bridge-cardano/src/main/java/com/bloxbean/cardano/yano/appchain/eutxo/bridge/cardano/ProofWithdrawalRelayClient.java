package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;

import java.util.Arrays;
import java.util.Objects;

/**
 * Permissionless proof-withdrawal relay boundary.
 *
 * <p>The relayer controls only its fee/collateral inputs and signing key. The
 * accepted root, MPF proof, payout, vault continuation, and nullifier advance
 * are fixed by {@link ProofWithdrawalTransactionBuilder}. Preparation returns
 * the exact signed bytes so callers can persist and resubmit them after an
 * ambiguous network failure without rebuilding the transaction.
 */
public final class ProofWithdrawalRelayClient {
    private final RelayerSigner signer;
    private final CardanoSettlementBackend backend;

    public ProofWithdrawalRelayClient(
            RelayerSigner signer,
            CardanoSettlementBackend backend
    ) {
        this.signer = Objects.requireNonNull(signer, "signer");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    public PreparedRelay prepare(
            ProofWithdrawalTransactionBuilder.Request request
    ) throws Exception {
        ProofWithdrawalTransactionBuilder.Plan plan =
                ProofWithdrawalTransactionBuilder.build(request);
        byte[] signedTransaction = Objects.requireNonNull(
                signer.sign(plan),
                "signed transaction").clone();
        requireExactBody(plan.unsignedBodyCbor(), signedTransaction);
        return new PreparedRelay(
                plan,
                TransactionUtil.getTxHash(signedTransaction),
                signedTransaction);
    }

    public CardanoSettlementBackend.Submission submit(
            PreparedRelay prepared
    ) throws Exception {
        Objects.requireNonNull(prepared, "prepared");
        CardanoSettlementBackend.Submission submission =
                backend.submit(prepared.signedTransactionCbor());
        if (!prepared.transactionId().equals(submission.transactionId())) {
            throw new IllegalArgumentException(
                    "backend returned a different transaction id");
        }
        return submission;
    }

    public CardanoSettlementBackend.Status status(
            PreparedRelay prepared
    ) throws Exception {
        Objects.requireNonNull(prepared, "prepared");
        return backend.status(prepared.transactionId());
    }

    private static void requireExactBody(
            byte[] expectedBody,
            byte[] signedTransaction
    ) throws Exception {
        Transaction decoded = Transaction.deserialize(signedTransaction);
        byte[] actualBody = CborSerializationUtil.serialize(
                decoded.getBody().serialize());
        if (!Arrays.equals(expectedBody, actualBody)) {
            throw new IllegalArgumentException(
                    "relayer signer changed the prepared proof-settlement body");
        }
    }

    @FunctionalInterface
    public interface RelayerSigner {
        /**
         * Assemble witnesses and sign the exact body in {@code plan}. The plan
         * also exposes the proof redeemer needed for both spending scripts.
         */
        byte[] sign(ProofWithdrawalTransactionBuilder.Plan plan)
                throws Exception;
    }

    public record PreparedRelay(
            ProofWithdrawalTransactionBuilder.Plan plan,
            String transactionId,
            byte[] signedTransactionCbor
    ) {
        public PreparedRelay {
            Objects.requireNonNull(plan, "plan");
            if (transactionId == null || transactionId.isBlank()) {
                throw new IllegalArgumentException(
                        "transaction id is required");
            }
            signedTransactionCbor = Objects.requireNonNull(
                    signedTransactionCbor, "signedTransactionCbor").clone();
        }

        @Override
        public byte[] signedTransactionCbor() {
            return signedTransactionCbor.clone();
        }
    }
}
