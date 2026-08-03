package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
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
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalCommitment;
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
                "local-eutxo", "devnet");
    }

    EutxoStateMachine(
            EutxoProfile profile,
            EutxoGenesis genesis,
            UtxoTransitionEngine transitionEngine,
            EutxoBridgeConfig bridge
    ) {
        this(profile, genesis, transitionEngine, bridge, null,
                "local-eutxo", "devnet");
    }

    EutxoStateMachine(
            EutxoProfile profile,
            EutxoGenesis genesis,
            UtxoTransitionEngine transitionEngine,
            EutxoBridgeConfig bridge,
            EutxoValidityCommitmentEngine validityEngine
    ) {
        this(profile, genesis, transitionEngine, bridge, validityEngine,
                "local-eutxo", "devnet");
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
    public void apply(AppBlock block, AppStateWriter writer) {
        ensureGenesis(writer);
        int ordinal = 0;
        long summarySequence = writer.get(EutxoStateKeys.summaryCount())
                .map(EutxoStateMachine::longValue)
                .orElse(0L);
        for (AppMessage message : block.messages()) {
            if (bridge.enabled() && bridge.topic().equals(message.getTopic())) {
                importDeposit(acceptedDeposit(message), block.height(), writer);
                ordinal++;
                continue;
            }
            if (bridge.withdrawalsEnabled()
                    && bridge.confirmationTopic().equals(message.getTopic())) {
                confirmWithdrawal(
                        withdrawalConfirmation(message), block.height(), writer);
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
            EutxoWithdrawalClaim claim = new EutxoWithdrawalClaim(
                    EutxoWithdrawalClaim.ABI_VERSION,
                    bridge.chainId(),
                    bridge.bridgeEpoch(),
                    record.outpoint(),
                    datum.destinationAddress(),
                    output.lovelace(),
                    datum.nonce(),
                    totalCount,
                    height);
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
            reserve = reserve.confirmWithdrawal(claim.lovelace());
        } catch (IllegalArgumentException mismatch) {
            haltBridge(writer, "WITHDRAWAL_RESERVE_MISMATCH");
            return;
        }
        writer.put(key, confirmed.encode());
        writer.put(reserveKey, reserve.encode());
        writer.put(EutxoStateKeys.pendingWithdrawalCount(),
                longBytes(pendingCount - 1));
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
