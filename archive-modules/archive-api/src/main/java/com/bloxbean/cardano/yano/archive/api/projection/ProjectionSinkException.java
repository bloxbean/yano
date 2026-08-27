package com.bloxbean.cardano.yano.archive.api.projection;

/** Sink-side failure that must not be interpreted as progress. */
public class ProjectionSinkException extends RuntimeException {
    public ProjectionSinkException(String message) {
        super(message);
    }

    public ProjectionSinkException(String message, Throwable cause) {
        super(message, cause);
    }
}
