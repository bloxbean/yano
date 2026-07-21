package com.bloxbean.cardano.yano.appchain.roles.contracts;

/** Stable failure classification for the frozen role-workflow v1 contract. */
public final class RoleWorkflowException extends IllegalArgumentException {
    private final RoleWorkflowResultCode code;

    public RoleWorkflowException(RoleWorkflowResultCode code) {
        super(code.name());
        this.code = code;
    }

    public RoleWorkflowResultCode code() {
        return code;
    }
}
