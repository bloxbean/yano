package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeComponent;
import com.bloxbean.cardano.yano.appchain.composite.CompositeProfile;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;

import java.util.List;
import java.util.Objects;

/** Explicit committed assembly for the generic role-approvals-v1 profile. */
public final class RoleApprovalsPreset {
    public static final String PROFILE_ID = "role-approvals-v1";
    public static final String PROFILE_VERSION = "1.0.0";

    private RoleApprovalsPreset() {
    }

    public static CompositeStateMachine create(AppStateMachineContext context) {
        Objects.requireNonNull(context, "context");
        RoleWorkflowGovernanceConfig governance = RoleWorkflowGovernanceConfig.from(context);

        ComponentDescriptor actorsDescriptor = new ComponentDescriptor(
                DomainActorRegistryComponent.COMPONENT_ID, PROFILE_VERSION,
                governance.configurationId(), "domain-actors-state-v1", 1, 0,
                List.of(DomainActorRegistryComponent.TOPIC),
                List.of(DomainActorRegistryComponent.QUERY_ACTOR,
                        DomainActorRegistryComponent.QUERY_ACTOR_CURRENT,
                        DomainActorRegistryComponent.QUERY_ORGANIZATION,
                        DomainActorRegistryComponent.QUERY_ORGANIZATION_CURRENT), 0);
        ComponentDescriptor approvalsDescriptor = new ComponentDescriptor(
                RoleAwareApprovalsComponent.COMPONENT_ID, PROFILE_VERSION,
                governance.configurationId(), "role-approvals-state-v1", 1, 0,
                List.of(), List.of(RoleAwareApprovalsComponent.QUERY_POLICY,
                        RoleAwareApprovalsComponent.QUERY_POLICY_CURRENT,
                        RoleAwareApprovalsComponent.QUERY_PROPOSAL,
                        RoleAwareApprovalsComponent.QUERY_STATS), 0);

        DomainActorRegistryComponent actors = new DomainActorRegistryComponent(
                actorsDescriptor, context.chainId(), governance);
        RoleAwareApprovalsComponent approvals = new RoleAwareApprovalsComponent(
                approvalsDescriptor);
        List<CompositeComponent> components = List.of(actors, approvals);
        ComponentGeneration actorsGeneration = actorsDescriptor.generation();
        ComponentGeneration approvalsGeneration = approvalsDescriptor.generation();
        WorkflowDescriptor workflowDescriptor = new WorkflowDescriptor(
                RoleApprovalWorkflow.WORKFLOW_ID, RoleApprovalWorkflow.PRODUCT_VERSION,
                RoleApprovalWorkflow.TOPIC, 1, 0,
                List.of(actorsGeneration, approvalsGeneration), 0);
        RoleApprovalWorkflow workflow = new RoleApprovalWorkflow(workflowDescriptor,
                actorsGeneration, approvalsGeneration, context.chainId(), governance);
        List<CompositeWorkflow> workflows = List.of(workflow);
        CompositeProfile profile = new CompositeProfile(CompositeProfile.SCHEMA_VERSION,
                PROFILE_ID, PROFILE_VERSION,
                components.stream().map(CompositeComponent::descriptor).toList(),
                List.of(workflowDescriptor), List.of(), AggregateQueryLimitsV1.DEFAULT);
        return CompositeStateMachine.create(RoleApprovalsStateMachineProvider.ID,
                context, profile, components, workflows);
    }
}
