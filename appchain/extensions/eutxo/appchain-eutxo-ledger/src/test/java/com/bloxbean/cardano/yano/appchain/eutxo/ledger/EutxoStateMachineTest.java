package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.CostMdls;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.plutus.util.ScriptDataHashGenerator;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReserve;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoStateKeys;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalConfirmation;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoStateMachineTest {
    private static final EutxoTestWallet ALICE = wallet(1);
    private static final EutxoTestWallet BOB = wallet(2);

    @Test
    void sameBlockChainingWorksAndFirstConflictingSpendWins() {
        EutxoStateMachine machine = machine(ALICE.address(), 100);
        MemoryAppState state = new MemoryAppState();

        // Materialize genesis with an empty first block.
        apply(machine, block(1), state);
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
        apply(machine, block(2, firstMessage, chainedMessage, conflictMessage), state);
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
        List<EutxoTransactionSummary> summaries =
                EutxoTransactionSummary.decodeList(machine.query(
                        EutxoQueryCodec.TRANSACTION_SUMMARIES_PATH,
                        EutxoQueryCodec.summaryPageRequest(0, 10),
                        state));
        assertThat(summaries).hasSize(3);
        assertThat(summaries.getFirst().messageId())
                .isEqualTo(java.util.HexFormat.of().formatHex(
                        conflictMessage.getMessageId()));
        EutxoTransactionSummary accepted =
                EutxoTransactionSummary.decode(machine.query(
                        EutxoQueryCodec.TRANSACTION_SUMMARY_PATH,
                        EutxoQueryCodec.transactionRequest(firstId),
                        state));
        assertThat(accepted.status())
                .isEqualTo(EutxoTransactionSummary.Status.ACCEPTED);
        assertThat(accepted.inputs()).hasSize(1);
        assertThat(accepted.outputs()).extracting(
                        entry -> entry.lovelace().longValueExact())
                .containsExactly(60L, 40L);
    }

    @Test
    void invalidWitnessDoesNotMutateUtxosAndCanBeRetriedAsANewAttempt() {
        EutxoStateMachine machine = machine(ALICE.address(), 50);
        MemoryAppState state = new MemoryAppState();
        apply(machine, block(1), state);
        EutxoRecord genesis = records(machine, state, ALICE.address()).getFirst();

        Transaction wrongSigner = EutxoTransactionFixtures.signedPayment(
                genesis.outpoint(),
                BOB,
                List.of(payment(BOB.address(), 50)),
                0,
                0);
        AppMessage attempt = message(21, wrongSigner);
        byte[] rootBefore = state.stateRoot();
        apply(machine, block(2, attempt), state);

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
        apply(firstMachine, genesisBlock, firstState);
        apply(secondMachine, genesisBlock, secondState);
        EutxoRecord genesis = records(firstMachine, firstState, ALICE.address()).getFirst();
        Transaction payment = EutxoTransactionFixtures.signedPayment(
                genesis.outpoint(), ALICE, List.of(payment(BOB.address(), 100)), 0, 0);
        AppBlock paymentBlock = block(2, message(31, payment));

        apply(firstMachine, paymentBlock, firstState);
        apply(secondMachine, paymentBlock, secondState);

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

    @Test
    void plutusV3AlwaysSucceedsExecutesThroughScalusAndMutatesExactOutputs()
            throws Exception {
        PlutusV3Script script = PlutusV3Script.builder()
                .type("PlutusScriptV3")
                .cborHex("46450101002499")
                .build();
        String scriptAddress =
                AddressProvider.getEntAddress(script, Networks.testnet()).getAddress();
        Map<String, String> settings = Map.of(
                "machines.eutxo.profile", EutxoProfile.V2.id(),
                "machines.eutxo.genesis.address", scriptAddress,
                "machines.eutxo.genesis.lovelace", "100",
                "machines.eutxo.genesis.inline-datum-hex",
                PlutusData.unit().serializeToHex());
        EutxoGenesis genesis = EutxoGenesis.from(settings);
        EutxoStateMachine machine = new EutxoStateMachine(
                EutxoProfile.V2,
                genesis,
                new KeyPaymentTransitionEngine(EutxoProfile.V2));
        MemoryAppState state = new MemoryAppState();
        apply(machine, block(1), state);

        Transaction spend = plutusSpend(
                genesis.records().getFirst().outpoint(), script, ALICE.address(), 100);
        AppMessage submitted = message(41, spend);
        apply(machine, block(2, submitted), state);

        EutxoReceipt receipt = receipt(machine, state, submitted);
        assertThat(receipt.status()).isEqualTo(EutxoReceipt.Status.ACCEPTED);
        assertThat(records(machine, state, scriptAddress)).isEmpty();
        assertThat(records(machine, state, ALICE.address())).hasSize(1);
    }

    @Test
    void rejectedPlutusEvaluationHasStableCodeAndDoesNotSpendInput()
            throws Exception {
        PlutusV3Script script = PlutusV3Script.builder()
                .type("PlutusScriptV3")
                .cborHex("46450101002499")
                .build();
        String scriptAddress =
                AddressProvider.getEntAddress(script, Networks.testnet()).getAddress();
        Map<String, String> settings = Map.of(
                "machines.eutxo.genesis.address", scriptAddress,
                "machines.eutxo.genesis.lovelace", "100",
                "machines.eutxo.genesis.inline-datum-hex",
                PlutusData.unit().serializeToHex());
        EutxoGenesis genesis = EutxoGenesis.from(settings);
        EutxoStateMachine machine = new EutxoStateMachine(
                EutxoProfile.V2,
                genesis,
                new KeyPaymentTransitionEngine(
                        EutxoProfile.V2,
                        com.bloxbean.cardano.client.crypto.config.CryptoConfiguration
                                .INSTANCE.getSigningProvider(),
                        (transaction, inputs) -> PlutusV3Evaluator.Evaluation.reject(
                                "PLUTUS_VALIDATION_FAILED", "bounded rejection")));
        MemoryAppState state = new MemoryAppState();
        apply(machine, block(1), state);
        AppMessage submitted = message(42, plutusSpend(
                genesis.records().getFirst().outpoint(), script, ALICE.address(), 100));

        apply(machine, block(2, submitted), state);

        assertThat(receipt(machine, state, submitted).code())
                .isEqualTo("PLUTUS_VALIDATION_FAILED");
        assertThat(records(machine, state, scriptAddress)).hasSize(1);
        assertThat(records(machine, state, ALICE.address())).isEmpty();
    }

    @Test
    void scriptInputCannotBeSpentWithoutAPlutusWitness() throws Exception {
        PlutusV3Script script = PlutusV3Script.builder()
                .type("PlutusScriptV3")
                .cborHex("46450101002499")
                .build();
        String scriptAddress =
                AddressProvider.getEntAddress(script, Networks.testnet()).getAddress();
        Map<String, String> settings = Map.of(
                "machines.eutxo.profile", EutxoProfile.V2.id(),
                "machines.eutxo.genesis.address", scriptAddress,
                "machines.eutxo.genesis.lovelace", "100",
                "machines.eutxo.genesis.inline-datum-hex",
                PlutusData.unit().serializeToHex());
        EutxoGenesis genesis = EutxoGenesis.from(settings);
        EutxoStateMachine machine = (EutxoStateMachine)
                new EutxoStateMachineProvider().create(context(settings));
        MemoryAppState state = new MemoryAppState();
        apply(machine, block(1), state);
        Transaction unsigned = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(TransactionInput.builder()
                                .transactionId(genesis.transactionId())
                                .index(0)
                                .build()))
                        .outputs(List.of(TransactionOutput.builder()
                                .address(ALICE.address())
                                .value(Value.fromCoin(BigInteger.valueOf(100)))
                                .build()))
                        .fee(BigInteger.ZERO)
                        .networkId(NetworkId.TESTNET)
                        .build())
                .isValid(true)
                .build();
        AppMessage submitted = message(43, unsigned);

        apply(machine, block(2, submitted), state);

        assertThat(receipt(machine, state, submitted).code())
                .isEqualTo("SCRIPT_WITNESS_MISSING");
        assertThat(records(machine, state, scriptAddress)).hasSize(1);
    }

    @Test
    void stableVaultObservationCreditsExactlyOnceAndUpdatesReserveAtomically()
            throws Exception {
        String vaultAddress = "addr_test1_bridge_vault";
        String vaultScriptHash = "11".repeat(28);
        Map<String, String> settings = Map.of(
                "machines.eutxo.profile", EutxoProfile.V2.id(),
                "machines.eutxo.bridge.observer-id", "bridge-deposits",
                "machines.eutxo.bridge.vault-address", vaultAddress,
                "machines.eutxo.bridge.vault-script-hash", vaultScriptHash);
        EutxoStateMachine machine = (EutxoStateMachine)
                new EutxoStateMachineProvider().create(context(settings));
        MemoryAppState state = new MemoryAppState();
        TransactionOutput mirroredOutput = TransactionOutput.builder()
                .address(ALICE.address())
                .value(Value.fromCoin(BigInteger.valueOf(25)))
                .build();
        byte[] outputCbor =
                com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(
                        mirroredOutput.serialize());
        EutxoDepositClaim claim = new EutxoDepositClaim(
                EutxoDepositClaim.ABI_VERSION,
                "eutxo-test",
                new EutxoOutpoint("22".repeat(32), 1),
                100,
                fill(32, 3),
                vaultAddress,
                vaultScriptHash,
                outputCbor,
                ALICE.address(),
                outputCbor,
                fill(32, 4),
                new EutxoOutpoint("33".repeat(32), 0),
                1_000);
        L1Observation observation = new L1Observation(
                "bridge-deposits",
                java.util.HexFormat.of().parseHex("22".repeat(32)),
                100,
                fill(32, 3),
                claim.encode());
        AppMessage deposit = observationMessage(51, observation);

        assertThat(machine.validate(deposit).isAccepted()).isTrue();
        apply(machine, block(1, deposit, deposit), state);

        assertThat(records(machine, state, ALICE.address()))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.origin()).isEqualTo(EutxoRecord.Origin.L1_DEPOSIT);
                    assertThat(record.outpoint()).isEqualTo(claim.mirroredOutpoint());
                });
        EutxoDepositRecord retained = state.get(
                        EutxoStateKeys.deposit(claim.acceptedOutpoint()))
                .map(EutxoDepositRecord::decode)
                .orElseThrow();
        assertThat(retained.claim()).isEqualTo(claim);
        EutxoReserve reserve = state.get(EutxoStateKeys.reserve(EutxoReserve.LOVELACE))
                .map(EutxoReserve::decode)
                .orElseThrow();
        assertThat(reserve.stableVault()).isEqualTo(BigInteger.valueOf(25));
        assertThat(reserve.spendableMirrored()).isEqualTo(BigInteger.valueOf(25));
    }

    @Test
    void signedWithdrawalBecomesIrrevocableAndExactL1ConfirmationReconcilesReserve()
            throws Exception {
        String vaultAddress = "addr_test1_bridge_vault";
        String vaultScriptHash = "11".repeat(28);
        String withdrawalAddress = BOB.address();
        Map<String, String> settings = Map.ofEntries(
                Map.entry("machines.eutxo.profile", EutxoProfile.V2.id()),
                Map.entry("machines.eutxo.bridge.observer-id", "bridge-deposits"),
                Map.entry("machines.eutxo.bridge.vault-address", vaultAddress),
                Map.entry("machines.eutxo.bridge.vault-script-hash", vaultScriptHash),
                Map.entry("machines.eutxo.bridge.confirmation-observer-id",
                        "bridge-withdrawals"),
                Map.entry("machines.eutxo.bridge.withdrawal-address", withdrawalAddress),
                Map.entry("machines.eutxo.bridge.epoch", "7"),
                Map.entry("machines.eutxo.bridge.max-withdrawal-lovelace", "25"),
                Map.entry("machines.eutxo.bridge.max-pending-withdrawals", "2"),
                Map.entry("machines.eutxo.bridge.withdrawals-paused", "false"));
        EutxoStateMachine machine = (EutxoStateMachine)
                new EutxoStateMachineProvider().create(context(settings));
        MemoryAppState state = new MemoryAppState();
        EutxoDepositClaim depositClaim = depositClaim(
                vaultAddress, vaultScriptHash, BigInteger.valueOf(25));
        L1Observation depositObservation = new L1Observation(
                "bridge-deposits",
                java.util.HexFormat.of().parseHex(
                        depositClaim.acceptedOutpoint().transactionId()),
                depositClaim.l1Slot(),
                depositClaim.l1BlockHash(),
                depositClaim.encode());
        apply(machine, block(1, observationMessage(61, depositObservation)), state);

        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                1, "eutxo-test", 7, ALICE.address(), fill(32, 6));
        Transaction withdrawal = EutxoTransactionFixtures.signedOutputs(
                depositClaim.mirroredOutpoint(),
                ALICE,
                List.of(TransactionOutput.builder()
                        .address(withdrawalAddress)
                        .value(Value.fromCoin(BigInteger.valueOf(25)))
                        .inlineDatum(PlutusData.deserialize(datum.encode()))
                        .build()),
                0,
                0);
        String withdrawalTxId = TransactionUtil.getTxHash(
                EutxoTransactionFixtures.serialize(withdrawal));
        EutxoWithdrawalClaim claim = new EutxoWithdrawalClaim(
                1,
                "eutxo-test",
                7,
                new EutxoOutpoint(withdrawalTxId, 0),
                ALICE.address(),
                BigInteger.valueOf(25),
                datum.nonce(),
                0,
                2);
        AppMessage withdrawalMessage = message(62, withdrawal);
        apply(machine, block(2, withdrawalMessage), state);

        assertThat(receipt(machine, state, withdrawalMessage).status())
                .isEqualTo(EutxoReceipt.Status.ACCEPTED);
        assertThat(records(machine, state, ALICE.address())).isEmpty();
        assertThat(records(machine, state, withdrawalAddress)).isEmpty();
        EutxoWithdrawalRecord pending = EutxoQueryCodec.decodeOptionalWithdrawalRecord(
                machine.query(
                        EutxoQueryCodec.WITHDRAWAL_PATH,
                        EutxoQueryCodec.withdrawalRequest(claim.claimId()),
                        state));
        assertThat(pending.status()).isEqualTo(EutxoWithdrawalRecord.Status.PENDING);
        assertThat(EutxoQueryCodec.decodeDepositRecords(machine.query(
                EutxoQueryCodec.DEPOSITS_PATH,
                EutxoQueryCodec.lifecyclePageRequest(0, 10),
                state))).extracting(record -> record.claim().acceptedOutpoint())
                .containsExactly(depositClaim.acceptedOutpoint());
        assertThat(EutxoQueryCodec.decodeCount(machine.query(
                EutxoQueryCodec.DEPOSIT_COUNT_PATH, new byte[0], state)))
                .isEqualTo(1);
        assertThat(EutxoQueryCodec.decodeWithdrawalRecords(machine.query(
                EutxoQueryCodec.WITHDRAWALS_PATH,
                EutxoQueryCodec.lifecyclePageRequest(0, 10),
                state))).extracting(record -> record.claim().claimId())
                .containsExactly(claim.claimId());
        assertThat(EutxoQueryCodec.decodeCount(machine.query(
                EutxoQueryCodec.WITHDRAWAL_COUNT_PATH, new byte[0], state)))
                .isEqualTo(1);
        EutxoReserve pendingReserve = EutxoReserve.decode(
                state.get(EutxoStateKeys.reserve(EutxoReserve.LOVELACE)).orElseThrow());
        assertThat(pendingReserve.spendableMirrored()).isZero();
        assertThat(pendingReserve.pendingWithdrawals()).isEqualTo(BigInteger.valueOf(25));

        EutxoWithdrawalConfirmation confirmation =
                new EutxoWithdrawalConfirmation(
                        1,
                        "eutxo-test",
                        7,
                        claim.claimId(),
                        "77".repeat(32),
                        0,
                        ALICE.address(),
                        BigInteger.valueOf(25),
                        new EutxoOutpoint("77".repeat(32), 1),
                        BigInteger.ZERO,
                        200,
                        fill(32, 7));
        L1Observation confirmationObservation = new L1Observation(
                "bridge-withdrawals",
                java.util.HexFormat.of().parseHex("77".repeat(32)),
                200,
                fill(32, 7),
                confirmation.encode());
        AppMessage confirmationMessage =
                observationMessage(63, confirmationObservation);
        apply(machine, block(3, confirmationMessage, confirmationMessage), state);

        EutxoWithdrawalRecord confirmed = EutxoQueryCodec.decodeOptionalWithdrawalRecord(
                machine.query(
                        EutxoQueryCodec.WITHDRAWAL_PATH,
                        EutxoQueryCodec.withdrawalRequest(claim.claimId()),
                        state));
        assertThat(confirmed.status()).isEqualTo(EutxoWithdrawalRecord.Status.CONFIRMED);
        assertThat(EutxoQueryCodec.decodeWithdrawalRecords(machine.query(
                EutxoQueryCodec.WITHDRAWALS_PATH,
                EutxoQueryCodec.lifecyclePageRequest(0, 10),
                state))).extracting(EutxoWithdrawalRecord::status)
                .containsExactly(EutxoWithdrawalRecord.Status.CONFIRMED);
        EutxoReserve reconciled = EutxoReserve.decode(
                state.get(EutxoStateKeys.reserve(EutxoReserve.LOVELACE)).orElseThrow());
        assertThat(reconciled.stableVault()).isZero();
        assertThat(reconciled.pendingWithdrawals()).isZero();
        assertThat(reconciled.confirmedWithdrawals()).isEqualTo(BigInteger.valueOf(25));
    }

    @Test
    void invalidWithdrawalPauseFlagFailsClosed() {
        Map<String, String> settings = Map.of(
                "machines.eutxo.bridge.observer-id", "bridge-deposits",
                "machines.eutxo.bridge.vault-address", "addr_test1vault",
                "machines.eutxo.bridge.vault-script-hash", "11".repeat(28),
                "machines.eutxo.bridge.confirmation-observer-id", "bridge-withdrawals",
                "machines.eutxo.bridge.withdrawal-address", BOB.address(),
                "machines.eutxo.bridge.withdrawals-paused", "yes");

        assertThatThrownBy(() -> new EutxoStateMachineProvider().create(context(settings)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("true or false");
    }

    @Test
    void unknownStableSettlementHaltsTheBridgeWithoutCrashingBlockApplication() {
        Map<String, String> settings = Map.of(
                "machines.eutxo.bridge.observer-id", "bridge-deposits",
                "machines.eutxo.bridge.vault-address", "addr_test1vault",
                "machines.eutxo.bridge.vault-script-hash", "11".repeat(28),
                "machines.eutxo.bridge.confirmation-observer-id", "bridge-withdrawals",
                "machines.eutxo.bridge.withdrawal-address", BOB.address(),
                "machines.eutxo.bridge.epoch", "1");
        EutxoStateMachine machine = (EutxoStateMachine)
                new EutxoStateMachineProvider().create(context(settings));
        MemoryAppState state = new MemoryAppState();
        EutxoWithdrawalConfirmation unknown = new EutxoWithdrawalConfirmation(
                1,
                "eutxo-test",
                1,
                "88".repeat(32),
                "99".repeat(32),
                0,
                ALICE.address(),
                BigInteger.ONE,
                new EutxoOutpoint("99".repeat(32), 1),
                BigInteger.TEN,
                300,
                fill(32, 9));
        L1Observation observation = new L1Observation(
                "bridge-withdrawals",
                java.util.HexFormat.of().parseHex("99".repeat(32)),
                300,
                fill(32, 9),
                unknown.encode());

        apply(machine, block(1, observationMessage(64, observation)), state);

        assertThat(new String(
                state.get(EutxoStateKeys.bridgeHalt()).orElseThrow(),
                java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("UNKNOWN_WITHDRAWAL_CONFIRMATION");
        assertThat(EutxoQueryCodec.decodeBridgeHalt(machine.query(
                EutxoQueryCodec.BRIDGE_HALT_PATH,
                new byte[0],
                state)))
                .isEqualTo("UNKNOWN_WITHDRAWAL_CONFIRMATION");
    }

    private static EutxoDepositClaim depositClaim(
            String vaultAddress,
            String vaultScriptHash,
            BigInteger lovelace
    ) throws Exception {
        TransactionOutput mirroredOutput = TransactionOutput.builder()
                .address(ALICE.address())
                .value(Value.fromCoin(lovelace))
                .build();
        byte[] outputCbor =
                com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(
                        mirroredOutput.serialize());
        return new EutxoDepositClaim(
                EutxoDepositClaim.ABI_VERSION,
                "eutxo-test",
                new EutxoOutpoint("22".repeat(32), 1),
                100,
                fill(32, 3),
                vaultAddress,
                vaultScriptHash,
                outputCbor,
                ALICE.address(),
                outputCbor,
                fill(32, 4),
                new EutxoOutpoint("33".repeat(32), 0),
                1_000);
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
        return (EutxoStateMachine) new EutxoStateMachineProvider().create(context(settings));
    }

    private static AppStateMachineContext context(Map<String, String> settings) {
        return new AppStateMachineContext() {
            @Override
            public String chainId() {
                return "eutxo-test";
            }

            @Override
            public Map<String, String> settings() {
                return settings;
            }
        };
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

    private static Transaction plutusSpend(
            EutxoOutpoint input,
            PlutusV3Script script,
            String receiver,
            long lovelace
    ) throws Exception {
        Redeemer redeemer = Redeemer.builder()
                .tag(RedeemerTag.Spend)
                .index(0)
                .data(PlutusData.unit())
                .exUnits(ExUnits.builder()
                        .mem(BigInteger.valueOf(14_000_000))
                        .steps(BigInteger.valueOf(10_000_000_000L))
                        .build())
                .build();
        CostMdls costModels = new CostMdls();
        costModels.add(CostModelUtil.PlutusV3CostModel);
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder()
                        .transactionId(input.transactionId())
                        .index(input.index())
                        .build()))
                .outputs(List.of(TransactionOutput.builder()
                        .address(receiver)
                        .value(Value.fromCoin(BigInteger.valueOf(lovelace)))
                        .build()))
                .fee(BigInteger.ZERO)
                .networkId(NetworkId.TESTNET)
                .scriptDataHash(ScriptDataHashGenerator.generate(
                        List.of(redeemer), List.of(), costModels))
                .build();
        return Transaction.builder()
                .body(body)
                .witnessSet(TransactionWitnessSet.builder()
                        .plutusV3Scripts(List.of(script))
                        .redeemers(List.of(redeemer))
                        .build())
                .isValid(true)
                .build();
    }

    private static EutxoTestWallet wallet(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return EutxoTestWallet.fromSeed(seed);
    }

    private static byte[] fill(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static AppMessage observationMessage(int idByte, L1Observation observation) {
        byte[] id = fill(32, idByte);
        return AppMessage.builder()
                .version(1)
                .messageId(id)
                .chainId("eutxo-test")
                .topic(observation.topic())
                .sender(new byte[32])
                .senderSeq(idByte)
                .expiresAt(Long.MAX_VALUE)
                .body(observation.encode())
                .authScheme(0)
                .authProof(new byte[64])
                .build();
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

    private static void apply(
            EutxoStateMachine machine,
            AppBlock block,
            MemoryAppState state
    ) {
        machine.apply(AppBlockExecutionContext.fromValidatedBlock(block), state,
                com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter
                        .rejecting("unused"));
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
