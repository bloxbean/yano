package com.bloxbean.cardano.yano.wallet.bridge;

public class BridgeException extends RuntimeException {
    private final BridgeError error;
    private final BridgeMethod method;

    public BridgeException(BridgeError error, BridgeMethod method, String message) {
        super(message);
        this.error = error;
        this.method = method;
    }

    public BridgeException(BridgeError error, BridgeMethod method, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
        this.method = method;
    }

    public BridgeError error() {
        return error;
    }

    public BridgeMethod method() {
        return method;
    }
}
