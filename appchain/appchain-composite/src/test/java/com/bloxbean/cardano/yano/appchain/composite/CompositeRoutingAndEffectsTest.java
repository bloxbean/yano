package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryCodecV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeRoutingAndEffectsTest {
    private static final byte[] LOCAL_KEY = "record".getBytes(StandardCharsets.US_ASCII);

    @Test
    void routesOnlyExactActiveTopicsInProfileOrderAndEnforcesPhysicalNamespaces() {
        RecordingComponent first = component("first", "first.v1", 1, 0, 0);
        RecordingComponent second = component("second", "second.v1", 2, 0, 0);
        CompositeStateMachine machine = machine(first, second);
        MemoryState state = new MemoryState(1);

        machine.apply(block(1, message("second.v1", "future"), message("~fx/result", "system"),
                message("first.v1", "one"), message("first.v1", "two")), state, new CapturingEmitter(1));

        assertThat(first.appliedBodies).containsExactly("one", "two");
        assertThat(second.applyCalls).isZero();
        assertThat(state.get(LOCAL_KEY)).isEmpty();
        assertThat(state.get(CompositeStateKeys.componentKey("first", LOCAL_KEY)))
                .hasValue("two".getBytes(StandardCharsets.UTF_8));

        state.height = 2;
        machine.apply(block(2, message("second.v1", "active")), state, new CapturingEmitter(2));
        assertThat(second.appliedBodies).containsExactly("active");
    }

    @Test
    void nonOverlappingGenerationReplacementStopsOldAndStartsNewAtExactHeight() {
        RecordingComponent oldGeneration = component("item", "item.v1", 1, 10, 0);
        RecordingComponent newGeneration = new RecordingComponent(new ComponentDescriptor(
                "item", "2", "cfg-v2", "state-v1", 10, 0,
                List.of("item.v1"), List.of(), 0));
        CompositeStateMachine machine = machine(oldGeneration, newGeneration);
        MemoryState state = new MemoryState(1);

        machine.apply(block(1, message("item.v1", "old")), state, new CapturingEmitter(1));
        state.height = 9;
        machine.apply(block(9, message("item.v1", "still-old")), state, new CapturingEmitter(9));
        state.height = 10;
        machine.apply(block(10, message("item.v1", "new")), state, new CapturingEmitter(10));

        assertThat(oldGeneration.appliedBodies).containsExactly("old", "still-old");
        assertThat(newGeneration.appliedBodies).containsExactly("new");
        assertThat(oldGeneration.applyCalls).isEqualTo(2);
        assertThat(newGeneration.applyCalls).isEqualTo(1);
    }

    @Test
    void admissionRejectsUnknownAndRunsEveryScheduledGenerationValidator() {
        RecordingComponent oldGeneration = component("item", "item.v1", 1, 10, 0);
        RecordingComponent newGeneration = component("item", "item.v1", 10, 0, 0);
        CompositeStateMachine machine = machine(oldGeneration, newGeneration);

        assertThat(machine.validate(message("item.v1", "ok")).isAccepted()).isTrue();
        assertThat(oldGeneration.validateCalls).isEqualTo(1);
        assertThat(newGeneration.validateCalls).isEqualTo(1);
        assertThat(machine.validate(message("unknown.v1", "x")).isAccepted()).isFalse();
    }

    @Test
    void chainEffectResultReturnsOnlyToExactEmitterGenerationAndOwnerIsConsumed() {
        EffectComponent component = new EffectComponent(descriptor(
                "effects", "effects.v1", 1, 0, List.of("get"), 1));
        CompositeStateMachine machine = machine(component);
        MemoryState state = new MemoryState(1);
        CapturingEmitter emitter = new CapturingEmitter(1);

        machine.apply(block(1, message("effects.v1", "emit")), state, emitter);
        EffectId effectId = emitter.ids.getFirst();
        assertThat(state.get(CompositeStateKeys.effectOwnerKey(effectId))).isPresent();
        assertThat(state.get(CompositeStateKeys.quotaKey(1, component.descriptor().generation())))
                .isEmpty();

        EffectResult result = new EffectResult(effectId, "test.effect", "scope",
                EffectOutcome.CONFIRMED, "external".getBytes(StandardCharsets.UTF_8), null, 2);
        machine.onEffectResult(block(2, message("sibling.v1", "must-not-leak")),
                result, state, new CapturingEmitter(2));

        assertThat(component.resultCalls).isEqualTo(1);
        assertThat(component.resultBlockMessages).isZero();
        assertThat(state.get(CompositeStateKeys.effectOwnerKey(effectId))).isEmpty();
        assertThat(state.get(CompositeStateKeys.componentKey("effects", "result".getBytes(
                StandardCharsets.US_ASCII)))).hasValue("external".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> machine.onEffectResult(
                block(3), result, state, new CapturingEmitter(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing composite effect owner");
    }

    @Test
    void perComponentQuotaPreventsOneComponentFromConsumingAnotherReservation() {
        DoubleEmitComponent greedy = new DoubleEmitComponent(descriptor(
                "greedy", "greedy.v1", 1, 0, List.of(), 1));
        RecordingComponent later = component("later", "later.v1", 1, 0, 1);
        CompositeStateMachine machine = machine(greedy, later);

        assertThatThrownBy(() -> machine.apply(block(1,
                message("greedy.v1", "emit"), message("later.v1", "emit")),
                new MemoryState(1), new CapturingEmitter(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("component effect quota exceeded: greedy");
    }

    @Test
    void retiredGenerationContinuationKeepsSeparateCapacityAtReplacementHeight() {
        EffectComponent oldGeneration = new EffectComponent(new ComponentDescriptor(
                "item", "1", "cfg-v1", "state-v1", 1, 2,
                List.of("item.v1"), List.of(), 1), true);
        RecordingComponent newGeneration = new RecordingComponent(new ComponentDescriptor(
                "item", "2", "cfg-v2", "state-v1", 2, 0,
                List.of("item.v2"), List.of(), 1));
        CompositeProfile profile = CompositeProfile.of(
                "replacement", "1", List.of(oldGeneration.descriptor(), newGeneration.descriptor()));
        assertThatThrownBy(() -> profile.validateEffectBudget(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quota 2")
                .hasMessageContaining("height 2");
        profile.validateEffectBudget(2);

        CompositeStateMachine machine = CompositeStateMachine.forTest(
                profile, List.of(oldGeneration, newGeneration), List.of(), 2);
        MemoryState state = new MemoryState(1);
        CapturingEmitter original = new CapturingEmitter(1);
        machine.apply(block(1, message("item.v1", "emit")), state, original);

        state.height = 2;
        CapturingEmitter replacementBlock = new CapturingEmitter(2);
        EffectId oldEffect = original.ids.getFirst();
        machine.onEffectResult(block(2), new EffectResult(
                oldEffect, "test.effect", "scope", EffectOutcome.CONFIRMED,
                bytes("confirmed"), null, 2), state, replacementBlock);
        machine.apply(block(2, message("item.v2", "emit")), state, replacementBlock);

        assertThat(oldGeneration.resultCalls).isEqualTo(1);
        assertThat(replacementBlock.ids).hasSize(2);
        assertThat(newGeneration.appliedBodies).containsExactly("emit");
    }

    @Test
    void directAliasAndAggregateQueriesShareOneRootFixedNamespacedView() {
        QueryComponent first = new QueryComponent(descriptor(
                "first", "first.v1", 1, 0, List.of("get"), 0));
        QueryComponent second = new QueryComponent(descriptor(
                "second", "second.v1", 1, 0, List.of("get"), 0));
        CompositeProfile profile = new CompositeProfile(1, "queries", "1",
                List.of(first.descriptor(), second.descriptor()), List.of(),
                List.of(new LegacyQueryAlias("first/get", "first", "get")),
                AggregateQueryLimitsV1.DEFAULT);
        CompositeStateMachine machine = CompositeStateMachine.forTest(
                profile, List.of(first, second), List.of(), 1);
        MemoryState state = new MemoryState(1);
        state.put(CompositeStateKeys.componentKey("first", LOCAL_KEY), bytes("one"));
        state.put(CompositeStateKeys.componentKey("second", LOCAL_KEY), bytes("two"));

        assertThat(machine.query("components/first/get", LOCAL_KEY, state)).isEqualTo(bytes("one"));
        assertThat(machine.query("first/get", LOCAL_KEY, state)).isEqualTo(bytes("one"));

        byte[] request = AggregateQueryCodecV1.encodeRequest(List.of(
                new AggregateQueryCodecV1.Subquery("second", "get", LOCAL_KEY),
                new AggregateQueryCodecV1.Subquery("first", "get", LOCAL_KEY)),
                AggregateQueryLimitsV1.DEFAULT);
        List<AggregateQueryCodecV1.Result> results = AggregateQueryCodecV1.decodeResponse(
                machine.query("composite/aggregate-v1", request, state),
                AggregateQueryLimitsV1.DEFAULT);
        assertThat(results).extracting(AggregateQueryCodecV1.Result::componentId)
                .containsExactly("second", "first");
        assertThat(results.get(0).payload()).isEqualTo(bytes("two"));
        assertThat(first.observedRoot).isEqualTo(state.stateRoot());
        assertThat(second.observedRoot).isEqualTo(state.stateRoot());
    }

    @Test
    void aggregateCodecRejectsTrailingAndOversizedInputs() {
        byte[] request = AggregateQueryCodecV1.encodeRequest(List.of(
                new AggregateQueryCodecV1.Subquery("first", "get", new byte[]{1})),
                AggregateQueryLimitsV1.DEFAULT);
        byte[] trailing = java.util.Arrays.copyOf(request, request.length + 1);

        assertThatThrownBy(() -> AggregateQueryCodecV1.decodeRequest(
                trailing, AggregateQueryLimitsV1.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing");
        assertThatThrownBy(() -> AggregateQueryCodecV1.encodeRequest(List.of(
                new AggregateQueryCodecV1.Subquery("first", "get", new byte[5])),
                new AggregateQueryLimitsV1(1, 4, 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parameters exceed");

        assertThatThrownBy(() -> AggregateQueryCodecV1.encodeRequest(List.of(
                new AggregateQueryCodecV1.Subquery(
                        "first", "get", new byte[AggregateQueryLimitsV1.HOST_MAX_REQUEST_BYTES])),
                AggregateQueryLimitsV1.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host transport bound");
        assertThatThrownBy(() -> AggregateQueryCodecV1.decodeRequest(
                new byte[AggregateQueryLimitsV1.HOST_MAX_REQUEST_BYTES + 1],
                AggregateQueryLimitsV1.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encoding exceeds");
        assertThatThrownBy(() -> new AggregateQueryLimitsV1(1, 65_537, 1_048_576))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("65536");
        assertThatThrownBy(() -> new AggregateQueryLimitsV1(1, 65_536, 1_048_577))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1048576");

        byte[] response = AggregateQueryCodecV1.encodeResponse(List.of(
                new AggregateQueryCodecV1.Result("first", "get", new byte[]{1})),
                AggregateQueryLimitsV1.DEFAULT);
        assertThatThrownBy(() -> AggregateQueryCodecV1.decodeResponse(response,
                new AggregateQueryLimitsV1(1, 64, response.length - 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encoding exceeds");
    }

    @Test
    void routedBlocksExposeAMessagesRootConsistentWithTheirRoutedMessages() {
        RootCheckingStateMachine delegate = new RootCheckingStateMachine();
        StateMachineComponentAdapter adapter = new StateMachineComponentAdapter(
                descriptor("first", "first.v1", 1, 0, List.of(), 0), delegate);
        CompositeStateMachine machine = machine(adapter);

        AppBlock source = block(1, message("other.v1", "ignored"),
                message("first.v1", "accepted")).withCert(new FinalityCert(
                FinalityCert.SCHEME_ED25519,
                List.of(new FinalityCert.Signature(new byte[32], new byte[64]))));
        machine.apply(source,
                new MemoryState(1), new CapturingEmitter(1));

        assertThat(delegate.applied).isTrue();
    }

    private static CompositeStateMachine machine(CompositeComponent... components) {
        List<CompositeComponent> list = List.of(components);
        CompositeProfile profile = CompositeProfile.of("test", "1",
                list.stream().map(CompositeComponent::descriptor).toList());
        int cap = Math.max(1, list.stream()
                .mapToInt(component -> component.descriptor().maxEffectsPerBlock()).sum());
        return CompositeStateMachine.forTest(profile, list, List.of(), cap);
    }

    private static RecordingComponent component(
            String id,
            String topic,
            long from,
            long until,
            int quota
    ) {
        return new RecordingComponent(descriptor(id, topic, from, until, List.of(), quota));
    }

    private static ComponentDescriptor descriptor(
            String id,
            String topic,
            long from,
            long until,
            List<String> queryPaths,
            int quota
    ) {
        return new ComponentDescriptor(id, "1", "cfg", "state-v1", from, until,
                List.of(topic), queryPaths, quota);
    }

    private static AppMessage message(String topic, String body) {
        byte[] payload = bytes(body);
        return AppMessage.builder().messageId(new byte[32]).chainId("chain").topic(topic)
                .sender(new byte[32]).senderSeq(1).expiresAt(Long.MAX_VALUE).body(payload)
                .authScheme(0).authProof(new byte[]{1}).build();
    }

    private static AppBlock block(long height, AppMessage... messages) {
        return new AppBlock(1, "chain", height, new byte[32], 0, new byte[0], height,
                new byte[32], new byte[32], List.of(messages), new byte[32], FinalityCert.empty());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static class RecordingComponent implements CompositeComponent {
        final ComponentDescriptor descriptor;
        final List<String> appliedBodies = new ArrayList<>();
        int applyCalls;
        int validateCalls;

        private RecordingComponent(ComponentDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public ComponentDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public AppStateMachine.AdmissionResult validate(AppMessage routedMessage) {
            validateCalls++;
            return AppStateMachine.AdmissionResult.accept();
        }

        @Override
        public void apply(AppBlock routedBlock, AppStateWriter ownState, AppEffectEmitter effects) {
            applyCalls++;
            for (AppMessage message : routedBlock.messages()) {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                appliedBodies.add(body);
                ownState.put(LOCAL_KEY, message.getBody());
                if (descriptor.maxEffectsPerBlock() > 0 && "emit".equals(body)) {
                    effects.emit(intent());
                }
            }
        }
    }

    private static final class RootCheckingStateMachine implements AppStateMachine {
        private boolean applied;

        @Override
        public String id() {
            return "root-checking";
        }

        @Override
        public AdmissionResult validate(AppMessage message) {
            return AdmissionResult.accept();
        }

        @Override
        public void apply(AppBlock block, AppStateWriter state) {
            assertThat(block.messagesRoot()).isEqualTo(AppBlockCodec.messagesRoot(block.messages()));
            assertThat(block.cert().signatures()).isEmpty();
            applied = true;
        }
    }

    private static final class EffectComponent extends RecordingComponent {
        int resultCalls;
        int resultBlockMessages;
        private final boolean emitContinuation;

        private EffectComponent(ComponentDescriptor descriptor) {
            this(descriptor, false);
        }

        private EffectComponent(ComponentDescriptor descriptor, boolean emitContinuation) {
            super(descriptor);
            this.emitContinuation = emitContinuation;
        }

        @Override
        public void apply(AppBlock block, AppStateWriter state, AppEffectEmitter effects) {
            for (AppMessage ignored : block.messages()) {
                effects.emit(intent());
            }
        }

        @Override
        public void onEffectResult(
                AppBlock block,
                EffectResult result,
                AppStateWriter state,
                AppEffectEmitter effects
        ) {
            resultCalls++;
            resultBlockMessages = block.messages().size();
            state.put("result".getBytes(StandardCharsets.US_ASCII), result.externalRef());
            if (emitContinuation) {
                effects.emit(intent());
            }
        }
    }

    private static final class DoubleEmitComponent extends RecordingComponent {
        private DoubleEmitComponent(ComponentDescriptor descriptor) {
            super(descriptor);
        }

        @Override
        public void apply(AppBlock block, AppStateWriter state, AppEffectEmitter effects) {
            if (!block.messages().isEmpty()) {
                effects.emit(intent());
                effects.emit(intent());
            }
        }
    }

    private static final class QueryComponent extends RecordingComponent {
        byte[] observedRoot;

        private QueryComponent(ComponentDescriptor descriptor) {
            super(descriptor);
        }

        @Override
        public byte[] query(String path, byte[] params, AppQueryContext state) {
            observedRoot = state.stateRoot();
            return state.get(params).orElseThrow();
        }
    }

    private static EffectIntent intent() {
        return new EffectIntent("test.effect", new byte[]{1}, "scope",
                FinalityGate.APP_FINAL, ResultPolicy.CHAIN, 10, null);
    }

    private static final class CapturingEmitter implements AppEffectEmitter {
        private final long height;
        private final List<EffectId> ids = new ArrayList<>();

        private CapturingEmitter(long height) {
            this.height = height;
        }

        @Override
        public EffectId emit(EffectIntent intent) {
            EffectId id = new EffectId("chain", height, ids.size());
            ids.add(id);
            return id;
        }

        @Override
        public long pendingCount() {
            return 0;
        }
    }

    private static final class MemoryState implements AppStateWriter, AppQueryContext {
        private final Map<String, byte[]> values = new HashMap<>();
        private long height;

        private MemoryState(long height) {
            this.height = height;
        }

        @Override
        public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(HexFormat.of().formatHex(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override
        public byte[] stateRoot() {
            byte[] root = new byte[32];
            root[31] = (byte) height;
            return root;
        }

        @Override
        public long committedHeight() {
            return height;
        }

        @Override
        public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }

        @Override
        public void delete(byte[] key) {
            values.remove(HexFormat.of().formatHex(key));
        }
    }
}
