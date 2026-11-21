package org.yanoproject.api.governance;

/**
 * Lifecycle status of a Conway governance proposal at a completed epoch boundary.
 *
 * <p>Ledger state persists this enum by ordinal. Constants are append-only: never reorder,
 * rename or remove one.</p>
 */
public enum GovernanceProposalStatus {
    ACTIVE,
    RATIFIED,
    ENACTED,
    EXPIRED,
    DROPPED
}
