package com.bloxbean.cardano.yano.api.appchain.effects;

/**
 * Whether an effect's terminal outcome re-enters the chain (ADR app-layer/010
 * F8). Only {@code CHAIN} effects participate in {@code ~fx/result}
 * incorporation, deterministic expiry, and outcome commitments; {@code NONE}
 * effects terminate in the runtime tier with zero chain footprint.
 */
public enum ResultPolicy {

    /** Operator-only outcome: recorded node-locally, surfaced via REST/metrics. */
    NONE(0),

    /**
     * The outcome is fed back as a sequenced {@code ~fx/result} message and
     * incorporated exactly once into deterministic state (first result wins);
     * the state machine sees it via {@code onEffectResult}.
     */
    CHAIN(1);

    private final int code;

    ResultPolicy(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ResultPolicy fromCode(int code) {
        return switch (code) {
            case 0 -> NONE;
            case 1 -> CHAIN;
            default -> throw new IllegalArgumentException("Unknown result policy code: " + code);
        };
    }
}
