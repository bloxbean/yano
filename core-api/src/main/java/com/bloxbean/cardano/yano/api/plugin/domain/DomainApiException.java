package com.bloxbean.cardano.yano.api.plugin.domain;

import java.util.Objects;

/** Typed host-dispatch failure; HTTP and library adapters map the stable code. */
public final class DomainApiException extends RuntimeException {
    public enum Code {
        INVALID_REQUEST,
        NOT_FOUND,
        BUSY,
        TIMEOUT,
        RESULT_TOO_LARGE,
        UNAVAILABLE,
        FAILED
    }

    private final Code code;

    public DomainApiException(Code code, String message) {
        this(code, message, null);
    }

    public DomainApiException(Code code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
