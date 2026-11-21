package org.yanoproject.api.governance;

/**
 * Reason accompanying a {@link GovernanceProposalStatus}.
 *
 * <p>Ledger state persists this enum by ordinal. Constants are append-only: never reorder,
 * rename or remove one.</p>
 */
public enum GovernanceProposalStatusReason {
    NONE,
    RATIFIED,
    ENACTED,
    EXPIRED,
    SUPERSEDED,
    INVALIDATED,
    REMOVED
}
