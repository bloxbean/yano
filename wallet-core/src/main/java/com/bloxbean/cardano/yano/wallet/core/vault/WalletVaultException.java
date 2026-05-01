package com.bloxbean.cardano.yano.wallet.core.vault;

public class WalletVaultException extends RuntimeException {
    public WalletVaultException(String message) {
        super(message);
    }

    public WalletVaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
