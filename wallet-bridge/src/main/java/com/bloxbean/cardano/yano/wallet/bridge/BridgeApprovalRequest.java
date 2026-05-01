package com.bloxbean.cardano.yano.wallet.bridge;

public record BridgeApprovalRequest(
        BridgeSession session,
        BridgeMethod method,
        String txCborHex,
        boolean partialSign) {

    public BridgeApprovalRequest {
        if (session == null) {
            throw new IllegalArgumentException("session is required");
        }
        if (method == null) {
            throw new IllegalArgumentException("method is required");
        }
        if (method == BridgeMethod.SIGN_TX || method == BridgeMethod.SUBMIT_TX) {
            if (txCborHex == null || txCborHex.isBlank()) {
                throw new IllegalArgumentException("txCborHex is required");
            }
        }
    }
}
