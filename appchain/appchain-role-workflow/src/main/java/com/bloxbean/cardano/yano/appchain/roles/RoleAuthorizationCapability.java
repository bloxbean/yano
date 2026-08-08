package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalReferenceV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectAuthorizationEvidenceV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowIdentifiers;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Deterministic ADR-019 fact resolver and verifier shared by application bindings.
 * It reads only the explicitly supplied actor and approval namespaces and never mutates them.
 */
public final class RoleAuthorizationCapability {
    private final String chainId;

    public RoleAuthorizationCapability(String chainId) {
        this.chainId = RoleWorkflowIdentifiers.chainId(chainId);
    }

    public Decision<DirectFacts> verifyDirect(
            DirectAuthorizationEvidenceV1 evidence,
            byte[] expectedApplicationId,
            byte[] expectedActionCommitment,
            long height,
            boolean alreadyConsumed,
            AppStateReader actorState,
            AppStateReader approvalState
    ) {
        Objects.requireNonNull(evidence, "evidence");
        byte[] applicationId = exact32(expectedApplicationId, "expectedApplicationId");
        byte[] actionCommitment = exact32(expectedActionCommitment, "expectedActionCommitment");
        requireHeight(height);
        Objects.requireNonNull(actorState, "actorState");
        Objects.requireNonNull(approvalState, "approvalState");

        if (!chainId.equals(evidence.chainId())
                || !MessageDigest.isEqual(applicationId, evidence.applicationId())
                || !MessageDigest.isEqual(actionCommitment, evidence.actionCommitment())) {
            return Decision.rejected(Failure.WRONG_APPLICATION);
        }
        if (evidence.issuedHeight() < 1 || evidence.deadlineHeight() < 1
                || evidence.issuedHeight() > height || evidence.deadlineHeight() <= height) {
            return Decision.rejected(Failure.AUTHORIZATION_DEADLINE);
        }

        long policyRevision = pointer(approvalState,
                RoleWorkflowKeys.directPolicyCurrent(evidence.policyId()));
        if (policyRevision == 0) {
            return Decision.rejected(Failure.UNKNOWN_POLICY);
        }
        if (policyRevision != evidence.policyRevision()) {
            return Decision.rejected(Failure.WRONG_REVISION);
        }
        DirectRolePolicyV1 policy = approvalState.get(RoleWorkflowKeys.directPolicyRevision(
                        evidence.policyId(), policyRevision))
                .map(DirectRolePolicyV1::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "direct-role policy current pointer is dangling"));
        if (!policy.policyId().equals(evidence.policyId())
                || policy.revision() != policyRevision) {
            throw new IllegalStateException("direct-role policy pointer is incompatible");
        }
        if (policy.status() != RecordStatus.ACTIVE) {
            return Decision.rejected(Failure.POLICY_INACTIVE);
        }
        long lifetime = evidence.deadlineHeight() - evidence.issuedHeight();
        if (lifetime > policy.maximumAuthorizationLifetimeBlocks()) {
            return Decision.rejected(Failure.AUTHORIZATION_DEADLINE);
        }

        long actorRevision = pointer(actorState,
                RoleWorkflowKeys.actorCurrent(evidence.actorId()));
        if (actorRevision == 0 || actorRevision != evidence.actorRevision()) {
            return Decision.rejected(Failure.ACTOR_INELIGIBLE);
        }
        ActorRecordV1 actor = actorState.get(RoleWorkflowKeys.actorRevision(
                        evidence.actorId(), actorRevision))
                .map(ActorRecordV1::decode)
                .orElseThrow(() -> new IllegalStateException("actor current pointer is dangling"));
        if (!actor.actorId().equals(evidence.actorId()) || actor.revision() != actorRevision) {
            throw new IllegalStateException("actor current pointer is incompatible");
        }
        if (actor.status() != RecordStatus.ACTIVE
                || !actor.roles().contains(policy.requiredRole())) {
            return Decision.rejected(Failure.ACTOR_INELIGIBLE);
        }

        long organizationRevision = pointer(actorState,
                RoleWorkflowKeys.organizationCurrent(actor.organizationId()));
        if (organizationRevision == 0) {
            return Decision.rejected(Failure.ACTOR_INELIGIBLE);
        }
        OrganizationRecordV1 organization = actorState.get(
                        RoleWorkflowKeys.organizationRevision(
                                actor.organizationId(), organizationRevision))
                .map(OrganizationRecordV1::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "organization current pointer is dangling"));
        if (!organization.organizationId().equals(actor.organizationId())
                || organization.revision() != organizationRevision) {
            throw new IllegalStateException("organization current pointer is incompatible");
        }
        ActorKeyEpochV1 key = actor.key(evidence.keyId());
        if (organization.status() != RecordStatus.ACTIVE || key == null
                || !key.activeAt(height)
                || !MessageDigest.isEqual(key.publicKey(), evidence.publicKey())) {
            return Decision.rejected(Failure.ACTOR_INELIGIBLE);
        }
        if (!evidence.verifyClaimedKey()) {
            return Decision.rejected(Failure.INVALID_SIGNATURE);
        }
        if (alreadyConsumed) {
            return Decision.rejected(Failure.DIRECT_REPLAY);
        }
        return Decision.accepted(new DirectFacts(policy, actor, organization, key));
    }

    public Decision<ApprovalFacts> verifyApproval(
            ApprovalReferenceV1 reference,
            String expectedPayloadDomain,
            byte[] expectedPayloadHash,
            byte[] expectedActionCommitment,
            long height,
            boolean alreadyConsumed,
            AppStateReader approvalState
    ) {
        Objects.requireNonNull(reference, "reference");
        String payloadDomain = RoleWorkflowIdentifiers.payloadDomain(expectedPayloadDomain);
        byte[] payloadHash = exact32(expectedPayloadHash, "expectedPayloadHash");
        byte[] actionCommitment = exact32(expectedActionCommitment, "expectedActionCommitment");
        requireHeight(height);
        Objects.requireNonNull(approvalState, "approvalState");

        ApprovalProposalV1 proposal = approvalState.get(
                        RoleWorkflowKeys.proposal(reference.proposalId()))
                .map(ApprovalProposalV1::decode).orElse(null);
        if (proposal == null
                || proposal.status() != ApprovalProposalV1.ProposalStatus.APPROVED
                || height > proposal.deadlineHeight()) {
            return Decision.rejected(Failure.APPROVAL_NOT_APPROVED);
        }
        if (!proposal.proposalId().equals(reference.proposalId())) {
            throw new IllegalStateException(
                    "approval proposal key is incompatible with retained record");
        }
        if (!MessageDigest.isEqual(reference.actionCommitment(), actionCommitment)
                || !proposal.payloadDomain().equals(payloadDomain)
                || !MessageDigest.isEqual(proposal.payloadHash(), payloadHash)
                || !proposal.policyId().equals(reference.policyId())
                || proposal.policyRevision() != reference.policyRevision()) {
            return Decision.rejected(Failure.APPROVAL_MISMATCH);
        }
        ApprovalPolicyV1 policy = approvalState.get(RoleWorkflowKeys.policyRevision(
                        reference.policyId(), reference.policyRevision()))
                .map(ApprovalPolicyV1::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "approved proposal policy revision is absent"));
        if (!policy.policyId().equals(reference.policyId())
                || policy.revision() != reference.policyRevision()
                || !MessageDigest.isEqual(policy.digest(), proposal.policyDigest())) {
            throw new IllegalStateException("approved proposal policy revision is incompatible");
        }
        if (alreadyConsumed) {
            return Decision.rejected(Failure.APPROVAL_REPLAY);
        }
        return Decision.accepted(new ApprovalFacts(proposal, policy));
    }

    /** Binds an accepted direct decision to an application-owned replay key and receipt. */
    public ConsumptionPlan planDirectConsumption(
            Decision<DirectFacts> decision,
            byte[] replayKey,
            byte[] applicationReceipt
    ) {
        return plan(decision, replayKey, applicationReceipt, Failure.DIRECT_REPLAY);
    }

    /** Binds an accepted approval decision to an application-owned replay key and receipt. */
    public ConsumptionPlan planApprovalConsumption(
            Decision<ApprovalFacts> decision,
            byte[] replayKey,
            byte[] applicationReceipt
    ) {
        return plan(decision, replayKey, applicationReceipt, Failure.APPROVAL_REPLAY);
    }

    private static ConsumptionPlan plan(
            Decision<?> decision,
            byte[] replayKey,
            byte[] applicationReceipt,
            Failure replayFailure
    ) {
        Objects.requireNonNull(decision, "decision");
        if (!decision.accepted()) {
            throw new IllegalStateException("cannot consume a rejected authorization decision");
        }
        return new ConsumptionPlan(replayKey, applicationReceipt, replayFailure);
    }

    private static long pointer(AppStateReader state, byte[] key) {
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

    private static byte[] exact32(byte[] value, String field) {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException(field + " must be 32 bytes");
        }
        return value.clone();
    }

    private static void requireHeight(long height) {
        if (height < 1) throw new IllegalArgumentException("height must be positive");
    }

    public enum Failure {
        WRONG_APPLICATION,
        AUTHORIZATION_DEADLINE,
        UNKNOWN_POLICY,
        WRONG_REVISION,
        POLICY_INACTIVE,
        ACTOR_INELIGIBLE,
        INVALID_SIGNATURE,
        DIRECT_REPLAY,
        APPROVAL_NOT_APPROVED,
        APPROVAL_MISMATCH,
        APPROVAL_REPLAY
    }

    public record Decision<T>(T value, Failure failure) {
        public Decision {
            if ((value == null) == (failure == null)) {
                throw new IllegalArgumentException(
                        "authorization decision must contain exactly one outcome");
            }
        }

        public static <T> Decision<T> accepted(T value) {
            return new Decision<>(Objects.requireNonNull(value, "value"), null);
        }

        public static <T> Decision<T> rejected(Failure failure) {
            return new Decision<>(null, Objects.requireNonNull(failure, "failure"));
        }

        public boolean accepted() {
            return value != null;
        }
    }

    public record DirectFacts(
            DirectRolePolicyV1 policy,
            ActorRecordV1 actor,
            OrganizationRecordV1 organization,
            ActorKeyEpochV1 key
    ) {
        public DirectFacts {
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(organization, "organization");
            Objects.requireNonNull(key, "key");
        }
    }

    public record ApprovalFacts(ApprovalProposalV1 proposal, ApprovalPolicyV1 policy) {
        public ApprovalFacts {
            Objects.requireNonNull(proposal, "proposal");
            Objects.requireNonNull(policy, "policy");
        }
    }

    /** Pure one-use write plan; the application controls key and canonical receipt bytes. */
    public record ConsumptionPlan(byte[] replayKey, byte[] applicationReceipt,
                                  Failure replayFailure) {
        public ConsumptionPlan {
            replayKey = nonEmpty(replayKey, "replayKey");
            applicationReceipt = nonEmpty(applicationReceipt, "applicationReceipt");
            if (replayFailure != Failure.DIRECT_REPLAY
                    && replayFailure != Failure.APPROVAL_REPLAY) {
                throw new IllegalArgumentException("invalid replay failure");
            }
        }

        @Override public byte[] replayKey() { return replayKey.clone(); }
        @Override public byte[] applicationReceipt() { return applicationReceipt.clone(); }

        private static byte[] nonEmpty(byte[] value, String field) {
            if (value == null || value.length == 0) {
                throw new IllegalArgumentException(field + " must not be empty");
            }
            return value.clone();
        }
    }
}
