package com.bloxbean.cardano.yano.api.appchain.l1view;

/** Closed ADR-028 governance-action wire vocabulary. */
public enum GovernanceActionType {
    PARAMETER_CHANGE,
    HARD_FORK_INITIATION,
    TREASURY_WITHDRAWALS,
    NO_CONFIDENCE,
    UPDATE_COMMITTEE,
    NEW_CONSTITUTION,
    INFO_ACTION
}
