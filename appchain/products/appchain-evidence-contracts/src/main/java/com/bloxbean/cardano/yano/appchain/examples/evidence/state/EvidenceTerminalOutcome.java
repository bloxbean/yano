package com.bloxbean.cardano.yano.appchain.examples.evidence.state;

/**
 * Frozen evidence-v1 terminal outcome codes.
 *
 * <p>This domain enum deliberately does not depend on the Effect Runtime API.
 * The executable registry maps framework outcomes into these wire values at
 * its boundary.</p>
 */
public enum EvidenceTerminalOutcome {
    /** The external action completed with a valid terminal response. */
    CONFIRMED(1),
    /** The external action completed with a definitive failure. */
    FAILED(2),
    /** Execution was cancelled before a successful result committed. */
    CANCELLED(3),
    /** No result committed before the deterministic expiry height. */
    EXPIRED(4);

    private final int code;

    EvidenceTerminalOutcome(int code) {
        this.code = code;
    }

    /** Returns the immutable evidence-v1 wire code. */
    public int code() {
        return code;
    }

    /** Resolves one immutable evidence-v1 wire code. */
    public static EvidenceTerminalOutcome fromCode(int code) {
        return switch (code) {
            case 1 -> CONFIRMED;
            case 2 -> FAILED;
            case 3 -> CANCELLED;
            case 4 -> EXPIRED;
            default -> throw new IllegalArgumentException(
                    "Unknown evidence terminal outcome code: " + code);
        };
    }
}
