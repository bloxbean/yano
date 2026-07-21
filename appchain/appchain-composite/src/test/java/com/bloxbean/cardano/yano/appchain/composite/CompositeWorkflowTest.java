package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
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

class CompositeWorkflowTest {
    private static final byte[] KEY = "item".getBytes(StandardCharsets.US_ASCII);

    @Test
    void workflowRunsAfterComponentsAndAtomicallyCoordinatesDeclaredViews() {
        PutComponent registry = component("registry", "registry.v1");
        PutComponent approvals = component("approvals", "approvals.v1");
        PutComponent documents = component("documents", "documents.v1");
        PutComponent evidence = component("evidence", "evidence.v1");
        ReleaseWorkflow release = new ReleaseWorkflow(workflow(
                registry, approvals, documents, evidence), false, false);
        CompositeStateMachine machine = machine(
                List.of(registry, approvals, documents, evidence), List.of(release));
        MemoryState state = new MemoryState();

        machine.apply(block(1,
                message("registry.v1", "registered"),
                message("approvals.v1", "approved"),
                message("release.v1", "release")), state, new CapturingEmitter(1));

        assertThat(state.value("documents", KEY)).containsExactly(bytes("documented"));
        assertThat(state.value("evidence", KEY)).containsExactly(bytes("submitted"));
        assertThat(release.calls).isEqualTo(1);
    }

    @Test
    void businessPreconditionFailureIsAFinalizablePerMessageNoOp() {
        PutComponent registry = component("registry", "registry.v1");
        PutComponent approvals = component("approvals", "approvals.v1");
        PutComponent documents = component("documents", "documents.v1");
        PutComponent evidence = component("evidence", "evidence.v1");
        ReleaseWorkflow release = new ReleaseWorkflow(workflow(
                registry, approvals, documents, evidence), false, false);
        CompositeStateMachine machine = machine(
                List.of(registry, approvals, documents, evidence), List.of(release));
        MemoryState state = new MemoryState();

        machine.apply(block(1,
                message("registry.v1", "registered"), message("release.v1", "release")),
                state, new CapturingEmitter(1));
        assertThat(state.value("documents", KEY)).isNull();
        assertThat(state.value("evidence", KEY)).isNull();
    }

    @Test
    void undeclaredParticipantAccessFailsClosed() {
        PutComponent registry = component("registry", "registry.v1");
        PutComponent approvals = component("approvals", "approvals.v1");
        PutComponent documents = component("documents", "documents.v1");
        PutComponent evidence = component("evidence", "evidence.v1");
        ReleaseWorkflow release = new ReleaseWorkflow(workflow(
                registry, approvals, documents, evidence), true, false);
        CompositeStateMachine machine = machine(
                List.of(registry, approvals, documents, evidence), List.of(release));

        assertThatThrownBy(() -> machine.apply(block(1,
                message("registry.v1", "registered"),
                message("approvals.v1", "approved"),
                message("release.v1", "release")),
                new MemoryState(), new CapturingEmitter(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("undeclared component access");
    }

    @Test
    void workflowEffectIsOwnedByDeclaredComponentAndUsesSeparateQuota() {
        PutComponent registry = component("registry", "registry.v1");
        PutComponent approvals = component("approvals", "approvals.v1");
        PutComponent documents = component("documents", "documents.v1");
        PutComponent evidence = component("evidence", "evidence.v1");
        ReleaseWorkflow release = new ReleaseWorkflow(workflow(
                registry, approvals, documents, evidence), false, true);
        CompositeStateMachine machine = machine(
                List.of(registry, approvals, documents, evidence), List.of(release));
        MemoryState state = new MemoryState();
        CapturingEmitter emitter = new CapturingEmitter(1);

        machine.apply(block(1,
                message("registry.v1", "registered"),
                message("approvals.v1", "approved"),
                message("release.v1", "release")), state, emitter);
        EffectId effectId = emitter.ids.getFirst();
        assertThat(CompositeStateKeys.decodeGeneration(state.get(
                CompositeStateKeys.effectOwnerKey(effectId)).orElseThrow()))
                .isEqualTo(evidence.descriptor().generation());
        assertThat(state.get(CompositeStateKeys.workflowQuotaKey(1, release.descriptor())))
                .isEmpty();

        machine.onEffectResult(block(2), new EffectResult(effectId, "workflow.effect", "release",
                EffectOutcome.CONFIRMED, new byte[0], null, 2), state, new CapturingEmitter(2));
        assertThat(evidence.resultCalls).isEqualTo(1);
    }

    private static CompositeStateMachine machine(
            List<CompositeComponent> components,
            List<CompositeWorkflow> workflows
    ) {
        CompositeProfile profile = new CompositeProfile(1, "workflow-test", "1",
                components.stream().map(CompositeComponent::descriptor).toList(),
                workflows.stream().map(CompositeWorkflow::descriptor).toList(),
                List.of(), AggregateQueryLimitsV1.DEFAULT);
        profile.validateEffectBudget(1);
        return CompositeStateMachine.forTest(profile, components, workflows, 1);
    }

    private static WorkflowDescriptor workflow(PutComponent... participants) {
        return new WorkflowDescriptor("release", "1", "release.v1", 1, 0,
                java.util.Arrays.stream(participants)
                        .map(component -> component.descriptor().generation()).toList(), 1);
    }

    private static PutComponent component(String id, String topic) {
        return new PutComponent(new ComponentDescriptor(id, "1", "cfg", "state-v1", 1, 0,
                List.of(topic), List.of(), 0));
    }

    private static final class PutComponent implements CompositeComponent {
        private final ComponentDescriptor descriptor;
        private int resultCalls;

        private PutComponent(ComponentDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public ComponentDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void apply(AppBlock block, AppStateWriter state, AppEffectEmitter effects) {
            for (AppMessage message : block.messages()) {
                state.put(KEY, message.getBody());
            }
        }

        @Override
        public void onEffectResult(
                AppBlock block,
                EffectResult result,
                AppStateWriter ownState,
                AppEffectEmitter ownedEffects
        ) {
            resultCalls++;
        }
    }

    private static final class ReleaseWorkflow implements CompositeWorkflow {
        private final WorkflowDescriptor descriptor;
        private final boolean accessUndeclared;
        private final boolean emit;
        private int calls;

        private ReleaseWorkflow(
                WorkflowDescriptor descriptor,
                boolean accessUndeclared,
                boolean emit
        ) {
            this.descriptor = descriptor;
            this.accessUndeclared = accessUndeclared;
            this.emit = emit;
        }

        @Override
        public WorkflowDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void apply(AppBlock block, CompositeWorkflowContext context) {
            for (AppMessage ignored : block.messages()) {
                calls++;
                ComponentGeneration registry = descriptor.participants().get(0);
                ComponentGeneration approvals = descriptor.participants().get(1);
                ComponentGeneration documents = descriptor.participants().get(2);
                ComponentGeneration evidence = descriptor.participants().get(3);
                if (context.state(registry).get(KEY).isEmpty()) {
                    continue;
                }
                if (context.state(approvals).get(KEY).isEmpty()) {
                    continue;
                }
                if (accessUndeclared) {
                    context.state(new ComponentGeneration("outsider", "1", 1));
                }
                context.state(documents).put(KEY, bytes("documented"));
                context.state(evidence).put(KEY, bytes("submitted"));
                if (emit) {
                    context.effects(evidence).emit(new EffectIntent(
                            "workflow.effect", new byte[]{1}, "release", FinalityGate.APP_FINAL,
                            ResultPolicy.CHAIN, 10, null));
                }
            }
        }
    }

    private static AppMessage message(String topic, String value) {
        return AppMessage.builder().messageId(new byte[32]).chainId("chain").topic(topic)
                .sender(new byte[32]).senderSeq(1).expiresAt(Long.MAX_VALUE).body(bytes(value))
                .authScheme(0).authProof(new byte[]{1}).build();
    }

    private static AppBlock block(long height, AppMessage... messages) {
        return new AppBlock(1, "chain", height, new byte[32], 0, new byte[0], height,
                new byte[32], new byte[32], List.of(messages), new byte[32], FinalityCert.empty());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
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

    private static final class MemoryState implements AppStateWriter {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override
        public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(HexFormat.of().formatHex(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override
        public byte[] stateRoot() {
            return new byte[32];
        }

        @Override
        public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }

        @Override
        public void delete(byte[] key) {
            values.remove(HexFormat.of().formatHex(key));
        }

        private byte[] value(String component, byte[] localKey) {
            return get(CompositeStateKeys.componentKey(component, localKey)).orElse(null);
        }
    }
}
