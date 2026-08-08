package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import co.nstant.in.cbor.model.Map;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoFederatedRoot;
import com.bloxbean.cardano.yano.appchain.proofs.MpfNormalizedProof;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoNullifierState;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProofWithdrawal;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoStateKeys;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalCommitment;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProofWithdrawalTransactionBuilderTest {
    private static final String VAULT =
            EutxoTestWallet.fromSeed(fill(32, 10)).address();
    private static final String NULLIFIER =
            EutxoTestWallet.fromSeed(fill(32, 11)).address();

    @Test
    void arbitraryRelayerBuildsACompleteCurrentRootSettlement()
            throws Exception {
        Fixture fixture = fixture();
        byte[] policy = fill(28, 12);
        Value threadValue = Value.fromCoin(BigInteger.valueOf(2_000_000))
                .add(HexFormat.of().formatHex(policy), "",
                        BigInteger.ONE);
        ProofWithdrawalTransactionBuilder.Plan plan =
                ProofWithdrawalTransactionBuilder.build(
                        request(fixture, policy, threadValue));

        TransactionBody body = TransactionBody.deserialize(
                (Map) CborSerializationUtil.deserialize(
                        plan.unsignedBodyCbor()));
        assertThat(body.getInputs()).hasSize(3);
        assertThat(body.getOutputs()).hasSize(4);
        assertThat(body.getReferenceInputs()).hasSize(3);
        assertThat(body.getRequiredSigners())
                .singleElement()
                .isEqualTo(fill(28, 31));
        assertThat(plan.nextNullifierState().nextSettlementSequence())
                .isEqualTo(4);
        assertThat(plan.continuingVaultLovelace())
                .isEqualTo(BigInteger.valueOf(70_000_000));
        assertThat(plan.proofRedeemerCbor()).isEqualTo(
                fixture.withdrawal().encode());
        assertThat(plan.unsignedBodyCbor().length).isLessThan(16 * 1024);
        assertThat(plan.proofRedeemerCbor().length).isLessThan(16 * 1024);
        System.out.printf(
                "[ProofWithdrawalTransactionBuilder] bodyBytes=%d, "
                        + "redeemerBytes=%d%n",
                plan.unsignedBodyCbor().length,
                plan.proofRedeemerCbor().length);
    }

    @Test
    void permissionlessRelayReturnsExactReusableSignedBytes()
            throws Exception {
        Fixture fixture = fixture();
        byte[] policy = fill(28, 12);
        Value threadValue = Value.fromCoin(BigInteger.valueOf(2_000_000))
                .add(HexFormat.of().formatHex(policy), "",
                        BigInteger.ONE);
        RecordingBackend backend = new RecordingBackend();
        ProofWithdrawalRelayClient client =
                new ProofWithdrawalRelayClient(
                        plan -> signed(plan.unsignedBodyCbor()),
                        backend);

        ProofWithdrawalRelayClient.PreparedRelay prepared =
                client.prepare(request(fixture, policy, threadValue));
        CardanoSettlementBackend.Submission first =
                client.submit(prepared);
        CardanoSettlementBackend.Submission retry =
                client.submit(prepared);

        assertThat(first.transactionId())
                .isEqualTo(prepared.transactionId())
                .isEqualTo(retry.transactionId());
        assertThat(backend.submitted)
                .hasSize(2)
                .allSatisfy(bytes -> assertThat(bytes)
                        .isEqualTo(prepared.signedTransactionCbor()));
        assertThat(client.status(prepared))
                .isEqualTo(CardanoSettlementBackend.Status.PENDING);
    }

    @Test
    void relaySignerCannotChangeTheProofSettlementBody() throws Exception {
        Fixture fixture = fixture();
        byte[] policy = fill(28, 12);
        Value threadValue = Value.fromCoin(BigInteger.valueOf(2_000_000))
                .add(HexFormat.of().formatHex(policy), "",
                        BigInteger.ONE);
        ProofWithdrawalRelayClient client =
                new ProofWithdrawalRelayClient(
                        plan -> {
                            Transaction transaction =
                                    Transaction.deserialize(signed(
                                            plan.unsignedBodyCbor()));
                            transaction.getBody().setFee(
                                    transaction.getBody().getFee()
                                            .add(BigInteger.ONE));
                            return transaction.serialize();
                        },
                        new RecordingBackend());

        assertThatThrownBy(() ->
                client.prepare(request(fixture, policy, threadValue)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "changed the prepared proof-settlement body");
    }

    @Test
    void relayerFeeMustBeFundedOutsideTheVault() {
        Fixture fixture = fixture();
        byte[] policy = fill(28, 12);
        Value threadValue = Value.fromCoin(BigInteger.valueOf(2_000_000))
                .add(HexFormat.of().formatHex(policy), "",
                        BigInteger.ONE);

        assertThatThrownBy(() ->
                ProofWithdrawalTransactionBuilder.build(
                        request(
                                fixture,
                                policy,
                                threadValue,
                                execution(
                                        BigInteger.valueOf(3_000_000),
                                        BigInteger.valueOf(1_000_000)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fee inputs");
    }

    @Test
    void selectorRejectsOldRootsAndAmbiguousCurrentState() {
        Fixture fixture = fixture();
        EutxoFederatedRoot newer = new EutxoFederatedRoot(
                1,
                "payments",
                7,
                43,
                fill(32, 44),
                List.of(fill(32, 1)),
                1,
                0);

        assertThatThrownBy(() -> FederatedRootSelector.selectCurrent(
                List.of(
                        new FederatedRootSelector.Candidate(
                                outpoint(1), fixture.root()),
                        new FederatedRootSelector.Candidate(
                                outpoint(2), newer)),
                fixture.withdrawal()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current accepted root");
        assertThatThrownBy(() -> FederatedRootSelector.selectCurrent(
                List.of(
                        new FederatedRootSelector.Candidate(
                                outpoint(1), fixture.root()),
                        new FederatedRootSelector.Candidate(
                                outpoint(2), fixture.root())),
                fixture.withdrawal()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void selectorIgnoresNewerRootsFromOtherChains() {
        Fixture fixture = fixture();
        EutxoFederatedRoot foreign = new EutxoFederatedRoot(
                1,
                "other-chain",
                99,
                999,
                fill(32, 44),
                List.of(fill(32, 1)),
                1,
                99);

        FederatedRootSelector.Candidate selected =
                FederatedRootSelector.selectCurrent(
                        List.of(
                                new FederatedRootSelector.Candidate(
                                        outpoint(1), fixture.root()),
                                new FederatedRootSelector.Candidate(
                                        outpoint(2), foreign)),
                        fixture.withdrawal());

        assertThat(selected.root()).isEqualTo(fixture.root());
    }

    private static Fixture fixture() {
        EutxoWithdrawalClaim claim = new EutxoWithdrawalClaim(
                1,
                "payments",
                7,
                outpoint(3),
                EutxoTestWallet.fromSeed(fill(32, 13)).address(),
                BigInteger.valueOf(30_000_000),
                fill(32, 14),
                3,
                42);
        EutxoWithdrawalCommitment commitment =
                EutxoWithdrawalCommitment.fromClaim(claim);
        byte[] key = EutxoStateKeys.withdrawalCommitment(claim.claimId());
        byte[] value = commitment.encode();
        byte[] path = MpfNormalizedProof.nibbles(
                Blake2bUtil.blake2bHash256(key));
        byte[] suffix = MpfNormalizedProof.encodeLeafSuffix(path);
        byte[] leaf = concatenate(
                suffix, Blake2bUtil.blake2bHash256(value));
        byte[] rootHash = Blake2bUtil.blake2bHash256(leaf);
        MpfNormalizedProof proof = new MpfNormalizedProof(
                rootHash, key, value, suffix, List.of(), 42);
        EutxoProofWithdrawal withdrawal =
                new EutxoProofWithdrawal(1, commitment, proof);
        EutxoFederatedRoot root = new EutxoFederatedRoot(
                1,
                "payments",
                7,
                42,
                rootHash,
                List.of(fill(32, 1)),
                1,
                0);
        return new Fixture(
                withdrawal,
                root,
                new EutxoNullifierState(
                        1, "payments", 7, 3, 0));
    }

    private static ProofWithdrawalTransactionBuilder.Execution execution() {
        return execution(
                BigInteger.valueOf(3_000_000),
                BigInteger.valueOf(2_000_000));
    }

    private static ProofWithdrawalTransactionBuilder.Execution execution(
            BigInteger feeInput,
            BigInteger feeChange
    ) {
        return new ProofWithdrawalTransactionBuilder.Execution(
                NetworkId.TESTNET,
                List.of(new ProofWithdrawalTransactionBuilder.FundingInput(
                        outpoint(26),
                        feeInput)),
                TransactionOutput.builder()
                        .address(VAULT)
                        .value(Value.fromCoin(feeChange))
                        .build(),
                List.of(outpoint(23)),
                BigInteger.valueOf(5_000_000),
                TransactionOutput.builder()
                        .address(VAULT)
                        .value(Value.fromCoin(
                                BigInteger.valueOf(5_000_000)))
                        .build(),
                List.of(outpoint(24), outpoint(25)),
                fill(32, 30),
                List.of(fill(28, 31)));
    }

    private static ProofWithdrawalTransactionBuilder.Request request(
            Fixture fixture,
            byte[] policy,
            Value threadValue
    ) {
        return request(fixture, policy, threadValue, execution());
    }

    private static ProofWithdrawalTransactionBuilder.Request request(
            Fixture fixture,
            byte[] policy,
            Value threadValue,
            ProofWithdrawalTransactionBuilder.Execution execution
    ) {
        return new ProofWithdrawalTransactionBuilder.Request(
                fixture.withdrawal(),
                fixture.root(),
                fixture.nullifier(),
                List.of(new VaultWithdrawalTransactionBuilder.VaultInput(
                        outpoint(20),
                        BigInteger.valueOf(100_000_000))),
                EutxoTestWallet.fromSeed(fill(32, 13)).address(),
                VAULT,
                new ProofWithdrawalTransactionBuilder.ThreadStateInput(
                        outpoint(21),
                        NULLIFIER,
                        threadValue,
                        policy,
                        new byte[0]),
                outpoint(22),
                BigInteger.valueOf(1_000_000),
                BigInteger.valueOf(2_000_000),
                100,
                30,
                execution);
    }

    private static byte[] signed(byte[] bodyCbor) throws Exception {
        TransactionBody body = TransactionBody.deserialize(
                (Map) CborSerializationUtil.deserialize(bodyCbor));
        return Transaction.builder()
                .body(body)
                .witnessSet(TransactionWitnessSet.builder().build())
                .isValid(true)
                .build()
                .serialize();
    }

    private static EutxoOutpoint outpoint(int value) {
        return new EutxoOutpoint(
                "%02x".formatted(value).repeat(32), 0);
    }

    private static byte[] concatenate(byte[] left, byte[] right) {
        byte[] joined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        return joined;
    }

    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private record Fixture(
            EutxoProofWithdrawal withdrawal,
            EutxoFederatedRoot root,
            EutxoNullifierState nullifier
    ) {
    }

    private static final class RecordingBackend
            implements CardanoSettlementBackend {
        private final List<byte[]> submitted = new ArrayList<>();

        @Override
        public Submission submit(byte[] signedTransactionCbor)
                throws Exception {
            submitted.add(signedTransactionCbor.clone());
            return new Submission(
                    TransactionUtil.getTxHash(signedTransactionCbor),
                    Status.PENDING,
                    "");
        }

        @Override
        public Status status(String transactionId) {
            return Status.PENDING;
        }
    }
}
