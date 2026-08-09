package com.bloxbean.cardano.yano.api.appchain.l1view;

/** Canonical reason accompanying an ADR-028 proposal lifecycle status. */
public enum GovernanceProposalStatusReason {
    NONE,
    RATIFIED,
    ENACTED,
    EXPIRED,
    SUPERSEDED,
    INVALIDATED,
    REMOVED
}
