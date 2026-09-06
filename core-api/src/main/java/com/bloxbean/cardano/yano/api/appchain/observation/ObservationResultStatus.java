package com.bloxbean.cardano.yano.api.appchain.observation;

/** Application-visible terminal status for an observation round. */
public enum ObservationResultStatus {
    VALUE(0),
    NO_RESULT(1),
    EXPIRED(2),
    CANCELLED(3);

    private final int code;

    ObservationResultStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ObservationResultStatus fromCode(int code) {
        return switch (code) {
            case 0 -> VALUE;
            case 1 -> NO_RESULT;
            case 2 -> EXPIRED;
            case 3 -> CANCELLED;
            default -> throw new IllegalArgumentException("unknown observation result status");
        };
    }
}
