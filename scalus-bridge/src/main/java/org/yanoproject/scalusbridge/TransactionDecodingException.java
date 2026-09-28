package org.yanoproject.scalusbridge;

/**
 * Scalus could not decode the transaction bytes (after the definite-length fallback of
 * {@code ScalusTransactions}). The Scalus engine reports it as {@code ENGINE.DecodingFailure}, not as an engine
 * crash.
 */
public final class TransactionDecodingException extends RuntimeException {

    public TransactionDecodingException(Throwable cause) {
        super("Scalus cannot decode the transaction: " + cause.getMessage(), cause);
    }
}
