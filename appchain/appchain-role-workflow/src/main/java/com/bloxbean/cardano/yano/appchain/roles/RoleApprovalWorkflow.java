package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflowContext;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedAuthorizationLimitsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedMutationCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.PolicyMutationV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedActorCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.internal.ActorApprovalProcessor;
import com.bloxbean.cardano.yano.appchain.roles.internal.GovernedMutationProcessor;
import com.bloxbean.cardano.yano.appchain.roles.internal.OverlayState;
import com.bloxbean.cardano.yano.appchain.roles.internal.RoleState;

import java.util.List;
import java.util.Objects;

/** Membership-governed policy route over the shared ADR-019 actor approval lifecycle. */
public final class RoleApprovalWorkflow implements CompositeWorkflow {
    public static final String WORKFLOW_ID = "role-approval";
    public static final String TOPIC = SignedActorCommandV1.DEFAULT_TOPIC;
    public static final String PRODUCT_VERSION = "2.0.0";

    private final WorkflowDescriptor descriptor;
    private final ComponentGeneration registry;
    private final ComponentGeneration approvals;
    private final GovernedMutationProcessor governance;
    private final ActorApprovalProcessor actorApprovals;

    public RoleApprovalWorkflow(WorkflowDescriptor descriptor,
                                ComponentGeneration registry,
                                ComponentGeneration approvals,
                                String chainId,
                                RoleWorkflowGovernanceConfig governanceConfig) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.governance = new GovernedMutationProcessor(governanceConfig);
        this.actorApprovals = new ActorApprovalProcessor(
                chainId, GovernedAuthorizationLimitsV1.defaults());
        if (!descriptor.workflowId().equals(WORKFLOW_ID)
                || !descriptor.topic().equals(TOPIC)
                || !descriptor.participants().equals(List.of(registry, approvals))) {
            throw new IllegalArgumentException("invalid role approval workflow descriptor");
        }
    }

    @Override public WorkflowDescriptor descriptor() { return descriptor; }

    @Override
    public AppStateMachine.AdmissionResult validate(AppMessage message) {
        try {
            decode(message.getBody());
            return AppStateMachine.AdmissionResult.accept();
        } catch (RuntimeException malformed) {
            return AppStateMachine.AdmissionResult.reject("INVALID_ROLE_APPROVAL_COMMAND");
        }
    }

    @Override
    public void apply(AppBlockExecutionContext execution, CompositeWorkflowContext context) {
        AppBlock block = execution.block();
        AppStateWriter registryState = context.state(registry);
        OverlayState approvalState = new OverlayState(context.state(approvals));
        actorApprovals.prepareHeight(block.height(), approvalState);
        GovernedMutationProcessor.MutationHandler handler = policyHandler();
        for (AppMessage message : execution.messages()) {
            try {
                Object command = decode(message.getBody());
                if (command instanceof GovernedMutationCommandV1 governed) {
                    governance.apply(governed, message.getSender(), block.height(),
                            approvalState, handler);
                } else {
                    actorApprovals.apply((SignedActorCommandV1) command,
                            block.height(), registryState, approvalState);
                }
            } catch (IllegalArgumentException malformed) {
                // Full validation is repeated; malformed finalized commands are no-ops.
            }
        }
    }

    private Object decode(byte[] body) {
        try {
            GovernedMutationCommandV1 command = GovernedMutationCommandV1.decode(body);
            if (command instanceof GovernedMutationCommandV1.Propose proposed) {
                PolicyMutationV1.decode(proposed.mutation());
            }
            return command;
        } catch (IllegalArgumentException notGovernance) {
            return SignedActorCommandV1.decode(body);
        }
    }

    private GovernedMutationProcessor.MutationHandler policyHandler() {
        return new GovernedMutationProcessor.MutationHandler() {
            @Override public void validate(byte[] mutation) { PolicyMutationV1.decode(mutation); }

            @Override
            public boolean activate(byte[] mutation, long height, AppStateWriter state) {
                PolicyMutationV1 parsed = PolicyMutationV1.decode(mutation);
                if (parsed instanceof PolicyMutationV1.PutPolicy put) {
                    ApprovalPolicyV1 policy = put.policy();
                    long current = RoleState.pointer(state,
                            RoleWorkflowKeys.policyCurrent(policy.policyId()));
                    if (policy.revision() != current + 1) return false;
                    state.put(RoleWorkflowKeys.policyRevision(
                            policy.policyId(), policy.revision()), policy.encode());
                    RoleState.pointer(state, RoleWorkflowKeys.policyCurrent(
                            policy.policyId()), policy.revision());
                    return true;
                }
                return actorApprovals.cancelByGovernance(
                        ((PolicyMutationV1.CancelProposal) parsed).proposalId(), state);
            }
        };
    }
}
