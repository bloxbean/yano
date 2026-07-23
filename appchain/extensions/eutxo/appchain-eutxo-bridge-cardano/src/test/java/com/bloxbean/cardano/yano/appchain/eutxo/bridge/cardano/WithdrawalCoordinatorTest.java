package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalCoordinatorTest {
    private static final String VAULT =
            EutxoTestWallet.fromSeed(fill(32, 4)).address();

    @TempDir
    Path temporaryDirectory;

    @Test
    void crashAfterSubmitRetriesTheSameSignedTransactionWithoutResigning()
            throws Exception {
        EutxoWithdrawalClaim claim = claim();
        AtomicInteger signCalls = new AtomicInteger();
        ExternalSettlementSigner signer = request -> {
            signCalls.incrementAndGet();
            return signed(request.unsignedBodyCbor());
        };
        RecordingBackend backend = new RecordingBackend();
        backend.failFirstSubmission = true;
        FileSettlementJournal journal =
                new FileSettlementJournal(temporaryDirectory.resolve("journal"));
        WithdrawalCoordinator coordinator =
                new WithdrawalCoordinator(signer, backend, journal);

        WithdrawalCoordinator.Result first = coordinator.reconcile(
                claim, inventory(), VAULT,
                BigInteger.ONE, BigInteger.TEN, 100, 20, execution());
        WithdrawalCoordinator.Result second = coordinator.reconcile(
                claim, inventory(), VAULT,
                BigInteger.ONE, BigInteger.TEN, 101, 20, execution());

        assertThat(first.state())
                .isEqualTo(WithdrawalCoordinator.State.RETRY_SAME_TRANSACTION);
        assertThat(second.state()).isEqualTo(WithdrawalCoordinator.State.SUBMITTED);
        assertThat(signCalls).hasValue(1);
        assertThat(backend.submitted).hasSize(2);
        assertThat(backend.submitted.get(0)).isEqualTo(backend.submitted.get(1));
        assertThat(journal.find(claim.claimId())).get()
                .extracting(SettlementJournal.Entry::stage)
                .isEqualTo(SettlementJournal.Stage.SUBMITTED);
        assertThat(coordinator.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.signedTransactions()).isEqualTo(1);
            assertThat(snapshot.retrySameTransaction()).isEqualTo(1);
            assertThat(snapshot.submissions()).isEqualTo(1);
        });
    }

    @Test
    void signerFailureNeverCreatesAJournalEntry() {
        EutxoWithdrawalClaim claim = claim();
        FileSettlementJournal journal =
                new FileSettlementJournal(temporaryDirectory.resolve("failure"));
        WithdrawalCoordinator coordinator = new WithdrawalCoordinator(
                request -> {
                    throw new IllegalStateException("threshold unavailable");
                },
                new RecordingBackend(),
                journal);

        assertThat(coordinator.reconcile(
                claim, inventory(), VAULT,
                BigInteger.ONE, BigInteger.TEN, 100, 20, execution()).state())
                .isEqualTo(WithdrawalCoordinator.State.SIGNER_FAILED);
        assertThat(journal.find(claim.claimId())).isEmpty();
        assertThat(coordinator.snapshot().signerFailures()).isEqualTo(1);
    }

    @Test
    void signerCannotChangeThePreparedSettlementBody() {
        EutxoWithdrawalClaim claim = claim();
        FileSettlementJournal journal =
                new FileSettlementJournal(temporaryDirectory.resolve("changed-body"));
        WithdrawalCoordinator coordinator = new WithdrawalCoordinator(
                request -> signedWithChangedFee(request.unsignedBodyCbor()),
                new RecordingBackend(),
                journal);

        WithdrawalCoordinator.Result result = coordinator.reconcile(
                claim, inventory(), VAULT,
                BigInteger.ONE, BigInteger.TEN, 100, 20, execution());

        assertThat(result.state()).isEqualTo(WithdrawalCoordinator.State.SIGNER_FAILED);
        assertThat(result.detail()).contains("changed the prepared settlement body");
        assertThat(journal.find(claim.claimId())).isEmpty();
    }

    @Test
    void rejectedCompetingVaultInputParksTheOriginalTransaction() throws Exception {
        EutxoWithdrawalClaim claim = claim();
        AtomicInteger signCalls = new AtomicInteger();
        RecordingBackend backend = new RecordingBackend();
        backend.status = CardanoSettlementBackend.Status.REJECTED;
        FileSettlementJournal journal =
                new FileSettlementJournal(temporaryDirectory.resolve("parked"));
        WithdrawalCoordinator coordinator = new WithdrawalCoordinator(
                request -> {
                    signCalls.incrementAndGet();
                    return signed(request.unsignedBodyCbor());
                },
                backend,
                journal);

        assertThat(coordinator.reconcile(
                claim, inventory(), VAULT,
                BigInteger.ONE, BigInteger.TEN, 100, 20, execution()).state())
                .isEqualTo(WithdrawalCoordinator.State.PARKED);
        assertThat(coordinator.reconcile(
                claim, inventory(), VAULT,
                BigInteger.ONE, BigInteger.TEN, 101, 20, execution()).state())
                .isEqualTo(WithdrawalCoordinator.State.PARKED);
        assertThat(signCalls).hasValue(1);
        assertThat(backend.submitted).isEmpty();
        assertThat(journal.find(claim.claimId())).get()
                .extracting(SettlementJournal.Entry::stage)
                .isEqualTo(SettlementJournal.Stage.PARKED);
    }

    @Test
    void vaultInputsAreSelectedInCanonicalOutpointOrder() {
        VaultWithdrawalTransactionBuilder.Plan plan =
                VaultWithdrawalTransactionBuilder.build(
                        claim(),
                        List.of(
                                vaultInput("cc", 40),
                                vaultInput("aa", 20),
                                vaultInput("bb", 30),
                                vaultInput("dd", 50)),
                        VAULT,
                        BigInteger.ONE,
                        BigInteger.TEN,
                        100,
                        20);

        assertThat(plan.selectedInputs())
                .extracting(input -> input.outpoint().transactionId())
                .containsExactly("dd".repeat(32));
        assertThat(plan.continuingVaultLovelace()).isEqualTo(BigInteger.valueOf(19));
        assertThat(plan.ttl()).isEqualTo(120);
    }

    @Test
    void doctorUsesFederatedTrustLabelAndFailsClosedOnUnsafeSignerEndpoint()
            throws Exception {
        Path journal = java.nio.file.Files.createDirectory(
                temporaryDirectory.resolve("doctor"));

        BridgeDoctor.Report ready = BridgeDoctor.inspect(
                new BridgeDoctor.Configuration(
                        java.net.URI.create("https://signer.example/v1/settlements"),
                        journal,
                        false,
                        1,
                        BigInteger.valueOf(50),
                        100));
        BridgeDoctor.Report unsafe = BridgeDoctor.inspect(
                new BridgeDoctor.Configuration(
                        java.net.URI.create("http://signer.example/v1/settlements"),
                        journal,
                        false,
                        1,
                        BigInteger.valueOf(50),
                        100));

        assertThat(ready.ready()).isTrue();
        assertThat(ready.checks())
                .anySatisfy(check -> assertThat(check.detail())
                        .contains(BridgeDoctor.TRUST_LABEL));
        assertThat(unsafe.ready()).isFalse();
    }

    private static ExternalSettlementSigner.SignedSettlement signed(byte[] bodyCbor)
            throws Exception {
        TransactionBody body = TransactionBody.deserialize(
                (co.nstant.in.cbor.model.Map)
                        CborSerializationUtil.deserialize(bodyCbor));
        byte[] transaction = Transaction.builder()
                .body(body)
                .witnessSet(TransactionWitnessSet.builder().build())
                .isValid(true)
                .build()
                .serialize();
        return new ExternalSettlementSigner.SignedSettlement(
                TransactionUtil.getTxHash(transaction), transaction);
    }

    private static ExternalSettlementSigner.SignedSettlement signedWithChangedFee(
            byte[] bodyCbor
    ) throws Exception {
        TransactionBody body = TransactionBody.deserialize(
                (co.nstant.in.cbor.model.Map)
                        CborSerializationUtil.deserialize(bodyCbor));
        body.setFee(body.getFee().add(BigInteger.ONE));
        byte[] transaction = Transaction.builder()
                .body(body)
                .witnessSet(TransactionWitnessSet.builder().build())
                .isValid(true)
                .build()
                .serialize();
        return new ExternalSettlementSigner.SignedSettlement(
                TransactionUtil.getTxHash(transaction), transaction);
    }

    private static EutxoWithdrawalClaim claim() {
        return new EutxoWithdrawalClaim(
                1,
                "payments",
                2,
                new EutxoOutpoint("11".repeat(32), 0),
                EutxoTestWallet.fromSeed(fill(32, 5)).address(),
                BigInteger.valueOf(30),
                fill(32, 6),
                20);
    }

    private static List<VaultWithdrawalTransactionBuilder.VaultInput> inventory() {
        return List.of(vaultInput("22", 100));
    }

    private static VaultWithdrawalTransactionBuilder.ExecutionPolicy execution() {
        return VaultWithdrawalTransactionBuilder.ExecutionPolicy.plutusV3(
                com.bloxbean.cardano.client.spec.NetworkId.TESTNET,
                List.of(new EutxoOutpoint("33".repeat(32), 0)),
                BigInteger.valueOf(5),
                com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                        .address(VAULT)
                        .value(com.bloxbean.cardano.client.transaction.spec.Value.fromCoin(
                                BigInteger.valueOf(5)))
                        .build(),
                List.of(new EutxoOutpoint("44".repeat(32), 0)),
                fill(32, 8),
                List.of(fill(28, 9)));
    }

    private static VaultWithdrawalTransactionBuilder.VaultInput vaultInput(
            String byteHex,
            long lovelace
    ) {
        return new VaultWithdrawalTransactionBuilder.VaultInput(
                new EutxoOutpoint(byteHex.repeat(32), 0),
                BigInteger.valueOf(lovelace));
    }

    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final class RecordingBackend implements CardanoSettlementBackend {
        private final List<byte[]> submitted = new ArrayList<>();
        private Status status = Status.UNKNOWN;
        private boolean failFirstSubmission;

        @Override
        public Submission submit(byte[] signedTransactionCbor) throws Exception {
            submitted.add(signedTransactionCbor.clone());
            if (failFirstSubmission) {
                failFirstSubmission = false;
                throw new IllegalStateException("connection lost after submit");
            }
            String transactionId = TransactionUtil.getTxHash(signedTransactionCbor);
            return new Submission(transactionId, Status.PENDING, "");
        }

        @Override
        public Status status(String transactionId) {
            return status;
        }
    }
}
