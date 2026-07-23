package com.bloxbean.cardano.yano.appchain.evidence.profile;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.effects.ActivationSchedule;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeComponent;
import com.bloxbean.cardano.yano.appchain.composite.CompositeProfile;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.LegacyQueryAlias;
import com.bloxbean.cardano.yano.appchain.composite.StateMachineComponentAdapter;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;
import com.bloxbean.cardano.yano.appchain.evidence.profile.contracts.EvidenceWorkflowCapacityV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceRegistryConfig;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceRegistryStateMachine;
import com.bloxbean.cardano.yano.appchain.roles.DomainActorRegistryComponent;
import com.bloxbean.cardano.yano.appchain.roles.RoleApprovalWorkflow;
import com.bloxbean.cardano.yano.appchain.roles.RoleAwareApprovalsComponent;
import com.bloxbean.cardano.yano.appchain.roles.RoleWorkflowGovernanceConfig;
import com.bloxbean.cardano.yano.appchain.stdlib.DocTrailStateMachine;
import com.bloxbean.cardano.yano.appchain.stdlib.KvRegistryStateMachine;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Explicit committed assembly for the stock evidence-role-v1 profile. */
public final class RoleEvidencePreset {
    public static final String PROFILE_ID = "evidence-role-v1";
    public static final String REGISTRY_ID = "registry";
    public static final String DOC_TRAIL_ID = "doc-trail";
    public static final String EVIDENCE_ID = "evidence";
    public static final String REGISTRY_TOPIC = "registry.command.v1";

    private RoleEvidencePreset() {
    }

    public static CompositeStateMachine create(AppStateMachineContext context) {
        Objects.requireNonNull(context, "context");
        Map<String, String> settings = Map.copyOf(context.settings());
        var consensus = context.consensusProfile().orElseThrow(() ->
                new IllegalArgumentException("role-evidence requires consensusProfile()"));
        RoleWorkflowGovernanceConfig governance = RoleWorkflowGovernanceConfig.from(context);
        EvidenceWorkflowCapacityV1 capacity = capacity(settings);
        capacity.validateAgainst(consensus.maxBlockMessages(), consensus.effectsMaxPerBlock());
        KvRegistryStateMachine.ValueFormat registryFormat =
                KvRegistryStateMachine.ValueFormat.parse(settings.getOrDefault(
                        "machines.kv-registry.value-format", "raw").trim());
        EvidenceRegistryConfig evidenceConfig = EvidenceRegistryConfig.from(context);

        ComponentDescriptor registryDescriptor = descriptor(REGISTRY_ID,
                "value-format:" + registryFormat.name().toLowerCase(Locale.ROOT),
                List.of(REGISTRY_TOPIC), List.of(), 0);
        ComponentDescriptor actorDescriptor = descriptor(
                DomainActorRegistryComponent.COMPONENT_ID, governance.configurationId(),
                List.of(DomainActorRegistryComponent.TOPIC),
                List.of(DomainActorRegistryComponent.QUERY_ACTOR,
                        DomainActorRegistryComponent.QUERY_ACTOR_CURRENT,
                        DomainActorRegistryComponent.QUERY_ORGANIZATION,
                        DomainActorRegistryComponent.QUERY_ORGANIZATION_CURRENT), 0);
        ComponentDescriptor approvalDescriptor = descriptor(
                RoleAwareApprovalsComponent.COMPONENT_ID, governance.configurationId(),
                List.of(), List.of(RoleAwareApprovalsComponent.QUERY_POLICY,
                        RoleAwareApprovalsComponent.QUERY_POLICY_CURRENT,
                        RoleAwareApprovalsComponent.QUERY_PROPOSAL,
                        EvidenceRoleApprovalsComponent.QUERY_EVIDENCE_APPROVAL,
                        RoleAwareApprovalsComponent.QUERY_STATS), 0);
        ComponentDescriptor docTrailDescriptor = descriptor(DOC_TRAIL_ID, "workflow-only-v1",
                List.of(), List.of(), 0);
        ComponentDescriptor evidenceDescriptor = descriptor(EVIDENCE_ID,
                evidenceConfig.configurationId(), List.of(), List.of("get"),
                capacity.gatedEvidenceComponentEffects());

        KvRegistryStateMachine registryMachine = new KvRegistryStateMachine(registryFormat);
        DocTrailStateMachine docTrailMachine = new DocTrailStateMachine();
        EvidenceRegistryStateMachine evidenceMachine = new EvidenceRegistryStateMachine(evidenceConfig);
        DomainActorRegistryComponent actorComponent = new DomainActorRegistryComponent(
                actorDescriptor, context.chainId(), governance);
        EvidenceRoleApprovalsComponent approvalComponent =
                new EvidenceRoleApprovalsComponent(approvalDescriptor);
        List<CompositeComponent> components = List.of(
                new StateMachineComponentAdapter(registryDescriptor, registryMachine),
                actorComponent,
                approvalComponent,
                new StateMachineComponentAdapter(docTrailDescriptor, docTrailMachine),
                new StateMachineComponentAdapter(evidenceDescriptor, evidenceMachine,
                        Map.of("get", EvidenceContract.GET_QUERY_PATH)));
        List<ComponentGeneration> generations = components.stream()
                .map(component -> component.descriptor().generation()).toList();

        WorkflowDescriptor notifyDescriptor = new WorkflowDescriptor(
                RoleEvidenceNotifyWorkflow.ID, "1.0.0", RoleEvidenceNotifyWorkflow.TOPIC,
                1, 0, List.of(generations.get(4)), capacity.notificationWorkflowEffects());
        WorkflowDescriptor roleDescriptor = new WorkflowDescriptor(
                RoleApprovalWorkflow.WORKFLOW_ID, RoleApprovalWorkflow.PRODUCT_VERSION,
                RoleApprovalWorkflow.TOPIC, 1, 0,
                List.of(generations.get(1), generations.get(2)), 0);
        WorkflowDescriptor releaseDescriptor = new WorkflowDescriptor(
                RoleEvidenceReleaseWorkflow.ID, RoleEvidenceReleaseWorkflow.PRODUCT_VERSION,
                RoleEvidenceReleaseWorkflow.TOPIC, 1, 0,
                List.of(generations.get(0), generations.get(2),
                        generations.get(3), generations.get(4)),
                capacity.releaseWorkflowEffects());
        List<CompositeWorkflow> workflows = List.of(
                new RoleEvidenceNotifyWorkflow(notifyDescriptor, generations.get(4), evidenceMachine),
                new RoleApprovalWorkflow(roleDescriptor, generations.get(1), generations.get(2),
                        context.chainId(), governance),
                new RoleEvidenceReleaseWorkflow(releaseDescriptor, generations.get(0),
                        generations.get(2), generations.get(3), generations.get(4),
                        docTrailMachine, evidenceMachine));
        CompositeProfile profile = new CompositeProfile(1, PROFILE_ID, "1.0.0",
                components.stream().map(CompositeComponent::descriptor).toList(),
                List.of(notifyDescriptor, roleDescriptor, releaseDescriptor),
                List.of(new LegacyQueryAlias(
                        EvidenceContract.GET_QUERY_PATH, EVIDENCE_ID, "get")),
                AggregateQueryLimitsV1.DEFAULT);
        return CompositeStateMachine.create(RoleEvidenceStateMachineProvider.ID,
                context, profile, components, workflows);
    }

    private static ComponentDescriptor descriptor(String id, String configurationId,
                                                  List<String> topics,
                                                  List<String> queries, int effectQuota) {
        return new ComponentDescriptor(id, "1.0.0", configurationId,
                id + "-state-v1", 1, 0, topics, queries, effectQuota);
    }

    private static EvidenceWorkflowCapacityV1 capacity(Map<String, String> settings) {
        String value = settings.getOrDefault(
                "machines.composite.evidence-capacity-per-block",
                Integer.toString(EvidenceWorkflowCapacityV1.DEFAULT_CAPACITY)).trim();
        try {
            return new EvidenceWorkflowCapacityV1(Integer.parseInt(value));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "invalid machines.composite.evidence-capacity-per-block", invalid);
        }
    }
}
