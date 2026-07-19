package com.bloxbean.cardano.yano.appchain.roles.contracts;

/** Consensus-owned v1 bounds. Changing a value requires a new contract version. */
public final class RoleWorkflowLimits {
    public static final int MAX_COMMAND_BYTES = 16_384;
    public static final int MAX_MUTATION_BYTES = 12_288;
    public static final int MAX_PAYLOAD_DOMAIN_BYTES = 64;
    public static final int MAX_IDENTIFIER_BYTES = 63;
    public static final int MAX_ROLE_BYTES = 63;
    public static final int MAX_ROLES_PER_ACTOR = 16;
    public static final int MAX_KEYS_PER_ACTOR = 16;
    public static final int MAX_CLAUSES_PER_POLICY = 16;
    public static final int MAX_PROPOSER_ROLES = 16;
    public static final int MAX_DECISIONS_PER_PROPOSAL = 64;
    public static final int MAX_ADMINISTRATORS = 64;
    public static final int MAX_PENDING_MUTATIONS = 1_024;
    public static final int MAX_PENDING_PROPOSALS = 10_000;
    public static final int MAX_NESTING_DEPTH = 8;
    public static final int MAX_CBOR_ITEMS = 512;

    private RoleWorkflowLimits() {
    }
}
