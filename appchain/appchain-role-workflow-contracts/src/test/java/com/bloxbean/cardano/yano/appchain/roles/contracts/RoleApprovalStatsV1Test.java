package com.bloxbean.cardano.yano.appchain.roles.contracts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleApprovalStatsV1Test {
    @Test
    void countersRoundTripAndPreserveTheCreatedInvariant() {
        RoleApprovalStatsV1 pending = RoleApprovalStatsV1.empty().proposalCreated();
        RoleApprovalStatsV1 approved = pending.terminal(
                ApprovalProposalV1.ProposalStatus.APPROVED);

        assertThat(RoleApprovalStatsV1.decode(approved.encode())).isEqualTo(
                new RoleApprovalStatsV1(1, 0, 1, 0, 0, 0));
        assertThatThrownBy(() -> new RoleApprovalStatsV1(2, 0, 1, 0, 0, 0))
                .isInstanceOf(RoleWorkflowException.class);
        assertThatThrownBy(() -> approved.terminal(
                ApprovalProposalV1.ProposalStatus.CANCELLED))
                .isInstanceOf(RoleWorkflowException.class);
    }

    @Test
    void publicStateKeysRejectInvalidIdentifiersAndRevisions() {
        assertThatThrownBy(() -> RoleWorkflowKeys.actorCurrent("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RoleWorkflowKeys.policyRevision("policy", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RoleWorkflowKeys.approvalStats()).isNotEmpty();
    }
}
