package com.bloxbean.cardano.yano.api.appchain.observation;

/** Source of the keys authorized to sign reports for a round. */
public enum ObservationReporterMode {
    ACTIVE_MEMBERS(0),
    EXTERNAL_REPORTERS(1);

    private final int code;

    ObservationReporterMode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ObservationReporterMode fromCode(int code) {
        return switch (code) {
            case 0 -> ACTIVE_MEMBERS;
            case 1 -> EXTERNAL_REPORTERS;
            default -> throw new IllegalArgumentException("unknown observation reporter mode");
        };
    }
}
