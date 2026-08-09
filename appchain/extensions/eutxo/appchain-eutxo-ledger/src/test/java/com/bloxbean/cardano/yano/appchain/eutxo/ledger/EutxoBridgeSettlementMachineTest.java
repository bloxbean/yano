package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipView;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementBatch;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBatchWithdrawalConfirmation;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBridgeParams;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBridgeParamsGovernanceV1;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReserve;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoStateKeys;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalConfirmation;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTransactionFixtures;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.MemoryAppState;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-UTXO-009 SP-M1: v3 bridge-settlement machine semantics — claim ABI v2
 * with the committed executor bounty resolved from governed parameters, and
 * the threshold-governed parameter lifecycle at recorded heights.
 */
class EutxoBridgeSettlementMachineTest {
    private static final EutxoTestWallet ALICE = wallet(1);
    private static final String MEMBER_ONE = "aa".repeat(32);
    private static final String MEMBER_TWO = "bb".repeat(32);
    private static final String OUTSIDER = "cc".repeat(32);
    private static final String VAULT = "addr_test1_bridge_vault";
    private static final String VAULT_HASH = "11".repeat(28);

    /**
     * Wallets hand out BASE addresses, and the vault validator fingerprints
     * either form ({@code Nothing} vs {@code Just (StakingHash …)}), so a base
     * destination must form a claim normally.
     */
    @Test
    void v3WithdrawalToABaseAddressIsAccepted() throws Exception {
        EutxoStateMachine machine = v3Machine(2);
        MemoryAppState state = new MemoryAppState();
        long total = 10_000_000L;
        applyDeposit(machine, state, total);

        AppMessage withdrawalMessage = withdrawTo(
                machine, state, baseAddressOf(ALICE.address()), total);
        assertThat(receiptStatus(machine, state, withdrawalMessage))
                .isEqualTo(EutxoReceipt.Status.ACCEPTED);
        assertThat(state.get(EutxoStateKeys.totalWithdrawalCount(7))).isPresent();
    }

    /**
     * A destination the vault could never pay must not form a claim at all:
     * batches form oldest-first, so one unsettleable claim would re-batch and
     * fail forever, blocking every later claim on the chain.
     */
    @Test
    void v3WithdrawalToANonPayableDestinationIsRejected() throws Exception {
        EutxoStateMachine machine = v3Machine(2);
        MemoryAppState state = new MemoryAppState();
        long total = 10_000_000L;
        applyDeposit(machine, state, total);

        String rewardAddress = com.bloxbean.cardano.client.address.AddressProvider
                .getRewardAddress(
                        com.bloxbean.cardano.client.address.Credential.fromKey(
                                fill(28, 9)),
                        com.bloxbean.cardano.client.common.model.Networks.testnet())
                .getAddress();
        AppMessage withdrawalMessage = withdrawTo(
                machine, state, rewardAddress, total);

        assertThat(receiptStatus(machine, state, withdrawalMessage))
                .isEqualTo(EutxoReceipt.Status.REJECTED);
        assertThat(state.get(EutxoStateKeys.totalWithdrawalCount(7))).isEmpty();
    }

    /**
     * The effect runtime injects a reserved '~fx/result' message after every
     * settlement. It is not a transaction: decoding it produced INVALID_CBOR
     * and a receipt with an EMPTY transaction id, which polluted the explorer
     * and — because a blank id repeats — broke the lifecycle index's unique
     * constraint on the second settlement.
     */
    @Test
    void reservedTopicMessagesAreNotIndexedAsTransactions() throws Exception {
        EutxoStateMachine machine = v3Machine(2);
        MemoryAppState state = new MemoryAppState();
        applyDeposit(machine, state, 10_000_000L);

        AppMessage reserved = AppMessage.builder()
                .version(1)
                .messageId(fill(32, 99))
                .chainId("eutxo-test")
                .topic(com.bloxbean.cardano.yano.api.appchain.effects
                        .FxResultBody.TOPIC)
                .sender(new byte[32])
                .senderSeq(99)
                .expiresAt(Long.MAX_VALUE)
                .body(new byte[] {0x00})
                .authScheme(0)
                .authProof(new byte[64])
                .build();
        apply(machine, block(2, reserved), state);

        assertThat(EutxoQueryCodec.decodeOptionalReceipt(machine.query(
                EutxoQueryCodec.ATTEMPT_PATH,
                EutxoQueryCodec.attemptRequest(reserved.getMessageId()),
                state)))
                .as("a reserved system message must produce no transaction receipt")
                .isNull();
    }

    /** Same payment credential, with a staking part attached. */

    private static String baseAddressOf(String enterpriseAddress) {
        com.bloxbean.cardano.client.address.Address enterprise =
                new com.bloxbean.cardano.client.address.Address(enterpriseAddress);
        return com.bloxbean.cardano.client.address.AddressProvider.getBaseAddress(
                        enterprise.getPaymentCredential().orElseThrow(),
                        com.bloxbean.cardano.client.address.Credential.fromKey(
                                fill(28, 9)),
                        com.bloxbean.cardano.client.common.model.Networks.testnet())
                .getAddress();
    }

    private static AppMessage withdrawTo(
            EutxoStateMachine machine, MemoryAppState state,
            String destination, long total) throws Exception {
        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                1, "eutxo-test", 7, destination, fill(32, 6));
        Transaction withdrawal = EutxoTransactionFixtures.signedOutputs(
                mirroredOutpoint(machine, state),
                ALICE,
                List.of(TransactionOutput.builder()
                        .address(withdrawalAddress())
                        .value(Value.fromCoin(BigInteger.valueOf(total)))
                        .inlineDatum(PlutusData.deserialize(datum.encode()))
                        .build()),
                0,
                0);
        AppMessage message = message(62, withdrawal);
        apply(machine, block(2, message), state);
        return message;
    }

    @Test
    void v3WithdrawalSplitsPayoutAndBountyAndReconcilesTotalReserve()
            throws Exception {
        EutxoStateMachine machine = v3Machine(2);
        MemoryAppState state = new MemoryAppState();
        long total = 10_000_000L;
        applyDeposit(machine, state, total);

        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                1, "eutxo-test", 7, ALICE.address(), fill(32, 6));
        Transaction withdrawal = EutxoTransactionFixtures.signedOutputs(
                new EutxoOutpoint("44".repeat(32), 0),
                ALICE,
                List.of(TransactionOutput.builder()
                        .address(ALICE.address())
                        .value(Value.fromCoin(BigInteger.valueOf(total)))
                        .inlineDatum(PlutusData.deserialize(datum.encode()))
                        .build()),
                0,
                0);
        // Rebuild the spend against the actual mirrored outpoint.
        withdrawal = EutxoTransactionFixtures.signedOutputs(
                mirroredOutpoint(machine, state),
                ALICE,
                List.of(TransactionOutput.builder()
                        .address(withdrawalAddress())
                        .value(Value.fromCoin(BigInteger.valueOf(total)))
                        .inlineDatum(PlutusData.deserialize(datum.encode()))
                        .build()),
                0,
                0);
        String withdrawalTxId = TransactionUtil.getTxHash(
                EutxoTransactionFixtures.serialize(withdrawal));
        AppMessage withdrawalMessage = message(62, withdrawal);
        apply(machine, block(2, withdrawalMessage), state);
        assertThat(receiptStatus(machine, state, withdrawalMessage))
                .isEqualTo(EutxoReceipt.Status.ACCEPTED);

        // Flat 2 ADA fee (bps 0): payout 8 ADA, bounty 2 ADA, ABI v2.
        EutxoWithdrawalClaim claim = new EutxoWithdrawalClaim(
                EutxoWithdrawalClaim.ABI_VERSION_V2,
                "eutxo-test",
                7,
                new EutxoOutpoint(withdrawalTxId, 0),
                ALICE.address(),
                BigInteger.valueOf(8_000_000L),
                datum.nonce(),
                0,
                2,
                BigInteger.valueOf(2_000_000L));
        EutxoWithdrawalRecord pending = EutxoQueryCodec.decodeOptionalWithdrawalRecord(
                machine.query(
                        EutxoQueryCodec.WITHDRAWAL_PATH,
                        EutxoQueryCodec.withdrawalRequest(claim.claimId()),
                        state));
        assertThat(pending.status()).isEqualTo(EutxoWithdrawalRecord.Status.PENDING);
        assertThat(pending.claim().abiVersion())
                .isEqualTo(EutxoWithdrawalClaim.ABI_VERSION_V2);
        assertThat(pending.claim().lovelace())
                .isEqualTo(BigInteger.valueOf(8_000_000L));
        assertThat(pending.claim().bounty())
                .isEqualTo(BigInteger.valueOf(2_000_000L));
        EutxoReserve reserved = EutxoReserve.decode(
                state.get(EutxoStateKeys.reserve(EutxoReserve.LOVELACE)).orElseThrow());
        // The FULL output (payout + bounty) is reserved.
        assertThat(reserved.pendingWithdrawals())
                .isEqualTo(BigInteger.valueOf(total));

        // v3 settles in batches; a size-1 batch confirmation clears the claim.
        // It must SPEND tracked vault custody (the deposit's accepted
        // outpoint) — the authenticity anchor.
        EutxoBatchWithdrawalConfirmation confirmation =
                new EutxoBatchWithdrawalConfirmation(
                        1,
                        "eutxo-test",
                        7,
                        "77".repeat(32),
                        List.of(new EutxoOutpoint("22".repeat(32), 1)),
                        new EutxoOutpoint("77".repeat(32), 1),
                        BigInteger.valueOf(500_000L),
                        200,
                        fill(32, 7),
                        List.of(new EutxoBatchWithdrawalConfirmation.Entry(
                                claim.claimId(), 0, ALICE.address(),
                                BigInteger.valueOf(8_000_000L))));
        L1Observation observation = L1Observation.transaction(
                "bridge-withdrawals",
                HexFormat.of().parseHex("77".repeat(32)),
                200,
                fill(32, 7),
                confirmation.encode());
        apply(machine, block(3, observationMessage(63, observation)), state);

        EutxoReserve reconciled = EutxoReserve.decode(
                state.get(EutxoStateKeys.reserve(EutxoReserve.LOVELACE)).orElseThrow());
        assertThat(reconciled.pendingWithdrawals()).isZero();
        // Total outflow (payout + bounty) leaves the reserve on confirmation.
        assertThat(reconciled.confirmedWithdrawals())
                .isEqualTo(BigInteger.valueOf(total));
        assertThat(state.get(EutxoStateKeys.bridgeHalt())).isEmpty();
    }

    @Test
    void payoutBelowGovernedMinimumRejectsTheTransaction() throws Exception {
        EutxoStateMachine machine = v3Machine(2);
        MemoryAppState state = new MemoryAppState();
        // 3.5 ADA total - 2 ADA fee = 1.5 ADA payout < 2 ADA governed minimum.
        applyDeposit(machine, state, 3_500_000L);
        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                1, "eutxo-test", 7, ALICE.address(), fill(32, 6));
        Transaction withdrawal = EutxoTransactionFixtures.signedOutputs(
                mirroredOutpoint(machine, state),
                ALICE,
                List.of(TransactionOutput.builder()
                        .address(withdrawalAddress())
                        .value(Value.fromCoin(BigInteger.valueOf(3_500_000L)))
                        .inlineDatum(PlutusData.deserialize(datum.encode()))
                        .build()),
                0,
                0);
        AppMessage withdrawalMessage = message(65, withdrawal);
        apply(machine, block(2, withdrawalMessage), state);
        assertThat(receiptStatus(machine, state, withdrawalMessage))
                .isEqualTo(EutxoReceipt.Status.REJECTED);
        EutxoReserve reserve = EutxoReserve.decode(
                state.get(EutxoStateKeys.reserve(EutxoReserve.LOVELACE)).orElseThrow());
        assertThat(reserve.pendingWithdrawals()).isZero();
    }

    @Test
    void governedParameterChangeNeedsThresholdAndActivatesAtRecordedHeight()
            throws Exception {
        EutxoStateMachine machine = v3Machine(2);
        MemoryAppState state = new MemoryAppState();
        apply(machine, block(1), state);
        EutxoBridgeParams initial = EutxoBridgeParams.decode(
                state.get(EutxoStateKeys.bridgeParamsCurrent()).orElseThrow());
        assertThat(initial.feeFlatLovelace()).isEqualTo(2_000_000L);
        assertThat(initial.effectiveHeight()).isZero();

        EutxoBridgeParamsGovernanceV1.Command command =
                new EutxoBridgeParamsGovernanceV1.Command(
                        1,
                        new EutxoBridgeParams(
                                1, 3_000_000L, 0, 2_000_000L, 8,
                                100L, 3_600L, 86_400L, 0L),
                        2);
        // Outsider approval is skipped deterministically.
        apply(machine, block(2, paramsMessage(70, OUTSIDER, command)), state);
        assertThat(state.get(EutxoStateKeys.bridgeParamsProposals())).isEmpty();
        assertThat(state.get(EutxoStateKeys.bridgeParamsPending())).isEmpty();

        apply(machine, block(3, paramsMessage(71, MEMBER_ONE, command)), state);
        assertThat(state.get(EutxoStateKeys.bridgeParamsProposals())).isPresent();
        assertThat(state.get(EutxoStateKeys.bridgeParamsPending())).isEmpty();

        // Duplicate approval by the same member does not schedule.
        apply(machine, block(4, paramsMessage(72, MEMBER_ONE, command)), state);
        assertThat(state.get(EutxoStateKeys.bridgeParamsPending())).isEmpty();

        apply(machine, block(5, paramsMessage(73, MEMBER_TWO, command)), state);
        assertThat(state.get(EutxoStateKeys.bridgeParamsPending())).isPresent();
        assertThat(state.get(EutxoStateKeys.bridgeParamsProposals())).isEmpty();
        // Not yet active.
        assertThat(EutxoBridgeParams.decode(
                state.get(EutxoStateKeys.bridgeParamsCurrent()).orElseThrow())
                .feeFlatLovelace()).isEqualTo(2_000_000L);

        apply(machine, block(6), state);
        apply(machine, block(7), state);
        EutxoBridgeParams active = EutxoBridgeParams.decode(
                state.get(EutxoStateKeys.bridgeParamsCurrent()).orElseThrow());
        assertThat(active.feeFlatLovelace()).isEqualTo(3_000_000L);
        assertThat(active.effectiveHeight()).isEqualTo(7L);
        assertThat(state.get(EutxoStateKeys.bridgeParamsPending())).isEmpty();
        assertThat(state.get(EutxoStateKeys.bridgeParamsHistory(7L))).isPresent();

        // The NEW fee governs subsequent claims: 10 ADA -> payout 7 ADA.
        applyDeposit(machine, state, 10_000_000L);
        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                1, "eutxo-test", 7, ALICE.address(), fill(32, 9));
        Transaction withdrawal = EutxoTransactionFixtures.signedOutputs(
                mirroredOutpoint(machine, state),
                ALICE,
                List.of(TransactionOutput.builder()
                        .address(withdrawalAddress())
                        .value(Value.fromCoin(BigInteger.valueOf(10_000_000L)))
                        .inlineDatum(PlutusData.deserialize(datum.encode()))
                        .build()),
                0,
                0);
        AppMessage withdrawalMessage = message(74, withdrawal);
        apply(machine, block(9, withdrawalMessage), state);
        assertThat(receiptStatus(machine, state, withdrawalMessage))
                .isEqualTo(EutxoReceipt.Status.ACCEPTED);
        EutxoWithdrawalRecord record = EutxoQueryCodec.decodeWithdrawalRecords(
                machine.query(
                        EutxoQueryCodec.WITHDRAWALS_PATH,
                        EutxoQueryCodec.lifecyclePageRequest(0, 10),
                        state)).getFirst();
        assertThat(record.claim().lovelace())
                .isEqualTo(BigInteger.valueOf(7_000_000L));
        assertThat(record.claim().bounty())
                .isEqualTo(BigInteger.valueOf(3_000_000L));
    }

    @Test
    void privilegedSubmissionAdmitsOnlyValidParamsCommandsOnV3() {
        EutxoStateMachine v3 = v3Machine(2);
        EutxoBridgeParamsGovernanceV1.Command command =
                new EutxoBridgeParamsGovernanceV1.Command(
                        1, EutxoBridgeParams.defaults(), 1);
        assertThat(v3.validatePrivilegedSystemSubmission(
                EutxoBridgeParamsGovernanceV1.TOPIC, command.encode())
                .isAccepted()).isTrue();
        assertThat(v3.validatePrivilegedSystemSubmission(
                EutxoBridgeParamsGovernanceV1.TOPIC, new byte[] {1})
                .isAccepted()).isFalse();
        assertThat(v3.validatePrivilegedSystemSubmission(
                "~governance/other", command.encode()).isAccepted()).isFalse();

        EutxoStateMachine v2 = (EutxoStateMachine) new EutxoStateMachineProvider()
                .create(context(Map.of(
                        "machines.eutxo.profile", EutxoProfile.V2.id(),
                        "machines.eutxo.genesis.address", ALICE.address(),
                        "machines.eutxo.genesis.lovelace", "1000000"), 2));
        assertThat(v2.validatePrivilegedSystemSubmission(
                EutxoBridgeParamsGovernanceV1.TOPIC, command.encode())
                .isAccepted()).isFalse();
    }

    @Test
    void replayFromScratchReproducesIdenticalRootsAcrossGovernanceAndClaims()
            throws Exception {
        // Same block sequence applied to two fresh states must agree bit-for-
        // bit (the conformance-harness property, exercised directly here with
        // governance + bounty claims in the corpus).
        List<AppBlock> blocks = settlementCorpus();
        MemoryAppState first = new MemoryAppState();
        MemoryAppState second = new MemoryAppState();
        EutxoStateMachine one = v3Machine(2);
        EutxoStateMachine two = v3Machine(2);
        for (AppBlock block : blocks) {
            apply(one, block, first);
        }
        for (AppBlock block : blocks) {
            apply(two, block, second);
        }
        assertThat(first.sameState(second)).isTrue();
        assertThat(EutxoBridgeParams.decode(
                first.get(EutxoStateKeys.bridgeParamsCurrent()).orElseThrow())
                .feeFlatLovelace()).isEqualTo(4_000_000L);
    }

    @Test
    void governedParamsSurviveRestartAndSnapshotDeterministically() {
        // The conformance harness's membership epoch is a single member
        // ("11" * 32) with threshold 1, so each command schedules directly.
        Map<String, String> settings = Map.of(
                "machines.eutxo.profile", EutxoProfile.V3.id(),
                "machines.eutxo.expected-profile-digest",
                EutxoProfile.V3.digestHex(),
                "machines.eutxo.genesis.address", ALICE.address(),
                "machines.eutxo.genesis.lovelace", "100");
        List<com.bloxbean.cardano.yano.runtime.appchain.StateMachineConformance
                .CorpusMessage> corpus = List.of(
                new com.bloxbean.cardano.yano.runtime.appchain
                        .StateMachineConformance.CorpusMessage(
                        EutxoBridgeParamsGovernanceV1.TOPIC,
                        new EutxoBridgeParamsGovernanceV1.Command(
                                1,
                                new EutxoBridgeParams(1, 3_000_000L, 25,
                                        2_000_000L, 8, 100L, 3_600L,
                                        86_400L, 0L),
                                1).encode()),
                new com.bloxbean.cardano.yano.runtime.appchain
                        .StateMachineConformance.CorpusMessage(
                        EutxoBridgeParamsGovernanceV1.TOPIC,
                        new byte[] {0x01}),
                new com.bloxbean.cardano.yano.runtime.appchain
                        .StateMachineConformance.CorpusMessage(
                        EutxoBridgeParamsGovernanceV1.TOPIC,
                        new EutxoBridgeParamsGovernanceV1.Command(
                                1,
                                new EutxoBridgeParams(1, 1_500_000L, 0,
                                        2_000_000L, 4, 50L, 1_800L,
                                        86_400L, 0L),
                                2).encode()),
                new com.bloxbean.cardano.yano.runtime.appchain
                        .StateMachineConformance.CorpusMessage(
                        EutxoBridgeParamsGovernanceV1.TOPIC,
                        new EutxoBridgeParamsGovernanceV1.Command(
                                1,
                                new EutxoBridgeParams(1, 500_000L, 10,
                                        2_000_000L, 8, 100L, 3_600L,
                                        86_400L, 0L),
                                1).encode()));
        com.bloxbean.cardano.yano.runtime.appchain.StateMachineConformance.Result
                result = com.bloxbean.cardano.yano.runtime.appchain
                .StateMachineConformance.builder(new EutxoStateMachineProvider())
                .chainId("eutxo-conformance")
                .settings(settings)
                .blocks(corpus.size())
                .messagesPerBlock(1)
                .runs(3)
                .restartAtHeight(2)
                .snapshotAtHeight(3)
                .messageGenerator((height, index, random) ->
                        corpus.get(Math.toIntExact(height - 1)))
                .run();
        assertThat(result.deterministic())
                .as(result.describeDivergence()).isTrue();
    }

    @Test
    void settlementFiresOnCapAndAdvancesTheCursorExactlyOnce() throws Exception {
        // softBatchCap defaults to 8; create 8 pending claims by depositing +
        // withdrawing, then the trigger emits one batch [0,8).
        EutxoStateMachine machine = v3Machine(2);
        MemoryAppState state = new MemoryAppState();
        long height = 1;
        for (int i = 0; i < 7; i++) {
            height = createWithdrawal(machine, state, height, 5_000_000L, 0x50 + i);
        }
        // The 8th claim reaches softBatchCap and fires the batch in-block.
        CapturingEmitter emitter = new CapturingEmitter(height + 1);
        height = createWithdrawal(machine, state, height, 5_000_000L, 0x57, emitter);
        assertThat(emitter.batches()).hasSize(1);
        EutxoSettlementBatch batch = emitter.batches().getFirst();
        assertThat(batch.fromSequence()).isZero();
        assertThat(batch.toSequence()).isEqualTo(8);
        assertThat(batch.batchSeq()).isZero();
        // Cursor advanced; a follow-up block does NOT re-emit the same range.
        CapturingEmitter again = new CapturingEmitter(height + 1);
        apply(machine, block(height + 1), state, again);
        assertThat(again.batches()).isEmpty();
    }

    @Test
    void settlementFiresOnElapsedRootingBlocksBelowCap() throws Exception {
        EutxoStateMachine machine = v3Machine(2);
        MemoryAppState state = new MemoryAppState();
        // 2 pending claims (< cap 8); the window must fire after rootingBlocks
        // (default 100) elapse.
        long height = createWithdrawal(machine, state, 1, 5_000_000L, 0x60);
        height = createWithdrawal(machine, state, height, 5_000_000L, 0x61);
        CapturingEmitter early = new CapturingEmitter(height + 1);
        apply(machine, block(height + 1), state, early);
        assertThat(early.batches()).isEmpty();
        CapturingEmitter fired = new CapturingEmitter(height + 200);
        apply(machine, block(height + 200), state, fired);
        assertThat(fired.batches()).hasSize(1);
        assertThat(fired.batches().getFirst().toSequence()).isEqualTo(2);
    }

    @Test
    void terminalFailureRewindsTheCursorForRebatching() throws Exception {
        EutxoStateMachine machine = v3Machine(2);
        MemoryAppState state = new MemoryAppState();
        long height = 1;
        for (int i = 0; i < 7; i++) {
            height = createWithdrawal(machine, state, height, 5_000_000L, 0x70 + i);
        }
        CapturingEmitter emitter = new CapturingEmitter(height + 1);
        height = createWithdrawal(machine, state, height, 5_000_000L, 0x77, emitter);
        assertThat(emitter.batches()).hasSize(1);
        // A FAILED result rewinds the cursor; the window reopens and re-fires.
        EffectResult failed = new EffectResult(
                new EffectId("eutxo-test", height + 1, 0),
                EutxoStateMachine.SETTLEMENT_EFFECT_TYPE,
                "bridge/settlement/7/0",
                EffectOutcome.FAILED, new byte[0], null, height + 2);
        onEffectResult(machine, block(height + 2), failed, state);
        CapturingEmitter refired = new CapturingEmitter(height + 3);
        apply(machine, block(height + 3), state, refired);
        assertThat(refired.batches()).hasSize(1);
        assertThat(refired.batches().getFirst().fromSequence()).isZero();
    }

    @Test
    void batchConfirmationClearsEveryClaimAndReconcilesReserve() throws Exception {
        EutxoStateMachine machine = v3Machine(2);
        MemoryAppState state = new MemoryAppState();
        long height = 1;
        height = createWithdrawal(machine, state, height, 5_000_000L, 0x40);
        height = createWithdrawal(machine, state, height, 6_000_000L, 0x41);
        height = createWithdrawal(machine, state, height, 7_000_000L, 0x42);

        List<EutxoWithdrawalRecord> pending = EutxoQueryCodec.decodeWithdrawalRecords(
                machine.query(EutxoQueryCodec.WITHDRAWALS_PATH,
                        EutxoQueryCodec.lifecyclePageRequest(0, 10), state));
        assertThat(pending).hasSize(3);
        assertThat(pending).allMatch(record ->
                record.status() == EutxoWithdrawalRecord.Status.PENDING);
        BigInteger reservedTotal = pending.stream()
                .map(record -> record.claim().totalLovelace())
                .reduce(BigInteger.ZERO, BigInteger::add);
        assertThat(EutxoReserve.decode(state.get(
                        EutxoStateKeys.reserve(EutxoReserve.LOVELACE)).orElseThrow())
                .pendingWithdrawals()).isEqualTo(reservedTotal);

        // One batch confirmation carries every settled claim positionally.
        List<EutxoBatchWithdrawalConfirmation.Entry> entries = new ArrayList<>();
        for (int index = 0; index < pending.size(); index++) {
            EutxoWithdrawalClaim c = pending.get(index).claim();
            entries.add(new EutxoBatchWithdrawalConfirmation.Entry(
                    c.claimId(), index, c.destinationAddress(), c.lovelace()));
        }
        EutxoBatchWithdrawalConfirmation confirmation =
                new EutxoBatchWithdrawalConfirmation(
                        1, "eutxo-test", 7, "88".repeat(32),
                        List.of(new EutxoOutpoint("40" + "22".repeat(31), 1)),
                        new EutxoOutpoint("88".repeat(32), entries.size()),
                        BigInteger.valueOf(1_000_000L), 250, fill(32, 8), entries);
        L1Observation observation = L1Observation.transaction(
                "bridge-withdrawals", HexFormat.of().parseHex("88".repeat(32)),
                250, fill(32, 8), confirmation.encode());
        apply(machine, block(height, observationMessage(0xB1, observation)),
                state, new CapturingEmitter(height));

        List<EutxoWithdrawalRecord> after = EutxoQueryCodec.decodeWithdrawalRecords(
                machine.query(EutxoQueryCodec.WITHDRAWALS_PATH,
                        EutxoQueryCodec.lifecyclePageRequest(0, 10), state));
        assertThat(after).hasSize(3);
        assertThat(after).allMatch(record ->
                record.status() == EutxoWithdrawalRecord.Status.CONFIRMED);
        EutxoReserve reconciled = EutxoReserve.decode(state.get(
                EutxoStateKeys.reserve(EutxoReserve.LOVELACE)).orElseThrow());
        assertThat(reconciled.pendingWithdrawals()).isZero();
        assertThat(reconciled.confirmedWithdrawals()).isEqualTo(reservedTotal);
        assertThat(state.get(EutxoStateKeys.pendingWithdrawalCount()))
                .hasValueSatisfying(bytes ->
                        assertThat(new BigInteger(bytes)).isZero());
        assertThat(state.get(EutxoStateKeys.bridgeHalt())).isEmpty();
    }

    @Test
    void fabricatedConfirmationWithoutAVaultSpendHaltsTheBridge() throws Exception {
        EutxoStateMachine machine = v3Machine(2);
        MemoryAppState state = new MemoryAppState();
        long height = createWithdrawal(machine, state, 1, 5_000_000L, 0x48);
        EutxoWithdrawalClaim claim = EutxoQueryCodec.decodeWithdrawalRecords(
                machine.query(EutxoQueryCodec.WITHDRAWALS_PATH,
                        EutxoQueryCodec.lifecyclePageRequest(0, 10), state))
                .getFirst().claim();

        // Attacker pays the claim's real destination its real amount out of
        // pocket and attaches a well-formed marker — but the transaction
        // spends NO tracked vault outpoint, so custody proves it fake.
        EutxoBatchWithdrawalConfirmation forged =
                new EutxoBatchWithdrawalConfirmation(
                        1, "eutxo-test", 7, "99".repeat(32),
                        List.of(new EutxoOutpoint("ee".repeat(32), 0)),
                        new EutxoOutpoint("99".repeat(32), 1),
                        BigInteger.valueOf(2_000_000L), 260, fill(32, 9),
                        List.of(new EutxoBatchWithdrawalConfirmation.Entry(
                                claim.claimId(), 0, claim.destinationAddress(),
                                claim.lovelace())));
        L1Observation observation = L1Observation.transaction(
                "bridge-withdrawals", HexFormat.of().parseHex("99".repeat(32)),
                260, fill(32, 9), forged.encode());
        apply(machine, block(height, observationMessage(0xB7, observation)),
                state, new CapturingEmitter(height));

        // Bridge halts; the claim stays PENDING; the reserve is untouched.
        assertThat(state.get(EutxoStateKeys.bridgeHalt())).isPresent();
        EutxoWithdrawalRecord record = EutxoQueryCodec.decodeWithdrawalRecords(
                machine.query(EutxoQueryCodec.WITHDRAWALS_PATH,
                        EutxoQueryCodec.lifecyclePageRequest(0, 10), state))
                .getFirst();
        assertThat(record.status()).isEqualTo(EutxoWithdrawalRecord.Status.PENDING);
        assertThat(EutxoReserve.decode(state.get(
                        EutxoStateKeys.reserve(EutxoReserve.LOVELACE)).orElseThrow())
                .pendingWithdrawals()).isEqualTo(claim.totalLovelace());
    }

    @Test
    void governedFallbackDelayBelowTheProfileFloorIsRejected() throws Exception {
        // ADR-UTXO-009 §13.2: the FLOOR lives on the profile and is enforced
        // by the machine (the params record only bounds structure). A
        // sub-floor proposal must never accumulate approvals on any member.
        EutxoStateMachine machine = v3Machine(2);
        MemoryAppState state = new MemoryAppState();
        apply(machine, block(1), state);

        EutxoBridgeParamsGovernanceV1.Command subFloor =
                new EutxoBridgeParamsGovernanceV1.Command(
                        1,
                        new EutxoBridgeParams(1, 2_000_000L, 0, 2_000_000L, 8,
                                100L, 3_600L,
                                EutxoProfile.V3.fallbackDelayMinSlots() - 1, 0L),
                        2);
        apply(machine, block(2, paramsMessage(90, MEMBER_ONE, subFloor)), state);
        apply(machine, block(3, paramsMessage(91, MEMBER_TWO, subFloor)), state);
        // Dropped before it becomes a proposal — nothing scheduled, nothing
        // recorded, current params untouched.
        assertThat(state.get(EutxoStateKeys.bridgeParamsProposals())).isEmpty();
        assertThat(state.get(EutxoStateKeys.bridgeParamsPending())).isEmpty();
        assertThat(EutxoBridgeParams.decode(state.get(
                        EutxoStateKeys.bridgeParamsCurrent()).orElseThrow())
                .fallbackDelaySlots())
                .isEqualTo(EutxoBridgeParams.defaults().fallbackDelaySlots());

        // At the floor exactly, the same flow schedules normally.
        EutxoBridgeParamsGovernanceV1.Command atFloor =
                new EutxoBridgeParamsGovernanceV1.Command(
                        1,
                        new EutxoBridgeParams(1, 2_000_000L, 0, 2_000_000L, 8,
                                100L, 3_600L,
                                EutxoProfile.V3.fallbackDelayMinSlots(), 0L),
                        4);
        apply(machine, block(4, paramsMessage(92, MEMBER_ONE, atFloor)), state);
        apply(machine, block(5, paramsMessage(93, MEMBER_TWO, atFloor)), state);
        assertThat(state.get(EutxoStateKeys.bridgeParamsPending())).isPresent();
    }

    private long createWithdrawal(
            EutxoStateMachine machine, MemoryAppState state, long height,
            long total, int nonceByte) throws Exception {
        return createWithdrawal(machine, state, height, total, nonceByte,
                new CapturingEmitter(height + 1));
    }

    private long createWithdrawal(
            EutxoStateMachine machine, MemoryAppState state, long height,
            long total, int nonceByte, CapturingEmitter withdrawalEmitter)
            throws Exception {
        applyDepositAt(machine, state, total, height, nonceByte);
        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                1, "eutxo-test", 7, ALICE.address(), fill(32, nonceByte));
        Transaction withdrawal = EutxoTransactionFixtures.signedOutputs(
                mirroredOutpoint(machine, state),
                ALICE,
                List.of(TransactionOutput.builder()
                        .address(withdrawalAddress())
                        .value(Value.fromCoin(BigInteger.valueOf(total)))
                        .inlineDatum(PlutusData.deserialize(datum.encode()))
                        .build()),
                0, 0);
        apply(machine, block(height + 1, message(0x90 + nonceByte, withdrawal)),
                state, withdrawalEmitter);
        return height + 2;
    }

    private static void applyDepositAt(
            EutxoStateMachine machine, MemoryAppState state, long lovelace,
            long height, int nonceByte) throws Exception {
        EutxoDepositClaim claim = depositClaimNonce(BigInteger.valueOf(lovelace), nonceByte);
        L1Observation observation = L1Observation.transaction(
                "bridge-deposits",
                HexFormat.of().parseHex(claim.acceptedOutpoint().transactionId()),
                claim.l1Slot(), claim.l1BlockHash(), claim.encode());
        apply(machine, block(height, observationMessage(0xA0 + nonceByte, observation)),
                state, new CapturingEmitter(height));
    }

    private static EutxoDepositClaim depositClaimNonce(BigInteger lovelace, int nonceByte)
            throws Exception {
        TransactionOutput mirroredOutput = TransactionOutput.builder()
                .address(ALICE.address())
                .value(Value.fromCoin(lovelace))
                .build();
        byte[] outputCbor = com.bloxbean.cardano.client.common.cbor
                .CborSerializationUtil.serialize(mirroredOutput.serialize());
        String hex = String.format("%02x", nonceByte & 0xFF);
        return new EutxoDepositClaim(
                EutxoDepositClaim.ABI_VERSION, "eutxo-test",
                new EutxoOutpoint(hex + "22".repeat(31), 1),
                100, fill(32, nonceByte), VAULT, VAULT_HASH, outputCbor,
                ALICE.address(), outputCbor, fill(32, nonceByte),
                new EutxoOutpoint(hex + "33".repeat(31), 0), 1_000);
    }

    private static final class CapturingEmitter implements AppEffectEmitter {
        private final long height;
        private final List<EutxoSettlementBatch> batches = new ArrayList<>();
        private int ordinal;

        private CapturingEmitter(long height) {
            this.height = height;
        }

        @Override
        public EffectId emit(EffectIntent intent) {
            if (EutxoStateMachine.SETTLEMENT_EFFECT_TYPE.equals(intent.type())) {
                batches.add(EutxoSettlementBatch.decode(intent.payload()));
            }
            return new EffectId("eutxo-test", height, ordinal++);
        }

        @Override
        public long pendingCount() {
            return 0;
        }

        List<EutxoSettlementBatch> batches() {
            return batches;
        }
    }

    private List<AppBlock> settlementCorpus() throws Exception {
        EutxoBridgeParamsGovernanceV1.Command command =
                new EutxoBridgeParamsGovernanceV1.Command(
                        1,
                        new EutxoBridgeParams(
                                1, 4_000_000L, 50, 2_000_000L, 4,
                                50L, 1_800L, 86_400L, 0L),
                        1);
        EutxoDepositClaim deposit = depositClaim(BigInteger.valueOf(20_000_000L));
        L1Observation observation = L1Observation.transaction(
                "bridge-deposits",
                HexFormat.of().parseHex(deposit.acceptedOutpoint().transactionId()),
                deposit.l1Slot(),
                deposit.l1BlockHash(),
                deposit.encode());
        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                1, "eutxo-test", 7, ALICE.address(), fill(32, 8));
        Transaction withdrawal = EutxoTransactionFixtures.signedOutputs(
                deposit.mirroredOutpoint(),
                ALICE,
                List.of(TransactionOutput.builder()
                        .address(withdrawalAddress())
                        .value(Value.fromCoin(BigInteger.valueOf(20_000_000L)))
                        .inlineDatum(PlutusData.deserialize(datum.encode()))
                        .build()),
                0,
                0);
        return List.of(
                block(1, observationMessage(81, observation)),
                block(2, paramsMessage(82, MEMBER_ONE, command)),
                block(3, paramsMessage(83, MEMBER_TWO, command)),
                block(4),
                block(5, message(84, withdrawal)));
    }

    // ------------------------------------------------------------------

    private static String withdrawalAddress() {
        return wallet(2).address();
    }

    private static EutxoStateMachine v3Machine(int threshold) {
        Map<String, String> settings = Map.ofEntries(
                Map.entry("machines.eutxo.profile", EutxoProfile.V3.id()),
                Map.entry("machines.eutxo.expected-profile-digest",
                        EutxoProfile.V3.digestHex()),
                Map.entry("machines.eutxo.bridge.observer-id", "bridge-deposits"),
                Map.entry("machines.eutxo.bridge.vault-address", VAULT),
                Map.entry("machines.eutxo.bridge.vault-script-hash", VAULT_HASH),
                Map.entry("machines.eutxo.bridge.confirmation-observer-id",
                        "bridge-withdrawals"),
                Map.entry("machines.eutxo.bridge.withdrawal-address",
                        withdrawalAddress()),
                Map.entry("machines.eutxo.bridge.epoch", "7"),
                Map.entry("machines.eutxo.bridge.max-withdrawal-lovelace",
                        "100000000"),
                Map.entry("machines.eutxo.bridge.max-pending-withdrawals", "16"),
                Map.entry("machines.eutxo.bridge.withdrawals-paused", "false"));
        return (EutxoStateMachine) new EutxoStateMachineProvider()
                .create(context(settings, threshold));
    }

    private static AppStateMachineContext context(
            Map<String, String> settings, int threshold) {
        AppChainMembershipView view = height -> new AppChainMembershipEpoch(
                0, List.of(MEMBER_ONE, MEMBER_TWO), threshold);
        return new AppStateMachineContext() {
            @Override
            public String chainId() {
                return "eutxo-test";
            }

            @Override
            public Map<String, String> settings() {
                return settings;
            }

            @Override
            public Optional<AppChainMembershipView> membershipView() {
                return Optional.of(view);
            }
        };
    }

    private static void applyDeposit(
            EutxoStateMachine machine, MemoryAppState state, long lovelace)
            throws Exception {
        EutxoDepositClaim claim = depositClaim(BigInteger.valueOf(lovelace));
        L1Observation observation = L1Observation.transaction(
                "bridge-deposits",
                HexFormat.of().parseHex(claim.acceptedOutpoint().transactionId()),
                claim.l1Slot(),
                claim.l1BlockHash(),
                claim.encode());
        apply(machine, block(1, observationMessage(61, observation)), state);
    }

    private static EutxoOutpoint mirroredOutpoint(
            EutxoStateMachine machine, MemoryAppState state) {
        return EutxoQueryCodec.decodeRecords(machine.query(
                        EutxoQueryCodec.ADDRESS_PATH,
                        EutxoQueryCodec.addressRequest(ALICE.address()),
                        state))
                .getFirst().outpoint();
    }

    private static EutxoDepositClaim depositClaim(BigInteger lovelace)
            throws Exception {
        TransactionOutput mirroredOutput = TransactionOutput.builder()
                .address(ALICE.address())
                .value(Value.fromCoin(lovelace))
                .build();
        byte[] outputCbor = com.bloxbean.cardano.client.common.cbor
                .CborSerializationUtil.serialize(mirroredOutput.serialize());
        return new EutxoDepositClaim(
                EutxoDepositClaim.ABI_VERSION,
                "eutxo-test",
                new EutxoOutpoint("22".repeat(32), 1),
                100,
                fill(32, 3),
                VAULT,
                VAULT_HASH,
                outputCbor,
                ALICE.address(),
                outputCbor,
                fill(32, 4),
                new EutxoOutpoint("33".repeat(32), 0),
                1_000);
    }

    private static EutxoReceipt.Status receiptStatus(
            EutxoStateMachine machine, MemoryAppState state, AppMessage message) {
        return EutxoQueryCodec.decodeOptionalReceipt(machine.query(
                        EutxoQueryCodec.ATTEMPT_PATH,
                        EutxoQueryCodec.attemptRequest(message.getMessageId()),
                        state))
                .status();
    }

    private static AppMessage paramsMessage(
            int idByte, String senderHex,
            EutxoBridgeParamsGovernanceV1.Command command) {
        return AppMessage.builder()
                .version(1)
                .messageId(fill(32, idByte))
                .chainId("eutxo-test")
                .topic(EutxoBridgeParamsGovernanceV1.TOPIC)
                .sender(HexFormat.of().parseHex(senderHex))
                .senderSeq(idByte)
                .expiresAt(Long.MAX_VALUE)
                .body(command.encode())
                .authScheme(0)
                .authProof(new byte[64])
                .build();
    }

    private static AppMessage observationMessage(
            int idByte, L1Observation observation) {
        return AppMessage.builder()
                .version(1)
                .messageId(fill(32, idByte))
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
        return AppMessage.builder()
                .version(1)
                .messageId(fill(32, idByte))
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
        apply(machine, block, state, AppEffectEmitter.rejecting("unused"));
    }

    private static void apply(
            EutxoStateMachine machine,
            AppBlock block,
            MemoryAppState state,
            AppEffectEmitter effects
    ) {
        machine.apply(AppBlockExecutionContext.fromValidatedBlock(block), state, effects);
    }

    private static void onEffectResult(
            EutxoStateMachine machine,
            AppBlock block,
            EffectResult result,
            MemoryAppState state
    ) {
        machine.onEffectResult(AppBlockExecutionContext.fromValidatedBlock(block), result, state,
                AppEffectEmitter.rejecting("unused"));
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
}
