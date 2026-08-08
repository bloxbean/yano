package com.bloxbean.cardano.yano.appchain.roles;

import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorKeyEpochV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ActorRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalReferenceV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectAuthorizationEvidenceV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.OrganizationRecordV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleAuthorizationCapabilityTest {
    private static final String CHAIN_ID = "application-chain";
    private static final byte[] APPLICATION_ID = filled(1);
    private static final byte[] ACTION = filled(2);
    private static final byte[] PAYLOAD = filled(3);
    private static final byte[] PUBLIC_KEY = filled(4);

    private final MemoryState actors = new MemoryState();
    private final MemoryState approvals = new MemoryState();
    private RoleAuthorizationCapability capability;

    @BeforeEach
    void setUp() {
        capability = new RoleAuthorizationCapability(CHAIN_ID);

        DirectRolePolicyV1 directPolicy = new DirectRolePolicyV1(
                "document-writer", 2, RecordStatus.ACTIVE, "writer", 20);
        approvals.put(RoleWorkflowKeys.directPolicyRevision("document-writer", 2),
                directPolicy.encode());
        pointer(approvals, RoleWorkflowKeys.directPolicyCurrent("document-writer"), 2);

        ActorKeyEpochV1 key = new ActorKeyEpochV1(
                "writer-key", PUBLIC_KEY, 2, 0, RecordStatus.ACTIVE);
        ActorRecordV1 actor = new ActorRecordV1(
                "writer-a", "publisher-a", 3, RecordStatus.ACTIVE,
                List.of("writer"), List.of(key), new byte[0]);
        actors.put(RoleWorkflowKeys.actorRevision("writer-a", 3), actor.encode());
        pointer(actors, RoleWorkflowKeys.actorCurrent("writer-a"), 3);
        OrganizationRecordV1 organization = new OrganizationRecordV1(
                "publisher-a", 4, RecordStatus.ACTIVE, new byte[0]);
        actors.put(RoleWorkflowKeys.organizationRevision("publisher-a", 4),
                organization.encode());
        pointer(actors, RoleWorkflowKeys.organizationCurrent("publisher-a"), 4);

        ApprovalPolicyV1 approvalPolicy = approvalPolicy();
        approvals.put(RoleWorkflowKeys.policyRevision("document-release", 5),
                approvalPolicy.encode());
        ApprovalProposalV1 proposal = new ApprovalProposalV1(
                "release-7", "document-release", 5, approvalPolicy.digest(),
                "document.release.v1", PAYLOAD, 50,
                ApprovalProposalV1.ProposalStatus.APPROVED,
                "writer-a", "publisher-a", 4, "writer", 3,
                "writer-key", 10, List.of());
        approvals.put(RoleWorkflowKeys.proposal("release-7"), proposal.encode());
    }

    @Test
    void resolvesFrozenDirectActorRoleOrganizationKeyAndPolicyFacts() {
        var decision = capability.verifyDirect(evidence(), APPLICATION_ID, ACTION, 10,
                false, actors, approvals);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.value().policy().revision()).isEqualTo(2);
        assertThat(decision.value().actor().revision()).isEqualTo(3);
        assertThat(decision.value().organization().revision()).isEqualTo(4);
        assertThat(decision.value().key().keyId()).isEqualTo("writer-key");
    }

    @Test
    void rejectsApplicationRevisionDeadlineSignatureEligibilityAndReplaySubstitution() {
        assertFailure(evidence().withChainId("other-chain"),
                RoleAuthorizationCapability.Failure.WRONG_APPLICATION);
        assertFailure(evidence().withApplicationId(filled(9)),
                RoleAuthorizationCapability.Failure.WRONG_APPLICATION);
        assertFailure(evidence().withActionCommitment(filled(9)),
                RoleAuthorizationCapability.Failure.WRONG_APPLICATION);
        assertFailure(evidence().withPolicyRevision(1),
                RoleAuthorizationCapability.Failure.WRONG_REVISION);
        assertFailure(evidence().withDeadlineHeight(10),
                RoleAuthorizationCapability.Failure.AUTHORIZATION_DEADLINE);
        assertFailure(evidence().withDeadlineHeight(31),
                RoleAuthorizationCapability.Failure.AUTHORIZATION_DEADLINE);
        assertFailure(evidence().withActorRevision(2),
                RoleAuthorizationCapability.Failure.ACTOR_INELIGIBLE);
        assertFailure(evidence().withPublicKey(filled(9)),
                RoleAuthorizationCapability.Failure.ACTOR_INELIGIBLE);
        assertFailure(evidence().withValidSignature(false),
                RoleAuthorizationCapability.Failure.INVALID_SIGNATURE);

        assertThat(capability.verifyDirect(evidence(), APPLICATION_ID, ACTION, 10,
                true, actors, approvals).failure())
                .isEqualTo(RoleAuthorizationCapability.Failure.DIRECT_REPLAY);
    }

    @Test
    void resolvesApprovedProposalAndFrozenPolicyThenRejectsMismatchExpiryAndReplay() {
        ApprovalReference reference = new ApprovalReference(
                "release-7", ACTION, "document-release", 5);

        var accepted = capability.verifyApproval(reference,
                "document.release.v1", PAYLOAD, ACTION, 20, false, approvals);
        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.value().proposal().proposalId()).isEqualTo("release-7");
        assertThat(accepted.value().policy().digest())
                .containsExactly(approvalPolicy().digest());
        var consumption = capability.planApprovalConsumption(
                accepted, new byte[]{7}, new byte[]{8});
        assertThat(consumption.replayKey()).containsExactly(7);
        assertThat(consumption.applicationReceipt()).containsExactly(8);
        assertThat(consumption.replayFailure())
                .isEqualTo(RoleAuthorizationCapability.Failure.APPROVAL_REPLAY);

        assertThat(capability.verifyApproval(reference.withAction(filled(9)),
                "document.release.v1", PAYLOAD, ACTION, 20, false, approvals).failure())
                .isEqualTo(RoleAuthorizationCapability.Failure.APPROVAL_MISMATCH);
        assertThat(capability.verifyApproval(reference,
                "document.release.v1", filled(9), ACTION, 20, false, approvals).failure())
                .isEqualTo(RoleAuthorizationCapability.Failure.APPROVAL_MISMATCH);
        assertThat(capability.verifyApproval(reference,
                "document.release.v1", PAYLOAD, ACTION, 51, false, approvals).failure())
                .isEqualTo(RoleAuthorizationCapability.Failure.APPROVAL_NOT_APPROVED);
        assertThat(capability.verifyApproval(reference,
                "document.release.v1", PAYLOAD, ACTION, 20, true, approvals).failure())
                .isEqualTo(RoleAuthorizationCapability.Failure.APPROVAL_REPLAY);
        assertThatThrownBy(() -> capability.planApprovalConsumption(
                capability.verifyApproval(reference, "document.release.v1", PAYLOAD,
                        ACTION, 20, true, approvals), new byte[]{7}, new byte[]{8}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnknownPolicyButFailsClosedForCorruptAuthenticatedPointers() {
        MemoryState unknown = new MemoryState();
        assertThat(capability.verifyDirect(evidence(), APPLICATION_ID, ACTION, 10,
                false, actors, unknown).failure())
                .isEqualTo(RoleAuthorizationCapability.Failure.UNKNOWN_POLICY);

        approvals.put(RoleWorkflowKeys.directPolicyCurrent("document-writer"), new byte[]{1});
        assertThatThrownBy(() -> capability.verifyDirect(evidence(), APPLICATION_ID, ACTION, 10,
                false, actors, approvals))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt role-workflow pointer");
    }

    private void assertFailure(TestEvidence evidence, RoleAuthorizationCapability.Failure failure) {
        assertThat(capability.verifyDirect(evidence, APPLICATION_ID, ACTION, 10,
                false, actors, approvals).failure()).isEqualTo(failure);
    }

    private static TestEvidence evidence() {
        return new TestEvidence(CHAIN_ID, APPLICATION_ID, ACTION,
                "document-writer", 2, "writer-a", 3, "writer-key",
                PUBLIC_KEY, 5, 20, true);
    }

    private static ApprovalPolicyV1 approvalPolicy() {
        return new ApprovalPolicyV1("document-release", 5, RecordStatus.ACTIVE,
                List.of("writer"), List.of(new ApprovalPolicyV1.RequiredClause(
                "publishers", "writer", 1, ApprovalPolicyV1.DistinctBy.ACTOR)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 100);
    }

    private static void pointer(MemoryState state, byte[] key, long revision) {
        state.put(key, ByteBuffer.allocate(Long.BYTES).putLong(revision).array());
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private record ApprovalReference(
            String proposalId,
            byte[] actionCommitment,
            String policyId,
            long policyRevision
    ) implements ApprovalReferenceV1 {
        private ApprovalReference {
            actionCommitment = actionCommitment.clone();
        }

        @Override public byte[] actionCommitment() { return actionCommitment.clone(); }

        private ApprovalReference withAction(byte[] action) {
            return new ApprovalReference(proposalId, action, policyId, policyRevision);
        }
    }

    private record TestEvidence(
            String chainId,
            byte[] applicationId,
            byte[] actionCommitment,
            String policyId,
            long policyRevision,
            String actorId,
            long actorRevision,
            String keyId,
            byte[] publicKey,
            long issuedHeight,
            long deadlineHeight,
            boolean validSignature
    ) implements DirectAuthorizationEvidenceV1 {
        private TestEvidence {
            applicationId = applicationId.clone();
            actionCommitment = actionCommitment.clone();
            publicKey = publicKey.clone();
        }

        @Override public byte[] authorizationId() { return filled(5); }
        @Override public byte[] applicationId() { return applicationId.clone(); }
        @Override public byte[] actionCommitment() { return actionCommitment.clone(); }
        @Override public byte[] publicKey() { return publicKey.clone(); }
        @Override public byte[] statementDigest() { return filled(6); }
        @Override public byte[] signature() { return new byte[64]; }
        @Override public boolean verifyClaimedKey() { return validSignature; }

        private TestEvidence withChainId(String value) {
            return copy(value, applicationId, actionCommitment, policyRevision,
                    actorRevision, publicKey, deadlineHeight, validSignature);
        }

        private TestEvidence withApplicationId(byte[] value) {
            return copy(chainId, value, actionCommitment, policyRevision,
                    actorRevision, publicKey, deadlineHeight, validSignature);
        }

        private TestEvidence withActionCommitment(byte[] value) {
            return copy(chainId, applicationId, value, policyRevision,
                    actorRevision, publicKey, deadlineHeight, validSignature);
        }

        private TestEvidence withPolicyRevision(long value) {
            return copy(chainId, applicationId, actionCommitment, value,
                    actorRevision, publicKey, deadlineHeight, validSignature);
        }

        private TestEvidence withActorRevision(long value) {
            return copy(chainId, applicationId, actionCommitment, policyRevision,
                    value, publicKey, deadlineHeight, validSignature);
        }

        private TestEvidence withPublicKey(byte[] value) {
            return copy(chainId, applicationId, actionCommitment, policyRevision,
                    actorRevision, value, deadlineHeight, validSignature);
        }

        private TestEvidence withDeadlineHeight(long value) {
            return copy(chainId, applicationId, actionCommitment, policyRevision,
                    actorRevision, publicKey, value, validSignature);
        }

        private TestEvidence withValidSignature(boolean value) {
            return copy(chainId, applicationId, actionCommitment, policyRevision,
                    actorRevision, publicKey, deadlineHeight, value);
        }

        private TestEvidence copy(String chain, byte[] application, byte[] action,
                                  long policyRev, long actorRev, byte[] key,
                                  long deadline, boolean signature) {
            return new TestEvidence(chain, application, action, policyId,
                    policyRev, actorId, actorRev, keyId, key,
                    issuedHeight, deadline, signature);
        }
    }

    private static final class MemoryState implements AppStateWriter {
        private final Map<Key, byte[]> values = new LinkedHashMap<>();

        @Override public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(new Key(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public void put(byte[] key, byte[] value) {
            values.put(new Key(key), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(new Key(key)); }
    }

    private record Key(byte[] value) {
        private Key { value = value.clone(); }
        @Override public byte[] value() { return value.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(value, key.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }
}
