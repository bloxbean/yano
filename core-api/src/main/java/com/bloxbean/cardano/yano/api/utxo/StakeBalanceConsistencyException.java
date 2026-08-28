package com.bloxbean.cardano.yano.api.utxo;

/** Raised when a live stake view cannot prove the requested canonical coordinate. */
public class StakeBalanceConsistencyException extends IllegalStateException {
    public StakeBalanceConsistencyException(String message) {
        super(message);
    }

    public StakeBalanceConsistencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
