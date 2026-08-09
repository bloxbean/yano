package com.bloxbean.cardano.yano.appchain.history;

/** Stable public identities of the Cardano History product family. */
public final class CardanoHistoryProduct {
    public static final String BUNDLE_ID = "com.bloxbean.cardano.yano.appchain.cardano-history";
    public static final String STATE_MACHINE_ID = "cardano-history";
    public static final String APPLICATION_VERSION = "1.0.0";
    public static final String PARAMS_COMPONENT = "l1-epoch-params-v1";
    public static final String STAKE_COMPONENT = "l1-epoch-stake-v1";
    public static final String GOVERNANCE_COMPONENT = "l1-epoch-governance-v1";
    public static final String PROPOSAL_COMPONENT = "l1-epoch-proposal-history-v1";
    public static final String DREP_COMPONENT = "l1-epoch-drep-distribution-v1";

    private CardanoHistoryProduct() { }
}
