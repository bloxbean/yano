package org.yanoproject.ledger.amaru.runtime;

/**
 * A module call did not produce a usable response: a trap, a timeout, an instance that could not be
 * created, or an undecodable response. The transaction is rejected with {@code AmaruEngineFailure}
 * (ADR-057 invariant 4), or {@code AmaruEngineUnhealthy} once the engine has turned unhealthy.
 */
public class AmaruEngineException extends RuntimeException {

    public enum Kind {
        TRAP,
        TIMEOUT,
        BUSY,
        UNHEALTHY,
        BAD_RESPONSE
    }

    private final Kind kind;

    public AmaruEngineException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public AmaruEngineException(Kind kind, String message) {
        this(kind, message, null);
    }

    public Kind kind() {
        return kind;
    }
}
