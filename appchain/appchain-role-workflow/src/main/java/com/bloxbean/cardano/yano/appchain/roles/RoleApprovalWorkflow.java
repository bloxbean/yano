package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.composite.ComponentGeneration;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflow;
import com.bloxbean.cardano.yano.appchain.composite.CompositeWorkflowContext;
import com.bloxbean.cardano.yano.appchain.composite.WorkflowDescriptor;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorStatementV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedMutationCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.PolicyMutationV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleApprovalStatsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowLimits;
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedActorCommandV1;
import com.bloxbean.cardano.yano.appchain.roles.internal.GovernedMutationProcessor;
import com.bloxbean.cardano.yano.appchain.roles.internal.OverlayState;
import com.bloxbean.cardano.yano.appchain.roles.internal.RoleState;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Atomic registry-read + policy/proposal-write role authorization workflow. */
public final class RoleApprovalWorkflow implements CompositeWorkflow {
    public static final String WORKFLOW_ID = "role-approval";
    public static final String TOPIC = "role-approvals.command.v1";
    public static final String PRODUCT_VERSION = "1.0.0";

    private final WorkflowDescriptor descriptor;
    private final ComponentGeneration registry;
    private final ComponentGeneration approvals;
    private final String chainId;
    private final GovernedMutationProcessor governance;

    public RoleApprovalWorkflow(WorkflowDescriptor descriptor,
                                ComponentGeneration registry,
                                ComponentGeneration approvals,
                                String chainId,
                                RoleWorkflowGovernanceConfig governanceConfig) {
        this.descriptor = java.util.Objects.requireNonNull(descriptor, "descriptor");
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.approvals = java.util.Objects.requireNonNull(approvals, "approvals");
        this.chainId = RoleWorkflowIdentifiers.id(chainId, "chainId");
        this.governance = new GovernedMutationProcessor(governanceConfig);
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
    public void apply(AppBlock block, CompositeWorkflowContext context) {
        AppStateWriter registryState = context.state(registry);
        OverlayState approvalState = new OverlayState(context.state(approvals));
        GovernedMutationProcessor.MutationHandler handler = policyHandler();
        for (AppMessage message : block.messages()) {
            try {
                Object command = decode(message.getBody());
                if (command instanceof GovernedMutationCommandV1 governed) {
                    governance.apply(governed, message.getSender(), block.height(),
                            approvalState, handler);
                } else {
                    applyActor((SignedActorCommandV1) command, block.height(),
                            registryState, approvalState);
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

            @Override public boolean activate(byte[] mutation, long height,
                                              AppStateWriter state) {
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
                String proposalId = ((PolicyMutationV1.CancelProposal) parsed).proposalId();
                ApprovalProposalV1 proposal = proposal(state, proposalId);
                if (proposal == null
                        || proposal.status() != ApprovalProposalV1.ProposalStatus.PENDING) {
                    return false;
                }
                transition(proposal, ApprovalProposalV1.ProposalStatus.CANCELLED,
                        proposal.decisions(), state);
                return true;
            }
        };
    }

    private void applyActor(SignedActorCommandV1 command, long height,
                            AppStateWriter registryState, AppStateWriter approvalState) {
        ActorStatementV1 statement = command.statement();
        if (!statement.chainId().equals(chainId)) return;
        ApprovalProposalV1 existing = proposal(approvalState, statement.proposalId());
        if (existing != null && existing.status() == ApprovalProposalV1.ProposalStatus.PENDING
                && height > existing.deadlineHeight()) {
            transition(existing, ApprovalProposalV1.ProposalStatus.EXPIRED,
                    existing.decisions(), approvalState);
            return;
        }
        ActorEligibility actor = actorEligibility(registryState, statement, height);
        if (actor == null || !command.verify(actor.key.publicKey())) return;
        if (statement.action() == ActorStatementV1.Action.PROPOSE) {
            propose(statement, actor, existing, height, approvalState);
            return;
        }
        if (existing == null || existing.status() != ApprovalProposalV1.ProposalStatus.PENDING
                || !matches(existing, statement)) return;
        if (statement.action() == ActorStatementV1.Action.CANCEL) {
            if (existing.proposerActorId().equals(statement.actorId())) {
                transition(existing, ApprovalProposalV1.ProposalStatus.CANCELLED,
                        existing.decisions(), approvalState);
            }
            return;
        }
        decide(command, actor, existing, height, approvalState);
    }

    private void propose(ActorStatementV1 statement, ActorEligibility actor,
                         ApprovalProposalV1 existing, long height,
                         AppStateWriter approvalState) {
        if (existing != null || statement.deadlineHeight() <= height) return;
        long currentPolicy = RoleState.pointer(approvalState,
                RoleWorkflowKeys.policyCurrent(statement.policyId()));
        if (currentPolicy != statement.policyRevision()) return;
        ApprovalPolicyV1 policy = policy(approvalState,
                statement.policyId(), statement.policyRevision());
        if (policy == null) return;
        long maximumDeadline;
        try {
            maximumDeadline = Math.addExact(height, policy.maximumLifetimeBlocks());
        } catch (ArithmeticException exhausted) {
            maximumDeadline = Long.MAX_VALUE;
        }
        if (statement.deadlineHeight() > maximumDeadline) return;
        RoleApprovalStatsV1 currentStats = stats(approvalState);
        if (currentStats.pending() >= RoleWorkflowLimits.MAX_PENDING_PROPOSALS) return;
        String proposerRole = policy.proposerRoles().isEmpty()
                ? actor.actor.roles().getFirst()
                : policy.proposerRoles().stream().filter(actor.actor.roles()::contains)
                .findFirst().orElse(null);
        if (proposerRole == null) return;
        ApprovalProposalV1 proposal = new ApprovalProposalV1(statement.proposalId(),
                statement.policyId(), statement.policyRevision(), policy.digest(),
                statement.payloadDomain(), statement.payloadHash(), statement.deadlineHeight(),
                ApprovalProposalV1.ProposalStatus.PENDING, statement.actorId(),
                actor.actor.organizationId(), actor.organization.revision(), proposerRole,
                statement.actorRevision(), statement.keyId(), height, List.of());
        RoleApprovalStatsV1 nextStats = currentStats.proposalCreated();
        approvalState.put(RoleWorkflowKeys.proposal(statement.proposalId()), proposal.encode());
        writeStats(approvalState, nextStats);
    }

    private void decide(SignedActorCommandV1 command, ActorEligibility actor,
                        ApprovalProposalV1 proposal, long height,
                        AppStateWriter approvalState) {
        ActorStatementV1 statement = command.statement();
        ApprovalPolicyV1 policy = policy(approvalState,
                proposal.policyId(), proposal.policyRevision());
        if (policy == null || !MessageDigest.isEqual(policy.digest(), proposal.policyDigest())) {
            throw new IllegalStateException("proposal policy revision is missing or corrupt");
        }
        ApprovalPolicyV1.RequiredClause clause = policy.clause(statement.clauseId());
        if (clause == null || !actor.actor.roles().contains(clause.role())
                || proposal.decisions().stream().anyMatch(
                decision -> decision.actorId().equals(statement.actorId()))) return;
        if (clause.distinctBy() == ApprovalPolicyV1.DistinctBy.ORGANIZATION
                && proposal.decisions().stream().anyMatch(decision ->
                decision.clauseId().equals(clause.clauseId())
                        && decision.organizationId().equals(actor.actor.organizationId()))) return;
        if (statement.action() == ActorStatementV1.Action.REJECT
                && policy.rejectionMode() == ApprovalPolicyV1.RejectionMode.DISABLED) return;
        ApprovalProposalV1.AcceptedDecisionV1 decision =
                new ApprovalProposalV1.AcceptedDecisionV1(statement.action(),
                        statement.actorId(), actor.actor.organizationId(),
                        actor.organization.revision(), clause.role(), statement.actorRevision(),
                        statement.keyId(), statement.clauseId(), statement.digest(),
                        command.signature(), height);
        ArrayList<ApprovalProposalV1.AcceptedDecisionV1> decisions =
                new ArrayList<>(proposal.decisions());
        decisions.add(decision);
        ApprovalProposalV1.ProposalStatus status = statement.action()
                == ActorStatementV1.Action.REJECT
                ? ApprovalProposalV1.ProposalStatus.REJECTED
                : (satisfied(policy, decisions)
                ? ApprovalProposalV1.ProposalStatus.APPROVED
                : ApprovalProposalV1.ProposalStatus.PENDING);
        if (status == ApprovalProposalV1.ProposalStatus.PENDING) {
            approvalState.put(RoleWorkflowKeys.proposal(proposal.proposalId()),
                    copy(proposal, status, decisions).encode());
        } else {
            transition(proposal, status, decisions, approvalState);
        }
    }

    private static boolean satisfied(ApprovalPolicyV1 policy,
                                     List<ApprovalProposalV1.AcceptedDecisionV1> decisions) {
        for (ApprovalPolicyV1.RequiredClause clause : policy.clauses()) {
            long count = decisions.stream()
                    .filter(decision -> decision.action() == ActorStatementV1.Action.APPROVE)
                    .filter(decision -> decision.clauseId().equals(clause.clauseId()))
                    .map(decision -> clause.distinctBy() == ApprovalPolicyV1.DistinctBy.ACTOR
                            ? decision.actorId() : decision.organizationId())
                    .distinct().count();
            if (count < clause.minimumCount()) return false;
        }
        return true;
    }

    private ActorEligibility actorEligibility(AppStateWriter registryState,
                                              ActorStatementV1 statement, long height) {
        long current = RoleState.pointer(registryState,
                RoleWorkflowKeys.actorCurrent(statement.actorId()));
        if (current != statement.actorRevision()) return null;
        ActorRecordV1 actor = actor(registryState, statement.actorId(), current);
        if (actor == null || actor.status() != RecordStatus.ACTIVE) return null;
        long organizationRevision = RoleState.pointer(registryState,
                RoleWorkflowKeys.organizationCurrent(actor.organizationId()));
        OrganizationRecordV1 organization = organization(
                registryState, actor.organizationId(), organizationRevision);
        ActorKeyEpochV1 key = actor.key(statement.keyId());
        if (organization == null || organization.status() != RecordStatus.ACTIVE
                || key == null || !key.activeAt(height)) return null;
        return new ActorEligibility(actor, organization, key);
    }

    private static boolean matches(ApprovalProposalV1 proposal, ActorStatementV1 statement) {
        return proposal.policyId().equals(statement.policyId())
                && proposal.policyRevision() == statement.policyRevision()
                && proposal.payloadDomain().equals(statement.payloadDomain())
                && MessageDigest.isEqual(proposal.payloadHash(), statement.payloadHash())
                && proposal.deadlineHeight() == statement.deadlineHeight();
    }

    private static ApprovalPolicyV1 policy(AppStateWriter state, String id, long revision) {
        byte[] encoded = state.get(RoleWorkflowKeys.policyRevision(id, revision)).orElse(null);
        if (encoded == null) return null;
        try { return ApprovalPolicyV1.decode(encoded); }
        catch (RuntimeException corrupt) { throw new IllegalStateException("corrupt policy", corrupt); }
    }

    private static ApprovalProposalV1 proposal(AppStateWriter state, String id) {
        byte[] encoded = state.get(RoleWorkflowKeys.proposal(id)).orElse(null);
        if (encoded == null) return null;
        try { return ApprovalProposalV1.decode(encoded); }
        catch (RuntimeException corrupt) { throw new IllegalStateException("corrupt proposal", corrupt); }
    }

    private static ActorRecordV1 actor(AppStateWriter state, String id, long revision) {
        if (revision == 0) return null;
        byte[] encoded = state.get(RoleWorkflowKeys.actorRevision(id, revision)).orElse(null);
        if (encoded == null) throw new IllegalStateException("actor pointer is dangling");
        try { return ActorRecordV1.decode(encoded); }
        catch (RuntimeException corrupt) { throw new IllegalStateException("corrupt actor", corrupt); }
    }

    private static OrganizationRecordV1 organization(
            AppStateWriter state, String id, long revision) {
        if (revision == 0) return null;
        byte[] encoded = state.get(
                RoleWorkflowKeys.organizationRevision(id, revision)).orElse(null);
        if (encoded == null) throw new IllegalStateException("organization pointer is dangling");
        try { return OrganizationRecordV1.decode(encoded); }
        catch (RuntimeException corrupt) { throw new IllegalStateException("corrupt organization", corrupt); }
    }

    private static ApprovalProposalV1 copy(
            ApprovalProposalV1 proposal, ApprovalProposalV1.ProposalStatus status,
            List<ApprovalProposalV1.AcceptedDecisionV1> decisions) {
        return new ApprovalProposalV1(proposal.proposalId(), proposal.policyId(),
                proposal.policyRevision(), proposal.policyDigest(), proposal.payloadDomain(),
                proposal.payloadHash(), proposal.deadlineHeight(), status,
                proposal.proposerActorId(), proposal.proposerOrganizationId(),
                proposal.proposerOrganizationRevision(), proposal.proposerRole(),
                proposal.proposerActorRevision(), proposal.proposerKeyId(),
                proposal.createdHeight(), decisions);
    }

    private static void transition(ApprovalProposalV1 proposal,
                                   ApprovalProposalV1.ProposalStatus status,
                                   List<ApprovalProposalV1.AcceptedDecisionV1> decisions,
                                   AppStateWriter state) {
        if (proposal.status() != ApprovalProposalV1.ProposalStatus.PENDING
                || status == ApprovalProposalV1.ProposalStatus.PENDING) {
            throw new IllegalStateException("invalid proposal status transition");
        }
        ApprovalProposalV1 successor = copy(proposal, status, decisions);
        RoleApprovalStatsV1 nextStats = stats(state).terminal(status);
        state.put(RoleWorkflowKeys.proposal(proposal.proposalId()), successor.encode());
        writeStats(state, nextStats);
    }

    private static RoleApprovalStatsV1 stats(AppStateWriter state) {
        byte[] encoded = state.get(RoleWorkflowKeys.approvalStats()).orElse(null);
        if (encoded == null) return RoleApprovalStatsV1.empty();
        try {
            return RoleApprovalStatsV1.decode(encoded);
        } catch (RuntimeException corrupt) {
            throw new IllegalStateException("corrupt role approval statistics", corrupt);
        }
    }

    private static void writeStats(AppStateWriter state, RoleApprovalStatsV1 stats) {
        state.put(RoleWorkflowKeys.approvalStats(), stats.encode());
    }

    private record ActorEligibility(ActorRecordV1 actor,
                                    OrganizationRecordV1 organization,
                                    ActorKeyEpochV1 key) {
    }
}
