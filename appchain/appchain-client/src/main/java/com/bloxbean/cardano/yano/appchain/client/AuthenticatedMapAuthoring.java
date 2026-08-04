package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorStatementV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/** Dependency-light construction and external-signing helpers for governed map commands. */
public final class AuthenticatedMapAuthoring {
    private AuthenticatedMapAuthoring() {
    }

    public static AuthenticatedMapAuthorizationContract.MapActionV1 action(
            AuthenticatedMapContract.Command command,
            List<AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1> assignments
    ) {
        Objects.requireNonNull(command, "command");
        return new AuthenticatedMapAuthorizationContract.MapActionV1(
                command.batch(), command.mutations(), assignments);
    }

    public static DirectSigningRequest directSigningRequest(
            byte[] authorizationId,
            String chainId,
            byte[] genesisId,
            AuthenticatedMapAuthorizationContract.MapActionV1 action,
            List<Integer> coveredMutationIndexes,
            String policyId,
            long policyRevision,
            String actorId,
            long actorRevision,
            String keyId,
            byte[] publicKey,
            long issuedHeight,
            long deadlineHeight
    ) {
        byte[] commitment = AuthenticatedMapAuthorizationContract.actionCommitment(action);
        var unsigned = new AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1(
                authorizationId, chainId, genesisId, commitment, coveredMutationIndexes,
                policyId, policyRevision, actorId, actorRevision, keyId, publicKey,
                issuedHeight, deadlineHeight,
                AuthenticatedMapAuthorizationContract.SIGNATURE_ED25519, new byte[64]);
        return new DirectSigningRequest(unsigned, unsigned.signingPreimage());
    }

    /** Attach a signature returned by an external Ed25519 signer and verify it locally. */
    public static AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1
    completeDirectSignature(DirectSigningRequest request, byte[] signature) {
        Objects.requireNonNull(request, "request");
        var value = request.unsignedAuthorization();
        var completed = new AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1(
                value.authorizationId(), value.chainId(), value.genesisId(),
                value.actionCommitment(), value.coveredMutationIndexes(), value.policyId(),
                value.policyRevision(), value.actorId(), value.actorRevision(), value.keyId(),
                value.publicKey(), value.issuedHeight(), value.deadlineHeight(),
                value.signatureAlgorithm(), signature);
        if (!completed.verifyClaimedKey()) {
            throw new IllegalArgumentException(
                    "external signature does not verify against the claimed actor key");
        }
        return completed;
    }

    public static AuthenticatedMapAuthorizationContract.MapApprovalReferenceV1
    approvalReference(
            String proposalId,
            AuthenticatedMapAuthorizationContract.MapActionV1 action,
            List<Integer> coveredMutationIndexes,
            String policyId,
            long policyRevision
    ) {
        return new AuthenticatedMapAuthorizationContract.MapApprovalReferenceV1(
                proposalId, AuthenticatedMapAuthorizationContract.actionCommitment(action),
                coveredMutationIndexes, policyId, policyRevision);
    }

    /** Exact actor statement used to propose an authenticated-map approval. */
    public static ActorStatementV1 approvalStatement(
            ActorStatementV1.Action decision,
            String chainId,
            byte[] genesisId,
            AuthenticatedMapAuthorizationContract.MapActionV1 action,
            String proposalId,
            String policyId,
            long policyRevision,
            long deadlineHeight,
            String actorId,
            long actorRevision,
            String keyId,
            String clauseId
    ) {
        if (decision == null || decision == ActorStatementV1.Action.CANCEL
                && clauseId != null && !clauseId.isEmpty()) {
            throw new IllegalArgumentException("invalid authenticated-map approval decision");
        }
        byte[] payloadHash = AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                genesisId, AuthenticatedMapAuthorizationContract.actionCommitment(action));
        return new ActorStatementV1(decision, chainId, proposalId, policyId,
                policyRevision,
                AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN,
                payloadHash, deadlineHeight, actorId, actorRevision, keyId,
                clauseId == null ? "" : clauseId);
    }

    public static AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1 command(
            AuthenticatedMapAuthorizationContract.MapActionV1 action,
            List<AuthenticatedMapAuthorizationContract.AuthorizationEvidenceV1> evidence
    ) {
        return new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(
                action, evidence);
    }

    public record DirectSigningRequest(
            AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1
                    unsignedAuthorization,
            byte[] signingPreimage
    ) {
        public DirectSigningRequest {
            Objects.requireNonNull(unsignedAuthorization, "unsignedAuthorization");
            signingPreimage = Objects.requireNonNull(signingPreimage,
                    "signingPreimage").clone();
            if (!MessageDigest.isEqual(signingPreimage,
                    unsignedAuthorization.signingPreimage())) {
                throw new IllegalArgumentException("signing preimage differs from authorization");
            }
        }

        @Override public byte[] signingPreimage() { return signingPreimage.clone(); }
    }
}
