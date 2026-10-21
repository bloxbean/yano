package org.yanoproject.ledger.rules.view.model;

import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionType;

/**
 * Governance purposes that form a lineage (Haskell {@code GovPurposeId} / {@code GovRelation}).
 * Treasury withdrawals and info actions have no purpose.
 */
public enum GovPurpose {
    PPARAM_UPDATE,
    HARD_FORK,
    COMMITTEE,
    CONSTITUTION;

    /**
     * @param type a governance action type
     * @return its purpose, or {@code null} for actions without a lineage
     */
    public static GovPurpose of(GovActionType type) {
        return switch (type) {
            case PARAMETER_CHANGE_ACTION -> PPARAM_UPDATE;
            case HARD_FORK_INITIATION_ACTION -> HARD_FORK;
            case NO_CONFIDENCE, UPDATE_COMMITTEE -> COMMITTEE;
            case NEW_CONSTITUTION -> CONSTITUTION;
            case TREASURY_WITHDRAWALS_ACTION, INFO_ACTION -> null;
        };
    }
}
