package com.bloxbean.cardano.yano.api.appchain;

/** Stable public IDs used by the v1 application capability manifest. */
public final class AppCapabilityIds {
    public static final String DIRECT_ROLE = "authorization:direct-role-v1";
    public static final String BASIC_APPROVAL = "approval:basic-quorum-v1";
    public static final String ACTOR_ROLE_APPROVAL = "approval:actor-role-v1";
    public static final String OUTBOX_EFFECTS = "effects:outbox-v1";
    public static final String L1_VAULT_DEPOSIT = "l1-observer:eutxo-vault-deposit-v1";
    public static final String L1_WITHDRAWAL_CONFIRMATION =
            "l1-observer:eutxo-withdrawal-confirmation-v1";
    public static final String FINALIZED_MESSAGE = "state-index:finalized-message-v1";
    public static final String MPF = "state-commitment:mpf-blake2b256-v1";
    public static final String JMT = "state-commitment:jmt-blake2b256-v1";
    public static final String AUTHENTICATED_SNAPSHOTS = "authenticated-snapshots-v1";

    private AppCapabilityIds() {
    }
}
