package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.effects.ActivationSchedule;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeComponent;
import com.bloxbean.cardano.yano.appchain.composite.CompositeProfile;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.StateMachineComponentAdapter;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;
import com.bloxbean.cardano.yano.appchain.config.AppChainApprovalsConfig;
import com.bloxbean.cardano.yano.appchain.stdlib.ApprovalsStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.DocTrailStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.KvRegistryStateMachine;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One committed, deliberately opinionated demo composite profile. */
public final class ShowcaseCompositePreset {
    public static final String ID = "showcase-composite";
    public static final String PRESET = "order-approval-outbox-v1";
    public static final String ORDERS_ID = "orders";
    public static final String APPROVALS_ID = "approvals";
    public static final String AUDIT_ID = "audit";
    public static final String RELEASE_ID = "release";
    public static final String ORDERS_TOPIC = "orders.command.v1";
    public static final String APPROVALS_TOPIC = "approvals.command.v1";
    public static final String AUDIT_TOPIC = "audit.command.v1";

    private ShowcaseCompositePreset() {
    }

    public static CompositeStateMachine create(AppStateMachineContext context) {
        Objects.requireNonNull(context, "context");
        Map<String, String> settings = Map.copyOf(context.settings());
        String preset = settings.getOrDefault("machines.composite.preset", PRESET).trim();
        if (!PRESET.equals(preset)) {
            throw new IllegalArgumentException("Unsupported showcase composite preset: " + preset);
        }
        if (AppChainApprovalsConfig.fromSettings(settings).enabled()) {
            throw new IllegalArgumentException(
                    "showcase-composite owns effect emission; approvals on-approved effect must be disabled");
        }

        ComponentDescriptor ordersDescriptor = descriptor(ORDERS_ID, "raw-v1",
                List.of(ORDERS_TOPIC), List.of(), 0);
        ComponentDescriptor approvalsDescriptor = descriptor(APPROVALS_ID,
                "on-approved-effect-disabled-v1", List.of(APPROVALS_TOPIC), List.of(), 0);
        ComponentDescriptor auditDescriptor = descriptor(AUDIT_ID, "append-v1",
                List.of(AUDIT_TOPIC), List.of(), 0);
        ComponentDescriptor releaseDescriptor = descriptor(RELEASE_ID, "outbox-result-v1",
                List.of(), List.of(ShowcaseReleaseStateMachine.QUERY_PATH), 1);

        KvRegistryStateMachine orders = new KvRegistryStateMachine();
        ApprovalsStateMachine approvals = new ApprovalsStateMachine(
                AppChainApprovalsConfig.DISABLED, ActivationSchedule.empty());
        DocTrailStateMachine audit = new DocTrailStateMachine();
        ShowcaseReleaseStateMachine releaseState = new ShowcaseReleaseStateMachine();
        List<CompositeComponent> components = List.of(
                new StateMachineComponentAdapter(ordersDescriptor, orders),
                new StateMachineComponentAdapter(approvalsDescriptor, approvals),
                new StateMachineComponentAdapter(auditDescriptor, audit),
                new StateMachineComponentAdapter(releaseDescriptor, releaseState));
        List<ComponentGeneration> participants = components.stream()
                .map(component -> component.descriptor().generation()).toList();
        WorkflowDescriptor workflowDescriptor = new WorkflowDescriptor(
                ShowcaseReleaseWorkflow.ID, "1.0.0", ShowcaseReleaseWorkflow.TOPIC,
                1, 0, participants, 1);
        CompositeWorkflow workflow = new ShowcaseReleaseWorkflow(workflowDescriptor,
                participants.get(0), participants.get(1), participants.get(2), participants.get(3),
                audit, releaseState);
        CompositeProfile profile = new CompositeProfile(1, PRESET, "1.0.0",
                components.stream().map(CompositeComponent::descriptor).toList(),
                List.of(workflowDescriptor), List.of(), AggregateQueryLimitsV1.DEFAULT);
        return CompositeStateMachine.create(ID, context, profile, components, List.of(workflow));
    }

    private static ComponentDescriptor descriptor(String id, String configurationId,
                                                   List<String> topics, List<String> queries,
                                                   int effects) {
        return new ComponentDescriptor(id, "1.0.0", configurationId, id + "-state-v1",
                1, 0, topics, queries, effects);
    }
}
