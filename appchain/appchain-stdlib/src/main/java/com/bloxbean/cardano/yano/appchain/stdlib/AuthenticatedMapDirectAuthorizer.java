package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.roles.GovernedCryptoWork;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedAuthorizationLimitsV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract.*;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Stateful direct-role evidence verifier for the final authenticated-map workflow. */
final class AuthenticatedMapDirectAuthorizer {
    private final String chainId;
    private final byte[] genesisId;
    private final GovernedAuthorizationLimitsV1 limits;

    AuthenticatedMapDirectAuthorizer(
            String chainId,
            byte[] genesisId,
            GovernedAuthorizationLimitsV1 limits
    ) {
        this.chainId = java.util.Objects.requireNonNull(chainId, "chainId");
        this.genesisId = java.util.Objects.requireNonNull(genesisId, "genesisId").clone();
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        if (genesisId.length != 32) {
            throw new IllegalArgumentException("authenticated-map genesis id must be 32 bytes");
        }
    }

    AuthorizationResult authorize(
            AuthenticatedMapCommandV1 command,
            long height,
            byte[] messageId,
            AppStateWriter actorState,
            AppStateWriter approvalState,
            AppStateWriter mapState
    ) {
        int cryptoUnits = command.cryptoWorkUnits();
        if (cryptoUnits > 0 && !GovernedCryptoWork.reserve(actorState, height,
                cryptoUnits, limits.maximumCryptoWorkUnitsPerBlock())) {
            return AuthorizationResult.rejected(
                    AuthenticatedMapContract.ERROR_CRYPTO_WORK_EXCEEDED);
        }

        List<DirectConsumptionV1> consumptions = new ArrayList<>();
        List<ApprovalConsumptionV1> approvalConsumptions = new ArrayList<>();
        Set<Integer> governedIndexes = new LinkedHashSet<>();
        for (AuthorizationEvidenceV1 evidence : command.evidence()) {
            if (evidence instanceof MapActorAuthorizationV1 actor) {
                VerifiedActor verified = verify(actor, command, height,
                        actorState, approvalState, mapState);
                if (verified.errorCode() != AuthenticatedMapContract.ERROR_NONE) {
                    return AuthorizationResult.rejected(verified.errorCode());
                }
                governedIndexes.addAll(actor.coveredMutationIndexes());
                consumptions.add(new DirectConsumptionV1(
                        actor.actorId(), actor.authorizationId(),
                        actor.actionCommitment(), height, messageId,
                        actor.coveredMutationIndexes(), actor.policyId(),
                        actor.policyRevision(), actor.actorRevision(),
                        verified.organization().organizationId(),
                        verified.organization().revision(),
                        verified.policy().requiredRole(), actor.keyId(),
                        actor.statementDigest(), sha256(actor.signature())));
            } else {
                MapApprovalReferenceV1 approval =
                        (MapApprovalReferenceV1) evidence;
                int error = verifyApproval(
                        approval, height, approvalState, mapState);
                if (error != AuthenticatedMapContract.ERROR_NONE) {
                    return AuthorizationResult.rejected(error);
                }
                governedIndexes.addAll(approval.coveredMutationIndexes());
                approvalConsumptions.add(new ApprovalConsumptionV1(
                        approval.proposalId(), approval.actionCommitment(), height,
                        messageId, approval.coveredMutationIndexes(),
                        approval.policyId(), approval.policyRevision()));
            }
        }
        return AuthorizationResult.accepted(
                governedIndexes, consumptions, approvalConsumptions);
    }

    private int verifyApproval(
            MapApprovalReferenceV1 reference,
            long height,
            AppStateWriter approvalState,
            AppStateWriter mapState
    ) {
        ApprovalProposalV1 proposal = approvalState.get(
                        RoleWorkflowKeys.proposal(reference.proposalId()))
                .map(ApprovalProposalV1::decode).orElse(null);
        if (proposal == null
                || proposal.status() != ApprovalProposalV1.ProposalStatus.APPROVED
                || height > proposal.deadlineHeight()) {
            return AuthenticatedMapContract.ERROR_APPROVAL_NOT_APPROVED;
        }
        if (!proposal.proposalId().equals(reference.proposalId())) {
            throw new IllegalStateException(
                    "approval proposal key is incompatible with retained record");
        }
        if (!proposal.payloadDomain().equals(
                AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN)
                || !MessageDigest.isEqual(proposal.payloadHash(),
                AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                        genesisId, reference.actionCommitment()))
                || !proposal.policyId().equals(reference.policyId())
                || proposal.policyRevision() != reference.policyRevision()) {
            return AuthenticatedMapContract.ERROR_APPROVAL_MISMATCH;
        }
        ApprovalPolicyV1 policy = approvalState.get(
                        RoleWorkflowKeys.policyRevision(
                                reference.policyId(), reference.policyRevision()))
                .map(ApprovalPolicyV1::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "approved proposal policy revision is absent"));
        if (!policy.policyId().equals(reference.policyId())
                || policy.revision() != reference.policyRevision()
                || !MessageDigest.isEqual(policy.digest(), proposal.policyDigest())) {
            throw new IllegalStateException(
                    "approved proposal policy revision is incompatible");
        }
        if (mapState.get(AuthenticatedMapContract.approvalConsumptionKey(
                reference.proposalId())).isPresent()) {
            return AuthenticatedMapContract.ERROR_APPROVAL_REPLAY;
        }
        return AuthenticatedMapContract.ERROR_NONE;
    }

    private VerifiedActor verify(
            MapActorAuthorizationV1 authorization,
            AuthenticatedMapCommandV1 command,
            long height,
            AppStateWriter actorState,
            AppStateWriter approvalState,
            AppStateWriter mapState
    ) {
        if (!authorization.chainId().equals(chainId)
                || !MessageDigest.isEqual(authorization.genesisId(), genesisId)
                || !MessageDigest.isEqual(authorization.actionCommitment(),
                AuthenticatedMapAuthorizationContract.actionCommitment(command.action()))) {
            return VerifiedActor.rejected(AuthenticatedMapContract.ERROR_WRONG_GENESIS);
        }
        if (authorization.issuedHeight() > height
                || authorization.deadlineHeight() <= height) {
            return VerifiedActor.rejected(
                    AuthenticatedMapContract.ERROR_AUTHORIZATION_DEADLINE);
        }

        long policyRevision = pointer(approvalState,
                RoleWorkflowKeys.directPolicyCurrent(authorization.policyId()));
        if (policyRevision == 0) {
            return VerifiedActor.rejected(AuthenticatedMapContract.ERROR_UNKNOWN_POLICY);
        }
        if (policyRevision != authorization.policyRevision()) {
            return VerifiedActor.rejected(AuthenticatedMapContract.ERROR_WRONG_REVISION);
        }
        DirectRolePolicyV1 policy = approvalState.get(
                        RoleWorkflowKeys.directPolicyRevision(
                                authorization.policyId(), policyRevision))
                .map(DirectRolePolicyV1::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "direct-role policy current pointer is dangling"));
        if (!policy.policyId().equals(authorization.policyId())
                || policy.revision() != policyRevision) {
            throw new IllegalStateException("direct-role policy pointer is incompatible");
        }
        if (policy.status() != RecordStatus.ACTIVE) {
            return VerifiedActor.rejected(AuthenticatedMapContract.ERROR_POLICY_INACTIVE);
        }
        long authorizationLifetime = authorization.deadlineHeight()
                - authorization.issuedHeight();
        if (authorizationLifetime > policy.maximumAuthorizationLifetimeBlocks()) {
            return VerifiedActor.rejected(
                    AuthenticatedMapContract.ERROR_AUTHORIZATION_DEADLINE);
        }

        long actorRevision = pointer(actorState,
                RoleWorkflowKeys.actorCurrent(authorization.actorId()));
        if (actorRevision == 0 || actorRevision != authorization.actorRevision()) {
            return VerifiedActor.rejected(AuthenticatedMapContract.ERROR_ACTOR_INELIGIBLE);
        }
        ActorRecordV1 actor = actorState.get(RoleWorkflowKeys.actorRevision(
                        authorization.actorId(), actorRevision))
                .map(ActorRecordV1::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "actor current pointer is dangling"));
        if (!actor.actorId().equals(authorization.actorId())
                || actor.revision() != actorRevision) {
            throw new IllegalStateException("actor current pointer is incompatible");
        }
        if (actor.status() != RecordStatus.ACTIVE
                || !actor.roles().contains(policy.requiredRole())) {
            return VerifiedActor.rejected(AuthenticatedMapContract.ERROR_ACTOR_INELIGIBLE);
        }

        long organizationRevision = pointer(actorState,
                RoleWorkflowKeys.organizationCurrent(actor.organizationId()));
        if (organizationRevision == 0) {
            return VerifiedActor.rejected(AuthenticatedMapContract.ERROR_ACTOR_INELIGIBLE);
        }
        OrganizationRecordV1 organization = actorState.get(
                        RoleWorkflowKeys.organizationRevision(
                                actor.organizationId(), organizationRevision))
                .map(OrganizationRecordV1::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "organization current pointer is dangling"));
        if (!organization.organizationId().equals(actor.organizationId())
                || organization.revision() != organizationRevision) {
            throw new IllegalStateException(
                    "organization current pointer is incompatible");
        }
        ActorKeyEpochV1 key = actor.key(authorization.keyId());
        if (organization.status() != RecordStatus.ACTIVE || key == null
                || !key.activeAt(height)
                || !MessageDigest.isEqual(key.publicKey(), authorization.publicKey())) {
            return VerifiedActor.rejected(AuthenticatedMapContract.ERROR_ACTOR_INELIGIBLE);
        }
        if (!authorization.verifyClaimedKey()) {
            return VerifiedActor.rejected(AuthenticatedMapContract.ERROR_ACTOR_SIGNATURE);
        }
        if (mapState.get(AuthenticatedMapContract.directConsumptionKey(
                authorization.actorId(), authorization.authorizationId())).isPresent()) {
            return VerifiedActor.rejected(
                    AuthenticatedMapContract.ERROR_DIRECT_AUTHORIZATION_REPLAY);
        }
        return VerifiedActor.accepted(policy, organization);
    }

    private static long pointer(AppStateWriter state, byte[] key) {
        byte[] encoded = state.get(key).orElse(null);
        if (encoded == null) {
            return 0;
        }
        if (encoded.length != Long.BYTES) {
            throw new IllegalStateException("corrupt role-workflow pointer");
        }
        long revision = ByteBuffer.wrap(encoded).getLong();
        if (revision < 1) {
            throw new IllegalStateException("corrupt role-workflow pointer");
        }
        return revision;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record AuthorizationResult(
            int errorCode,
            Set<Integer> governedMutationIndexes,
            List<DirectConsumptionV1> consumptions,
            List<ApprovalConsumptionV1> approvalConsumptions
    ) {
        AuthorizationResult {
            governedMutationIndexes = Set.copyOf(governedMutationIndexes);
            consumptions = List.copyOf(consumptions);
            approvalConsumptions = List.copyOf(approvalConsumptions);
        }

        static AuthorizationResult accepted(
                Set<Integer> indexes,
                List<DirectConsumptionV1> consumptions,
                List<ApprovalConsumptionV1> approvalConsumptions
        ) {
            return new AuthorizationResult(AuthenticatedMapContract.ERROR_NONE,
                    indexes, consumptions, approvalConsumptions);
        }

        static AuthorizationResult rejected(int errorCode) {
            return new AuthorizationResult(
                    errorCode, Set.of(), List.of(), List.of());
        }

        boolean accepted() {
            return errorCode == AuthenticatedMapContract.ERROR_NONE;
        }
    }

    private record VerifiedActor(
            int errorCode,
            DirectRolePolicyV1 policy,
            OrganizationRecordV1 organization
    ) {
        static VerifiedActor accepted(
                DirectRolePolicyV1 policy,
                OrganizationRecordV1 organization
        ) {
            return new VerifiedActor(AuthenticatedMapContract.ERROR_NONE,
                    policy, organization);
        }

        static VerifiedActor rejected(int errorCode) {
            return new VerifiedActor(errorCode, null, null);
        }
    }
}
