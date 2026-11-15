package org.yanoproject.api.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Ledger state persists both enums by ordinal: the constant order is a storage format. */
class GovernanceEnumOrderTest {

    @Test
    void proposalStatusOrderIsPinned() {
        assertThat(GovernanceProposalStatus.values()).containsExactly(
                GovernanceProposalStatus.ACTIVE,
                GovernanceProposalStatus.RATIFIED,
                GovernanceProposalStatus.ENACTED,
                GovernanceProposalStatus.EXPIRED,
                GovernanceProposalStatus.DROPPED);
    }

    @Test
    void proposalStatusReasonOrderIsPinned() {
        assertThat(GovernanceProposalStatusReason.values()).containsExactly(
                GovernanceProposalStatusReason.NONE,
                GovernanceProposalStatusReason.RATIFIED,
                GovernanceProposalStatusReason.ENACTED,
                GovernanceProposalStatusReason.EXPIRED,
                GovernanceProposalStatusReason.SUPERSEDED,
                GovernanceProposalStatusReason.INVALIDATED,
                GovernanceProposalStatusReason.REMOVED);
    }
}
