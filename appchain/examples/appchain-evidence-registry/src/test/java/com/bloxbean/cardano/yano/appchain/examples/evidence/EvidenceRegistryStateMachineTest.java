package com.bloxbean.cardano.yano.appchain.examples.evidence;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.NotifyEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetRequestV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.query.EvidenceGetResponseV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceEffectOperation;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceStatus;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTypes;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishCommandV1;
import com.bloxbean.cardano.yano.runtime.appchain.StateMachineConformance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceRegistryStateMachineTest {
    private static final String CHAIN = "evidence-chain";
    private static final byte[] RUNNER = EvidenceFixtures.repeat(0x51);
    private static final byte[] FOREIGN = EvidenceFixtures.repeat(0x52);

    @Test
    void submitResultsSameBlockNotifyAndRepublishFormOneDeterministicWorkflow() {
        EvidenceRegistryStateMachine machine = machine(Map.of(
                "machines.evidence-registry.issuers", hex(EvidenceFixtures.OWNER),
                "machines.evidence-registry.notify-senders", hex(RUNNER),
                "machines.evidence-registry.storage-gate", "l1-anchored",
                "machines.evidence-registry.storage-expiry-blocks", "50",
                "machines.evidence-registry.notification-expiry-blocks", "25"));
        MemoryState state = new MemoryState();

        AppMessage submit = message(EvidenceFixtures.OWNER, 1,
                EvidenceFixtures.submit().encode(), EvidenceFixtures.SUBMIT_MESSAGE);
        CapturingEmitter submitEffects = new CapturingEmitter(10);
        machine.apply(block(10, submit), state, submitEffects);

        assertThat(submitEffects.intents).hasSize(2);
        assertStorageIntent(submitEffects.intents.get(0), ConnectorTypes.OBJECT_PUT,
                EvidenceEffectOperation.OBJECT, EvidenceFixtures.objectCommand().encode(), 50);
        assertStorageIntent(submitEffects.intents.get(1), ConnectorTypes.IPFS_PIN,
                EvidenceEffectOperation.IPFS, EvidenceFixtures.ipfsCommand().encode(), 50);
        EvidenceRecordV1 pending = record(state, 1);
        assertThat(EvidenceStatus.derive(pending)).isEqualTo(EvidenceStatus.STORAGE_PENDING);
        assertThat(pending.objectEffect().ordinal()).isZero();
        assertThat(pending.ipfsEffect().ordinal()).isEqualTo(1);

        machine.onEffectResult(block(11), result(pending.objectEffect().height(),
                pending.objectEffect().ordinal(), ConnectorTypes.OBJECT_PUT,
                EvidenceEffectOperation.OBJECT, EvidenceFixtures.objectReceipt(), 11), state);
        assertThat(EvidenceStatus.derive(record(state, 1)))
                .isEqualTo(EvidenceStatus.STORAGE_PENDING);

        EvidenceRecordV1 afterObject = record(state, 1);
        AppBlock readyAndNotify = block(12,
                message(RUNNER, 2, new NotifyEvidenceCommandV1(
                        EvidenceFixtures.ID, 1).encode(), EvidenceFixtures.NOTIFY_MESSAGE),
                message(RUNNER, 3, new NotifyEvidenceCommandV1(
                        EvidenceFixtures.ID, 1).encode(), EvidenceFixtures.repeat(0x44)));
        machine.onEffectResult(readyAndNotify, result(afterObject.ipfsEffect().height(),
                afterObject.ipfsEffect().ordinal(), ConnectorTypes.IPFS_PIN,
                EvidenceEffectOperation.IPFS, EvidenceFixtures.ipfsReceipt(), 12), state);
        CapturingEmitter notificationEffects = new CapturingEmitter(12);
        machine.apply(readyAndNotify, state, notificationEffects);

        assertThat(notificationEffects.intents).hasSize(1);
        EffectIntent notification = notificationEffects.intents.getFirst();
        assertThat(notification.type()).isEqualTo(ConnectorTypes.KAFKA_PUBLISH);
        assertThat(notification.gate()).isEqualTo(FinalityGate.APP_FINAL);
        assertThat(notification.result()).isEqualTo(ResultPolicy.CHAIN);
        assertThat(notification.expiryBlocks()).isEqualTo(25);
        assertThat(notification.scope()).isEqualTo(EvidenceKeys.effectScope(
                EvidenceFixtures.ID, 1, EvidenceEffectOperation.NOTIFY));
        KafkaPublishCommandV1 publish = KafkaPublishCommandV1.decode(notification.payload());
        assertThat(publish.key()).isEqualTo(EvidenceKeys.kafkaKey(EvidenceFixtures.ID, 1));
        assertThat(publish.headers()).isEmpty();

        EvidenceRecordV1 notifying = record(state, 1);
        assertThat(EvidenceStatus.derive(notifying))
                .isEqualTo(EvidenceStatus.NOTIFICATION_PENDING);
        assertThat(notifying.notifyMessageId()).isEqualTo(EvidenceFixtures.NOTIFY_MESSAGE);
        machine.onEffectResult(block(13), new EffectResult(
                new EffectId(CHAIN, notifying.notificationEffect().height(),
                        notifying.notificationEffect().ordinal()),
                ConnectorTypes.KAFKA_PUBLISH,
                EvidenceKeys.effectScope(EvidenceFixtures.ID, 1,
                        EvidenceEffectOperation.NOTIFY),
                EffectOutcome.CONFIRMED, EvidenceFixtures.kafkaReceipt(), null, 13), state);
        assertThat(EvidenceStatus.derive(record(state, 1))).isEqualTo(EvidenceStatus.READY);

        EvidenceGetResponseV1 queried = EvidenceGetResponseV1.decode(machine.query(
                EvidenceContract.GET_QUERY_PATH,
                new EvidenceGetRequestV1(EvidenceFixtures.ID, 0).encode(), state));
        assertThat(queried.found()).isTrue();
        assertThat(queried.record().businessVersion()).isEqualTo(1);

        CapturingEmitter republishEffects = new CapturingEmitter(14);
        machine.apply(block(14, message(EvidenceFixtures.OWNER, 4,
                EvidenceFixtures.republish().encode(), EvidenceFixtures.repeat(0x45))),
                state, republishEffects);
        assertThat(republishEffects.intents).hasSize(2);
        assertThat(record(state, 2).businessVersion()).isEqualTo(2);
        EvidenceGetResponseV1 latest = EvidenceGetResponseV1.decode(machine.query(
                EvidenceContract.GET_QUERY_PATH,
                new EvidenceGetRequestV1(EvidenceFixtures.ID, 0).encode(), state));
        assertThat(latest.record().businessVersion()).isEqualTo(2);
        assertThat(latest.head().latestVersion()).isEqualTo(2);
    }

    @Test
    void duplicateSubmitDoesNotReemitAndStorageResultsMayCompleteInReverseOrder() {
        EvidenceRegistryStateMachine machine = machine(Map.of());
        MemoryState state = new MemoryState();
        AppMessage submit = message(EvidenceFixtures.OWNER, 1,
                EvidenceFixtures.submit().encode(), EvidenceFixtures.SUBMIT_MESSAGE);

        CapturingEmitter initial = new CapturingEmitter(10);
        machine.apply(block(10, submit), state, initial);
        EvidenceRecordV1 pending = record(state, 1);
        byte[] beforeReplay = pending.encode();

        CapturingEmitter replay = new CapturingEmitter(11);
        machine.apply(block(11, submit), state, replay);
        assertThat(replay.intents).isEmpty();
        assertThat(record(state, 1).encode()).isEqualTo(beforeReplay);

        machine.onEffectResult(block(12), result(pending.ipfsEffect().height(),
                pending.ipfsEffect().ordinal(), ConnectorTypes.IPFS_PIN,
                EvidenceEffectOperation.IPFS, EvidenceFixtures.ipfsReceipt(), 12), state);
        assertThat(EvidenceStatus.derive(record(state, 1)))
                .isEqualTo(EvidenceStatus.STORAGE_PENDING);

        machine.onEffectResult(block(13), result(pending.objectEffect().height(),
                pending.objectEffect().ordinal(), ConnectorTypes.OBJECT_PUT,
                EvidenceEffectOperation.OBJECT, EvidenceFixtures.objectReceipt(), 13), state);
        assertThat(EvidenceStatus.derive(record(state, 1)))
                .isEqualTo(EvidenceStatus.STORAGE_READY);
    }

    @Test
    void authorizationMalformedCommandsAndForeignResultsFailClosed() {
        EvidenceRegistryStateMachine machine = machine(Map.of(
                "machines.evidence-registry.issuers", hex(EvidenceFixtures.OWNER)));
        MemoryState state = new MemoryState();

        AppMessage foreignSubmit = message(FOREIGN, 1,
                EvidenceFixtures.submit().encode(), EvidenceFixtures.SUBMIT_MESSAGE);
        assertThat(machine.validate(foreignSubmit).isAccepted()).isFalse();
        CapturingEmitter effects = new CapturingEmitter(10);
        machine.apply(block(10, foreignSubmit), state, effects);
        assertThat(effects.intents).isEmpty();

        AppMessage malformed = message(EvidenceFixtures.OWNER, 2,
                new byte[]{1, 2, 3}, EvidenceFixtures.repeat(0x46));
        assertThat(machine.validate(malformed).isAccepted()).isFalse();
        machine.apply(block(11, malformed), state, new CapturingEmitter(11));
        assertThat(state.entries).isEmpty();

        AppMessage submit = message(EvidenceFixtures.OWNER, 3,
                EvidenceFixtures.submit().encode(), EvidenceFixtures.SUBMIT_MESSAGE);
        CapturingEmitter valid = new CapturingEmitter(12);
        machine.apply(block(12, submit), state, valid);
        EvidenceRecordV1 pending = record(state, 1);

        machine.onEffectResult(block(13), new EffectResult(
                new EffectId(CHAIN, pending.objectEffect().height(),
                        pending.objectEffect().ordinal()),
                ConnectorTypes.IPFS_PIN,
                EvidenceKeys.effectScope(EvidenceFixtures.ID, 1,
                        EvidenceEffectOperation.OBJECT),
                EffectOutcome.CONFIRMED, EvidenceFixtures.objectReceipt(), null, 13), state);
        assertThat(record(state, 1).objectTerminal()).isNull();

        machine.onEffectResult(block(13), new EffectResult(
                new EffectId(CHAIN, pending.objectEffect().height(),
                        pending.objectEffect().ordinal() + 7),
                ConnectorTypes.OBJECT_PUT,
                EvidenceKeys.effectScope(EvidenceFixtures.ID, 1,
                        EvidenceEffectOperation.OBJECT),
                EffectOutcome.CONFIRMED, EvidenceFixtures.objectReceipt(), null, 13), state);
        assertThat(record(state, 1).objectTerminal()).isNull();

        machine.onEffectResult(block(13), result(pending.objectEffect().height(),
                pending.objectEffect().ordinal(), ConnectorTypes.OBJECT_PUT,
                EvidenceEffectOperation.OBJECT, new byte[]{1, 2, 3}, 13), state);
        assertThat(EvidenceStatus.derive(record(state, 1)))
                .isEqualTo(EvidenceStatus.STORAGE_PENDING);
        assertThat(record(state, 1).objectTerminal()).isNotNull();

        // First terminal result is absorbing in the business record too.
        machine.onEffectResult(block(14), result(pending.objectEffect().height(),
                pending.objectEffect().ordinal(), ConnectorTypes.OBJECT_PUT,
                EvidenceEffectOperation.OBJECT, EvidenceFixtures.objectReceipt(), 14), state);
        assertThat(record(state, 1).objectTerminal().externalRef())
                .containsExactly(1, 2, 3);
    }

    @Test
    void queryRejectsUnknownPathsAndMalformedParametersAndReportsAbsence() {
        EvidenceRegistryStateMachine machine = machine(Map.of());
        MemoryState state = new MemoryState();
        assertThatThrownBy(() -> machine.query("unknown", new byte[0], state))
                .isInstanceOfSatisfying(AppQueryException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(AppQueryException.Code.UNSUPPORTED));
        assertThatThrownBy(() -> machine.query(EvidenceContract.GET_QUERY_PATH,
                new byte[]{1, 2, 3}, state))
                .isInstanceOfSatisfying(AppQueryException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(AppQueryException.Code.INVALID_REQUEST));
        EvidenceGetResponseV1 missing = EvidenceGetResponseV1.decode(machine.query(
                EvidenceContract.GET_QUERY_PATH,
                new EvidenceGetRequestV1("missing", 0).encode(), state));
        assertThat(missing.found()).isFalse();
    }

    @Test
    void providerConfigFailsClosedAndOwnerCanNotifyWithoutRunnerConfiguration() {
        assertThatThrownBy(() -> new EvidenceRegistryStateMachineProvider()
                .create(rawContext(Map.of())))
                .hasMessageContaining("effects.enabled");
        assertThatThrownBy(() -> machineWithSettings(Map.of(
                "effects.enabled", "true", "effects.max-per-block", "1")))
                .hasMessageContaining("max-per-block");
        assertThatThrownBy(() -> machineWithSettings(Map.of(
                "effects.enabled", "true", "effects.max-payload-bytes", "1024")))
                .hasMessageContaining("max-payload-bytes");
        assertThatThrownBy(() -> machineWithSettings(Map.of(
                "effects.enabled", "true",
                "machines.evidence-registry.storage-gate", "zk-settled")))
                .hasMessageContaining("storage-gate");
        assertThatThrownBy(() -> machineWithSettings(Map.of(
                "effects.enabled", "true",
                "effects.result-window-blocks", "10",
                "machines.evidence-registry.storage-expiry-blocks", "11")))
                .hasMessageContaining("result window");

        EvidenceRegistryConfig config = config(Map.of());
        assertThat(config.isIssuer(FOREIGN)).isTrue();
        assertThat(config.canNotify(EvidenceFixtures.OWNER, EvidenceFixtures.OWNER)).isTrue();
        assertThat(config.canNotify(EvidenceFixtures.OWNER, FOREIGN)).isFalse();
    }

    @Test
    void realLedgerReplayAndRestartRemainByteDeterministic() {
        for (long restartAt : List.of(2L, 3L, 5L)) {
            StateMachineConformance.Result result = evidenceWorkflowConformance(restartAt).run();

            assertThat(result.deterministic()).as(result.describeDivergence()).isTrue();
            Map<Long, StateMachineConformance.HeightOutcome> baseline =
                    result.outcomesPerRun().getFirst();
            assertThat(baseline.get(1L).effectHashes()).hasSize(2);
            assertThat(baseline.get(3L).effectHashes()).hasSize(1);
            assertThat(baseline.get(5L).effectHashes()).hasSize(2);
            assertThat(baseline.get(2L).root()).isNotEqualTo(baseline.get(1L).root());
            assertThat(baseline.get(4L).root()).isNotEqualTo(baseline.get(3L).root());
            assertThat(baseline.get(6L).root()).isNotEqualTo(baseline.get(5L).root());
        }
    }

    private static StateMachineConformance.Builder evidenceWorkflowConformance(long restartAt) {
        return StateMachineConformance.builder(new EvidenceRegistryStateMachineProvider())
                .chainId(CHAIN)
                .settings(Map.of(
                        "effects.enabled", "true",
                        "effects.max-per-block", "8",
                        "effects.max-payload-bytes", "4096"))
                .blocks(6)
                .messagesPerBlock(2)
                .runs(2)
                .restartAtHeight(restartAt)
                .messageGenerator((height, index, random) -> workflowMessage(height, index));
    }

    private static StateMachineConformance.CorpusMessage workflowMessage(long height, int index) {
        if (height == 1) {
            return application(EvidenceFixtures.submit().encode());
        }
        if (height == 2) {
            return resultMessage(1, 0, EvidenceFixtures.objectReceipt());
        }
        if (height == 3) {
            return index == 0
                    ? resultMessage(1, 1, EvidenceFixtures.ipfsReceipt())
                    : application(new NotifyEvidenceCommandV1(EvidenceFixtures.ID, 1).encode());
        }
        if (height == 4) {
            return resultMessage(3, 0, EvidenceFixtures.kafkaReceipt());
        }
        if (height == 5) {
            return application(EvidenceFixtures.republish().encode());
        }
        return index == 0
                ? resultMessage(5, 0, EvidenceFixtures.objectReceipt())
                : resultMessage(5, 1, EvidenceFixtures.ipfsReceipt());
    }

    private static StateMachineConformance.CorpusMessage application(byte[] body) {
        return new StateMachineConformance.CorpusMessage(
                EvidenceContract.COMMAND_TOPIC, body);
    }

    private static StateMachineConformance.CorpusMessage resultMessage(
            long height, int ordinal, byte[] externalRef) {
        return new StateMachineConformance.CorpusMessage(
                FxResultBody.TOPIC,
                new FxResultBody(FxResultBody.BODY_VERSION, height, ordinal,
                        EffectOutcome.CONFIRMED, externalRef, null).encode());
    }

    private static void assertStorageIntent(EffectIntent intent, String type,
                                            EvidenceEffectOperation operation,
                                            byte[] payload, long expiry) {
        assertThat(intent.type()).isEqualTo(type);
        assertThat(intent.payload()).isEqualTo(payload);
        assertThat(intent.scope()).isEqualTo(EvidenceKeys.effectScope(
                EvidenceFixtures.ID, 1, operation));
        assertThat(intent.gate()).isEqualTo(FinalityGate.L1_ANCHORED);
        assertThat(intent.result()).isEqualTo(ResultPolicy.CHAIN);
        assertThat(intent.expiryBlocks()).isEqualTo(expiry);
        assertThat(intent.sourceMessageId()).isEqualTo(EvidenceFixtures.SUBMIT_MESSAGE);
    }

    private static EffectResult result(long effectHeight, int ordinal, String type,
                                       EvidenceEffectOperation operation,
                                       byte[] externalRef, long resultHeight) {
        return new EffectResult(new EffectId(CHAIN, effectHeight, ordinal), type,
                EvidenceKeys.effectScope(EvidenceFixtures.ID, 1, operation),
                EffectOutcome.CONFIRMED, externalRef, null, resultHeight);
    }

    private static EvidenceRecordV1 record(MemoryState state, long version) {
        return EvidenceRecordV1.decode(state.get(
                EvidenceKeys.recordKey(EvidenceFixtures.ID, version)).orElseThrow());
    }

    private static EvidenceRegistryStateMachine machine(Map<String, String> overrides) {
        return (EvidenceRegistryStateMachine) machineWithSettings(overrides);
    }

    private static Object machineWithSettings(Map<String, String> overrides) {
        return new EvidenceRegistryStateMachineProvider().create(context(overrides));
    }

    private static EvidenceRegistryConfig config(Map<String, String> overrides) {
        return EvidenceRegistryConfig.from(context(overrides));
    }

    private static AppStateMachineContext context(Map<String, String> overrides) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("effects.enabled", "true");
        settings.putAll(overrides);
        return rawContext(settings);
    }

    private static AppStateMachineContext rawContext(Map<String, String> settings) {
        return new AppStateMachineContext() {
            @Override public String chainId() { return CHAIN; }
            @Override public Map<String, String> settings() { return Map.copyOf(settings); }
        };
    }

    private static AppMessage message(byte[] sender, long sequence, byte[] body, byte[] id) {
        return AppMessage.builder()
                .messageId(id)
                .chainId(CHAIN)
                .topic(EvidenceContract.COMMAND_TOPIC)
                .sender(sender)
                .senderSeq(sequence)
                .expiresAt(4_000_000_000L)
                .body(body)
                .authScheme(0)
                .authProof(new byte[64])
                .build();
    }

    private static AppBlock block(long height, AppMessage... messages) {
        return new AppBlock(AppBlock.BLOCK_VERSION, CHAIN, height,
                height == 1 ? AppBlock.GENESIS_PREV_HASH : EvidenceFixtures.repeat(0x61),
                0, new byte[0], 1_700_000_000_000L + height,
                EvidenceFixtures.repeat(0x62), new byte[32], List.of(messages),
                EvidenceFixtures.repeat(0x63), FinalityCert.empty());
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private static final class CapturingEmitter implements AppEffectEmitter {
        private final long height;
        private final List<EffectIntent> intents = new ArrayList<>();

        private CapturingEmitter(long height) {
            this.height = height;
        }

        @Override
        public EffectId emit(EffectIntent intent) {
            intents.add(intent);
            return new EffectId(CHAIN, height, intents.size() - 1);
        }

        @Override
        public long pendingCount() {
            return 0;
        }
    }

    private static final class MemoryState implements AppStateWriter, AppQueryContext {
        private final Map<Key, byte[]> entries = new LinkedHashMap<>();

        @Override
        public Optional<byte[]> get(byte[] key) {
            byte[] value = entries.get(new Key(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override
        public void put(byte[] key, byte[] value) {
            entries.put(new Key(key), value.clone());
        }

        @Override
        public void delete(byte[] key) {
            entries.remove(new Key(key));
        }

        @Override
        public byte[] stateRoot() {
            return EvidenceFixtures.repeat(0x71);
        }

        @Override
        public long committedHeight() {
            return 99;
        }
    }

    private record Key(byte[] bytes) {
        private Key {
            bytes = bytes.clone();
        }

        @Override public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(bytes, key.bytes);
        }

        @Override public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }
}
