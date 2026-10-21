package org.yanoproject.ledger.rules.view;

/**
 * Thrown by rule and effects code that hits an {@link Lookup.Unavailable} read.
 *
 * <p>Validation engines catch it and reject the transaction with the phase-1 engine failure
 * {@code LedgerStateUnavailable}; it never leads to admission (ADR-056 invariant 2).</p>
 */
public class LedgerStateUnavailableException extends RuntimeException {

    public LedgerStateUnavailableException(String reason) {
        super(reason);
    }

    public LedgerStateUnavailableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
