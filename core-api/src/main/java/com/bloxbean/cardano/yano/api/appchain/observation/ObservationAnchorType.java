package com.bloxbean.cardano.yano.api.appchain.observation;

/** Consensus clock used by an observation subscription and round. */
public enum ObservationAnchorType {
    APP_HEIGHT(0),
    VERIFIED_L1_SLOT(1);

    private final int code;

    ObservationAnchorType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ObservationAnchorType fromCode(int code) {
        return switch (code) {
            case 0 -> APP_HEIGHT;
            case 1 -> VERIFIED_L1_SLOT;
            default -> throw new IllegalArgumentException("unknown observation anchor code");
        };
    }
}
