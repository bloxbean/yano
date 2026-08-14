package com.bloxbean.cardano.yano.api.appchain;

import java.util.Objects;

/** Typed failure from the bounded committed-state query surface. */
public final class AppQueryException extends RuntimeException {

    /** Stable runtime reason; transport layers map these to their own statuses. */
    public enum Code {
        INVALID_REQUEST,
        REQUEST_TOO_LARGE,
        UNSUPPORTED,
        BUSY,
        TIMEOUT,
        RESULT_TOO_LARGE,
        UNAVAILABLE,
        FAILED
    }

    private final Code code;

    public AppQueryException(Code code, String message) {
        this(code, message, null);
    }

    public AppQueryException(Code code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
