package com.bloxbean.cardano.yano.api.appchain.effects;

/**
 * When an emitted effect becomes eligible for external execution
 * (ADR app-layer/010 F7). The gate is evaluated by the Effect Runtime, not by
 * consensus — the emission itself is final the moment its block commits.
 */
public enum FinalityGate {

    /** Resolve to the chain's configured {@code effects.default-gate} at emission. */
    CHAIN_DEFAULT(-1),

    /**
     * Eligible once the block is committed (threshold finality cert). The app
     * ledger is append-only after APP_FINAL, so a committed emission is already
     * irrevocable.
     */
    APP_FINAL(0),

    /**
     * Eligible once the effect's block height is covered by an L1-confirmed,
     * stability-deep anchor — the emission is externally provable against
     * Cardano before the action fires. A verifiability delay, not a
     * rollback-safety mechanism (ADR-010 F7).
     */
    L1_ANCHORED(1);

    private final int code;

    FinalityGate(int code) {
        this.code = code;
    }

    /** Wire/storage code; {@link #CHAIN_DEFAULT} is never stored. */
    public int code() {
        if (this == CHAIN_DEFAULT) {
            throw new IllegalStateException("CHAIN_DEFAULT must be resolved before encoding");
        }
        return code;
    }

    public static FinalityGate fromCode(int code) {
        return switch (code) {
            case 0 -> APP_FINAL;
            case 1 -> L1_ANCHORED;
            default -> throw new IllegalArgumentException("Unknown finality gate code: " + code);
        };
    }
}
