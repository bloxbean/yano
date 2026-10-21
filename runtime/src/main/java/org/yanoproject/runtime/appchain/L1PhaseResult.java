package org.yanoproject.runtime.appchain;

import java.util.Objects;

/**
 * Outcome of one L1 delivery phase or rollback phase (app-layer ADR-038, D4a). The delivery loop advances its cursor,
 * or completes a rollback, only when every phase succeeded. A retryable failure keeps the pending intent and retries;
 * a quarantine is terminal.
 *
 * @param kind   the outcome
 * @param reason a short machine-readable reason; required for {@code RETRYABLE} and {@code QUARANTINED}
 */
record L1PhaseResult(Kind kind, String reason) {

    enum Kind {
        /** Nothing in this block or rollback concerns the phase. */
        NO_OP,
        /** The phase's writes for this block or rollback are durable. */
        DURABLE,
        /** A failure that may succeed later, for example storage that is temporarily unavailable. */
        RETRYABLE,
        /** A terminal safety state; the loop fails closed. */
        QUARANTINED
    }

    static final L1PhaseResult NO_OP = new L1PhaseResult(Kind.NO_OP, null);
    static final L1PhaseResult DURABLE = new L1PhaseResult(Kind.DURABLE, null);

    L1PhaseResult {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.RETRYABLE || kind == Kind.QUARANTINED) && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(kind + " requires a reason");
        }
    }

    static L1PhaseResult retryable(String reason) {
        return new L1PhaseResult(Kind.RETRYABLE, reason);
    }

    static L1PhaseResult quarantined(String reason) {
        return new L1PhaseResult(Kind.QUARANTINED, reason);
    }

    boolean succeeded() {
        return kind == Kind.NO_OP || kind == Kind.DURABLE;
    }
}
