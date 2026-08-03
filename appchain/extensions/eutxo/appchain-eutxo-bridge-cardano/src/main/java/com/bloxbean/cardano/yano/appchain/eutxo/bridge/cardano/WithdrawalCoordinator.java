package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Single-claim crash-safe coordinator. It write-ahead persists exact signed
 * bytes before submission and never builds a second transaction for a claim.
 */
public final class WithdrawalCoordinator {
    private final ExternalSettlementSigner signer;
    private final CardanoSettlementBackend backend;
    private final SettlementJournal journal;
    private final LongAdder reconcileAttempts = new LongAdder();
    private final LongAdder signedTransactions = new LongAdder();
    private final LongAdder submissions = new LongAdder();
    private final LongAdder retrySameTransaction = new LongAdder();
    private final LongAdder signerFailures = new LongAdder();
    private final LongAdder parked = new LongAdder();
    private final LongAdder confirmed = new LongAdder();

    public WithdrawalCoordinator(
            ExternalSettlementSigner signer,
            CardanoSettlementBackend backend,
            SettlementJournal journal
    ) {
        this.signer = Objects.requireNonNull(signer, "signer");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    public Result reconcile(
            EutxoWithdrawalClaim claim,
            List<VaultWithdrawalTransactionBuilder.VaultInput> inventory,
            String vaultAddress,
            java.math.BigInteger fee,
            java.math.BigInteger minimumContinuingLovelace,
            long currentSlot,
            long ttlSlots,
            VaultWithdrawalTransactionBuilder.ExecutionPolicy execution
    ) {
        Objects.requireNonNull(claim, "claim");
        reconcileAttempts.increment();
        SettlementJournal.Entry retained = journal.find(claim.claimId()).orElse(null);
        if (retained == null) {
            try {
                VaultWithdrawalTransactionBuilder.Plan plan =
                        VaultWithdrawalTransactionBuilder.build(
                                claim, inventory, vaultAddress, fee,
                                minimumContinuingLovelace, currentSlot, ttlSlots,
                                execution);
                if (!plan.submitReady()) {
                    throw new IllegalArgumentException(
                            "settlement transaction body is not submit-ready");
                }
                ExternalSettlementSigner.SignedSettlement signed = signer.sign(
                        new ExternalSettlementSigner.SigningRequest(
                                claim, plan.unsignedBodyCbor()));
                requireExactSignedBody(
                        plan.unsignedBodyCbor(), signed.transactionCbor());
                retained = new SettlementJournal.Entry(
                        claim.claimId(),
                        signed.transactionId(),
                        signed.transactionCbor(),
                        SettlementJournal.Stage.SIGNED,
                        "");
                journal.save(retained);
                signedTransactions.increment();
            } catch (Exception failure) {
                signerFailures.increment();
                return new Result(
                        State.SIGNER_FAILED, "", safeMessage(failure));
            }
        }
        if (!claim.claimId().equals(retained.claimId())) {
            parked.increment();
            return new Result(
                    State.PARKED, retained.transactionId(),
                    "journal claim identity mismatch");
        }
        if (retained.stage() == SettlementJournal.Stage.CONFIRMED) {
            confirmed.increment();
            return new Result(State.CONFIRMED, retained.transactionId(), retained.detail());
        }
        try {
            CardanoSettlementBackend.Status observed =
                    backend.status(retained.transactionId());
            if (observed == CardanoSettlementBackend.Status.CONFIRMED) {
                journal.save(retained.advance(
                        SettlementJournal.Stage.CONFIRMED, "confirmed"));
                confirmed.increment();
                return new Result(State.CONFIRMED, retained.transactionId(), "");
            }
            if (retained.stage() == SettlementJournal.Stage.PARKED
                    || observed == CardanoSettlementBackend.Status.REJECTED) {
                journal.save(retained.advance(
                        SettlementJournal.Stage.PARKED,
                        "backend rejected the exact signed settlement"));
                parked.increment();
                return new Result(
                        State.PARKED, retained.transactionId(),
                        "backend rejected the exact signed settlement");
            }
            CardanoSettlementBackend.Submission submitted =
                    backend.submit(retained.signedTransactionCbor());
            if (!retained.transactionId().equals(submitted.transactionId())) {
                journal.save(retained.advance(
                        SettlementJournal.Stage.PARKED,
                        "backend transaction-id mismatch"));
                parked.increment();
                return new Result(
                        State.PARKED, retained.transactionId(),
                        "backend transaction-id mismatch");
            }
            SettlementJournal.Stage stage =
                    submitted.status() == CardanoSettlementBackend.Status.CONFIRMED
                            ? SettlementJournal.Stage.CONFIRMED
                            : SettlementJournal.Stage.SUBMITTED;
            journal.save(retained.advance(stage, submitted.detail()));
            submissions.increment();
            if (stage == SettlementJournal.Stage.CONFIRMED) {
                confirmed.increment();
            }
            return new Result(
                    stage == SettlementJournal.Stage.CONFIRMED
                            ? State.CONFIRMED : State.SUBMITTED,
                    retained.transactionId(),
                    submitted.detail());
        } catch (Exception failure) {
            // The SIGNED/SUBMITTED bytes remain the only transaction this
            // claim may use. A retry re-polls and re-submits the same tx ID.
            retrySameTransaction.increment();
            return new Result(
                    State.RETRY_SAME_TRANSACTION,
                    retained.transactionId(),
                    safeMessage(failure));
        }
    }

    public BridgeSettlementSnapshot snapshot() {
        return new BridgeSettlementSnapshot(
                reconcileAttempts.sum(),
                signedTransactions.sum(),
                submissions.sum(),
                retrySameTransaction.sum(),
                signerFailures.sum(),
                parked.sum(),
                confirmed.sum());
    }

    public enum State {
        SIGNER_FAILED,
        SUBMITTED,
        RETRY_SAME_TRANSACTION,
        CONFIRMED,
        PARKED
    }

    public record Result(State state, String transactionId, String detail) {
        public Result {
            Objects.requireNonNull(state, "state");
            transactionId = Objects.requireNonNullElse(transactionId, "");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private static void requireExactSignedBody(
            byte[] expectedBody,
            byte[] signedTransaction
    ) throws Exception {
        com.bloxbean.cardano.client.transaction.spec.Transaction decoded =
                com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(
                        signedTransaction);
        byte[] actualBody =
                com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(
                        decoded.getBody().serialize());
        if (!java.util.Arrays.equals(expectedBody, actualBody)) {
            throw new IllegalArgumentException(
                    "external signer changed the prepared settlement body");
        }
    }
}
