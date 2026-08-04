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
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedActorCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedGenesisV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.PolicyMutationV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleCommandResultV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowResultCode;
import com.bloxbean.cardano.yano.appchain.roles.internal.ActorGovernanceProcessor;
import com.bloxbean.cardano.yano.appchain.roles.internal.ActorApprovalProcessor;
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
    private final ActorApprovalProcessor actorApprovals;

    public GovernedRoleApprovalWorkflow(
            WorkflowDescriptor descriptor,
            ComponentGeneration actors,
            ComponentGeneration approvals,
            String chainId,
            byte[] authenticatedMapGenesisId,
            GovernedGenesisV1 genesis,
            String approvalPayloadDomain
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
        this.actorApprovals = new ActorApprovalProcessor(
                chainId, approvalPayloadDomain, genesis.limits());
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage routedMessage) {
        try {
            Object decoded = decode(routedMessage.getBody());
            if (decoded instanceof ActorGovernanceCommandV1 command) {
                if (command.authorizations().stream().anyMatch(
                        authorization -> !authorization.verifyClaimedKey())) {
                    return AppStateMachine.AdmissionResult.reject("INVALID_SIGNATURE");
                }
                if (command.operation() == ActorGovernanceCommandV1.Operation.PROPOSE) {
                    PolicyMutationV1.decode(command.mutation());
                }
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
        actorApprovals.prepareHeight(routedBlock.height(), approvalState);
        ActorGovernanceProcessor.MutationHandler handler = policyGovernanceHandler();
        for (AppMessage message : routedBlock.messages()) {
            byte[] resultKey = RoleWorkflowKeys.commandResult(message.getMessageId());
            if (approvalState.get(resultKey).isPresent()) continue;
            try {
                Object command = decode(message.getBody());
                RoleWorkflowResultCode result;
                String subjectId;
                int commandKind;
                if (command instanceof ActorGovernanceCommandV1 governed) {
                    result = governance.apply(governed, routedBlock.height(),
                            actorState, approvalState, handler);
                    subjectId = governed.mutationId();
                    commandKind = RoleCommandResultV1.KIND_POLICY_GOVERNANCE;
                } else {
                    SignedActorCommandV1 actor = (SignedActorCommandV1) command;
                    result = actorApprovals.apply(actor,
                            routedBlock.height(), actorState, approvalState);
                    subjectId = actor.statement().proposalId();
                    commandKind = RoleCommandResultV1.KIND_APPROVAL;
                }
                approvalState.put(resultKey, new RoleCommandResultV1(
                        commandKind, subjectId, result, routedBlock.height(),
                        message.getMessageId(), RoleCommandResultV1.commandDigest(
                        message.getBody())).encode());
            } catch (IllegalArgumentException malformed) {
                // Canonically malformed finalized commands are deterministic no-ops.
            }
        }
    }

    private Object decode(byte[] body) {
        try {
            return ActorGovernanceCommandV1.decode(body);
        } catch (IllegalArgumentException notGovernance) {
            return SignedActorCommandV1.decode(body);
        }
    }

    private ActorGovernanceProcessor.MutationHandler policyGovernanceHandler() {
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
                PolicyMutationV1.CancelProposal cancel =
                        (PolicyMutationV1.CancelProposal) mutation;
                return actorApprovals.cancelByGovernance(
                        cancel.proposalId(), approvalState);
            }
        };
    }

    private boolean canActivate(
            PolicyMutationV1 mutation,
            AppStateWriter state
    ) {
        if (mutation instanceof PolicyMutationV1.PutDirectPolicy put) {
            DirectRolePolicyV1 policy = put.policy();
            return RoleState.pointer(state,
                    RoleWorkflowKeys.policyCurrent(policy.policyId())) == 0
                    && policy.revision() == RoleState.pointer(state,
                    RoleWorkflowKeys.directPolicyCurrent(policy.policyId())) + 1
                    && state.get(RoleWorkflowKeys.directPolicyRevision(
                    policy.policyId(), policy.revision())).isEmpty();
        }
        if (mutation instanceof PolicyMutationV1.PutPolicy put) {
            ApprovalPolicyV1 policy = put.policy();
            return RoleState.pointer(state,
                    RoleWorkflowKeys.directPolicyCurrent(policy.policyId())) == 0
                    && policy.revision() == RoleState.pointer(state,
                    RoleWorkflowKeys.policyCurrent(policy.policyId())) + 1
                    && state.get(RoleWorkflowKeys.policyRevision(
                    policy.policyId(), policy.revision())).isEmpty();
        }
        PolicyMutationV1.CancelProposal cancel =
                (PolicyMutationV1.CancelProposal) mutation;
        return actorApprovals.canCancelByGovernance(cancel.proposalId(), state);
    }
}
