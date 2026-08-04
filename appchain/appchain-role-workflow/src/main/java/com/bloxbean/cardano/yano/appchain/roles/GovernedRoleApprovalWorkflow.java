package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflowContext;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorGovernanceCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedGenesisV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.PolicyMutationV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.internal.ActorGovernanceProcessor;
import com.bloxbean.cardano.yano.appchain.roles.internal.RoleState;

import java.util.List;
import java.util.Objects;

/**
 * Genesis-bound role-approval route for the authenticated-map v1 assembly.
 * Actor-authenticated policy-governance route for the authenticated-map profile.
 */
public final class GovernedRoleApprovalWorkflow implements CompositeWorkflow {
    public static final String WORKFLOW_ID = "role-approval-v1";
    public static final String TOPIC = "role-approvals.command.v1";
    public static final String PRODUCT_VERSION = "1.0.0";

    private final WorkflowDescriptor descriptor;
    private final ComponentGeneration actors;
    private final ComponentGeneration approvals;
    private final ActorGovernanceProcessor governance;

    public GovernedRoleApprovalWorkflow(
            WorkflowDescriptor descriptor,
            ComponentGeneration actors,
            ComponentGeneration approvals,
            String chainId,
            byte[] authenticatedMapGenesisId,
            GovernedGenesisV1 genesis
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        if (!WORKFLOW_ID.equals(descriptor.workflowId())
                || !TOPIC.equals(descriptor.topic())
                || !descriptor.participants().equals(List.of(actors, approvals))) {
            throw new IllegalArgumentException("invalid governed role-approval descriptor");
        }
        this.governance = new ActorGovernanceProcessor(chainId,
                authenticatedMapGenesisId,
                genesis.administratorAuthority().authorityId(), genesis.limits());
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage routedMessage) {
        try {
            ActorGovernanceCommandV1 command =
                    ActorGovernanceCommandV1.decode(routedMessage.getBody());
            if (command.authorizations().stream().anyMatch(
                    authorization -> !authorization.verifyClaimedKey())) {
                return AppStateMachine.AdmissionResult.reject("INVALID_SIGNATURE");
            }
            if (command.operation() == ActorGovernanceCommandV1.Operation.PROPOSE) {
                PolicyMutationV1.decode(command.mutation());
            }
            return AppStateMachine.AdmissionResult.accept();
        } catch (RuntimeException malformed) {
            return AppStateMachine.AdmissionResult.reject("INVALID_PAYLOAD");
        }
    }

    @Override
    public void apply(AppBlock routedBlock, CompositeWorkflowContext context) {
        var actorState = context.state(actors);
        var approvalState = context.state(approvals);
        governance.prepareHeight(routedBlock.height(), actorState, approvalState);
        ActorGovernanceProcessor.MutationHandler handler = policyGovernanceHandler();
        for (AppMessage message : routedBlock.messages()) {
            try {
                governance.apply(ActorGovernanceCommandV1.decode(message.getBody()),
                        routedBlock.height(), actorState, approvalState, handler);
            } catch (IllegalArgumentException malformed) {
                // Canonically malformed finalized commands are deterministic no-ops.
            }
        }
    }

    private static ActorGovernanceProcessor.MutationHandler policyGovernanceHandler() {
        return new ActorGovernanceProcessor.MutationHandler() {
            @Override
            public void validate(
                    byte[] encoded,
                    AppStateWriter actorState,
                    AppStateWriter approvalState
            ) {
                if (!canActivate(PolicyMutationV1.decode(encoded), approvalState)) {
                    throw new IllegalArgumentException("policy mutation is not activatable");
                }
            }

            @Override
            public boolean activate(
                    byte[] encoded,
                    long height,
                    AppStateWriter actorState,
                    AppStateWriter approvalState
            ) {
                PolicyMutationV1 mutation = PolicyMutationV1.decode(encoded);
                if (!canActivate(mutation, approvalState)) return false;
                if (mutation instanceof PolicyMutationV1.PutDirectPolicy put) {
                    DirectRolePolicyV1 policy = put.policy();
                    approvalState.put(RoleWorkflowKeys.directPolicyRevision(
                            policy.policyId(), policy.revision()), policy.encode());
                    RoleState.pointer(approvalState,
                            RoleWorkflowKeys.directPolicyCurrent(policy.policyId()),
                            policy.revision());
                    return true;
                }
                if (mutation instanceof PolicyMutationV1.PutPolicy put) {
                    ApprovalPolicyV1 policy = put.policy();
                    approvalState.put(RoleWorkflowKeys.policyRevision(
                            policy.policyId(), policy.revision()), policy.encode());
                    RoleState.pointer(approvalState,
                            RoleWorkflowKeys.policyCurrent(policy.policyId()),
                            policy.revision());
                    return true;
                }
                return false;
            }
        };
    }

    private static boolean canActivate(
            PolicyMutationV1 mutation,
            AppStateWriter state
    ) {
        if (mutation instanceof PolicyMutationV1.PutDirectPolicy put) {
            DirectRolePolicyV1 policy = put.policy();
            return RoleState.pointer(state,
                    RoleWorkflowKeys.policyCurrent(policy.policyId())) == 0
                    && policy.revision() == RoleState.pointer(state,
                    RoleWorkflowKeys.directPolicyCurrent(policy.policyId())) + 1;
        }
        if (mutation instanceof PolicyMutationV1.PutPolicy put) {
            ApprovalPolicyV1 policy = put.policy();
            return RoleState.pointer(state,
                    RoleWorkflowKeys.directPolicyCurrent(policy.policyId())) == 0
                    && policy.revision() == RoleState.pointer(state,
                    RoleWorkflowKeys.policyCurrent(policy.policyId())) + 1;
        }
        return false;
    }
}
