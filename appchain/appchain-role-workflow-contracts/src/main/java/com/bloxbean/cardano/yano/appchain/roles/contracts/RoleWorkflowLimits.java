package com.bloxbean.cardano.yano.appchain.roles.contracts;

/** Consensus-owned v1 bounds. Changing a value requires a new contract version. */
public final class RoleWorkflowLimits {
    public static final int MAX_COMMAND_BYTES = 16_384;
    public static final int MAX_MUTATION_BYTES = 12_288;
    public static final int MAX_PAYLOAD_DOMAIN_BYTES = 64;
    public static final int MAX_CHAIN_ID_BYTES = 128;
    public static final int MAX_IDENTIFIER_BYTES = 63;
    public static final int MAX_ROLE_BYTES = 63;
    public static final int MAX_ROLES_PER_ACTOR = 16;
    public static final int MAX_KEYS_PER_ACTOR = 16;
    public static final int MAX_CLAUSES_PER_POLICY = 16;
    public static final int MAX_PROPOSER_ROLES = 16;
    public static final int MAX_DECISIONS_PER_PROPOSAL = 64;
    public static final int MAX_ADMINISTRATORS = 64;
    public static final int MAX_AUTHORIZATION_EVIDENCE_ITEMS = 32;
    public static final int MAX_COVERED_MUTATION_INDEXES = 128;
    public static final int MAX_PENDING_MUTATIONS = 1_024;
    public static final int MAX_PENDING_PROPOSALS = 4_096;
    public static final int MAX_PENDING_PER_ACTOR = 64;
    public static final int MAX_PENDING_PER_POLICY = 256;
    public static final int MAX_PENDING_PER_AUTHORITY = 256;
    public static final int MAX_PENDING_PER_DEADLINE = 128;
    public static final int MAX_EXPIRY_WORK_PER_BLOCK = 256;
    public static final int MAX_AUTHORITY_SUPERSESSION_WORK = 256;
    public static final int MAX_QUERY_PAGE_SIZE = 100;
    public static final int MAX_GENESIS_ORGANIZATIONS = 64;
    public static final int MAX_GENESIS_ACTORS = 128;
    public static final int MAX_GENESIS_KEYS = 256;
    public static final int MAX_GENESIS_POLICIES = 128;
    public static final int MAX_GENESIS_RECORD_BYTES = 262_144;
    public static final int MAX_AUTHORIZATION_LIFETIME_BLOCKS = 1_000_000;
    public static final int MAX_CRYPTO_WORK_UNITS_PER_BLOCK = 512;
    public static final int MAX_NESTING_DEPTH = 8;
    public static final int MAX_CBOR_ITEMS = 512;

    private RoleWorkflowLimits() {
    }
}
