package org.yanoproject.ledger.rules.conway.tx;

/**
 * The transaction bytes do not decode as a Conway transaction the way Haskell's decoder requires. The engine
 * reports it as {@code ENGINE.DecodingFailure}.
 */
public final class TxDecodingException extends RuntimeException {

    public TxDecodingException(String message) {
        super(message);
    }

    public TxDecodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
