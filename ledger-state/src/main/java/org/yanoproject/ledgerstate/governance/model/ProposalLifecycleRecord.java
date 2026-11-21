package org.yanoproject.ledgerstate.governance.model;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import org.yanoproject.api.governance.GovernanceProposalStatus;
import org.yanoproject.api.governance.GovernanceProposalStatusReason;

import java.util.Objects;

/**
 * Rollback-safe proposal lifecycle fact persisted for one completed epoch boundary.
 * Terminal facts are copied into later snapshots so historical point queries never
 * depend on the mutable active-proposal set.
 */
public record ProposalLifecycleRecord(
        GovActionType actionType,
        GovernanceProposalStatus status,
        GovernanceProposalStatusReason reason,
        int proposedEpoch,
        int expiresAfterEpoch
) {
    public ProposalLifecycleRecord {
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        if (proposedEpoch < 0 || expiresAfterEpoch < proposedEpoch) {
            throw new IllegalArgumentException("invalid proposal lifecycle epoch range");
        }
    }
}
