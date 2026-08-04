package com.bloxbean.cardano.yano.appchain.roles.contracts;

/** Stable command/result codes. Application rejects are deterministic no-ops. */
public enum RoleWorkflowResultCode {
    ACCEPTED(0),
    INVALID_PAYLOAD(1),
    UNSUPPORTED_VERSION(2),
    INVALID_SIGNATURE(3),
    UNAUTHORIZED_RELAY(4),
    UNAUTHORIZED_ACTOR(5),
    UNKNOWN_RECORD(6),
    CONFLICT(7),
    EXACT_REPLAY(8),
    EXPIRED(9),
    TERMINAL(10),
    ROLE_MISMATCH(11),
    DISTINCTNESS_DUPLICATE(12),
    GOVERNANCE_THRESHOLD_NOT_MET(13),
    GOVERNANCE_PROOF_INVALID(14),
    LIMIT_EXCEEDED(15),
    CAPACITY_EXCEEDED(16),
    CRYPTO_WORK_EXCEEDED(17),
    WRONG_GENESIS(18),
    WRONG_REVISION(19),
    NOT_READY(20),
    SUPERSEDED(21),
    GOVERNED_ROUTE_UNSUPPORTED(22);

    private final int code;

    RoleWorkflowResultCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static RoleWorkflowResultCode fromCode(int code) {
        for (RoleWorkflowResultCode value : values()) {
            if (value.code == code) return value;
        }
        throw OrganizationRecordV1.invalid();
    }
}
