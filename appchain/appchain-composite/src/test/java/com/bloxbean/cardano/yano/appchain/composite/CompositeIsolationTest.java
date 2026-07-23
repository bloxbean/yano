package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.appchain.testkit.AppChainTestProfiles;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeIsolationTest {
    private static final byte[] LOCAL_KEY = "item".getBytes(StandardCharsets.US_ASCII);

    @Test
    void productDescriptorsAreBoundOnceAndCannotChangeCommittedCapabilities() {
        ComponentDescriptor committed = descriptor("first", "first.v1", 1, 0, 0);
        ComponentDescriptor rogue = descriptor("other", "first.v1", 1, 0, 0);
        SwitchingComponent product = new SwitchingComponent(committed, rogue);
        CompositeStateMachine machine = CompositeStateMachine.forTest(
                CompositeProfile.of("bound", "1", List.of(committed)),
                List.of(product), List.of(), 1);
        MemoryState state = new MemoryState();

        machine.apply(block(message("first.v1", "safe")), state,
                AppEffectEmitter.rejecting("unused"));

        assertThat(product.descriptorCalls).isEqualTo(1);
        assertThat(state.get(CompositeStateKeys.componentKey("first", LOCAL_KEY)))
                .hasValue(bytes("safe"));
        assertThat(state.get(CompositeStateKeys.componentKey("other", LOCAL_KEY))).isEmpty();
    }

    @Test
    void workflowCannotExpandItsCommittedParticipantSetAfterBinding() {
        PutComponent first = new PutComponent(descriptor("first", "first.v1", 1, 0, 0));
        PutComponent other = new PutComponent(descriptor("other", "other.v1", 1, 0, 0));
        WorkflowDescriptor committed = new WorkflowDescriptor(
                "flow", "1", "flow.v1", 1, 0,
                List.of(first.descriptor().generation()), 0);
        WorkflowDescriptor rogue = new WorkflowDescriptor(
                "flow", "1", "flow.v1", 1, 0,
                List.of(first.descriptor().generation(), other.descriptor().generation()), 0);
        SwitchingWorkflow workflow = new SwitchingWorkflow(
                committed, rogue, other.descriptor().generation());
        CompositeProfile profile = new CompositeProfile(1, "bound", "1",
                List.of(first.descriptor(), other.descriptor()), List.of(committed),
                List.of(), AggregateQueryLimitsV1.DEFAULT);
        CompositeStateMachine machine = CompositeStateMachine.forTest(
                profile, List.of(first, other), List.of(workflow), 1);

        assertThatThrownBy(() -> machine.apply(block(message("flow.v1", "x")),
                new MemoryState(), AppEffectEmitter.rejecting("unused")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("undeclared component access");
        assertThat(workflow.descriptorCalls).isEqualTo(1);
    }

    @Test
    void callbackInputsAreDeepSnapshotsAndCannotCorruptSiblingOrOriginalInputs() {
        ComponentDescriptor oldDescriptor = descriptor("item", "item.v1", 1, 2, 0);
        ComponentDescriptor newDescriptor = new ComponentDescriptor(
                "item", "2", "cfg-v2", "item-state-v1", 2, 0,
                List.of("item.v1"), List.of(), 0);
        MutatingComponent mutator = new MutatingComponent(oldDescriptor);
        ObservingComponent observer = new ObservingComponent(newDescriptor);
        CompositeStateMachine machine = CompositeStateMachine.forTest(
                CompositeProfile.of("snapshots", "1", List.of(oldDescriptor, newDescriptor)),
                List.of(mutator, observer), List.of(), 1);
        AppMessage message = message("item.v1", "original");
        AppBlock block = block(message);
        byte[] originalMessageId = message.getMessageId().clone();
        byte[] originalBody = message.getBody().clone();
        byte[] originalPrevHash = block.prevHash().clone();

        assertThat(machine.validateForBlock(message, 1, new MemoryState()).isAccepted()).isTrue();
        machine.apply(block, new MemoryState(), AppEffectEmitter.rejecting("unused"));

        assertThat(observer.observedBody).isNull();
        assertThat(message.getMessageId()).isEqualTo(originalMessageId);
        assertThat(message.getBody()).isEqualTo(originalBody);
        assertThat(block.prevHash()).isEqualTo(originalPrevHash);
    }

    @Test
    void customMachineIdWorksAndFrameworkEffectCapIsMandatory() {
        PutComponent component = new PutComponent(descriptor("effects", "effects.v1", 1, 0, 2));
        CompositeProfile profile = CompositeProfile.of(
                "custom-profile", "1", List.of(component.descriptor()));
        AppStateMachineContext tooSmall = context(1);

        assertThatThrownBy(() -> CompositeStateMachine.create(
                "custom-machine", tooSmall, profile, List.of(component), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quota 2");

        CompositeStateMachine machine = CompositeStateMachine.create(
                "custom-machine", context(2), profile, List.of(component), List.of());
        assertThat(machine.id()).isEqualTo("custom-machine");
    }

    private static ComponentDescriptor descriptor(
            String id, String topic, long from, long until, int quota
    ) {
        return new ComponentDescriptor(id, "1", "cfg", id + "-state-v1",
                from, until, List.of(topic), List.of(), quota);
    }

    private static AppStateMachineContext context(int effectCap) {
        return new AppStateMachineContext() {
            @Override public String chainId() { return "chain"; }
            @Override public Map<String, String> settings() {
                return Map.of("effects.max-per-block", Integer.toString(effectCap));
            }
            @Override
            public java.util.Optional<com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile>
            consensusProfile() {
                return java.util.Optional.of(AppChainTestProfiles.enabledEffects(effectCap));
            }
        };
    }

    private static AppMessage message(String topic, String body) {
        byte[] id = new byte[32];
        Arrays.fill(id, (byte) 1);
        return AppMessage.builder().version(1).messageId(id).chainId("chain").topic(topic)
                .sender(new byte[32]).senderSeq(1).expiresAt(Long.MAX_VALUE)
                .body(bytes(body)).authScheme(0).authProof(new byte[64]).build();
    }

    private static AppBlock block(AppMessage... messages) {
        byte[] previous = new byte[32];
        Arrays.fill(previous, (byte) 2);
        return new AppBlock(1, "chain", 1, previous, 0, new byte[]{3}, 4,
                new byte[32], new byte[32], List.of(messages), new byte[32],
                FinalityCert.empty());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static class PutComponent implements CompositeComponent {
        private final ComponentDescriptor descriptor;

        private PutComponent(ComponentDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override public ComponentDescriptor descriptor() { return descriptor; }

        @Override
        public void apply(AppBlock block, AppStateWriter state, AppEffectEmitter effects) {
            for (AppMessage message : block.messages()) {
                state.put(LOCAL_KEY, message.getBody());
            }
        }
    }

    private static final class SwitchingComponent extends PutComponent {
        private final ComponentDescriptor first;
        private final ComponentDescriptor later;
        private int descriptorCalls;

        private SwitchingComponent(ComponentDescriptor first, ComponentDescriptor later) {
            super(first);
            this.first = first;
            this.later = later;
        }

        @Override
        public ComponentDescriptor descriptor() {
            return descriptorCalls++ == 0 ? first : later;
        }
    }

    private static final class SwitchingWorkflow implements CompositeWorkflow {
        private final WorkflowDescriptor first;
        private final WorkflowDescriptor later;
        private final ComponentGeneration unauthorized;
        private int descriptorCalls;

        private SwitchingWorkflow(WorkflowDescriptor first, WorkflowDescriptor later,
                                  ComponentGeneration unauthorized) {
            this.first = first;
            this.later = later;
            this.unauthorized = unauthorized;
        }

        @Override
        public WorkflowDescriptor descriptor() {
            return descriptorCalls++ == 0 ? first : later;
        }

        @Override
        public void apply(AppBlock block, CompositeWorkflowContext context) {
            context.state(unauthorized).put(LOCAL_KEY, new byte[]{1});
        }
    }

    private static final class MutatingComponent extends PutComponent {
        private MutatingComponent(ComponentDescriptor descriptor) { super(descriptor); }

        @Override
        public AppStateMachine.AdmissionResult validate(AppMessage message) {
            message.getBody()[0] ^= 1;
            message.getMessageId()[0] ^= 1;
            return AppStateMachine.AdmissionResult.accept();
        }

        @Override
        public void apply(AppBlock block, AppStateWriter state, AppEffectEmitter effects) {
            block.prevHash()[0] ^= 1;
            block.messages().getFirst().getBody()[0] ^= 1;
        }
    }

    private static final class ObservingComponent extends PutComponent {
        private String observedBody;

        private ObservingComponent(ComponentDescriptor descriptor) { super(descriptor); }

        @Override
        public AppStateMachine.AdmissionResult validate(AppMessage message) {
            observedBody = new String(message.getBody(), StandardCharsets.UTF_8);
            return AppStateMachine.AdmissionResult.accept();
        }
    }

    private static final class MemoryState implements AppStateWriter {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override
        public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(java.util.HexFormat.of().formatHex(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override public byte[] stateRoot() { return new byte[32]; }

        @Override
        public void put(byte[] key, byte[] value) {
            values.put(java.util.HexFormat.of().formatHex(key), value.clone());
        }

        @Override
        public void delete(byte[] key) {
            values.remove(java.util.HexFormat.of().formatHex(key));
        }
    }
}
