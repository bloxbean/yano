package com.bloxbean.cardano.yano.appchain.composite.stock;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.effects.ActivationSchedule;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;
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
        String preset = settings.getOrDefault("machines.composite.preset", EVIDENCE_V1).trim();
        if (!EVIDENCE_V1.equals(preset)) {
            throw new IllegalArgumentException("Unsupported composite preset: " + preset);
        }
        String approvalsPayments = settings.getOrDefault(
                "machines.approvals.payments", "false").trim().toLowerCase(Locale.ROOT);
        if (!approvalsPayments.equals("true") && !approvalsPayments.equals("false")) {
            throw new IllegalArgumentException(
                    "machines.approvals.payments must be true or false");
        }
        if (approvalsPayments.equals("true")) {
            throw new IllegalArgumentException(
                    "evidence-v1 keeps approvals payments disabled; use a custom composite profile");
        }

        int frameworkMaxEffects = positiveInt(settings, "effects.max-per-block", 256);
        if (frameworkMaxEffects < 4) {
            throw new IllegalArgumentException(
                    "evidence-v1 requires effects.max-per-block >= 4 for reserved component/workflow quotas");
        }
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
        ComponentDescriptor evidenceDescriptor = descriptor(EVIDENCE_ID,
                evidenceConfig.configurationId(), EvidenceContract.COMMAND_TOPIC,
                List.of("get"), frameworkMaxEffects - 2);

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
        WorkflowDescriptor workflowDescriptor = new WorkflowDescriptor(
                EvidenceReleaseWorkflow.ID, "1.0.0", EvidenceReleaseWorkflow.TOPIC,
                1, 0, participants, 2);
        CompositeWorkflow release = new EvidenceReleaseWorkflow(workflowDescriptor,
                participants.get(0), participants.get(1), participants.get(2), participants.get(3),
                docTrailMachine, evidenceMachine);
        CompositeProfile profile = new CompositeProfile(1, EVIDENCE_V1, "1.0.0",
                components.stream().map(CompositeComponent::descriptor).toList(),
                List.of(workflowDescriptor),
                List.of(new LegacyQueryAlias(
                        EvidenceContract.GET_QUERY_PATH, EVIDENCE_ID, "get")),
                AggregateQueryLimitsV1.DEFAULT);
        return CompositeStateMachine.create(context, profile, components, List.of(release));
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

    private static int positiveInt(Map<String, String> settings, String key, int fallback) {
        String configured = settings.get(key);
        try {
            int value = configured == null || configured.isBlank()
                    ? fallback : Integer.parseInt(configured.trim());
            if (value < 1 || value > 1_048_576) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(key + " must be an integer between 1 and 1048576");
        }
    }
}
