package com.bloxbean.cardano.yano.appchain.roles.internal;

import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.roles.GovernedCryptoWork;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorStatementV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedAuthorizationLimitsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleApprovalStatsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowResultCode;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RolePendingQueriesV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.SignedActorCommandV1;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Bounded actor-signed approval lifecycle for a governed authenticated-map profile. */
public final class ActorApprovalProcessor {
    private final String chainId;
    private final String payloadDomain;
    private final GovernedAuthorizationLimitsV1 limits;

    /** Product-neutral lifecycle; application bindings verify the exact payload domain on use. */
    public ActorApprovalProcessor(
            String chainId,
            GovernedAuthorizationLimitsV1 limits
    ) {
        this.chainId = RoleWorkflowIdentifiers.chainId(chainId);
        this.payloadDomain = null;
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
    }

    public ActorApprovalProcessor(
            String chainId,
            String payloadDomain,
            GovernedAuthorizationLimitsV1 limits
    ) {
        this.chainId = RoleWorkflowIdentifiers.chainId(chainId);
        this.payloadDomain = RoleWorkflowIdentifiers.payloadDomain(payloadDomain);
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
    }

    public void prepareHeight(long height, AppStateWriter approvalState) {
        ApprovalPendingIndexV1 index = pendingIndex(approvalState);
        List<ApprovalPendingIndexV1.Entry> expired = index.entries().stream()
                .filter(entry -> entry.deadlineHeight() < height).toList();
        if (expired.size() > limits.maximumExpiryWorkPerBlock()) {
            throw new IllegalStateException("approval expiry work exceeds genesis bound");
        }
        ApprovalPendingIndexV1 updated = index;
        for (ApprovalPendingIndexV1.Entry entry : expired) {
            ApprovalProposalV1 due = proposal(approvalState, entry.proposalId());
            if (due == null) {
                throw new IllegalStateException(
                        "approval pending index points to an absent proposal");
            }
            updated = terminal(approvalState, updated, entry,
                    ApprovalProposalV1.ProposalStatus.EXPIRED, due.decisions());
        }
        writeIndex(approvalState, updated);
    }

    public RoleWorkflowResultCode apply(
            SignedActorCommandV1 command,
            long height,
            AppStateWriter actorState,
            AppStateWriter approvalState
    ) {
        ActorStatementV1 statement = command.statement();
        if (!statement.chainId().equals(chainId)
                || payloadDomain != null && !statement.payloadDomain().equals(payloadDomain)) {
            return RoleWorkflowResultCode.WRONG_GENESIS;
        }
        if (statement.deadlineHeight() < height) {
            return RoleWorkflowResultCode.EXPIRED;
        }
        if (!GovernedCryptoWork.reserve(actorState, height, 1,
                limits.maximumCryptoWorkUnitsPerBlock())) {
            return RoleWorkflowResultCode.CRYPTO_WORK_EXCEEDED;
        }
        ActorEligibility actor = actorEligibility(actorState, statement, height);
        if (actor == null) return RoleWorkflowResultCode.UNAUTHORIZED_ACTOR;
        if (!command.verify(actor.key().publicKey())) {
            return RoleWorkflowResultCode.INVALID_SIGNATURE;
        }
        return switch (statement.action()) {
            case PROPOSE -> propose(statement, actor, height, approvalState);
            case APPROVE, REJECT -> decide(
                    command, actor, height, approvalState);
            case CANCEL -> cancel(statement, approvalState);
        };
    }

    public boolean cancelByGovernance(
            String proposalId,
            AppStateWriter approvalState
    ) {
        ApprovalProposalV1 proposal = proposal(approvalState, proposalId);
        if (proposal == null
                || proposal.status() != ApprovalProposalV1.ProposalStatus.PENDING) {
            return false;
        }
        ApprovalPendingIndexV1 index = pendingIndex(approvalState);
        ApprovalPendingIndexV1.Entry entry = indexEntry(index, proposalId);
        writeIndex(approvalState, terminal(approvalState, index, entry,
                ApprovalProposalV1.ProposalStatus.CANCELLED,
                proposal.decisions()));
        return true;
    }

    public boolean canCancelByGovernance(
            String proposalId,
            AppStateWriter approvalState
    ) {
        ApprovalProposalV1 proposal = proposal(approvalState, proposalId);
        return proposal != null
                && proposal.status() == ApprovalProposalV1.ProposalStatus.PENDING;
    }

    public static RolePendingQueriesV1.ApprovalPage pendingPage(
            AppStateReader state,
            RolePendingQueriesV1.PageQuery query,
            int maximumPageSize
    ) {
        if (query.limit() > maximumPageSize) {
            throw new IllegalArgumentException("approval page exceeds genesis limit");
        }
        List<ApprovalPendingIndexV1.Entry> remaining = pendingIndex(state).entries()
                .stream().filter(entry -> query.afterId().isEmpty()
                        || entry.proposalId().compareTo(query.afterId()) > 0)
                .toList();
        List<ApprovalPendingIndexV1.Entry> selected = remaining.stream()
                .limit(query.limit()).toList();
        String next = remaining.size() > selected.size() && !selected.isEmpty()
                ? selected.getLast().proposalId() : "";
        return new RolePendingQueriesV1.ApprovalPage(selected.stream()
                .map(entry -> new RolePendingQueriesV1.ApprovalEntry(
                        entry.proposalId(), entry.deadlineHeight(), entry.policyId(),
                        entry.proposerActorId())).toList(), next);
    }

    /** Fails startup when the retained pending index, markers, or totals disagree. */
    public static void verifyPendingState(
            AppStateReader state,
            GovernedAuthorizationLimitsV1 limits
    ) {
        ApprovalPendingIndexV1 index = pendingIndex(state);
        if (index.entries().size() > limits.maximumPendingApprovals()) {
            throw new IllegalStateException("approval pending index exceeds genesis bound");
        }
        requireDimensionBound(index.entries(), ApprovalPendingIndexV1.Entry::proposerActorId,
                limits.maximumPendingPerActor(), "actor");
        requireDimensionBound(index.entries(), ApprovalPendingIndexV1.Entry::policyId,
                limits.maximumPendingPerPolicy(), "policy");
        requireDimensionBound(index.entries(), entry -> Long.toString(entry.deadlineHeight()),
                limits.maximumPendingPerDeadline(), "deadline");

        for (ApprovalPendingIndexV1.Entry entry : index.entries()) {
            ApprovalProposalV1 proposal = state.get(RoleWorkflowKeys.proposal(
                            entry.proposalId()))
                    .map(ApprovalProposalV1::decode)
                    .orElseThrow(() -> new IllegalStateException(
                            "approval pending index points to an absent proposal"));
            if (proposal.status() != ApprovalProposalV1.ProposalStatus.PENDING
                    || proposal.deadlineHeight() != entry.deadlineHeight()
                    || !proposal.policyId().equals(entry.policyId())
                    || !proposal.proposerActorId().equals(entry.proposerActorId())) {
                throw new IllegalStateException(
                        "approval pending index is incompatible with proposal state");
            }
            requireMarker(state, RoleWorkflowKeys.approvalDeadline(
                    proposal.deadlineHeight(), proposal.proposalId()), proposal.payloadHash());
            requireMarker(state, RoleWorkflowKeys.approvalByActor(
                    proposal.proposerActorId(), proposal.proposalId()), proposal.payloadHash());
            requireMarker(state, RoleWorkflowKeys.approvalByPolicy(
                    proposal.policyId(), proposal.proposalId()), proposal.payloadHash());
        }
        RoleApprovalStatsV1 totals = state.get(RoleWorkflowKeys.approvalStats())
                .map(RoleApprovalStatsV1::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "approval aggregate statistics are absent"));
        if (totals.pending() != index.entries().size()) {
            throw new IllegalStateException(
                    "approval pending index disagrees with aggregate statistics");
        }
    }

    private RoleWorkflowResultCode propose(
            ActorStatementV1 statement,
            ActorEligibility actor,
            long height,
            AppStateWriter state
    ) {
        ApprovalProposalV1 existing = proposal(state, statement.proposalId());
        if (existing != null) {
            return proposalMatches(existing, statement)
                    ? RoleWorkflowResultCode.EXACT_REPLAY
                    : RoleWorkflowResultCode.CONFLICT;
        }
        if (statement.deadlineHeight() <= height) {
            return RoleWorkflowResultCode.EXPIRED;
        }
        long currentRevision = pointer(state,
                RoleWorkflowKeys.policyCurrent(statement.policyId()));
        if (currentRevision == 0) return RoleWorkflowResultCode.UNKNOWN_RECORD;
        if (currentRevision != statement.policyRevision()) {
            return RoleWorkflowResultCode.WRONG_REVISION;
        }
        ApprovalPolicyV1 policy = policy(state,
                statement.policyId(), statement.policyRevision());
        requirePolicyIdentity(policy, statement.policyId(), statement.policyRevision());
        if (policy.status() != RecordStatus.ACTIVE) {
            return RoleWorkflowResultCode.UNAUTHORIZED_ACTOR;
        }
        long maximumDeadline;
        try {
            maximumDeadline = Math.addExact(height, policy.maximumLifetimeBlocks());
        } catch (ArithmeticException exhausted) {
            maximumDeadline = Long.MAX_VALUE;
        }
        if (statement.deadlineHeight() > maximumDeadline) {
            return RoleWorkflowResultCode.LIMIT_EXCEEDED;
        }
        String proposerRole = policy.proposerRoles().isEmpty()
                ? actor.actor().roles().getFirst()
                : policy.proposerRoles().stream()
                .filter(actor.actor().roles()::contains).findFirst().orElse(null);
        if (proposerRole == null) return RoleWorkflowResultCode.ROLE_MISMATCH;

        ApprovalPendingIndexV1 index = pendingIndex(state);
        RoleApprovalStatsV1 currentStats = stats(state);
        if (currentStats.pending() >= limits.maximumPendingApprovals()) {
            return RoleWorkflowResultCode.CAPACITY_EXCEEDED;
        }
        if (currentStats.pending() != index.entries().size()) {
            throw new IllegalStateException(
                    "approval pending index disagrees with aggregate statistics");
        }
        if (!hasCapacity(index, statement.actorId(), statement.policyId(),
                statement.deadlineHeight())) {
            return RoleWorkflowResultCode.CAPACITY_EXCEEDED;
        }
        ApprovalProposalV1 created = new ApprovalProposalV1(
                statement.proposalId(), statement.policyId(),
                statement.policyRevision(), policy.digest(), statement.payloadDomain(),
                statement.payloadHash(), statement.deadlineHeight(),
                ApprovalProposalV1.ProposalStatus.PENDING, statement.actorId(),
                actor.actor().organizationId(), actor.organization().revision(),
                proposerRole, statement.actorRevision(), statement.keyId(), height,
                List.of());
        state.put(RoleWorkflowKeys.proposal(statement.proposalId()), created.encode());
        writeStats(state, currentStats.proposalCreated());
        putMarkers(state, created);
        writeIndex(state, index.add(new ApprovalPendingIndexV1.Entry(
                created.proposalId(), created.deadlineHeight(), created.policyId(),
                created.proposerActorId())));
        return RoleWorkflowResultCode.ACCEPTED;
    }

    private RoleWorkflowResultCode decide(
            SignedActorCommandV1 command,
            ActorEligibility actor,
            long height,
            AppStateWriter state
    ) {
        ActorStatementV1 statement = command.statement();
        ApprovalProposalV1 proposal = proposal(state, statement.proposalId());
        if (proposal == null) return RoleWorkflowResultCode.UNKNOWN_RECORD;
        if (proposal.status() != ApprovalProposalV1.ProposalStatus.PENDING) {
            return RoleWorkflowResultCode.TERMINAL;
        }
        if (!proposalMatches(proposal, statement)) {
            return RoleWorkflowResultCode.CONFLICT;
        }
        ApprovalPolicyV1 policy = policy(state,
                proposal.policyId(), proposal.policyRevision());
        requirePolicyIdentity(policy, proposal.policyId(), proposal.policyRevision());
        if (!MessageDigest.isEqual(policy.digest(), proposal.policyDigest())) {
            throw new IllegalStateException("proposal policy revision is incompatible");
        }
        ApprovalProposalV1.AcceptedDecisionV1 prior = proposal.decisions().stream()
                .filter(decision -> decision.actorId().equals(statement.actorId()))
                .findFirst().orElse(null);
        if (prior != null) {
            return MessageDigest.isEqual(prior.statementDigest(), statement.digest())
                    ? RoleWorkflowResultCode.EXACT_REPLAY
                    : RoleWorkflowResultCode.CONFLICT;
        }
        ApprovalPolicyV1.RequiredClause clause = policy.clause(statement.clauseId());
        if (clause == null || !actor.actor().roles().contains(clause.role())) {
            return RoleWorkflowResultCode.ROLE_MISMATCH;
        }
        if (clause.distinctBy() == ApprovalPolicyV1.DistinctBy.ORGANIZATION
                && proposal.decisions().stream().anyMatch(decision ->
                decision.clauseId().equals(clause.clauseId())
                        && decision.organizationId()
                        .equals(actor.actor().organizationId()))) {
            return RoleWorkflowResultCode.DISTINCTNESS_DUPLICATE;
        }
        if (statement.action() == ActorStatementV1.Action.REJECT
                && policy.rejectionMode() == ApprovalPolicyV1.RejectionMode.DISABLED) {
            return RoleWorkflowResultCode.ROLE_MISMATCH;
        }
        ApprovalProposalV1.AcceptedDecisionV1 decision =
                new ApprovalProposalV1.AcceptedDecisionV1(
                        statement.action(), statement.actorId(),
                        actor.actor().organizationId(), actor.organization().revision(),
                        clause.role(), statement.actorRevision(), statement.keyId(),
                        statement.clauseId(), statement.digest(), command.signature(), height);
        List<ApprovalProposalV1.AcceptedDecisionV1> decisions =
                new ArrayList<>(proposal.decisions());
        decisions.add(decision);
        ApprovalProposalV1.ProposalStatus status = statement.action()
                == ActorStatementV1.Action.REJECT
                ? ApprovalProposalV1.ProposalStatus.REJECTED
                : satisfied(policy, decisions)
                ? ApprovalProposalV1.ProposalStatus.APPROVED
                : ApprovalProposalV1.ProposalStatus.PENDING;
        if (status == ApprovalProposalV1.ProposalStatus.PENDING) {
            state.put(RoleWorkflowKeys.proposal(proposal.proposalId()),
                    copy(proposal, status, decisions).encode());
        } else {
            ApprovalPendingIndexV1 index = pendingIndex(state);
            writeIndex(state, terminal(state, index,
                    indexEntry(index, proposal.proposalId()), status, decisions));
        }
        return RoleWorkflowResultCode.ACCEPTED;
    }

    private RoleWorkflowResultCode cancel(
            ActorStatementV1 statement,
            AppStateWriter state
    ) {
        ApprovalProposalV1 proposal = proposal(state, statement.proposalId());
        if (proposal == null) return RoleWorkflowResultCode.UNKNOWN_RECORD;
        if (proposal.status() != ApprovalProposalV1.ProposalStatus.PENDING) {
            return RoleWorkflowResultCode.TERMINAL;
        }
        if (!proposalMatches(proposal, statement)) {
            return RoleWorkflowResultCode.CONFLICT;
        }
        if (!proposal.proposerActorId().equals(statement.actorId())) {
            return RoleWorkflowResultCode.UNAUTHORIZED_ACTOR;
        }
        ApprovalPendingIndexV1 index = pendingIndex(state);
        writeIndex(state, terminal(state, index,
                indexEntry(index, proposal.proposalId()),
                ApprovalProposalV1.ProposalStatus.CANCELLED,
                proposal.decisions()));
        return RoleWorkflowResultCode.ACCEPTED;
    }

    private boolean hasCapacity(
            ApprovalPendingIndexV1 index,
            String actorId,
            String policyId,
            long deadlineHeight
    ) {
        return index.entries().size() < limits.maximumPendingApprovals()
                && index.entries().stream().filter(entry ->
                entry.proposerActorId().equals(actorId)).count()
                < limits.maximumPendingPerActor()
                && index.entries().stream().filter(entry ->
                entry.policyId().equals(policyId)).count()
                < limits.maximumPendingPerPolicy()
                && index.entries().stream().filter(entry ->
                entry.deadlineHeight() == deadlineHeight).count()
                < limits.maximumPendingPerDeadline();
    }

    private static boolean satisfied(
            ApprovalPolicyV1 policy,
            List<ApprovalProposalV1.AcceptedDecisionV1> decisions
    ) {
        for (ApprovalPolicyV1.RequiredClause clause : policy.clauses()) {
            long count = decisions.stream()
                    .filter(decision ->
                            decision.action() == ActorStatementV1.Action.APPROVE)
                    .filter(decision -> decision.clauseId().equals(clause.clauseId()))
                    .map(decision -> clause.distinctBy()
                            == ApprovalPolicyV1.DistinctBy.ACTOR
                            ? decision.actorId() : decision.organizationId())
                    .distinct().count();
            if (count < clause.minimumCount()) return false;
        }
        return true;
    }

    private static ApprovalPendingIndexV1 terminal(
            AppStateWriter state,
            ApprovalPendingIndexV1 index,
            ApprovalPendingIndexV1.Entry entry,
            ApprovalProposalV1.ProposalStatus status,
            List<ApprovalProposalV1.AcceptedDecisionV1> decisions
    ) {
        ApprovalProposalV1 proposal = proposal(state, entry.proposalId());
        if (proposal == null
                || proposal.status() != ApprovalProposalV1.ProposalStatus.PENDING
                || status == ApprovalProposalV1.ProposalStatus.PENDING) {
            throw new IllegalStateException("invalid pending approval transition");
        }
        state.put(RoleWorkflowKeys.proposal(proposal.proposalId()),
                copy(proposal, status, decisions).encode());
        writeStats(state, stats(state).terminal(status));
        deleteMarkers(state, proposal);
        return index.remove(proposal.proposalId());
    }

    private static ApprovalProposalV1 copy(
            ApprovalProposalV1 proposal,
            ApprovalProposalV1.ProposalStatus status,
            List<ApprovalProposalV1.AcceptedDecisionV1> decisions
    ) {
        return new ApprovalProposalV1(
                proposal.proposalId(), proposal.policyId(), proposal.policyRevision(),
                proposal.policyDigest(), proposal.payloadDomain(), proposal.payloadHash(),
                proposal.deadlineHeight(), status, proposal.proposerActorId(),
                proposal.proposerOrganizationId(),
                proposal.proposerOrganizationRevision(), proposal.proposerRole(),
                proposal.proposerActorRevision(), proposal.proposerKeyId(),
                proposal.createdHeight(), decisions);
    }

    private static boolean proposalMatches(
            ApprovalProposalV1 proposal,
            ActorStatementV1 statement
    ) {
        return proposal.policyId().equals(statement.policyId())
                && proposal.policyRevision() == statement.policyRevision()
                && proposal.payloadDomain().equals(statement.payloadDomain())
                && MessageDigest.isEqual(proposal.payloadHash(), statement.payloadHash())
                && proposal.deadlineHeight() == statement.deadlineHeight();
    }

    private static ActorEligibility actorEligibility(
            AppStateWriter state,
            ActorStatementV1 statement,
            long height
    ) {
        long actorRevision = pointer(state,
                RoleWorkflowKeys.actorCurrent(statement.actorId()));
        if (actorRevision == 0 || actorRevision != statement.actorRevision()) return null;
        ActorRecordV1 actor = state.get(RoleWorkflowKeys.actorRevision(
                        statement.actorId(), actorRevision))
                .map(ActorRecordV1::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "actor current pointer is dangling"));
        long organizationRevision = pointer(state,
                RoleWorkflowKeys.organizationCurrent(actor.organizationId()));
        if (organizationRevision == 0) return null;
        OrganizationRecordV1 organization = state.get(
                        RoleWorkflowKeys.organizationRevision(
                                actor.organizationId(), organizationRevision))
                .map(OrganizationRecordV1::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "organization current pointer is dangling"));
        ActorKeyEpochV1 key = actor.key(statement.keyId());
        if (!actor.actorId().equals(statement.actorId())
                || actor.revision() != actorRevision
                || !organization.organizationId().equals(actor.organizationId())
                || organization.revision() != organizationRevision
                || actor.status() != RecordStatus.ACTIVE
                || organization.status() != RecordStatus.ACTIVE
                || key == null || !key.activeAt(height)) {
            return null;
        }
        return new ActorEligibility(actor, organization, key);
    }

    private static ApprovalPolicyV1 policy(
            AppStateReader state,
            String policyId,
            long revision
    ) {
        return state.get(RoleWorkflowKeys.policyRevision(policyId, revision))
                .map(ApprovalPolicyV1::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "approval policy revision is absent"));
    }

    private static void requirePolicyIdentity(
            ApprovalPolicyV1 policy,
            String policyId,
            long revision
    ) {
        if (!policy.policyId().equals(policyId) || policy.revision() != revision) {
            throw new IllegalStateException("approval policy revision is incompatible");
        }
    }

    private static ApprovalProposalV1 proposal(AppStateReader state, String proposalId) {
        return state.get(RoleWorkflowKeys.proposal(proposalId))
                .map(ApprovalProposalV1::decode).orElse(null);
    }

    private static RoleApprovalStatsV1 stats(AppStateReader state) {
        return state.get(RoleWorkflowKeys.approvalStats())
                .map(RoleApprovalStatsV1::decode).orElseGet(RoleApprovalStatsV1::empty);
    }

    private static void writeStats(AppStateWriter state, RoleApprovalStatsV1 stats) {
        state.put(RoleWorkflowKeys.approvalStats(), stats.encode());
    }

    private static ApprovalPendingIndexV1 pendingIndex(AppStateReader state) {
        return state.get(RoleWorkflowKeys.approvalPendingIndex())
                .map(ApprovalPendingIndexV1::decode)
                .orElseGet(ApprovalPendingIndexV1::empty);
    }

    private static void writeIndex(AppStateWriter state, ApprovalPendingIndexV1 index) {
        state.put(RoleWorkflowKeys.approvalPendingIndex(), index.encode());
    }

    private static ApprovalPendingIndexV1.Entry indexEntry(
            ApprovalPendingIndexV1 index,
            String proposalId
    ) {
        return index.entries().stream()
                .filter(entry -> entry.proposalId().equals(proposalId))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "pending proposal is absent from approval index"));
    }

    private static void putMarkers(AppStateWriter state, ApprovalProposalV1 proposal) {
        state.put(RoleWorkflowKeys.approvalDeadline(
                proposal.deadlineHeight(), proposal.proposalId()),
                proposal.payloadHash());
        state.put(RoleWorkflowKeys.approvalByActor(
                proposal.proposerActorId(), proposal.proposalId()),
                proposal.payloadHash());
        state.put(RoleWorkflowKeys.approvalByPolicy(
                proposal.policyId(), proposal.proposalId()), proposal.payloadHash());
    }

    private static void deleteMarkers(AppStateWriter state, ApprovalProposalV1 proposal) {
        state.delete(RoleWorkflowKeys.approvalDeadline(
                proposal.deadlineHeight(), proposal.proposalId()));
        state.delete(RoleWorkflowKeys.approvalByActor(
                proposal.proposerActorId(), proposal.proposalId()));
        state.delete(RoleWorkflowKeys.approvalByPolicy(
                proposal.policyId(), proposal.proposalId()));
    }

    private static <T> void requireDimensionBound(
            List<T> values,
            Function<T, String> classifier,
            int maximum,
            String dimension
    ) {
        Map<String, Long> counts = values.stream().collect(Collectors.groupingBy(
                classifier, Collectors.counting()));
        if (counts.values().stream().anyMatch(count -> count > maximum)) {
            throw new IllegalStateException(
                    "approval pending " + dimension + " count exceeds genesis bound");
        }
    }

    private static void requireMarker(
            AppStateReader state,
            byte[] key,
            byte[] expected
    ) {
        byte[] actual = state.get(key).orElseThrow(() -> new IllegalStateException(
                "approval pending marker is absent"));
        if (!MessageDigest.isEqual(actual, expected)) {
            throw new IllegalStateException("approval pending marker is incompatible");
        }
    }

    private static long pointer(AppStateWriter state, byte[] key) {
        byte[] encoded = state.get(key).orElse(null);
        if (encoded == null) return 0;
        if (encoded.length != Long.BYTES) {
            throw new IllegalStateException("corrupt role-workflow pointer");
        }
        long revision = ByteBuffer.wrap(encoded).getLong();
        if (revision < 1) {
            throw new IllegalStateException("corrupt role-workflow pointer");
        }
        return revision;
    }

    private record ActorEligibility(
            ActorRecordV1 actor,
            OrganizationRecordV1 organization,
            ActorKeyEpochV1 key
    ) {
    }
}
