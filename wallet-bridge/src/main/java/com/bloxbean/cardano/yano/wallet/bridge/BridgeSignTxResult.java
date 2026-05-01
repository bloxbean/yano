package com.bloxbean.cardano.yano.wallet.bridge;

public record BridgeSignTxResult(String witnessSetCborHex) {
    public BridgeSignTxResult {
        if (witnessSetCborHex == null || witnessSetCborHex.isBlank()) {
            throw new IllegalArgumentException("witnessSetCborHex is required");
        }
    }
}
