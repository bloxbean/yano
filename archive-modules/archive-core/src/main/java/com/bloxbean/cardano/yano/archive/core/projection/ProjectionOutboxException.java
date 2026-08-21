package com.bloxbean.cardano.yano.archive.core.projection;

/** Outbox storage failure; never interpreted as an empty or complete envelope. */
public class ProjectionOutboxException extends RuntimeException {
    public ProjectionOutboxException(String message) {
        super(message);
    }

    public ProjectionOutboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
