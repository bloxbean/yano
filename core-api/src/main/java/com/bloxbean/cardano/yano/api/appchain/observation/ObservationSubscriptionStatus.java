package com.bloxbean.cardano.yano.api.appchain.observation;

/** Durable lifecycle state of an observation subscription. */
public enum ObservationSubscriptionStatus {
    ACTIVE(0),
    COMPLETED(1),
    CANCELLED(2),
    EXPIRED(3);

    private final int code;

    ObservationSubscriptionStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ObservationSubscriptionStatus fromCode(int code) {
        return switch (code) {
            case 0 -> ACTIVE;
            case 1 -> COMPLETED;
            case 2 -> CANCELLED;
            case 3 -> EXPIRED;
            default -> throw new IllegalArgumentException("unknown observation subscription status");
        };
    }
}
