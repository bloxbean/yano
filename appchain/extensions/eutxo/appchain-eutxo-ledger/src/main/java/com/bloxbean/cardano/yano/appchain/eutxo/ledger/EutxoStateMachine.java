package com.bloxbean.cardano.yano.appchain.eutxo.ledger;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoStateKeys;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoContract;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Optional genesis-funded Cardano-shaped EUTxO app-chain state machine. */
public final class EutxoStateMachine implements AppStateMachine {
    public static final String ID = EutxoContract.STATE_MACHINE_ID;
    public static final String TOPIC = EutxoContract.TRANSACTION_TOPIC;

    private final EutxoProfile profile;
    private final EutxoGenesis genesis;
    private final UtxoTransitionEngine transitionEngine;

    EutxoStateMachine(
            EutxoProfile profile,
            EutxoGenesis genesis,
            UtxoTransitionEngine transitionEngine
    ) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.genesis = Objects.requireNonNull(genesis, "genesis");
        this.transitionEngine = Objects.requireNonNull(transitionEngine, "transitionEngine");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
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
        for (AppMessage message : block.messages()) {
            UtxoTransitionEngine.TransitionResult result =
                    transitionEngine.transition(message.getBody(), block.l1Slot(), writer);
            EutxoReceipt receipt = new EutxoReceipt(
                    result.accepted() ? EutxoReceipt.Status.ACCEPTED : EutxoReceipt.Status.REJECTED,
                    result.transactionId(),
                    message.getMessageId(),
                    block.height(),
                    ordinal++,
                    block.l1Slot(),
                    result.code(),
                    result.detail());
            if (result.accepted()) {
                applyAccepted(result, writer);
                writer.put(EutxoStateKeys.transaction(result.transactionId()), receipt.encode());
            }
            writer.put(EutxoStateKeys.attempt(message.getMessageId()), receipt.encode());
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
                case EutxoQueryCodec.ATTEMPT_PATH -> {
                    byte[] appMessageId = EutxoQueryCodec.decodeAttemptRequest(params);
                    yield EutxoQueryCodec.optionalReceipt(
                            state.get(EutxoStateKeys.attempt(appMessageId))
                                    .map(EutxoReceipt::decode).orElse(null));
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

    private void ensureGenesis(AppStateWriter writer) {
        byte[] expectedProfile = profile.digestHex().getBytes(StandardCharsets.UTF_8);
        byte[] existingProfile = writer.get(EutxoStateKeys.profile()).orElse(null);
        if (existingProfile != null && !java.util.Arrays.equals(existingProfile, expectedProfile)) {
            throw new IllegalStateException("retained EUTxO profile digest differs from configured profile");
        }
        if (writer.get(EutxoStateKeys.genesis()).isPresent()) {
            return;
        }
        writer.put(EutxoStateKeys.profile(), expectedProfile);
        for (EutxoRecord record : genesis.records()) {
            putRecord(writer, record);
        }
        writer.put(EutxoStateKeys.genesis(),
                genesis.transactionId().getBytes(StandardCharsets.UTF_8));
    }

    private void applyAccepted(
            UtxoTransitionEngine.TransitionResult result,
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
            if (writer.get(EutxoStateKeys.utxo(record.outpoint())).isPresent()) {
                throw new IllegalStateException("validated EUTxO output already exists");
            }
            putRecord(writer, record);
        }
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
}
