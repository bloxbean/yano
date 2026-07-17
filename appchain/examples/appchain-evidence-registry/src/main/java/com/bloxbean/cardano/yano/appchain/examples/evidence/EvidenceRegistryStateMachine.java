package com.bloxbean.cardano.yano.appchain.examples.evidence;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.EvidenceCommandCodec;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.EvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.NotifyEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.RepublishEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.SubmitEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.event.EvidenceAvailableEventV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetRequestV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetResponseV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceEffectOperation;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceEffectRef;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceHeadV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceScope;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceStatus;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceTerminalResultV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceTerminalOutcome;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTypes;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsCidFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutReceiptV1;

import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic ADR-013 evidence workflow and effect-result state reducer. */
public final class EvidenceRegistryStateMachine implements AppStateMachine {
    public static final String ID = EvidenceContract.STATE_MACHINE_ID;
    private static final String INVALID_COMMAND = "INVALID_EVIDENCE_COMMAND";
    private static final String UNAUTHORIZED_COMMAND = "UNAUTHORIZED_EVIDENCE_COMMAND";

    private final EvidenceRegistryConfig config;

    public EvidenceRegistryStateMachine(EvidenceRegistryConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AdmissionResult validate(AppMessage message) {
        if (message == null || !EvidenceContract.COMMAND_TOPIC.equals(message.getTopic())) {
            return AdmissionResult.reject(INVALID_COMMAND);
        }
        try {
            EvidenceCommandV1 command = EvidenceCommandCodec.decode(message.getBody());
            if (!validSender(message.getSender())) {
                return AdmissionResult.reject(UNAUTHORIZED_COMMAND);
            }
            if (command instanceof SubmitEvidenceCommandV1 submit) {
                if (!config.isIssuer(message.getSender())) {
                    return AdmissionResult.reject(UNAUTHORIZED_COMMAND);
                }
                requireNotificationShape(submit);
            } else if (command instanceof RepublishEvidenceCommandV1 republish) {
                requireNotificationShape(republish);
            }
            return AdmissionResult.accept();
        } catch (RuntimeException exception) {
            return AdmissionResult.reject(INVALID_COMMAND);
        }
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer) {
        apply(block, writer, AppEffectEmitter.rejecting(
                "Effects unavailable on the legacy evidence-registry apply path"));
    }

    @Override
    public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(effects, "effects");
        if (!config.chainId().equals(block.chainId())) {
            throw new IllegalArgumentException("Evidence registry received a block for another chain");
        }
        for (AppMessage message : block.messages()) {
            if (!EvidenceContract.COMMAND_TOPIC.equals(message.getTopic())) {
                continue;
            }
            final EvidenceCommandV1 command;
            try {
                command = EvidenceCommandCodec.decode(message.getBody());
            } catch (RuntimeException malformed) {
                continue;
            }
            if (!validSender(message.getSender())) {
                continue;
            }
            if (command instanceof SubmitEvidenceCommandV1 submit) {
                applySubmit(block, writer, effects, message, submit);
            } else if (command instanceof RepublishEvidenceCommandV1 republish) {
                applyRepublish(block, writer, effects, message, republish);
            } else if (command instanceof NotifyEvidenceCommandV1 notify) {
                applyNotify(block, writer, effects, message, notify);
            }
        }
    }

    @Override
    public void onEffectResult(AppBlock block, EffectResult result, AppStateWriter writer) {
        applyEffectResult(block, result, writer, null);
    }

    @Override
    public void onEffectResult(
            AppBlock block,
            EffectResult result,
            AppStateWriter writer,
            AppEffectEmitter effects
    ) {
        applyEffectResult(block, result, writer, Objects.requireNonNull(effects, "effects"));
    }

    private void applyEffectResult(
            AppBlock block,
            EffectResult result,
            AppStateWriter writer,
            AppEffectEmitter effects
    ) {
        if (block == null || result == null || writer == null
                || !config.chainId().equals(block.chainId())
                || !config.chainId().equals(result.effectId().chainId())
                || result.resultHeight() != block.height()
                || result.effectId().height() >= result.resultHeight()) {
            return;
        }

        final EvidenceScope scope;
        try {
            scope = EvidenceScope.parse(result.scope());
        } catch (RuntimeException malformed) {
            return;
        }
        Optional<byte[]> encoded = writer.get(scope.recordKey());
        if (encoded.isEmpty()) {
            return;
        }

        EvidenceRecordV1 record = EvidenceRecordV1.decode(encoded.get());
        if (scope.businessVersion() != record.businessVersion()
                || !MessageDigest.isEqual(scope.evidenceIdHash(),
                EvidenceKeys.idHash(record.evidenceId()))
                || !scope.encode().equals(result.scope())) {
            return;
        }

        EvidenceEffectRef expected = effectFor(record, scope.operation());
        EvidenceTerminalResultV1 existing = terminalFor(record, scope.operation());
        if (expected == null || existing != null
                || !typeFor(scope.operation()).equals(result.type())
                || expected.height() != result.effectId().height()
                || expected.ordinal() != result.effectId().ordinal()) {
            return;
        }

        final EvidenceTerminalResultV1 terminal;
        try {
            terminal = new EvidenceTerminalResultV1(
                    mapOutcome(result.outcome()), result.externalRef(),
                    result.detailHash(), result.resultHeight());
        } catch (RuntimeException malformed) {
            return;
        }
        EvidenceRecordV1 updated = withTerminal(record, scope.operation(), terminal);
        if (effects != null
                && scope.operation() != EvidenceEffectOperation.NOTIFY
                && config.directResultEmissionActive(block.height())
                && EvidenceStatus.storageReady(updated)
                && updated.notificationEffect() == null) {
            updated = emitNotification(updated, effects, null);
        }
        writer.put(scope.recordKey(), updated.encode());
    }

    @Override
    public byte[] query(String path, byte[] params, AppQueryContext state) {
        if (!EvidenceContract.GET_QUERY_PATH.equals(path)) {
            throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                    "Unsupported evidence query path");
        }
        final EvidenceGetRequestV1 request;
        try {
            request = EvidenceGetRequestV1.decode(params);
        } catch (RuntimeException malformed) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "Invalid evidence query parameters");
        }

        Optional<byte[]> encodedHead = state.get(EvidenceKeys.headKey(request.evidenceId()));
        if (encodedHead.isEmpty()) {
            return EvidenceGetResponseV1.notFound().encode();
        }
        EvidenceHeadV1 head = EvidenceHeadV1.decode(encodedHead.get());
        if (!head.evidenceId().equals(request.evidenceId())) {
            throw new IllegalStateException("Evidence head key/value mismatch");
        }
        long version = request.latest() ? head.latestVersion() : request.businessVersion();
        if (version > head.latestVersion()) {
            return EvidenceGetResponseV1.notFound().encode();
        }
        Optional<byte[]> encodedRecord = state.get(EvidenceKeys.recordKey(request.evidenceId(), version));
        if (encodedRecord.isEmpty()) {
            throw new IllegalStateException("Evidence record missing below the committed head");
        }
        EvidenceRecordV1 record = EvidenceRecordV1.decode(encodedRecord.get());
        return EvidenceGetResponseV1.found(head, record).encode();
    }

    private void applySubmit(AppBlock block, AppStateWriter writer, AppEffectEmitter effects,
                             AppMessage message, SubmitEvidenceCommandV1 command) {
        if (!config.isIssuer(message.getSender())) {
            return;
        }
        try {
            requireNotificationShape(command);
        } catch (RuntimeException invalid) {
            return;
        }
        byte[] headKey = EvidenceKeys.headKey(command.evidenceId());
        byte[] recordKey = EvidenceKeys.recordKey(command.evidenceId(), command.businessVersion());
        if (writer.get(headKey).isPresent() || writer.get(recordKey).isPresent()) {
            return;
        }

        EffectId object = emitStorage(effects, message, command.evidenceId(),
                command.businessVersion(), EvidenceEffectOperation.OBJECT,
                ConnectorTypes.OBJECT_PUT, command.objectPutCommand());
        EffectId ipfs = emitStorage(effects, message, command.evidenceId(),
                command.businessVersion(), EvidenceEffectOperation.IPFS,
                ConnectorTypes.IPFS_PIN, command.ipfsPinCommand());
        EvidenceRecordV1 record = newRecord(command, message, object, ipfs);
        writer.put(recordKey, record.encode());
        writer.put(headKey, new EvidenceHeadV1(command.evidenceId(),
                message.getSender(), command.businessVersion()).encode());
    }

    private void applyRepublish(AppBlock block, AppStateWriter writer, AppEffectEmitter effects,
                                AppMessage message, RepublishEvidenceCommandV1 command) {
        try {
            requireNotificationShape(command);
        } catch (RuntimeException invalid) {
            return;
        }
        byte[] headKey = EvidenceKeys.headKey(command.evidenceId());
        Optional<byte[]> encodedHead = writer.get(headKey);
        if (encodedHead.isEmpty()) {
            return;
        }
        EvidenceHeadV1 head = EvidenceHeadV1.decode(encodedHead.get());
        if (!head.evidenceId().equals(command.evidenceId())
                || !MessageDigest.isEqual(head.ownerPublicKey(), message.getSender())) {
            return;
        }
        final long nextVersion;
        try {
            nextVersion = Math.addExact(head.latestVersion(), 1);
        } catch (ArithmeticException exhausted) {
            return;
        }
        if (command.businessVersion() != nextVersion) {
            return;
        }

        byte[] recordKey = EvidenceKeys.recordKey(command.evidenceId(), nextVersion);
        if (writer.get(recordKey).isPresent()) {
            return;
        }
        Optional<byte[]> previous = writer.get(EvidenceKeys.recordKey(
                command.evidenceId(), head.latestVersion()));
        if (previous.isEmpty()) {
            return;
        }
        EvidenceRecordV1 priorRecord = EvidenceRecordV1.decode(previous.get());
        if (!priorRecord.evidenceId().equals(command.evidenceId())
                || priorRecord.businessVersion() != head.latestVersion()
                || !MessageDigest.isEqual(priorRecord.ownerPublicKey(), head.ownerPublicKey())
                || !EvidenceStatus.derive(priorRecord).permitsRepublish()) {
            return;
        }

        EffectId object = emitStorage(effects, message, command.evidenceId(), nextVersion,
                EvidenceEffectOperation.OBJECT, ConnectorTypes.OBJECT_PUT,
                command.objectPutCommand());
        EffectId ipfs = emitStorage(effects, message, command.evidenceId(), nextVersion,
                EvidenceEffectOperation.IPFS, ConnectorTypes.IPFS_PIN,
                command.ipfsPinCommand());
        EvidenceRecordV1 record = newRecord(command, message, object, ipfs);
        writer.put(recordKey, record.encode());
        writer.put(headKey, new EvidenceHeadV1(command.evidenceId(),
                head.ownerPublicKey(), nextVersion).encode());
    }

    private void applyNotify(AppBlock block, AppStateWriter writer, AppEffectEmitter effects,
                             AppMessage message, NotifyEvidenceCommandV1 command) {
        Optional<byte[]> encodedHead = writer.get(EvidenceKeys.headKey(command.evidenceId()));
        if (encodedHead.isEmpty()) {
            return;
        }
        EvidenceHeadV1 head = EvidenceHeadV1.decode(encodedHead.get());
        if (!head.evidenceId().equals(command.evidenceId())
                || !config.canNotify(head.ownerPublicKey(), message.getSender())) {
            return;
        }
        byte[] recordKey = EvidenceKeys.recordKey(command.evidenceId(), command.businessVersion());
        Optional<byte[]> encodedRecord = writer.get(recordKey);
        if (encodedRecord.isEmpty()) {
            return;
        }
        EvidenceRecordV1 record = EvidenceRecordV1.decode(encodedRecord.get());
        if (!record.evidenceId().equals(command.evidenceId())
                || record.businessVersion() != command.businessVersion()
                || !MessageDigest.isEqual(record.ownerPublicKey(), head.ownerPublicKey())
                || !EvidenceStatus.storageReady(record)
                || record.notificationEffect() != null) {
            return;
        }

        writer.put(recordKey, emitNotification(record, effects, message.getMessageId()).encode());
    }

    private EvidenceRecordV1 emitNotification(
            EvidenceRecordV1 record,
            AppEffectEmitter effects,
            byte[] sourceMessageId
    ) {
        EvidenceAvailableEventV1 event = EvidenceAvailableEventV1.fromRecord(record);
        KafkaPublishCommandV1 publish = new KafkaPublishCommandV1(
                record.kafkaTarget(), record.kafkaTopic(), event.kafkaKey(),
                event.contentType(), event.encode(), List.of());
        EffectId effect = effects.emit(EffectIntent.of(
                        ConnectorTypes.KAFKA_PUBLISH, publish.encode())
                .scope(EvidenceKeys.effectScope(record.evidenceId(), record.businessVersion(),
                        EvidenceEffectOperation.NOTIFY))
                .gate(FinalityGate.APP_FINAL)
                .result(ResultPolicy.CHAIN)
                .expiryBlocks(config.notificationExpiryBlocks())
                .sourceMessageId(sourceMessageId)
                .build());
        return copy(record, record.objectTerminal(), record.ipfsTerminal(),
                sourceMessageId, ref(effect), null);
    }

    private EffectId emitStorage(AppEffectEmitter effects, AppMessage source,
                                 String evidenceId, long version,
                                 EvidenceEffectOperation operation,
                                 String type, byte[] payload) {
        return effects.emit(EffectIntent.of(type, payload)
                .scope(EvidenceKeys.effectScope(evidenceId, version, operation))
                .gate(config.storageGate())
                .result(ResultPolicy.CHAIN)
                .expiryBlocks(config.storageExpiryBlocks())
                .sourceMessageId(source.getMessageId())
                .build());
    }

    private static EvidenceRecordV1 newRecord(EvidenceCommandV1 command,
                                              AppMessage source,
                                              EffectId object,
                                              EffectId ipfs) {
        if (command instanceof SubmitEvidenceCommandV1 submit) {
            return new EvidenceRecordV1(submit.evidenceId(), submit.businessVersion(),
                    source.getSender(), source.getMessageId(), submit.objectPutCommand(),
                    submit.expectedObjectDestinationFingerprint(), ref(object), null,
                    submit.ipfsPinCommand(), submit.expectedIpfsTargetFingerprint(), ref(ipfs), null,
                    submit.kafkaTarget(), submit.kafkaTopic(),
                    submit.expectedKafkaDestinationFingerprint(), null, null, null);
        }
        RepublishEvidenceCommandV1 republish = (RepublishEvidenceCommandV1) command;
        return new EvidenceRecordV1(republish.evidenceId(), republish.businessVersion(),
                source.getSender(), source.getMessageId(), republish.objectPutCommand(),
                republish.expectedObjectDestinationFingerprint(), ref(object), null,
                republish.ipfsPinCommand(), republish.expectedIpfsTargetFingerprint(), ref(ipfs), null,
                republish.kafkaTarget(), republish.kafkaTopic(),
                republish.expectedKafkaDestinationFingerprint(), null, null, null);
    }

    private static EvidenceRecordV1 withTerminal(EvidenceRecordV1 record,
                                                 EvidenceEffectOperation operation,
                                                 EvidenceTerminalResultV1 terminal) {
        return switch (operation) {
            case OBJECT -> copy(record, terminal, record.ipfsTerminal(),
                    record.notifyMessageId(), record.notificationEffect(),
                    record.notificationTerminal());
            case IPFS -> copy(record, record.objectTerminal(), terminal,
                    record.notifyMessageId(), record.notificationEffect(),
                    record.notificationTerminal());
            case NOTIFY -> copy(record, record.objectTerminal(), record.ipfsTerminal(),
                    record.notifyMessageId(), record.notificationEffect(), terminal);
        };
    }

    private static EvidenceRecordV1 copy(EvidenceRecordV1 record,
                                         EvidenceTerminalResultV1 objectTerminal,
                                         EvidenceTerminalResultV1 ipfsTerminal,
                                         byte[] notifyMessageId,
                                         EvidenceEffectRef notificationEffect,
                                         EvidenceTerminalResultV1 notificationTerminal) {
        return new EvidenceRecordV1(record.evidenceId(), record.businessVersion(),
                record.ownerPublicKey(), record.submitMessageId(), record.objectPutCommand(),
                record.expectedObjectDestinationFingerprint(), record.objectEffect(), objectTerminal,
                record.ipfsPinCommand(), record.expectedIpfsTargetFingerprint(), record.ipfsEffect(),
                ipfsTerminal, record.kafkaTarget(), record.kafkaTopic(),
                record.expectedKafkaDestinationFingerprint(), notifyMessageId,
                notificationEffect, notificationTerminal);
    }

    private static EvidenceEffectRef effectFor(EvidenceRecordV1 record,
                                               EvidenceEffectOperation operation) {
        return switch (operation) {
            case OBJECT -> record.objectEffect();
            case IPFS -> record.ipfsEffect();
            case NOTIFY -> record.notificationEffect();
        };
    }

    private static EvidenceTerminalResultV1 terminalFor(EvidenceRecordV1 record,
                                                        EvidenceEffectOperation operation) {
        return switch (operation) {
            case OBJECT -> record.objectTerminal();
            case IPFS -> record.ipfsTerminal();
            case NOTIFY -> record.notificationTerminal();
        };
    }

    private static String typeFor(EvidenceEffectOperation operation) {
        return switch (operation) {
            case OBJECT -> ConnectorTypes.OBJECT_PUT;
            case IPFS -> ConnectorTypes.IPFS_PIN;
            case NOTIFY -> ConnectorTypes.KAFKA_PUBLISH;
        };
    }

    private static EvidenceEffectRef ref(EffectId effect) {
        return new EvidenceEffectRef(effect.height(), effect.ordinal());
    }

    private static EvidenceTerminalOutcome mapOutcome(EffectOutcome outcome) {
        return switch (outcome) {
            case CONFIRMED -> EvidenceTerminalOutcome.CONFIRMED;
            case FAILED -> EvidenceTerminalOutcome.FAILED;
            case CANCELLED -> EvidenceTerminalOutcome.CANCELLED;
            case EXPIRED -> EvidenceTerminalOutcome.EXPIRED;
        };
    }

    private static boolean validSender(byte[] sender) {
        return sender != null && sender.length == EvidenceContract.HASH_BYTES;
    }

    private static void requireNotificationShape(EvidenceCommandV1 command) {
        byte[] objectCommand;
        byte[] objectDestination;
        byte[] ipfsCommand;
        byte[] ipfsTarget;
        if (command instanceof SubmitEvidenceCommandV1 submit) {
            objectCommand = submit.objectPutCommand();
            objectDestination = submit.expectedObjectDestinationFingerprint();
            ipfsCommand = submit.ipfsPinCommand();
            ipfsTarget = submit.expectedIpfsTargetFingerprint();
        } else if (command instanceof RepublishEvidenceCommandV1 republish) {
            objectCommand = republish.objectPutCommand();
            objectDestination = republish.expectedObjectDestinationFingerprint();
            ipfsCommand = republish.ipfsPinCommand();
            ipfsTarget = republish.expectedIpfsTargetFingerprint();
        } else {
            return;
        }
        var object = com.bloxbean.cardano.yano.appchain.integration.objectstore
                .ObjectPutCommandV1.decode(objectCommand);
        var ipfs = com.bloxbean.cardano.yano.appchain.integration.ipfs
                .IpfsPinCommandV1.decode(ipfsCommand);
        byte[] objectReceipt = new ObjectPutReceiptV1(objectDestination, new byte[32],
                object.digest(), object.size()).encode();
        byte[] ipfsReceipt = new IpfsPinReceiptV1(ipfsTarget,
                IpfsCidFingerprint.compute(ipfs.cid()).bytes()).encode();
        new EvidenceAvailableEventV1(command.evidenceId(), command.businessVersion(),
                object.digestAlgorithm(), object.digest(), object.size(), object.target(),
                object.destinationKey(), ipfs.cid(), objectReceipt, ipfsReceipt).encode();
    }
}
