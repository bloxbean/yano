package com.bloxbean.cardano.yano.appchain.composite.stock;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.effects.ActivationSchedule;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.stock.EvidenceWorkflowCapacityV1;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeComponent;
import com.bloxbean.cardano.yano.appchain.composite.CompositeProfile;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.LegacyQueryAlias;
import com.bloxbean.cardano.yano.appchain.composite.StateMachineComponentAdapter;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceRegistryConfig;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceRegistryStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.ApprovalsStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.DocTrailStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.KvRegistryStateMachine;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Configuration-only factory for stock, versioned composite profiles. */
public final class CompositeStockPresets {
    public static final String EVIDENCE_V1 = "evidence-v1";
    public static final String EVIDENCE_V1_GATED = "evidence-v1-gated";
    public static final String REGISTRY_ID = "registry";
    public static final String APPROVALS_ID = "approvals";
    public static final String DOC_TRAIL_ID = "doc-trail";
    public static final String EVIDENCE_ID = "evidence";
    public static final String REGISTRY_TOPIC = "registry.command.v1";
    public static final String APPROVALS_TOPIC = "approvals.command.v1";
    public static final String DOC_TRAIL_TOPIC = "doc-trail.command.v1";

    private CompositeStockPresets() {
    }

    public static CompositeStateMachine create(AppStateMachineContext context) {
        Objects.requireNonNull(context, "context");
        Map<String, String> settings = Map.copyOf(context.settings());
        String preset = settings.getOrDefault(
                "machines.composite.preset", EVIDENCE_V1_GATED).trim();
        if (!EVIDENCE_V1.equals(preset) && !EVIDENCE_V1_GATED.equals(preset)) {
            throw new IllegalArgumentException("Unsupported composite preset: " + preset);
        }
        boolean gated = EVIDENCE_V1_GATED.equals(preset);
        String approvalsPayments = settings.getOrDefault(
                "machines.approvals.payments", "false").trim().toLowerCase(Locale.ROOT);
        if (!approvalsPayments.equals("true") && !approvalsPayments.equals("false")) {
            throw new IllegalArgumentException(
                    "machines.approvals.payments must be true or false");
        }
        if (approvalsPayments.equals("true")) {
            throw new IllegalArgumentException(
                    preset + " keeps approvals payments disabled; use a custom composite profile");
        }

        var consensusProfile = context.consensusProfile().orElseThrow(() ->
                new IllegalArgumentException(
                        "composite preset requires AppStateMachineContext.consensusProfile() (ADR-016)"));
        EvidenceWorkflowCapacityV1 capacity = evidenceCapacity(settings);
        capacity.validateAgainst(consensusProfile.maxBlockMessages(),
                consensusProfile.effectsMaxPerBlock());
        String formatSetting = settings.getOrDefault(
                "machines.kv-registry.value-format", "raw").trim();
        KvRegistryStateMachine.ValueFormat registryFormat =
                KvRegistryStateMachine.ValueFormat.parse(formatSetting);
        EvidenceRegistryConfig evidenceConfig = EvidenceRegistryConfig.from(context);

        ComponentDescriptor registryDescriptor = descriptor(REGISTRY_ID,
                "value-format:" + registryFormat.name().toLowerCase(Locale.ROOT),
                REGISTRY_TOPIC, List.of(), 0);
        ComponentDescriptor approvalsDescriptor = descriptor(APPROVALS_ID,
                "payments-disabled-v1", APPROVALS_TOPIC, List.of(), 0);
        ComponentDescriptor docTrailDescriptor = descriptor(DOC_TRAIL_ID,
                "append-v1", DOC_TRAIL_TOPIC, List.of(), 0);
        int evidenceQuota = gated
                ? capacity.gatedEvidenceComponentEffects()
                : capacity.directEvidenceComponentEffects();
        ComponentDescriptor evidenceDescriptor = descriptor(EVIDENCE_ID,
                evidenceConfig.configurationId(),
                gated ? List.of() : List.of(EvidenceContract.COMMAND_TOPIC),
                List.of("get"), evidenceQuota);

        KvRegistryStateMachine registryMachine = new KvRegistryStateMachine(registryFormat);
        ApprovalsStateMachine approvalsMachine = new ApprovalsStateMachine(
                ApprovalsStateMachine.PaymentsConfig.DISABLED, ActivationSchedule.empty());
        DocTrailStateMachine docTrailMachine = new DocTrailStateMachine();
        EvidenceRegistryStateMachine evidenceMachine =
                new EvidenceRegistryStateMachine(evidenceConfig);
        List<CompositeComponent> components = List.of(
                new StateMachineComponentAdapter(registryDescriptor, registryMachine),
                new StateMachineComponentAdapter(approvalsDescriptor, approvalsMachine),
                new StateMachineComponentAdapter(docTrailDescriptor, docTrailMachine),
                new StateMachineComponentAdapter(evidenceDescriptor, evidenceMachine,
                        Map.of("get", EvidenceContract.GET_QUERY_PATH)));

        List<ComponentGeneration> participants = components.stream()
                .map(component -> component.descriptor().generation()).toList();
        WorkflowDescriptor releaseDescriptor = new WorkflowDescriptor(
                EvidenceReleaseWorkflow.ID, EvidenceReleaseWorkflow.PRODUCT_VERSION,
                EvidenceReleaseWorkflow.TOPIC,
                1, 0, participants, capacity.releaseWorkflowEffects());
        CompositeWorkflow release = new EvidenceReleaseWorkflow(releaseDescriptor,
                participants.get(0), participants.get(1), participants.get(2), participants.get(3),
                docTrailMachine, evidenceMachine, evidenceConfig);
        List<WorkflowDescriptor> workflowDescriptors;
        List<CompositeWorkflow> workflows;
        if (gated) {
            WorkflowDescriptor notifyDescriptor = new WorkflowDescriptor(
                    EvidenceNotifyWorkflow.ID, "1.0.0", EvidenceNotifyWorkflow.TOPIC,
                    1, 0, List.of(participants.get(3)),
                    capacity.notificationWorkflowEffects());
            CompositeWorkflow notify = new EvidenceNotifyWorkflow(
                    notifyDescriptor, participants.get(3), evidenceMachine);
            // CompositeProfile canonicalizes workflow order by workflow id;
            // products must be supplied in that same deterministic order.
            workflowDescriptors = List.of(notifyDescriptor, releaseDescriptor);
            workflows = List.of(notify, release);
        } else {
            workflowDescriptors = List.of(releaseDescriptor);
            workflows = List.of(release);
        }
        CompositeProfile profile = new CompositeProfile(1, preset, "1.0.0",
                components.stream().map(CompositeComponent::descriptor).toList(),
                workflowDescriptors,
                List.of(new LegacyQueryAlias(
                        EvidenceContract.GET_QUERY_PATH, EVIDENCE_ID, "get")),
                AggregateQueryLimitsV1.DEFAULT);
        return CompositeStateMachine.create(context, profile, components, workflows);
    }

    private static EvidenceWorkflowCapacityV1 evidenceCapacity(Map<String, String> settings) {
        String raw = settings.getOrDefault(
                "machines.composite.evidence-capacity-per-block",
                Integer.toString(EvidenceWorkflowCapacityV1.DEFAULT_CAPACITY)).trim();
        final int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(
                    "machines.composite.evidence-capacity-per-block must be a decimal integer",
                    malformed);
        }
        return new EvidenceWorkflowCapacityV1(parsed);
    }

    private static ComponentDescriptor descriptor(
            String componentId,
            String configurationId,
            String topic,
            List<String> queryPaths,
            int quota
    ) {
        return new ComponentDescriptor(componentId, "1.0.0", configurationId,
                componentId + "-state-v1",
                1, 0, List.of(topic), queryPaths, quota);
    }

    private static ComponentDescriptor descriptor(
            String componentId,
            String configurationId,
            List<String> topics,
            List<String> queryPaths,
            int quota
    ) {
        return new ComponentDescriptor(componentId, "1.0.0", configurationId,
                componentId + "-state-v1",
                1, 0, topics, queryPaths, quota);
    }

}
