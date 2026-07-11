package com.bloxbean.cardano.yano.api.appchain;

/**
 * Thrown by {@link AppChainGateway#submit} when the local pending-message pool
 * cannot accept the message (ADR app-layer/008.1 I1.1). The message was
 * neither retained nor relayed — the caller should back off and retry.
 * The REST layer maps this to HTTP 429 (Too Many Requests).
 */
public class PoolFullException extends IllegalStateException {

    public PoolFullException(String message) {
        super(message);
    }
}
