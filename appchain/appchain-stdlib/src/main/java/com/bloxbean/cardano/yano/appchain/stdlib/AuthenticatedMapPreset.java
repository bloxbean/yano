package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.appchain.composite.ComponentDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeComponent;
import com.bloxbean.cardano.yano.appchain.composite.CompositeProfile;
import com.bloxbean.cardano.yano.appchain.composite.CompositeStateMachine;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.LegacyQueryAlias;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;
import com.bloxbean.cardano.yano.appchain.roles.DomainActorRegistryComponent;
import com.bloxbean.cardano.yano.appchain.roles.GovernedRoleApprovalWorkflow;
import com.bloxbean.cardano.yano.appchain.roles.RoleAwareApprovalsComponent;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedGenesisV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Committed composite assembly behind the unchanged authenticated-map selector. */
public final class AuthenticatedMapPreset {
    public static final String PROFILE_ID = "authenticated-map-v1";
    public static final String PROFILE_VERSION = "1.0.0";

    private AuthenticatedMapPreset() {
    }

    public static CompositeStateMachine create(
            AppStateMachineContext context,
            AuthenticatedMapContract.Genesis genesis
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(genesis, "genesis");
        GovernedGenesisV1 governedGenesis = genesis.governedGenesis();
        boolean governed = governedGenesis != null;
        String configurationId = "authenticated-map-genesis-v4:"
                + HexFormat.of().formatHex(AuthenticatedMapContract.genesisId(genesis));

        ComponentDescriptor actorsDescriptor = new ComponentDescriptor(
                DomainActorRegistryComponent.COMPONENT_ID, PROFILE_VERSION,
                configurationId, "authenticated-map-domain-actors-state-v1", 1, 0,
                governed ? List.of(DomainActorRegistryComponent.TOPIC) : List.of(),
                governed ? actorQueries() : List.of(), 0);
        ComponentDescriptor approvalsDescriptor = new ComponentDescriptor(
                RoleAwareApprovalsComponent.COMPONENT_ID, PROFILE_VERSION,
                configurationId, "authenticated-map-role-approvals-state-v1", 1, 0,
                List.of(), governed ? approvalQueries() : List.of(), 0);
        ComponentDescriptor mapDescriptor = new ComponentDescriptor(
                AuthenticatedMapComponent.COMPONENT_ID, PROFILE_VERSION,
                configurationId, "authenticated-map-state-v1", 1, 0,
                List.of(), mapQueries(governed), 0);

        DomainActorRegistryComponent actors = DomainActorRegistryComponent.genesisBound(
                actorsDescriptor, context.chainId(), governedGenesis,
                AuthenticatedMapContract.genesisId(genesis));
        RoleAwareApprovalsComponent approvals = new RoleAwareApprovalsComponent(
                approvalsDescriptor, governedGenesis);
        AuthenticatedMapComponent map = new AuthenticatedMapComponent(mapDescriptor,
                new AuthenticatedMapStateMachine(genesis,
                        context.membershipView().orElse(null),
                        context.authenticatedMapValidatorResolver().orElse(null)));
        List<CompositeComponent> components = List.of(actors, approvals, map);
        List<ComponentGeneration> generations = components.stream()
                .map(component -> component.descriptor().generation()).toList();

        List<WorkflowDescriptor> workflowDescriptors = new ArrayList<>();
        List<CompositeWorkflow> workflows = new ArrayList<>();
        if (governed) {
            WorkflowDescriptor roleDescriptor = new WorkflowDescriptor(
                    GovernedRoleApprovalWorkflow.WORKFLOW_ID,
                    GovernedRoleApprovalWorkflow.PRODUCT_VERSION,
                    GovernedRoleApprovalWorkflow.TOPIC, 1, 0,
                    List.of(generations.get(0), generations.get(1)), 0);
            workflowDescriptors.add(roleDescriptor);
            workflows.add(new GovernedRoleApprovalWorkflow(roleDescriptor,
                    generations.get(0), generations.get(1), context.chainId(),
                    AuthenticatedMapContract.genesisId(genesis), governedGenesis,
                    AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN));
        }
        WorkflowDescriptor mapWorkflowDescriptor = new WorkflowDescriptor(
                AuthenticatedMapAuthorizationWorkflow.WORKFLOW_ID,
                AuthenticatedMapAuthorizationWorkflow.PRODUCT_VERSION,
                AuthenticatedMapContract.DEFAULT_TOPIC, 1, 0, generations, 0);
        workflowDescriptors.add(mapWorkflowDescriptor);
        workflows.add(new AuthenticatedMapAuthorizationWorkflow(
                mapWorkflowDescriptor, generations.get(0), generations.get(1),
                generations.get(2), map));

        List<LegacyQueryAlias> queryAliases = new ArrayList<>(List.of(
                new LegacyQueryAlias(AuthenticatedMapContract.POINT_QUERY_PATH,
                        AuthenticatedMapComponent.COMPONENT_ID,
                        AuthenticatedMapContract.POINT_QUERY_PATH),
                new LegacyQueryAlias(AuthenticatedMapContract.RECEIPT_QUERY_PATH,
                        AuthenticatedMapComponent.COMPONENT_ID,
                        AuthenticatedMapContract.RECEIPT_QUERY_PATH),
                new LegacyQueryAlias(AuthenticatedMapContract.CAPABILITIES_QUERY_PATH,
                        AuthenticatedMapComponent.COMPONENT_ID,
                        AuthenticatedMapContract.CAPABILITIES_QUERY_PATH)));
        if (governed) {
            queryAliases.add(new LegacyQueryAlias(
                    AuthenticatedMapContract.DIRECT_CONSUMPTION_QUERY_PATH,
                    AuthenticatedMapComponent.COMPONENT_ID,
                    AuthenticatedMapContract.DIRECT_CONSUMPTION_QUERY_PATH));
            queryAliases.add(new LegacyQueryAlias(
                    AuthenticatedMapContract.APPROVAL_CONSUMPTION_QUERY_PATH,
                    AuthenticatedMapComponent.COMPONENT_ID,
                    AuthenticatedMapContract.APPROVAL_CONSUMPTION_QUERY_PATH));
        }
        CompositeProfile profile = new CompositeProfile(
                CompositeProfile.SCHEMA_VERSION, PROFILE_ID, PROFILE_VERSION,
                components.stream().map(CompositeComponent::descriptor).toList(),
                workflowDescriptors,
                queryAliases,
                AggregateQueryLimitsV1.DEFAULT);
        return CompositeStateMachine.create(AuthenticatedMapContract.STATE_MACHINE_ID,
                context, profile, components, workflows);
    }

    private static List<String> actorQueries() {
        return List.of(
                DomainActorRegistryComponent.QUERY_ACTOR,
                DomainActorRegistryComponent.QUERY_ACTOR_CURRENT,
                DomainActorRegistryComponent.QUERY_ORGANIZATION,
                DomainActorRegistryComponent.QUERY_ORGANIZATION_CURRENT,
                DomainActorRegistryComponent.QUERY_AUTHORITY,
                DomainActorRegistryComponent.QUERY_AUTHORITY_CURRENT,
                DomainActorRegistryComponent.QUERY_GOVERNANCE_MUTATION,
                DomainActorRegistryComponent.QUERY_COMMAND_RESULT,
                DomainActorRegistryComponent.QUERY_PENDING_GOVERNANCE);
    }

    private static List<String> approvalQueries() {
        return List.of(
                RoleAwareApprovalsComponent.QUERY_POLICY,
                RoleAwareApprovalsComponent.QUERY_POLICY_CURRENT,
                RoleAwareApprovalsComponent.QUERY_DIRECT_POLICY,
                RoleAwareApprovalsComponent.QUERY_DIRECT_POLICY_CURRENT,
                RoleAwareApprovalsComponent.QUERY_PROPOSAL,
                RoleAwareApprovalsComponent.QUERY_GOVERNANCE_MUTATION,
                RoleAwareApprovalsComponent.QUERY_STATS,
                RoleAwareApprovalsComponent.QUERY_COMMAND_RESULT,
                RoleAwareApprovalsComponent.QUERY_PENDING_APPROVALS,
                RoleAwareApprovalsComponent.QUERY_PENDING_GOVERNANCE);
    }

    private static List<String> mapQueries(boolean governed) {
        List<String> paths = new ArrayList<>(List.of(
                AuthenticatedMapContract.POINT_QUERY_PATH,
                AuthenticatedMapContract.RECEIPT_QUERY_PATH,
                AuthenticatedMapContract.CAPABILITIES_QUERY_PATH));
        if (governed) {
            paths.add(AuthenticatedMapContract.DIRECT_CONSUMPTION_QUERY_PATH);
            paths.add(AuthenticatedMapContract.APPROVAL_CONSUMPTION_QUERY_PATH);
        }
        return List.copyOf(paths);
    }
}
