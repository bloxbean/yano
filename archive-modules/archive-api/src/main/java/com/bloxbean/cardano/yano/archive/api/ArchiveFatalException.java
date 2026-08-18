package com.bloxbean.cardano.yano.archive.api;

/**
 * A non-retryable archive error: configuration, schema, identity, or projection
 * mismatch. Retrying the same job against the same archive cannot succeed, so a
 * worker reports {@code FAILED} rather than {@code DEGRADED} and stops
 * re-deriving the batch every poll interval.
 */
public class ArchiveFatalException extends ArchiveStoreException {
    public ArchiveFatalException(String message) {
        super(message);
    }

    public ArchiveFatalException(String message, Throwable cause) {
        super(message, cause);
    }
}
