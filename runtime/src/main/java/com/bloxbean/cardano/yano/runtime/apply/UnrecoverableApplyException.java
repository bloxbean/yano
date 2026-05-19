package com.bloxbean.cardano.yano.runtime.apply;

/**
 * Signals a local apply failure that cannot be repaired by replacing the
 * upstream peer session.
 *
 * <p>Recoverable apply failures normally close the current peer generation and
 * restart network sync from the last safe cursor. This exception marks failures
 * where local state compensation also failed, so a fresh peer session alone is
 * not sufficient to make the node safe to advance.</p>
 */
public final class UnrecoverableApplyException extends RuntimeException {
    /**
     * Creates an unrecoverable apply failure.
     *
     * @param message failure description for logs and health status
     * @param cause original failure that made local compensation unsafe
     */
    public UnrecoverableApplyException(String message, Throwable cause) {
        super(message, cause);
    }
}
