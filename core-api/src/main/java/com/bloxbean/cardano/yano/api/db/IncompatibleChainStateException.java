package com.bloxbean.cardano.yano.api.db;

/** Raised when persisted chainstate cannot be opened safely by the running build. */
public final class IncompatibleChainStateException extends IllegalStateException {
    public IncompatibleChainStateException(String message) {
        super(message);
    }
}
