package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReserve;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoStateKeys;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Domain;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2KeyRegistration;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2ParameterSnapshot;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoContract;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipView;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBridgeParams;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBridgeParamsGovernanceV1;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalCommitment;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBatchWithdrawalConfirmation;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalConfirmation;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitment;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityCommitmentEngine;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityTransition;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Optional genesis-funded Cardano-shaped EUTxO app-chain state machine. */
public final class EutxoStateMachine implements AppStateMachine {
    public static final String ID = EutxoContract.STATE_MACHINE_ID;
    public static final String TOPIC = EutxoContract.TRANSACTION_TOPIC;

    private final EutxoProfile profile;
    private final AppChainMembershipView membershipView;
    private final EutxoBridgeParams initialBridgeParams;
    private final EutxoGenesis genesis;
    private final UtxoTransitionEngine transitionEngine;
    private final EutxoBridgeConfig bridge;
    private final EutxoValidityCommitmentEngine validityEngine;
    private final String chainId;
    private final String network;

    EutxoStateMachine(
            EutxoProfile profile,
            EutxoGenesis genesis,
            UtxoTransitionEngine transitionEngine
    ) {
        this(profile, genesis, transitionEngine, EutxoBridgeConfig.disabled(), null,
                "local-eutxo", "devnet", null, null);
    }

    EutxoStateMachine(
            EutxoProfile profile,
            EutxoGenesis genesis,
            UtxoTransitionEngine transitionEngine,
            EutxoBridgeConfig bridge
    ) {
        this(profile, genesis, transitionEngine, bridge, null,
                "local-eutxo", "devnet", null, null);
    }

    EutxoStateMachine(
            EutxoProfile profile,
            EutxoGenesis genesis,
            UtxoTransitionEngine transitionEngine,
            EutxoBridgeConfig bridge,
            EutxoValidityCommitmentEngine validityEngine
    ) {
        this(profile, genesis, transitionEngine, bridge, validityEngine,
                "local-eutxo", "devnet", null, null);
    }

    EutxoStateMachine(
            EutxoProfile profile,
            EutxoGenesis genesis,
            UtxoTransitionEngine transitionEngine,
            EutxoBridgeConfig bridge,
            EutxoValidityCommitmentEngine validityEngine,
            String chainId,
            String network
    ) {
        this(profile, genesis, transitionEngine, bridge, validityEngine,
                chainId, network, null, null);
    }

    EutxoStateMachine(
            EutxoProfile profile,
            EutxoGenesis genesis,
            UtxoTransitionEngine transitionEngine,
            EutxoBridgeConfig bridge,
            EutxoValidityCommitmentEngine validityEngine,
            String chainId,
            String network,
            AppChainMembershipView membershipView,
            EutxoBridgeParams initialBridgeParams
    ) {
        this.membershipView = membershipView;
        this.initialBridgeParams = initialBridgeParams != null
                ? initialBridgeParams : EutxoBridgeParams.defaults();
        this.profile = Objects.requireNonNull(profile, "profile");
        this.genesis = Objects.requireNonNull(genesis, "genesis");
        this.transitionEngine = Objects.requireNonNull(transitionEngine, "transitionEngine");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.validityEngine = validityEngine;
        this.chainId = requireText(chainId, "chainId");
        this.network = requireText(network, "network");
        if (validityEngine != null
                && !List.of("devnet", "preview", "preprod").contains(this.network)) {
            throw new IllegalArgumentException(
                    "validity-enabled EUTxO requires devnet, preview, or preprod");
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AppCapabilityManifest capabilityManifest() {
        return AppCapabilityManifest.builder(ID, profile.id())
                .component(new AppCapabilityManifest.Component(
                        ID, profile.id(), profile.digestHex(), "application/v1",
                        List.of(TOPIC), List.of("transactions/receipt", "utxos/address"),
                        AppCapabilityManifest.Origin.INTRINSIC))
                .proofSubject(new AppCapabilityManifest.ProofSubject(
                        "eutxo-transaction-receipt-v1", "", "tx/", "state-proof"))
                .build();
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        if (bridge.enabled() && bridge.topic().equals(message.getTopic())) {
            try {
                acceptedDeposit(message);
                return AdmissionResult.accept();
            } catch (IllegalArgumentException failure) {
                return AdmissionResult.reject("BRIDGE_DEPOSIT_INVALID");
            }
        }
        if (bridge.withdrawalsEnabled()
                && bridge.confirmationTopic().equals(message.getTopic())) {
            try {
                withdrawalConfirmation(message);
                return AdmissionResult.accept();
            } catch (IllegalArgumentException failure) {
                return AdmissionResult.reject("BRIDGE_WITHDRAWAL_CONFIRMATION_INVALID");
            }
        }
        if (!TOPIC.equals(message.getTopic())) {
            return AdmissionResult.reject("EUTxO transactions require topic " + TOPIC);
        }
        UtxoTransitionEngine.PreflightResult result =
                transitionEngine.preflight(message.getBody());
        return result.accepted()
                ? AdmissionResult.accept()
                : AdmissionResult.reject(result.code() + ": " + result.detail());
    }

    @Override
    public AdmissionResult validateForBlock(
            AppMessage message,
            long candidateHeight,
            AppStateReader committedState
    ) {
        return validate(message);
    }

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                      AppEffectEmitter effects) {
        AppBlock block = context.block();
        ensureGenesis(writer);
        if (settlementProfile()) {
            activateScheduledParams(block.height(), writer);
        }
        int ordinal = 0;
        int visibleMessageIndex = 0;
        long summarySequence = writer.get(EutxoStateKeys.summaryCount())
                .map(EutxoStateMachine::longValue)
                .orElse(0L);
        for (AppMessage message : context.messages()) {
            int originalMessageIndex = context.originalMessageIndex(visibleMessageIndex++);
            if (settlementProfile() && EutxoBridgeParamsGovernanceV1.TOPIC
                    .equals(message.getTopic())) {
                processParamsCommand(message, block.height(), writer);
                ordinal++;
                continue;
            }
            if (bridge.enabled() && bridge.topic().equals(message.getTopic())) {
                importDeposit(acceptedDeposit(context.l1ObservationAt(originalMessageIndex)
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "accepted bridge observation is missing from execution context"))
                                .observation()),
                        block.height(), writer);
                ordinal++;
                continue;
            }
            if (bridge.withdrawalsEnabled()
                    && bridge.confirmationTopic().equals(message.getTopic())) {
                if (settlementProfile()) {
                    // v3 (A2 batch) settlement: one observation confirms the
                    // whole batch; clear each positional claim in order.
                    EutxoBatchWithdrawalConfirmation batch =
                            batchWithdrawalConfirmation(context.l1ObservationAt(originalMessageIndex)
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "accepted withdrawal observation is missing from execution context"))
                                    .observation());
                    if (applyVaultCustody(batch, writer)) {
                        for (EutxoWithdrawalConfirmation confirmation :
                                batch.confirmations()) {
                            confirmWithdrawal(confirmation, block.height(), writer);
                        }
                    }
                } else {
                    confirmWithdrawal(
                            withdrawalConfirmation(context.l1ObservationAt(originalMessageIndex)
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "accepted withdrawal observation is missing from execution context"))
                                    .observation()),
                            block.height(), writer);
                }
                ordinal++;
                continue;
            }
            // Reserved topics ('~…') are runtime-internal — the effect
            // runtime injects '~fx/result' after every settlement. They are
            // NOT EUTxO transactions: decoding one yields INVALID_CBOR and a
            // receipt with an empty transaction id, which shows up as a bogus
            // rejected transaction in the explorer and (because a blank id
            // repeats) used to break the lifecycle index outright.
            String topic = message.getTopic();
            if (topic != null && topic.startsWith("~")) {
                ordinal++;
                continue;
            }
            UtxoTransitionEngine.TransitionResult result =
                    transitionEngine.transition(message.getBody(), block.l1Slot(), writer);
            WithdrawalPlan withdrawalPlan = WithdrawalPlan.empty();
            if (result.accepted()) {
                try {
                    withdrawalPlan = planWithdrawals(result, block.height(), writer);
                } catch (WithdrawalFailure failure) {
                    result = UtxoTransitionEngine.TransitionResult.reject(
                            result.transactionId(), failure.code(), failure.getMessage());
                }
            }
            List<EutxoRecord> resolvedInputs = result.accepted()
                    ? result.resolvedInputs() : List.of();
            EutxoReceipt receipt = new EutxoReceipt(
                    result.accepted() ? EutxoReceipt.Status.ACCEPTED : EutxoReceipt.Status.REJECTED,
                    result.transactionId(),
                    message.getMessageId(),
                    block.height(),
                    ordinal,
                    block.l1Slot(),
                    result.code(),
                    result.detail());
            if (result.accepted()) {
                applyValidity(
                        result,
                        withdrawalPlan,
                        block.l1Slot(),
                        block.height(),
                        ordinal,
                        writer);
                applyAccepted(result, withdrawalPlan, writer);
                writer.put(EutxoStateKeys.transaction(result.transactionId()), receipt.encode());
            }
            writer.put(EutxoStateKeys.attempt(message.getMessageId()), receipt.encode());
            EutxoTransactionSummary summary = summary(
                    result, resolvedInputs, message,
                    Math.addExact(summarySequence, 1),
                    block.height(), ordinal, block.l1Slot());
            summarySequence = summary.sequence();
            byte[] summaryBytes = summary.encode();
            writer.put(EutxoStateKeys.messageSummary(
                    message.getMessageId()), summaryBytes);
            if (!result.transactionId().isBlank()) {
                writer.put(EutxoStateKeys.transactionSummary(
                        result.transactionId()), summaryBytes);
            }
            writer.put(EutxoStateKeys.summaryIndex(summarySequence), summaryBytes);
            ordinal++;
        }
        writer.put(EutxoStateKeys.summaryCount(), longBytes(summarySequence));
        if (settlementProfile() && bridge.withdrawalsEnabled()) {
            maybeEmitSettlement(block, writer, effects);
        }
    }

    /**
     * A2 N-or-T trigger (ADR-UTXO-009 §7.2): a settlement window opens at the
     * first unsettled claim and fires an {@code l1.settlement} effect once
     * {@code softBatchCap} claims are pending OR {@code rootingBlocks} have
     * elapsed. The cursor advances by the batched range, so each claim is
     * batched exactly once; deterministic and a pure function of committed
     * state.
     */
    private void maybeEmitSettlement(
            AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
        long epoch = bridge.bridgeEpoch();
        long created = writer.get(EutxoStateKeys.totalWithdrawalCount(epoch))
                .map(EutxoStateMachine::longValue).orElse(0L);
        long cursor = writer.get(EutxoStateKeys.settlementCursor(epoch))
                .map(EutxoStateMachine::longValue).orElse(0L);
        long unsettled = created - cursor;
        if (unsettled <= 0) {
            writer.delete(EutxoStateKeys.settlementWindowOpen(epoch));
            return;
        }
        long windowOpen = writer.get(EutxoStateKeys.settlementWindowOpen(epoch))
                .map(EutxoStateMachine::longValue).orElse(0L);
        if (windowOpen == 0) {
            windowOpen = block.height();
            writer.put(EutxoStateKeys.settlementWindowOpen(epoch),
                    longBytes(windowOpen));
        }
        EutxoBridgeParams params = currentParams(writer);
        boolean capReached = unsettled >= params.softBatchCap();
        boolean elapsed = block.height() - windowOpen >= params.rootingBlocks();
        if (!capReached && !elapsed) {
            return;
        }
        long batchSize = Math.min(unsettled, params.softBatchCap());
        long batchSeq = writer.get(EutxoStateKeys.settlementBatchSeq(epoch))
                .map(EutxoStateMachine::longValue).orElse(0L);
        EutxoSettlementBatch batchPayload = new EutxoSettlementBatch(
                EutxoSettlementBatch.VERSION, bridge.chainId(), epoch,
                batchSeq, cursor, cursor + batchSize);
        effects.emit(EffectIntent.of(SETTLEMENT_EFFECT_TYPE, batchPayload.encode())
                .scope(settlementScope(epoch, batchSeq))
                .gate(FinalityGate.APP_FINAL)
                .result(ResultPolicy.CHAIN)
                .expiryBlocks(settlementExpiryBlocks(params))
                .build());
        writer.put(EutxoStateKeys.settlementBatchStart(epoch, batchSeq),
                longBytes(cursor));
        writer.put(EutxoStateKeys.settlementCursor(epoch),
                longBytes(cursor + batchSize));
        writer.put(EutxoStateKeys.settlementBatchSeq(epoch),
                longBytes(batchSeq + 1));
        writer.delete(EutxoStateKeys.settlementWindowOpen(epoch));
    }

    @Override
    public void onEffectResult(
            AppBlockExecutionContext context,
            EffectResult result,
            AppStateWriter writer,
            AppEffectEmitter effects
    ) {
        AppBlock block = context.block();
        if (!settlementProfile()
                || !SETTLEMENT_EFFECT_TYPE.equals(result.type())
                || !result.scope().startsWith(SETTLEMENT_SCOPE_PREFIX)) {
            return;
        }
        // A terminal non-success (FAILED/EXPIRED) rolls the cursor back to the
        // batch start so the claims re-batch on a later window; CONFIRMED needs
        // no state change here (the confirmation observer closes each claim).
        if (result.confirmed()) {
            return;
        }
        long epoch = bridge.bridgeEpoch();
        long batchSeq = settlementScopeBatch(result.scope());
        byte[] cursorKey = EutxoStateKeys.settlementCursor(epoch);
        long cursor = writer.get(cursorKey)
                .map(EutxoStateMachine::longValue).orElse(0L);
        long currentBatchSeq = writer.get(EutxoStateKeys.settlementBatchSeq(epoch))
                .map(EutxoStateMachine::longValue).orElse(0L);
        // Only roll back if this was the most recent batch and nothing newer
        // has advanced past it (keeps the cursor monotone across interleavings).
        if (batchSeq == currentBatchSeq - 1) {
            long start = writer.get(
                    EutxoStateKeys.settlementBatchStart(epoch, batchSeq))
                    .map(EutxoStateMachine::longValue).orElse(cursor);
            // Rewind the cursor to the batch start so its claims re-batch, and
            // reopen the window so a later block re-triggers.
            writer.put(cursorKey, longBytes(Math.min(cursor, start)));
            writer.delete(EutxoStateKeys.settlementBatchStart(epoch, batchSeq));
            writer.put(EutxoStateKeys.settlementWindowOpen(epoch),
                    longBytes(block.height()));
        }
    }

    @Override
    public byte[] query(String path, byte[] params, AppQueryContext state) {
        try {
            return switch (path) {
                case EutxoQueryCodec.OUTPOINT_PATH -> {
                    EutxoOutpoint outpoint = EutxoQueryCodec.decodeOutpointRequest(params);
                    yield EutxoQueryCodec.optionalRecord(state.get(EutxoStateKeys.utxo(outpoint))
                            .map(EutxoRecord::decode).orElse(null));
                }
                case EutxoQueryCodec.ADDRESS_PATH -> {
                    String address = EutxoQueryCodec.decodeAddressRequest(params);
                    yield EutxoQueryCodec.records(addressRecords(state, address));
                }
                case EutxoQueryCodec.TRANSACTION_PATH -> {
                    String transactionId = EutxoQueryCodec.decodeTransactionRequest(params);
                    yield EutxoQueryCodec.optionalReceipt(
                            state.get(EutxoStateKeys.transaction(transactionId))
                                    .map(EutxoReceipt::decode).orElse(null));
                }
                case EutxoQueryCodec.TRANSACTION_SUMMARY_PATH -> {
                    String transactionId =
                            EutxoQueryCodec.decodeTransactionRequest(params);
                    yield state.get(EutxoStateKeys.transactionSummary(
                            transactionId)).orElse(new byte[0]);
                }
                case EutxoQueryCodec.MESSAGE_SUMMARY_PATH -> {
                    byte[] appMessageId =
                            EutxoQueryCodec.decodeAttemptRequest(params);
                    yield state.get(EutxoStateKeys.messageSummary(
                            appMessageId)).orElse(new byte[0]);
                }
                case EutxoQueryCodec.TRANSACTION_SUMMARIES_PATH -> {
                    EutxoQueryCodec.SummaryPage page =
                            EutxoQueryCodec.decodeSummaryPageRequest(params);
                    long count = state.get(EutxoStateKeys.summaryCount())
                            .map(EutxoStateMachine::longValue)
                            .orElse(0L);
                    long cursor = page.before() == 0
                            ? count : Math.min(count, page.before() - 1);
                    List<EutxoTransactionSummary> summaries =
                            new ArrayList<>();
                    while (cursor > 0
                            && summaries.size() < page.limit()) {
                        state.get(EutxoStateKeys.summaryIndex(cursor))
                                .map(EutxoTransactionSummary::decode)
                                .ifPresent(summaries::add);
                        cursor--;
                    }
                    yield EutxoTransactionSummary.encodeList(summaries);
                }
                case EutxoQueryCodec.ATTEMPT_PATH -> {
                    byte[] appMessageId = EutxoQueryCodec.decodeAttemptRequest(params);
                    yield EutxoQueryCodec.optionalReceipt(
                            state.get(EutxoStateKeys.attempt(appMessageId))
                                    .map(EutxoReceipt::decode).orElse(null));
                }
                case EutxoQueryCodec.DEPOSIT_PATH -> {
                    EutxoOutpoint outpoint = EutxoQueryCodec.decodeDepositRequest(params);
                    yield EutxoQueryCodec.optionalDepositRecord(
                            state.get(EutxoStateKeys.deposit(outpoint))
                                    .map(EutxoDepositRecord::decode).orElse(null));
                }
                case EutxoQueryCodec.DEPOSITS_PATH -> {
                    EutxoQueryCodec.LifecyclePage page =
                            EutxoQueryCodec.decodeLifecyclePageRequest(params);
                    long count = state.get(EutxoStateKeys.depositCount())
                            .map(EutxoStateMachine::longValue)
                            .orElse(0L);
                    long cursor = page.before() == 0
                            ? count : Math.min(count, page.before() - 1);
                    List<EutxoDepositRecord> deposits = new ArrayList<>();
                    while (cursor > 0 && deposits.size() < page.limit()) {
                        state.get(EutxoStateKeys.depositIndex(cursor))
                                .map(EutxoDepositRecord::decode)
                                .ifPresent(deposits::add);
                        cursor--;
                    }
                    yield EutxoQueryCodec.depositRecords(deposits);
                }
                case EutxoQueryCodec.DEPOSIT_COUNT_PATH -> {
                    requireEmptyQuery(params);
                    yield EutxoQueryCodec.count(
                            state.get(EutxoStateKeys.depositCount())
                                    .map(EutxoStateMachine::longValue)
                                    .orElse(0L));
                }
                case EutxoQueryCodec.RESERVE_PATH -> {
                    String assetId = EutxoQueryCodec.decodeReserveRequest(params);
                    yield EutxoQueryCodec.optionalReserve(
                            state.get(EutxoStateKeys.reserve(assetId))
                                    .map(EutxoReserve::decode).orElse(null));
                }
                case EutxoQueryCodec.BRIDGE_HALT_PATH -> {
                    requireEmptyQuery(params);
                    yield EutxoQueryCodec.bridgeHalt(
                            state.get(EutxoStateKeys.bridgeHalt())
                                    .map(bytes -> new String(
                                            bytes,
                                            StandardCharsets.US_ASCII))
                                    .orElse(""));
                }
                case EutxoQueryCodec.WITHDRAWAL_PATH -> {
                    String claimId = EutxoQueryCodec.decodeWithdrawalRequest(params);
                    yield EutxoQueryCodec.optionalWithdrawalRecord(
                            state.get(EutxoStateKeys.withdrawal(claimId))
                                    .map(EutxoWithdrawalRecord::decode).orElse(null));
                }
                case EutxoQueryCodec.WITHDRAWALS_PATH -> {
                    EutxoQueryCodec.LifecyclePage page =
                            EutxoQueryCodec.decodeLifecyclePageRequest(params);
                    long count = state.get(EutxoStateKeys.totalWithdrawalCount(
                                    bridge.bridgeEpoch()))
                            .map(EutxoStateMachine::longValue)
                            .orElse(0L);
                    long cursor = page.before() == 0
                            ? count : Math.min(count, page.before() - 1);
                    List<EutxoWithdrawalRecord> withdrawals =
                            new ArrayList<>();
                    while (cursor > 0 && withdrawals.size() < page.limit()) {
                        state.get(EutxoStateKeys.withdrawalIndex(
                                        bridge.bridgeEpoch(), cursor))
                                .map(bytes -> new String(
                                        bytes, StandardCharsets.US_ASCII))
                                .flatMap(claimId -> state.get(
                                        EutxoStateKeys.withdrawal(claimId)))
                                .map(EutxoWithdrawalRecord::decode)
                                .ifPresent(withdrawals::add);
                        cursor--;
                    }
                    yield EutxoQueryCodec.withdrawalRecords(withdrawals);
                }
                case EutxoQueryCodec.WITHDRAWAL_COUNT_PATH -> {
                    requireEmptyQuery(params);
                    yield EutxoQueryCodec.count(
                            state.get(EutxoStateKeys.totalWithdrawalCount(
                                            bridge.bridgeEpoch()))
                                    .map(EutxoStateMachine::longValue)
                                    .orElse(0L));
                }
                case EutxoQueryCodec.VALIDITY_TRANSITION_PATH -> {
                    EutxoQueryCodec.Position position =
                            EutxoQueryCodec.decodeValidityTransitionRequest(params);
                    yield EutxoQueryCodec.optionalValidityTransition(
                            state.get(EutxoStateKeys.validityTransition(
                                            position.appHeight(),
                                            position.ordinal()))
                                    .map(EutxoValidityTransition::decode)
                                    .orElse(null));
                }
                case EutxoQueryCodec.L2_PARAMETERS_PATH -> {
                    if (params.length != 0 || validityEngine == null) {
                        throw new AppQueryException(
                                AppQueryException.Code.UNSUPPORTED,
                                "L2 protocol parameters require a validity profile");
                    }
                    yield EutxoQueryCodec.l2Parameters(
                            EutxoL2ParameterSnapshot.create(
                                    chainId, profile, validityEngine));
                }
                case EutxoQueryCodec.PROFILE_PATH ->
                        profile.digestHex().getBytes(StandardCharsets.UTF_8);
                default -> throw new AppQueryException(
                        AppQueryException.Code.UNSUPPORTED,
                        "unsupported EUTxO query path");
            };
        } catch (AppQueryException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw new AppQueryException(
                    AppQueryException.Code.INVALID_REQUEST,
                    "invalid EUTxO query parameters");
        }
    }

    private static void requireEmptyQuery(byte[] params) {
        if (params.length != 0) {
            throw new IllegalArgumentException(
                    "count query does not accept parameters");
        }
    }

    private void ensureGenesis(AppStateWriter writer) {
        byte[] expectedProfile = profile.digestHex().getBytes(StandardCharsets.UTF_8);
        byte[] existingProfile = writer.get(EutxoStateKeys.profile()).orElse(null);
        if (existingProfile != null && !java.util.Arrays.equals(existingProfile, expectedProfile)) {
            throw new IllegalStateException("retained EUTxO profile digest differs from configured profile");
        }
        if (settlementProfile()
                && writer.get(EutxoStateKeys.bridgeParamsCurrent()).isEmpty()) {
            requireFallbackFloor(initialBridgeParams);
            byte[] initial = initialBridgeParams.encode();
            writer.put(EutxoStateKeys.bridgeParamsCurrent(), initial);
            writer.put(EutxoStateKeys.bridgeParamsHistory(0L), initial);
        }
        if (writer.get(EutxoStateKeys.genesis()).isPresent()) {
            ensureValidityState(writer, false);
            return;
        }
        writer.put(EutxoStateKeys.profile(), expectedProfile);
        for (EutxoRecord record : genesis.records()) {
            putRecord(writer, record);
        }
        for (EutxoL2KeyRegistration registration :
                genesis.l2KeyRegistrations()) {
            writer.put(
                    EutxoStateKeys.l2Key(registration.paymentCredential()),
                    registration.encode());
        }
        writer.put(EutxoStateKeys.genesis(),
                genesis.transactionId().getBytes(StandardCharsets.UTF_8));
        ensureValidityState(writer, true);
    }

    private void ensureValidityState(
            AppStateWriter writer,
            boolean creatingGenesis
    ) {
        if (validityEngine == null) {
            if (writer.get(EutxoStateKeys.validityEngine()).isPresent()
                    || writer.get(EutxoStateKeys.validityRoot()).isPresent()
                    || writer.get(EutxoStateKeys.validityWitness()).isPresent()) {
                throw new IllegalStateException(
                        "retained EUTxO state requires its configured validity engine");
            }
            return;
        }
        byte[] engineId = validityEngine.id().getBytes(StandardCharsets.UTF_8);
        byte[] retainedEngine = writer.get(EutxoStateKeys.validityEngine()).orElse(null);
        if (retainedEngine != null && !java.util.Arrays.equals(retainedEngine, engineId)) {
            throw new IllegalStateException(
                    "retained EUTxO validity engine differs from configured engine");
        }
        if (retainedEngine != null) {
            if (writer.get(EutxoStateKeys.validityRoot()).isEmpty()
                    || writer.get(EutxoStateKeys.validityWitness()).isEmpty()) {
                throw new IllegalStateException(
                        "retained EUTxO validity state is incomplete");
            }
            return;
        }
        if (writer.get(EutxoStateKeys.validityRoot()).isPresent()
                || writer.get(EutxoStateKeys.validityWitness()).isPresent()) {
            throw new IllegalStateException(
                    "retained EUTxO validity state has no engine identity");
        }
        if (!creatingGenesis) {
            throw new IllegalStateException(
                    "validity commitments cannot be enabled on retained EUTxO state "
                            + "without an explicit checkpoint migration");
        }
        EutxoValidityCommitment genesisCommitment = validityEngine.genesis();
        writer.put(EutxoStateKeys.validityEngine(), engineId);
        writer.put(EutxoStateKeys.validityRoot(), genesisCommitment.root());
        writer.put(EutxoStateKeys.validityWitness(),
                genesisCommitment.witnessDescriptor());
    }

    private void applyValidity(
            UtxoTransitionEngine.TransitionResult result,
            WithdrawalPlan withdrawalPlan,
            long l1Slot,
            long appHeight,
            int ordinal,
            AppStateWriter writer
    ) {
        if (validityEngine == null) {
            return;
        }
        byte[] previousRoot = writer.get(EutxoStateKeys.validityRoot())
                .orElseThrow(() -> new IllegalStateException(
                        "selected EUTxO validity engine has no committed root"));
        EutxoL2Transaction l2Transaction = Objects.requireNonNull(
                result.l2Transaction(),
                "validity-enabled accepted transition has no L2 envelope");
        EutxoL2Domain domain = l2Transaction.domain();
        EutxoValidityTransition transition = new EutxoValidityTransition(
                previousRoot,
                chainId,
                network,
                profile.digestHex(),
                validityEngine.profileDigest(),
                validityEngine.authorizationProfile(),
                validityEngine.authorizationProfileDigest(),
                domain.commitment(),
                result.transactionId(),
                result.canonicalTransaction(),
                result.resolvedInputs(),
                result.consumed(),
                result.created(),
                withdrawalPlan.claims(),
                l1Slot,
                appHeight,
                ordinal);
        EutxoValidityCommitment next = validityEngine.commit(transition);
        writer.put(EutxoStateKeys.validityRoot(), next.root());
        writer.put(EutxoStateKeys.validityWitness(), next.witnessDescriptor());
        writer.put(EutxoStateKeys.validityTransition(appHeight, ordinal),
                transition.canonicalBytes());
    }

    private static String requireText(String value, String label) {
        value = Objects.requireNonNull(value, label).trim();
        if (value.isEmpty() || value.length() > 63) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }

    private static EutxoTransactionSummary summary(
            UtxoTransitionEngine.TransitionResult result,
            List<EutxoRecord> resolvedInputs,
            AppMessage message,
            long sequence,
            long appHeight,
            int ordinal,
            long l1Slot
    ) {
        String authorization = result.l2Transaction() == null
                ? "cardano-vkey"
                : result.l2Transaction().domain().authorizationProfile();
        return new EutxoTransactionSummary(
                result.transactionId(),
                HexFormat.of().formatHex(message.getMessageId()),
                sequence,
                appHeight,
                ordinal,
                l1Slot,
                result.accepted()
                        ? EutxoTransactionSummary.Status.ACCEPTED
                        : EutxoTransactionSummary.Status.REJECTED,
                authorization,
                resolvedInputs.stream()
                        .map(EutxoStateMachine::summaryEntry)
                        .toList(),
                result.created().stream()
                        .map(EutxoStateMachine::summaryEntry)
                        .toList(),
                result.code());
    }

    private static EutxoTransactionSummary.Entry summaryEntry(
            EutxoRecord record
    ) {
        try {
            TransactionOutput output = TransactionOutput.deserialize(
                    com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
                            .deserialize(record.outputCbor()));
            BigInteger lovelace = output.getValue() == null
                    || output.getValue().getCoin() == null
                    ? BigInteger.ZERO : output.getValue().getCoin();
            return new EutxoTransactionSummary.Entry(
                    record.outpoint(), record.address(), lovelace);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "validated EUTxO output cannot be projected", failure);
        }
    }

    private void applyAccepted(
            UtxoTransitionEngine.TransitionResult result,
            WithdrawalPlan withdrawalPlan,
            AppStateWriter writer
    ) {
        for (EutxoOutpoint outpoint : result.consumed()) {
            byte[] key = EutxoStateKeys.utxo(outpoint);
            EutxoRecord record = writer.get(key)
                    .map(EutxoRecord::decode)
                    .orElseThrow(() -> new IllegalStateException(
                            "validated EUTxO input disappeared before mutation"));
            removeAddressRecord(writer, record);
            writer.delete(key);
        }
        for (EutxoRecord record : result.created()) {
            if (withdrawalPlan.outpoints().contains(record.outpoint())) {
                continue;
            }
            if (writer.get(EutxoStateKeys.utxo(record.outpoint())).isPresent()) {
                throw new IllegalStateException("validated EUTxO output already exists");
            }
            putRecord(writer, record);
        }
        if (!withdrawalPlan.claims().isEmpty()) {
            withdrawalPlan.claims().forEach(claim -> {
                writer.put(
                        EutxoStateKeys.withdrawal(claim.claimId()),
                        EutxoWithdrawalRecord.pending(
                                claim, claim.requestedHeight()).encode());
                writer.put(
                        EutxoStateKeys.withdrawalIndex(
                                claim.bridgeEpoch(),
                                Math.addExact(claim.settlementSequence(), 1)),
                        claim.claimId().getBytes(StandardCharsets.US_ASCII));
            });
            withdrawalPlan.claims().forEach(claim -> writer.put(
                    EutxoStateKeys.withdrawalCommitment(claim.claimId()),
                    EutxoWithdrawalCommitment.fromClaim(claim).encode()));
            writer.put(
                    EutxoStateKeys.reserve(EutxoReserve.LOVELACE),
                    withdrawalPlan.reserve().encode());
            writer.put(EutxoStateKeys.pendingWithdrawalCount(),
                    longBytes(withdrawalPlan.pendingCount()));
            writer.put(EutxoStateKeys.totalWithdrawalCount(
                            bridge.bridgeEpoch()),
                    longBytes(withdrawalPlan.totalCount()));
        }
    }

    private WithdrawalPlan planWithdrawals(
            UtxoTransitionEngine.TransitionResult result,
            long height,
            AppStateReader state
    ) {
        if (!bridge.withdrawalsEnabled()) {
            return WithdrawalPlan.empty();
        }
        List<EutxoWithdrawalClaim> claims = new ArrayList<>();
        EutxoReserve reserve = state.get(EutxoStateKeys.reserve(EutxoReserve.LOVELACE))
                .map(EutxoReserve::decode)
                .orElse(null);
        long pendingCount = state.get(EutxoStateKeys.pendingWithdrawalCount())
                .map(EutxoStateMachine::longValue)
                .orElse(0L);
        long totalCount = state.get(EutxoStateKeys.totalWithdrawalCount(
                        bridge.bridgeEpoch()))
                .map(EutxoStateMachine::longValue)
                .orElse(0L);
        for (EutxoRecord record : result.created()) {
            if (!bridge.withdrawalAddress().equals(record.address())) {
                continue;
            }
            if (bridge.withdrawalsPaused()
                    || state.get(EutxoStateKeys.bridgeHalt()).isPresent()) {
                throw new WithdrawalFailure(
                        "BRIDGE_WITHDRAWALS_PAUSED",
                        "bridge withdrawals are paused or halted");
            }
            if (reserve == null) {
                throw new WithdrawalFailure(
                        "BRIDGE_RESERVE_MISSING",
                        "bridge withdrawal has no committed reserve");
            }
            WithdrawalOutput output = withdrawalOutput(record);
            if (output.lovelace().compareTo(bridge.maximumWithdrawalLovelace()) > 0) {
                throw new WithdrawalFailure(
                        "BRIDGE_WITHDRAWAL_LIMIT",
                        "withdrawal exceeds the configured per-claim limit");
            }
            EutxoWithdrawalDatum datum = output.datum();
            if (!bridge.chainId().equals(datum.chainId())
                    || bridge.bridgeEpoch() != datum.bridgeEpoch()) {
                throw new WithdrawalFailure(
                        "BRIDGE_WITHDRAWAL_IDENTITY",
                        "withdrawal targets another chain or bridge epoch");
            }
            EutxoWithdrawalClaim claim;
            if (settlementProfile()) {
                // Keep a claim the vault could never pay out of the queue:
                // batches form oldest-first, so one unsettleable claim would
                // re-batch and fail forever, blocking every later claim on
                // the chain. Enterprise AND base destinations are payable —
                // the validator fingerprints both — so only pointer or
                // malformed addresses are refused here.
                requireSettleableDestination(datum.destinationAddress());
                // ADR-UTXO-009: the withdrawer's output funds BOTH the payout
                // and the committed executor bounty; the fee resolves from
                // the governed schedule at creation and is frozen in the
                // claim (and its id) forever.
                EutxoBridgeParams params = currentParams(state);
                java.math.BigInteger total = output.lovelace();
                java.math.BigInteger fee = params.resolveBounty(total);
                java.math.BigInteger payout = total.subtract(fee);
                if (payout.signum() <= 0 || payout.compareTo(
                        java.math.BigInteger.valueOf(
                                params.minWithdrawalLovelace())) < 0) {
                    throw new WithdrawalFailure(
                            "BRIDGE_WITHDRAWAL_MINIMUM",
                            "withdrawal payout after the executor bounty is "
                                    + "below the governed minimum");
                }
                claim = new EutxoWithdrawalClaim(
                        EutxoWithdrawalClaim.ABI_VERSION_V2,
                        bridge.chainId(),
                        bridge.bridgeEpoch(),
                        record.outpoint(),
                        datum.destinationAddress(),
                        payout,
                        datum.nonce(),
                        totalCount,
                        height,
                        fee);
            } else {
                claim = new EutxoWithdrawalClaim(
                        EutxoWithdrawalClaim.ABI_VERSION,
                        bridge.chainId(),
                        bridge.bridgeEpoch(),
                        record.outpoint(),
                        datum.destinationAddress(),
                        output.lovelace(),
                        datum.nonce(),
                        totalCount,
                        height);
            }
            try {
                EutxoWithdrawalCommitment.fromClaim(claim);
            } catch (IllegalArgumentException failure) {
                throw new WithdrawalFailure(
                        "BRIDGE_WITHDRAWAL_DESTINATION",
                        "withdrawal destination is outside the proof bridge "
                                + "address profile: " + failure.getMessage());
            }
            if (state.get(EutxoStateKeys.withdrawal(claim.claimId())).isPresent()) {
                throw new WithdrawalFailure(
                        "BRIDGE_WITHDRAWAL_DUPLICATE",
                        "withdrawal claim already exists");
            }
            claims.add(claim);
            reserve = reserve.requestWithdrawal(output.lovelace());
            pendingCount = Math.addExact(pendingCount, 1);
            totalCount = Math.addExact(totalCount, 1);
            if (pendingCount > bridge.maximumPendingWithdrawals()) {
                throw new WithdrawalFailure(
                        "BRIDGE_PENDING_LIMIT",
                        "bridge pending-withdrawal limit is reached");
            }
        }
        return claims.isEmpty()
                ? WithdrawalPlan.empty()
                : new WithdrawalPlan(
                        List.copyOf(claims), reserve, pendingCount, totalCount);
    }

    private WithdrawalOutput withdrawalOutput(EutxoRecord record) {
        try {
            TransactionOutput output = TransactionOutput.deserialize(
                    com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.deserialize(
                            record.outputCbor()));
            if (!bridge.withdrawalAddress().equals(output.getAddress())
                    || output.getValue() == null
                    || output.getValue().getCoin() == null
                    || output.getValue().getCoin().signum() <= 0
                    || (output.getValue().getMultiAssets() != null
                    && !output.getValue().getMultiAssets().isEmpty())
                    || output.getInlineDatum() == null
                    || output.getDatumHash() != null
                    || output.getScriptRef() != null) {
                throw new WithdrawalFailure(
                        "BRIDGE_WITHDRAWAL_OUTPUT_INVALID",
                        "withdrawal output is outside the lovelace-only bridge profile");
            }
            return new WithdrawalOutput(
                    output.getValue().getCoin(),
                    EutxoWithdrawalDatum.decode(
                            output.getInlineDatum().serializeToBytes()));
        } catch (WithdrawalFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new WithdrawalFailure(
                    "BRIDGE_WITHDRAWAL_OUTPUT_INVALID",
                    "withdrawal output cannot be decoded");
        }
    }

    private EutxoDepositClaim acceptedDeposit(AppMessage message) {
        L1Observation observation = L1Observation.decode(message.getBody());
        return acceptedDeposit(observation);
    }

    private EutxoDepositClaim acceptedDeposit(L1Observation observation) {
        if (observation == null
                || !bridge.topic().equals(observation.topic())
                || !bridge.observerId().equals(observation.observerId())) {
            throw new IllegalArgumentException("invalid bridge observation envelope");
        }
        EutxoDepositClaim claim = EutxoDepositClaim.decode(observation.claim());
        if (!bridge.chainId().equals(claim.chainId())
                || !bridge.vaultAddress().equals(claim.vaultAddress())
                || !bridge.vaultScriptHash().equals(claim.vaultScriptHash())
                || !claim.acceptedOutpoint().transactionId().equals(
                java.util.HexFormat.of().formatHex(observation.txHash()))
                || claim.l1Slot() != observation.slot()
                || !java.util.Arrays.equals(claim.l1BlockHash(), observation.blockHash())) {
            throw new IllegalArgumentException("bridge observation does not match its configured identity");
        }
        mirroredLovelace(claim);
        return claim;
    }

    private void importDeposit(
            EutxoDepositClaim claim,
            long creditedHeight,
            AppStateWriter writer
    ) {
        if (writer.get(EutxoStateKeys.bridgeHalt()).isPresent()) {
            return;
        }
        EutxoDepositRecord expected = new EutxoDepositRecord(
                claim, claim.mirroredOutpoint(), creditedHeight);
        byte[] depositKey = EutxoStateKeys.deposit(claim.acceptedOutpoint());
        EutxoDepositRecord existing = writer.get(depositKey)
                .map(EutxoDepositRecord::decode)
                .orElse(null);
        if (existing != null) {
            if (!existing.claim().equals(claim)
                    || !existing.mirroredOutpoint().equals(expected.mirroredOutpoint())) {
                throw new IllegalStateException(
                        "accepted L1 outpoint is bound to a different bridge deposit");
            }
            return;
        }
        EutxoRecord mirrored = new EutxoRecord(
                claim.mirroredOutpoint(),
                claim.l2Address(),
                claim.mirroredOutputCbor(),
                EutxoRecord.Origin.L1_DEPOSIT);
        if (writer.get(EutxoStateKeys.utxo(mirrored.outpoint())).isPresent()) {
            throw new IllegalStateException("derived bridge outpoint already exists");
        }
        BigInteger lovelace = mirroredLovelace(claim);
        byte[] reserveKey = EutxoStateKeys.reserve(EutxoReserve.LOVELACE);
        EutxoReserve reserve = writer.get(reserveKey)
                .map(EutxoReserve::decode)
                .orElseGet(() -> EutxoReserve.empty(EutxoReserve.LOVELACE))
                .credit(lovelace);
        importL2KeyBinding(claim, writer);
        putRecord(writer, mirrored);
        writer.put(depositKey, expected.encode());
        long depositSequence = Math.addExact(
                writer.get(EutxoStateKeys.depositCount())
                        .map(EutxoStateMachine::longValue)
                        .orElse(0L),
                1);
        writer.put(EutxoStateKeys.depositIndex(depositSequence), expected.encode());
        writer.put(EutxoStateKeys.depositCount(), longBytes(depositSequence));
        writer.put(reserveKey, reserve.encode());
        if (settlementProfile()) {
            // Track the accepted L1 outpoint as LIVE vault custody: batch
            // settlement confirmations must consume a tracked outpoint.
            writer.put(EutxoStateKeys.bridgeVaultOutpoint(
                    claim.acceptedOutpoint()), new byte[] {1});
        }
    }

    private void importL2KeyBinding(
            EutxoDepositClaim claim,
            AppStateWriter writer
    ) {
        if (!claim.l2KeyBinding().present()) {
            return;
        }
        if (validityEngine == null
                || !validityEngine.authorizationProfile().equals(
                claim.l2KeyBinding().authorizationProfile())) {
            throw new IllegalStateException(
                    "deposit L2 key binding differs from the selected authorization profile");
        }
        Address address;
        try {
            address = new Address(claim.l2Address());
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "deposit L2 key binding has an invalid address", failure);
        }
        if (!AddressProvider.isPubKeyHashInPaymentPart(address)) {
            throw new IllegalStateException(
                    "deposit L2 key binding requires a key-controlled address");
        }
        byte[] paymentCredential = address.getPaymentCredentialHash()
                .orElseThrow(() -> new IllegalStateException(
                        "deposit L2 key binding has no payment credential"));
        if (!java.util.Arrays.equals(
                paymentCredential, claim.depositorKeyHash())) {
            throw new IllegalStateException(
                    "deposit L2 key binding is not authorized by its depositor");
        }
        String credential = HexFormat.of().formatHex(paymentCredential);
        EutxoL2KeyRegistration registration =
                new EutxoL2KeyRegistration(
                        credential,
                        claim.l2KeyBinding().authorizationProfile(),
                        claim.l2KeyBinding().keyEpoch(),
                        claim.l2KeyBinding().publicKey(),
                        EutxoL2KeyRegistration.Status.ACTIVE);
        byte[] key = EutxoStateKeys.l2Key(credential);
        EutxoL2KeyRegistration existing = writer.get(key)
                .map(EutxoL2KeyRegistration::decode)
                .orElse(null);
        if (existing == null) {
            writer.put(key, registration.encode());
        } else if (!existing.equals(registration)) {
            throw new IllegalStateException(
                    "deposit conflicts with the active L2 key registration");
        }
    }

    private static BigInteger mirroredLovelace(EutxoDepositClaim claim) {
        try {
            TransactionOutput output = TransactionOutput.deserialize(
                    com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.deserialize(
                            claim.mirroredOutputCbor()));
            if (!claim.l2Address().equals(output.getAddress())
                    || output.getValue() == null
                    || output.getValue().getCoin() == null
                    || output.getValue().getCoin().signum() <= 0
                    || (output.getValue().getMultiAssets() != null
                    && !output.getValue().getMultiAssets().isEmpty())
                    || output.getDatumHash() != null
                    || output.getInlineDatum() != null
                    || output.getScriptRef() != null) {
                throw new IllegalArgumentException(
                        "mirrored bridge output is outside the lovelace-only profile");
            }
            return output.getValue().getCoin();
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid mirrored bridge output", failure);
        }
    }

    private EutxoWithdrawalConfirmation withdrawalConfirmation(AppMessage message) {
        L1Observation observation = L1Observation.decode(message.getBody());
        return withdrawalConfirmation(observation);
    }

    private EutxoWithdrawalConfirmation withdrawalConfirmation(L1Observation observation) {
        if (observation == null
                || !bridge.confirmationTopic().equals(observation.topic())
                || !bridge.confirmationObserverId().equals(observation.observerId())) {
            throw new IllegalArgumentException(
                    "invalid bridge withdrawal confirmation envelope");
        }
        EutxoWithdrawalConfirmation confirmation =
                EutxoWithdrawalConfirmation.decode(observation.claim());
        String observedTransactionId =
                java.util.HexFormat.of().formatHex(observation.txHash());
        if (!bridge.chainId().equals(confirmation.chainId())
                || bridge.bridgeEpoch() != confirmation.bridgeEpoch()
                || !observedTransactionId.equals(
                confirmation.settlementTransactionId())
                || confirmation.l1Slot() != observation.slot()
                || !java.util.Arrays.equals(
                confirmation.l1BlockHash(), observation.blockHash())) {
            throw new IllegalArgumentException(
                    "withdrawal confirmation does not match its configured identity");
        }
        return confirmation;
    }

    /**
     * Authenticity gate for a batch settlement (ADR-UTXO-009 review fix):
     * the confirmed transaction must have SPENT a tracked live vault
     * outpoint. Anyone can pay the vault address with a fabricated marker
     * output, but only a genuine Settle/Exit spend consumes vault custody —
     * which only the on-chain validator authorizes. On success the custody
     * set rotates: matched outpoints leave, the continuing output enters.
     * Returns false (and halts the bridge) when no tracked outpoint was
     * spent.
     */
    private boolean applyVaultCustody(
            EutxoBatchWithdrawalConfirmation confirmation,
            AppStateWriter writer
    ) {
        if (writer.get(EutxoStateKeys.bridgeHalt()).isPresent()) {
            return false;
        }
        boolean spendsVault = false;
        for (var spent : confirmation.spentOutpoints()) {
            byte[] key = EutxoStateKeys.bridgeVaultOutpoint(spent);
            if (writer.get(key).isPresent()) {
                spendsVault = true;
                writer.delete(key);
            }
        }
        if (!spendsVault) {
            haltBridge(writer, "WITHDRAWAL_CONFIRMATION_UNPROVEN");
            return false;
        }
        writer.put(EutxoStateKeys.bridgeVaultOutpoint(
                confirmation.continuingVaultOutpoint()), new byte[] {1});
        return true;
    }

    private EutxoBatchWithdrawalConfirmation batchWithdrawalConfirmation(
            AppMessage message
    ) {
        L1Observation observation = L1Observation.decode(message.getBody());
        return batchWithdrawalConfirmation(observation);
    }

    private EutxoBatchWithdrawalConfirmation batchWithdrawalConfirmation(
            L1Observation observation
    ) {
        if (observation == null
                || !bridge.confirmationTopic().equals(observation.topic())
                || !bridge.confirmationObserverId().equals(observation.observerId())) {
            throw new IllegalArgumentException(
                    "invalid bridge batch withdrawal confirmation envelope");
        }
        EutxoBatchWithdrawalConfirmation confirmation =
                EutxoBatchWithdrawalConfirmation.decode(observation.claim());
        String observedTransactionId =
                java.util.HexFormat.of().formatHex(observation.txHash());
        if (!bridge.chainId().equals(confirmation.chainId())
                || bridge.bridgeEpoch() != confirmation.bridgeEpoch()
                || !observedTransactionId.equals(
                confirmation.settlementTransactionId())
                || confirmation.l1Slot() != observation.slot()
                || !java.util.Arrays.equals(
                confirmation.l1BlockHash(), observation.blockHash())) {
            throw new IllegalArgumentException(
                    "batch withdrawal confirmation does not match its configured identity");
        }
        return confirmation;
    }

    private void confirmWithdrawal(
            EutxoWithdrawalConfirmation confirmation,
            long height,
            AppStateWriter writer
    ) {
        byte[] key = EutxoStateKeys.withdrawal(confirmation.claimId());
        EutxoWithdrawalRecord record = writer.get(key)
                .map(EutxoWithdrawalRecord::decode)
                .orElse(null);
        if (record == null) {
            haltBridge(writer, "UNKNOWN_WITHDRAWAL_CONFIRMATION");
            return;
        }
        EutxoWithdrawalClaim claim = record.claim();
        if (!claim.claimId().equals(confirmation.claimId())
                || !claim.destinationAddress().equals(
                confirmation.destinationAddress())
                || !claim.lovelace().equals(confirmation.lovelace())
                || claim.bridgeEpoch() != confirmation.bridgeEpoch()) {
            haltBridge(writer, "WITHDRAWAL_CONFIRMATION_MISMATCH");
            return;
        }
        EutxoWithdrawalRecord confirmed;
        try {
            confirmed = record.confirm(
                    confirmation.settlementTransactionId(),
                    confirmation.l1Slot(),
                    confirmation.l1BlockHash(),
                    height);
        } catch (IllegalStateException mismatch) {
            haltBridge(writer, "WITHDRAWAL_CONFIRMATION_REBIND");
            return;
        }
        if (confirmed == record) {
            return;
        }
        byte[] reserveKey = EutxoStateKeys.reserve(EutxoReserve.LOVELACE);
        EutxoReserve reserve = writer.get(reserveKey)
                .map(EutxoReserve::decode)
                .orElse(null);
        if (reserve == null) {
            haltBridge(writer, "WITHDRAWAL_RESERVE_MISSING");
            return;
        }
        long pendingCount = writer.get(EutxoStateKeys.pendingWithdrawalCount())
                .map(EutxoStateMachine::longValue)
                .orElse(-1L);
        if (pendingCount <= 0) {
            haltBridge(writer, "WITHDRAWAL_PENDING_COUNT_INVALID");
            return;
        }
        try {
            reserve = reserve.confirmWithdrawal(claim.totalLovelace());
        } catch (IllegalArgumentException mismatch) {
            haltBridge(writer, "WITHDRAWAL_RESERVE_MISMATCH");
            return;
        }
        writer.put(key, confirmed.encode());
        writer.put(reserveKey, reserve.encode());
        writer.put(EutxoStateKeys.pendingWithdrawalCount(),
                longBytes(pendingCount - 1));
    }

    /**
     * Genesis guard for the per-profile fallback floor (ADR-UTXO-009 §13.2):
     * a settlement chain cannot start below its profile's floor.
     */
    private void requireFallbackFloor(EutxoBridgeParams params) {
        if (params.fallbackDelaySlots() < profile.fallbackDelayMinSlots()) {
            throw new IllegalStateException(
                    "genesis bridge params fall below the settlement profile's"
                            + " fallback-delay floor");
        }
    }

    private boolean settlementProfile() {
        return profile.version() >= 3;
    }

    private EutxoBridgeParams currentParams(AppStateReader state) {
        return state.get(EutxoStateKeys.bridgeParamsCurrent())
                .map(EutxoBridgeParams::decode)
                .orElse(initialBridgeParams);
    }

    @Override
    public AdmissionResult validatePrivilegedSystemSubmission(String topic, byte[] body) {
        if (!settlementProfile()
                || !EutxoBridgeParamsGovernanceV1.TOPIC.equals(topic)) {
            return AdmissionResult.reject(
                    "unsupported privileged EUTxO system topic");
        }
        try {
            EutxoBridgeParamsGovernanceV1.decode(body);
            return AdmissionResult.accept();
        } catch (IllegalArgumentException failure) {
            return AdmissionResult.reject(
                    "invalid bridge params command: " + failure.getMessage());
        }
    }

    /**
     * Deterministic governed parameter change: approvals accumulate on the
     * EXACT command bytes from distinct membership-epoch members; the
     * threshold schedules the record at {@code height + max(1, lag)}.
     * Malformed or unauthorized inputs are skipped deterministically —
     * privileged admission already screened local submissions.
     */
    private void processParamsCommand(
            AppMessage message, long height, AppStateWriter writer) {
        if (membershipView == null) {
            return;
        }
        EutxoBridgeParamsGovernanceV1.Command command;
        try {
            command = EutxoBridgeParamsGovernanceV1.decode(message.getBody());
        } catch (IllegalArgumentException malformed) {
            return;
        }
        // ADR-UTXO-009 §13.2: the tier-1 fallback floor is per-PROFILE, so
        // the machine enforces it here (the params record only carries the
        // structural bound). A sub-floor proposal is dropped deterministically
        // — it never accumulates approvals on any member.
        if (command.params().fallbackDelaySlots()
                < profile.fallbackDelayMinSlots()) {
            return;
        }
        byte[] senderKey = message.getSender();
        if (senderKey == null || senderKey.length != 32) {
            return;
        }
        String sender = java.util.HexFormat.of().formatHex(senderKey);
        AppChainMembershipEpoch epoch = membershipView.epochAt(height);
        if (epoch == null || !epoch.members().contains(sender)) {
            return;
        }
        if (writer.get(EutxoStateKeys.bridgeParamsPending()).isPresent()) {
            // One scheduled change at a time; later proposals wait for it.
            return;
        }
        java.util.TreeMap<String, ParamsProposal> proposals =
                decodeProposals(writer);
        String digest = command.digestHex();
        ParamsProposal proposal = proposals.computeIfAbsent(digest,
                ignored -> new ParamsProposal(
                        command.encode(), new java.util.TreeSet<>()));
        if (!proposal.approvers().add(sender)) {
            return;
        }
        if (proposal.approvers().size() >= epoch.threshold()) {
            long activation = Math.addExact(
                    height, Math.max(1L, command.activationLag()));
            writer.put(EutxoStateKeys.bridgeParamsPending(),
                    encodePending(activation,
                            command.params().withEffectiveHeight(activation)));
            // A scheduled change voids every open proposal.
            writer.delete(EutxoStateKeys.bridgeParamsProposals());
            return;
        }
        writer.put(EutxoStateKeys.bridgeParamsProposals(),
                encodeProposals(proposals));
    }

    private void activateScheduledParams(long height, AppStateWriter writer) {
        byte[] pending = writer.get(
                EutxoStateKeys.bridgeParamsPending()).orElse(null);
        if (pending == null) {
            return;
        }
        PendingParams scheduled = decodePending(pending);
        if (height < scheduled.activationHeight()) {
            return;
        }
        writer.put(EutxoStateKeys.bridgeParamsCurrent(),
                scheduled.params().encode());
        writer.put(EutxoStateKeys.bridgeParamsHistory(
                scheduled.activationHeight()), scheduled.params().encode());
        writer.delete(EutxoStateKeys.bridgeParamsPending());
    }

    private record ParamsProposal(
            byte[] commandBytes, java.util.TreeSet<String> approvers) {
    }

    private record PendingParams(long activationHeight, EutxoBridgeParams params) {
    }

    private static byte[] encodePending(long activation, EutxoBridgeParams params) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            new co.nstant.in.cbor.CborEncoder(out).encode(
                    new co.nstant.in.cbor.CborBuilder()
                            .addArray()
                            .add(new co.nstant.in.cbor.model.UnsignedInteger(activation))
                            .add(new co.nstant.in.cbor.model.ByteString(params.encode()))
                            .end()
                            .build());
            return out.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot encode pending params", failure);
        }
    }

    private static PendingParams decodePending(byte[] bytes) {
        try {
            var items = new co.nstant.in.cbor.CborDecoder(
                    new java.io.ByteArrayInputStream(bytes)).decode();
            var array = (co.nstant.in.cbor.model.Array) items.getFirst();
            var fields = array.getDataItems();
            long activation = ((co.nstant.in.cbor.model.UnsignedInteger)
                    fields.get(0)).getValue().longValueExact();
            EutxoBridgeParams params = EutxoBridgeParams.decode(
                    ((co.nstant.in.cbor.model.ByteString) fields.get(1)).getBytes());
            return new PendingParams(activation, params);
        } catch (Exception failure) {
            throw new IllegalStateException("malformed pending params", failure);
        }
    }

    private java.util.TreeMap<String, ParamsProposal> decodeProposals(
            AppStateReader state) {
        java.util.TreeMap<String, ParamsProposal> proposals = new java.util.TreeMap<>();
        byte[] retained = state.get(
                EutxoStateKeys.bridgeParamsProposals()).orElse(null);
        if (retained == null) {
            return proposals;
        }
        try {
            var items = new co.nstant.in.cbor.CborDecoder(
                    new java.io.ByteArrayInputStream(retained)).decode();
            var array = (co.nstant.in.cbor.model.Array) items.getFirst();
            for (var item : array.getDataItems()) {
                var entry = ((co.nstant.in.cbor.model.Array) item).getDataItems();
                byte[] commandBytes = ((co.nstant.in.cbor.model.ByteString)
                        entry.get(0)).getBytes();
                java.util.TreeSet<String> approvers = new java.util.TreeSet<>();
                for (var approver : ((co.nstant.in.cbor.model.Array)
                        entry.get(1)).getDataItems()) {
                    approvers.add(java.util.HexFormat.of().formatHex(
                            ((co.nstant.in.cbor.model.ByteString) approver)
                                    .getBytes()));
                }
                proposals.put(EutxoBridgeParamsGovernanceV1
                        .decode(commandBytes).digestHex(),
                        new ParamsProposal(commandBytes, approvers));
            }
        } catch (Exception failure) {
            throw new IllegalStateException("malformed params proposals", failure);
        }
        return proposals;
    }

    private static byte[] encodeProposals(
            java.util.TreeMap<String, ParamsProposal> proposals) {
        try {
            var root = new co.nstant.in.cbor.model.Array();
            for (ParamsProposal proposal : proposals.values()) {
                var entry = new co.nstant.in.cbor.model.Array();
                entry.add(new co.nstant.in.cbor.model.ByteString(
                        proposal.commandBytes()));
                var approvers = new co.nstant.in.cbor.model.Array();
                for (String approver : proposal.approvers()) {
                    approvers.add(new co.nstant.in.cbor.model.ByteString(
                            java.util.HexFormat.of().parseHex(approver)));
                }
                entry.add(approvers);
                root.add(entry);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            new co.nstant.in.cbor.CborEncoder(out).encode(root);
            return out.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot encode params proposals", failure);
        }
    }

    static final String SETTLEMENT_EFFECT_TYPE = "l1.settlement";
    private static final String SETTLEMENT_SCOPE_PREFIX = "bridge/settlement/";

    private static String settlementScope(long epoch, long batchSeq) {
        return SETTLEMENT_SCOPE_PREFIX + epoch + "/" + batchSeq;
    }

    private static long settlementScopeBatch(String scope) {
        int slash = scope.lastIndexOf('/');
        try {
            return Long.parseLong(scope.substring(slash + 1));
        } catch (RuntimeException invalid) {
            return -1;
        }
    }

    /** Bound the CHAIN result window to the rooting cadence, min 8 blocks. */
    private static int settlementExpiryBlocks(EutxoBridgeParams params) {
        long blocks = Math.max(8, params.rootingBlocks() * 4);
        return (int) Math.min(blocks, 512);
    }

    private static void haltBridge(AppStateWriter writer, String reason) {
        writer.put(
                EutxoStateKeys.bridgeHalt(),
                reason.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] longBytes(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("counter cannot be negative");
        }
        return java.nio.ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static long longValue(byte[] bytes) {
        if (bytes.length != Long.BYTES) {
            throw new IllegalArgumentException("invalid committed counter");
        }
        long value = java.nio.ByteBuffer.wrap(bytes).getLong();
        if (value < 0) {
            throw new IllegalArgumentException("committed counter cannot be negative");
        }
        return value;
    }

    private void putRecord(AppStateWriter writer, EutxoRecord record) {
        writer.put(EutxoStateKeys.utxo(record.outpoint()), record.encode());
        List<EutxoRecord> records = new ArrayList<>(addressRecords(writer, record.address()));
        records.add(record);
        records.sort(Comparator.comparing(EutxoRecord::outpoint));
        if (records.size() > profile.maxAddressUtxos()) {
            throw new IllegalStateException("EUTxO address index exceeds the profile bound");
        }
        writer.put(EutxoStateKeys.addressIndex(record.address()), EutxoQueryCodec.records(records));
    }

    private void removeAddressRecord(AppStateWriter writer, EutxoRecord record) {
        List<EutxoRecord> records = new ArrayList<>(addressRecords(writer, record.address()));
        if (!records.removeIf(value -> value.outpoint().equals(record.outpoint()))) {
            throw new IllegalStateException("EUTxO address index is inconsistent");
        }
        byte[] key = EutxoStateKeys.addressIndex(record.address());
        if (records.isEmpty()) {
            writer.delete(key);
        } else {
            writer.put(key, EutxoQueryCodec.records(records));
        }
    }

    private static List<EutxoRecord> addressRecords(AppStateReader state, String address) {
        return state.get(EutxoStateKeys.addressIndex(address))
                .map(EutxoQueryCodec::decodeRecords)
                .orElse(List.of());
    }

    private record WithdrawalOutput(
            BigInteger lovelace,
            EutxoWithdrawalDatum datum
    ) {
    }

    private record WithdrawalPlan(
            List<EutxoWithdrawalClaim> claims,
            EutxoReserve reserve,
            long pendingCount,
            long totalCount
    ) {
        private static WithdrawalPlan empty() {
            return new WithdrawalPlan(List.of(), null, 0, 0);
        }

        private java.util.Set<EutxoOutpoint> outpoints() {
            return claims.stream()
                    .map(EutxoWithdrawalClaim::withdrawalOutpoint)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    /**
     * ADR-UTXO-009: the settle redeemer commits the destination as a Plutus
     * {@code Address}. The vault validator fingerprints an enterprise
     * ({@code Nothing}) or a base ({@code Just (StakingHash …)}) destination,
     * so both are payable; a pointer address is not, and a claim carrying one
     * could never settle.
     */
    private static void requireSettleableDestination(String destinationAddress) {
        try {
            Address destination = new Address(destinationAddress);
            if (destination.getPaymentCredential().isEmpty()) {
                throw new WithdrawalFailure("BRIDGE_WITHDRAWAL_DESTINATION",
                        "withdrawal destination has no payment credential");
            }
            switch (destination.getAddressType()) {
                case Base, Enterprise -> { }
                default -> throw new WithdrawalFailure(
                        "BRIDGE_WITHDRAWAL_DESTINATION",
                        "withdrawal destination must be a base or enterprise "
                                + "address; the settlement vault cannot pay a "
                                + destination.getAddressType() + " address");
            }
        } catch (WithdrawalFailure failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw new WithdrawalFailure("BRIDGE_WITHDRAWAL_DESTINATION",
                    "withdrawal destination is not a valid Cardano address");
        }
    }

    private static final class WithdrawalFailure extends RuntimeException {
        private final String code;

        private WithdrawalFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
