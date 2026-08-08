package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.roles.GovernedCryptoWork;
import com.bloxbean.cardano.yano.appchain.roles.RoleAuthorizationCapability;
import com.bloxbean.cardano.yano.appchain.roles.contracts.GovernedAuthorizationLimitsV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract.*;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Stateful direct-role evidence verifier for the final authenticated-map workflow. */
final class AuthenticatedMapDirectAuthorizer {
    private final byte[] genesisId;
    private final GovernedAuthorizationLimitsV1 limits;
    private final RoleAuthorizationCapability authorization;

    AuthenticatedMapDirectAuthorizer(
            String chainId,
            byte[] genesisId,
            GovernedAuthorizationLimitsV1 limits
    ) {
        java.util.Objects.requireNonNull(chainId, "chainId");
        this.genesisId = java.util.Objects.requireNonNull(genesisId, "genesisId").clone();
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        if (genesisId.length != 32) {
            throw new IllegalArgumentException("authenticated-map genesis id must be 32 bytes");
        }
        this.authorization = new RoleAuthorizationCapability(chainId);
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

        List<RoleAuthorizationCapability.ConsumptionPlan> consumptions = new ArrayList<>();
        Set<Integer> governedIndexes = new LinkedHashSet<>();
        for (AuthorizationEvidenceV1 evidence : command.evidence()) {
            if (evidence instanceof MapActorAuthorizationV1 actor) {
                var verified = authorization.verifyDirect(
                        actor,
                        genesisId,
                        AuthenticatedMapAuthorizationContract.actionCommitment(command.action()),
                        height,
                        mapState.get(AuthenticatedMapContract.directConsumptionKey(
                                actor.actorId(), actor.authorizationId())).isPresent(),
                        actorState,
                        approvalState);
                if (!verified.accepted()) {
                    return AuthorizationResult.rejected(errorCode(verified.failure()));
                }
                governedIndexes.addAll(actor.coveredMutationIndexes());
                RoleAuthorizationCapability.DirectFacts facts = verified.value();
                DirectConsumptionV1 receipt = new DirectConsumptionV1(
                        actor.actorId(), actor.authorizationId(),
                        actor.actionCommitment(), height, messageId,
                        actor.coveredMutationIndexes(), actor.policyId(),
                        actor.policyRevision(), actor.actorRevision(),
                        facts.organization().organizationId(),
                        facts.organization().revision(),
                        facts.policy().requiredRole(), actor.keyId(),
                        actor.statementDigest(), sha256(actor.signature()));
                consumptions.add(authorization.planDirectConsumption(verified,
                        AuthenticatedMapContract.directConsumptionKey(
                                actor.actorId(), actor.authorizationId()),
                        receipt.encode()));
            } else {
                MapApprovalReferenceV1 approval =
                        (MapApprovalReferenceV1) evidence;
                var verified = authorization.verifyApproval(
                        approval,
                        AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN,
                        AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                                genesisId, approval.actionCommitment()),
                        AuthenticatedMapAuthorizationContract.actionCommitment(command.action()),
                        height,
                        mapState.get(AuthenticatedMapContract.approvalConsumptionKey(
                                approval.proposalId())).isPresent(),
                        approvalState);
                if (!verified.accepted()) {
                    return AuthorizationResult.rejected(errorCode(verified.failure()));
                }
                governedIndexes.addAll(approval.coveredMutationIndexes());
                ApprovalConsumptionV1 receipt = new ApprovalConsumptionV1(
                        approval.proposalId(), approval.actionCommitment(), height,
                        messageId, approval.coveredMutationIndexes(),
                        approval.policyId(), approval.policyRevision());
                consumptions.add(authorization.planApprovalConsumption(verified,
                        AuthenticatedMapContract.approvalConsumptionKey(
                                approval.proposalId()), receipt.encode()));
            }
        }
        return AuthorizationResult.accepted(
                governedIndexes, consumptions);
    }

    private static int errorCode(RoleAuthorizationCapability.Failure failure) {
        return switch (failure) {
            case WRONG_APPLICATION -> AuthenticatedMapContract.ERROR_WRONG_GENESIS;
            case AUTHORIZATION_DEADLINE ->
                    AuthenticatedMapContract.ERROR_AUTHORIZATION_DEADLINE;
            case UNKNOWN_POLICY -> AuthenticatedMapContract.ERROR_UNKNOWN_POLICY;
            case WRONG_REVISION -> AuthenticatedMapContract.ERROR_WRONG_REVISION;
            case POLICY_INACTIVE -> AuthenticatedMapContract.ERROR_POLICY_INACTIVE;
            case ACTOR_INELIGIBLE -> AuthenticatedMapContract.ERROR_ACTOR_INELIGIBLE;
            case INVALID_SIGNATURE -> AuthenticatedMapContract.ERROR_ACTOR_SIGNATURE;
            case DIRECT_REPLAY -> AuthenticatedMapContract.ERROR_DIRECT_AUTHORIZATION_REPLAY;
            case APPROVAL_NOT_APPROVED -> AuthenticatedMapContract.ERROR_APPROVAL_NOT_APPROVED;
            case APPROVAL_MISMATCH -> AuthenticatedMapContract.ERROR_APPROVAL_MISMATCH;
            case APPROVAL_REPLAY -> AuthenticatedMapContract.ERROR_APPROVAL_REPLAY;
        };
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
            List<RoleAuthorizationCapability.ConsumptionPlan> consumptions
    ) {
        AuthorizationResult {
            governedMutationIndexes = Set.copyOf(governedMutationIndexes);
            consumptions = List.copyOf(consumptions);
        }

        static AuthorizationResult accepted(
                Set<Integer> indexes,
                List<RoleAuthorizationCapability.ConsumptionPlan> consumptions
        ) {
            return new AuthorizationResult(AuthenticatedMapContract.ERROR_NONE,
                    indexes, consumptions);
        }

        static AuthorizationResult rejected(int errorCode) {
            return new AuthorizationResult(
                    errorCode, Set.of(), List.of());
        }

        boolean accepted() {
            return errorCode == AuthenticatedMapContract.ERROR_NONE;
        }
    }

}
