package com.bloxbean.cardano.yano.appchain.roles.contracts;

public enum RecordStatus {
    ACTIVE(0), SUSPENDED(1), REVOKED(2);

    private final int code;

    RecordStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static RecordStatus fromCode(int code) {
        for (RecordStatus value : values()) if (value.code == code) return value;
        throw new RoleWorkflowException(RoleWorkflowResultCode.INVALID_PAYLOAD);
    }
}
