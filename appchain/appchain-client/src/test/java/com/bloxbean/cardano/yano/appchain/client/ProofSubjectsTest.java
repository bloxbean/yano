package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalProposalV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RoleWorkflowKeys;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.DocTrailContract;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProofSubjectsTest {
    @Test
    void derivesEveryFirstPartySubjectThroughTheSharedCompositeMapping() {
        byte[] messageId = filled(1, 32);
        assertThat(ProofSubjects.compositeFinalizedMessage("ordered-log", messageId)
                .canonicalKey()).containsExactly(CompositeCommitmentV1.componentKey(
                "ordered-log", messageId));
        assertThat(ProofSubjects.documentHead("doc-trail", "invoice-7").canonicalKey())
                .containsExactly(CompositeCommitmentV1.componentKey(
                        "doc-trail", DocTrailContract.entityKey("invoice-7")));
        assertThat(ProofSubjects.authenticatedMapEntry(
                "authenticated-map", "orders", new byte[]{7}).canonicalKey())
                .containsExactly(CompositeCommitmentV1.componentKey(
                        "authenticated-map", AuthenticatedMapContract.canonicalKey(
                                "orders", new byte[]{7})));
        assertThat(ProofSubjects.approvalOutcome("role-approvals", "release-7")
                .canonicalKey()).containsExactly(CompositeCommitmentV1.componentKey(
                "role-approvals", RoleWorkflowKeys.proposal("release-7")));
        assertThat(ProofSubjects.compositeProfile().canonicalKey())
                .containsExactly(CompositeCommitmentV1.profileMarkerKey());
    }

    @Test
    void epochSubjectsUseBinaryEpochAndCanonicalCredentialBytes() {
        byte[] credential = filled(3, 28);
        byte[] parameterKey = ProofSubjects.HistoricalL1StateKeys.protocolParameters(500);
        assertThat(parameterKey).containsExactly(ByteBuffer.allocate(7 + Long.BYTES)
                .put("params/".getBytes(StandardCharsets.US_ASCII)).putLong(500).array());

        byte[] stakeKey = ProofSubjects.HistoricalL1StateKeys.epochStake(500, 1, credential);
        assertThat(stakeKey).containsExactly(ByteBuffer.allocate(6 + Long.BYTES + 1 + 28)
                .put("stake/".getBytes(StandardCharsets.US_ASCII)).putLong(500)
                .put((byte) 1).put(credential).array());
        assertThat(ProofSubjects.epochStake("epoch-stake", 500, 1, credential)
                .canonicalKey()).containsExactly(CompositeCommitmentV1.componentKey(
                "epoch-stake", stakeKey));
    }

    @Test
    void approvalSubjectDecodesAndBindsTheFrozenOutcome() {
        ApprovalPolicyV1 policy = new ApprovalPolicyV1("release-policy", 1,
                RecordStatus.ACTIVE, List.of("issuer"),
                List.of(new ApprovalPolicyV1.RequiredClause("auditors", "auditor", 1,
                        ApprovalPolicyV1.DistinctBy.ACTOR)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 100);
        ApprovalProposalV1 proposal = new ApprovalProposalV1(
                "release-7", policy.policyId(), policy.revision(), policy.digest(),
                "document.release.v1", filled(4, 32), 50,
                ApprovalProposalV1.ProposalStatus.APPROVED,
                "issuer-a", "issuer-org", 1, "issuer", 2,
                "issuer-key", 10, List.of());

        assertThat(ProofSubjects.approvalOutcome("role-approvals", "release-7")
                .decodePresentValue(proposal.encode())).satisfies(decoded -> {
            assertThat(decoded.proposalId()).isEqualTo(proposal.proposalId());
            assertThat(decoded.policyDigest()).containsExactly(proposal.policyDigest());
            assertThat(decoded.status()).isEqualTo(ApprovalProposalV1.ProposalStatus.APPROVED);
        });
    }

    private static byte[] filled(int value, int length) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
