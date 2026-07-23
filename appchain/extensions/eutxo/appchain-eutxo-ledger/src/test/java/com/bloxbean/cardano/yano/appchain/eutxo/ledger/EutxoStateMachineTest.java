package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTransactionFixtures;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.MemoryAppState;
import com.bloxbean.cardano.yano.runtime.appchain.StateMachineConformance;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoStateMachineTest {
    private static final EutxoTestWallet ALICE = wallet(1);
    private static final EutxoTestWallet BOB = wallet(2);

    @Test
    void sameBlockChainingWorksAndFirstConflictingSpendWins() {
        EutxoStateMachine machine = machine(ALICE.address(), 100);
        MemoryAppState state = new MemoryAppState();

        // Materialize genesis with an empty first block.
        machine.apply(block(1), state);
        EutxoRecord genesis = records(machine, state, ALICE.address()).getFirst();

        Transaction first = EutxoTransactionFixtures.signedPayment(
                genesis.outpoint(),
                ALICE,
                List.of(
                        payment(BOB.address(), 60),
                        payment(ALICE.address(), 40)),
                0,
                0);
        String firstId = TransactionUtil.getTxHash(
                EutxoTransactionFixtures.serialize(first));
        Transaction chained = EutxoTransactionFixtures.signedPayment(
                new EutxoOutpoint(firstId, 0),
                BOB,
                List.of(payment(ALICE.address(), 60)),
                0,
                0);
        Transaction conflict = EutxoTransactionFixtures.signedPayment(
                genesis.outpoint(),
                ALICE,
                List.of(payment(BOB.address(), 100)),
                0,
                0);

        AppMessage firstMessage = message(11, first);
        AppMessage chainedMessage = message(12, chained);
        AppMessage conflictMessage = message(13, conflict);
        machine.apply(block(2, firstMessage, chainedMessage, conflictMessage), state);
        state.committedHeight(2);

        assertThat(records(machine, state, BOB.address())).isEmpty();
        assertThat(records(machine, state, ALICE.address()))
                .extracting(record -> outputCoin(record).longValueExact())
                .containsExactlyInAnyOrder(40L, 60L);
        assertThat(receipt(machine, state, firstMessage).status())
                .isEqualTo(EutxoReceipt.Status.ACCEPTED);
        assertThat(receipt(machine, state, chainedMessage).status())
                .isEqualTo(EutxoReceipt.Status.ACCEPTED);
        assertThat(receipt(machine, state, conflictMessage).status())
                .isEqualTo(EutxoReceipt.Status.REJECTED);
        assertThat(receipt(machine, state, conflictMessage).code())
                .isEqualTo("INPUT_NOT_FOUND");
    }

    @Test
    void invalidWitnessDoesNotMutateUtxosAndCanBeRetriedAsANewAttempt() {
        EutxoStateMachine machine = machine(ALICE.address(), 50);
        MemoryAppState state = new MemoryAppState();
        machine.apply(block(1), state);
        EutxoRecord genesis = records(machine, state, ALICE.address()).getFirst();

        Transaction wrongSigner = EutxoTransactionFixtures.signedPayment(
                genesis.outpoint(),
                BOB,
                List.of(payment(BOB.address(), 50)),
                0,
                0);
        AppMessage attempt = message(21, wrongSigner);
        byte[] rootBefore = state.stateRoot();
        machine.apply(block(2, attempt), state);

        assertThat(records(machine, state, ALICE.address())).containsExactly(genesis);
        assertThat(receipt(machine, state, attempt).code()).isEqualTo("MISSING_INPUT_WITNESS");
        assertThat(state.stateRoot()).isNotEqualTo(rootBefore); // attempt receipt is committed
        assertThat(machine.query(
                EutxoQueryCodec.TRANSACTION_PATH,
                EutxoQueryCodec.transactionRequest(TransactionUtil.getTxHash(
                        EutxoTransactionFixtures.serialize(wrongSigner))),
                state))
                .isEqualTo(EutxoQueryCodec.optionalReceipt(null));
    }

    @Test
    void replayOnIndependentStateProducesIdenticalRoot() {
        EutxoStateMachine firstMachine = machine(ALICE.address(), 100);
        EutxoStateMachine secondMachine = machine(ALICE.address(), 100);
        MemoryAppState firstState = new MemoryAppState();
        MemoryAppState secondState = new MemoryAppState();
        AppBlock genesisBlock = block(1);
        firstMachine.apply(genesisBlock, firstState);
        secondMachine.apply(genesisBlock, secondState);
        EutxoRecord genesis = records(firstMachine, firstState, ALICE.address()).getFirst();
        Transaction payment = EutxoTransactionFixtures.signedPayment(
                genesis.outpoint(), ALICE, List.of(payment(BOB.address(), 100)), 0, 0);
        AppBlock paymentBlock = block(2, message(31, payment));

        firstMachine.apply(paymentBlock, firstState);
        secondMachine.apply(paymentBlock, secondState);

        assertThat(firstState.sameState(secondState)).isTrue();
        assertThat(firstState.stateRoot()).isEqualTo(secondState.stateRoot());
    }

    @Test
    void threeMemberReplayRestartAndSnapshotProduceIdenticalRoots() {
        Map<String, String> settings = Map.of(
                "machines.eutxo.profile", "yano-eutxo-v1",
                "machines.eutxo.expected-profile-digest",
                "2499d01ee7cb0d09d0d498040c6351accd9da83df31666cd4463d0b1722d1212",
                "machines.eutxo.genesis.address", ALICE.address(),
                "machines.eutxo.genesis.lovelace", "100");
        EutxoRecord genesis = EutxoGenesis.from(settings).records().getFirst();
        List<Transaction> corpus = paymentCorpus(genesis);

        StateMachineConformance.Result result =
                StateMachineConformance.builder(new EutxoStateMachineProvider())
                        .chainId("eutxo-conformance")
                        .settings(settings)
                        .blocks(corpus.size())
                        .messagesPerBlock(1)
                        .runs(3)
                        .restartAtHeight(2)
                        .snapshotAtHeight(3)
                        .messageGenerator((height, index, random) ->
                                new StateMachineConformance.CorpusMessage(
                                        EutxoStateMachine.TOPIC,
                                        EutxoTransactionFixtures.serialize(
                                                corpus.get(Math.toIntExact(height - 1)))))
                        .run();

        assertThat(result.deterministic()).as(result.describeDivergence()).isTrue();
        assertThat(result.outcomesPerRun()).hasSize(4);
        result.outcomesPerRun().forEach(outcomes ->
                assertThat(outcomes).containsKeys(1L, 2L, 3L, 4L));
    }

    private static List<Transaction> paymentCorpus(EutxoRecord genesis) {
        List<Transaction> transactions = new ArrayList<>();
        EutxoOutpoint input = genesis.outpoint();
        EutxoTestWallet owner = ALICE;
        for (int index = 0; index < 4; index++) {
            EutxoTestWallet receiver = owner == ALICE ? BOB : ALICE;
            Transaction transaction = EutxoTransactionFixtures.signedPayment(
                    input, owner, List.of(payment(receiver.address(), 100)), 0, 0);
            transactions.add(transaction);
            input = new EutxoOutpoint(
                    TransactionUtil.getTxHash(EutxoTransactionFixtures.serialize(transaction)),
                    0);
            owner = receiver;
        }
        return List.copyOf(transactions);
    }

    private static EutxoStateMachine machine(String address, long lovelace) {
        Map<String, String> settings = Map.of(
                "machines.eutxo.profile", "yano-eutxo-v1",
                "machines.eutxo.genesis.address", address,
                "machines.eutxo.genesis.lovelace", Long.toString(lovelace));
        return (EutxoStateMachine) new EutxoStateMachineProvider().create(
                new AppStateMachineContext() {
                    @Override
                    public String chainId() {
                        return "eutxo-test";
                    }

                    @Override
                    public Map<String, String> settings() {
                        return settings;
                    }
                });
    }

    private static List<EutxoRecord> records(
            EutxoStateMachine machine,
            MemoryAppState state,
            String address
    ) {
        return EutxoQueryCodec.decodeRecords(machine.query(
                EutxoQueryCodec.ADDRESS_PATH,
                EutxoQueryCodec.addressRequest(address),
                state));
    }

    private static EutxoReceipt receipt(
            EutxoStateMachine machine,
            MemoryAppState state,
            AppMessage message
    ) {
        return EutxoQueryCodec.decodeOptionalReceipt(machine.query(
                EutxoQueryCodec.ATTEMPT_PATH,
                message.getMessageId(),
                state));
    }

    private static BigInteger outputCoin(EutxoRecord record) {
        try {
            return com.bloxbean.cardano.client.transaction.spec.TransactionOutput.deserialize(
                    com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.deserialize(
                            record.outputCbor()))
                    .getValue()
                    .getCoin();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static EutxoTransactionFixtures.Payment payment(String address, long lovelace) {
        return new EutxoTransactionFixtures.Payment(address, BigInteger.valueOf(lovelace));
    }

    private static EutxoTestWallet wallet(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return EutxoTestWallet.fromSeed(seed);
    }

    private static AppMessage message(int idByte, Transaction transaction) {
        byte[] id = new byte[32];
        Arrays.fill(id, (byte) idByte);
        return AppMessage.builder()
                .version(1)
                .messageId(id)
                .chainId("eutxo-test")
                .topic(EutxoStateMachine.TOPIC)
                .sender(new byte[32])
                .senderSeq(idByte)
                .expiresAt(Long.MAX_VALUE)
                .body(EutxoTransactionFixtures.serialize(transaction))
                .authScheme(0)
                .authProof(new byte[64])
                .build();
    }

    private static AppBlock block(long height, AppMessage... messages) {
        return new AppBlock(
                AppBlock.BLOCK_VERSION,
                "eutxo-test",
                height,
                new byte[32],
                0,
                new byte[0],
                height,
                new byte[32],
                new byte[32],
                List.of(messages),
                new byte[32],
                FinalityCert.empty());
    }
}
