package org.yanoproject.api.wallet;

/** The caller must select a still-canonical saved cursor and restore its outpoint state. */
public final class WalletScanRollbackException extends IllegalStateException {
    public WalletScanRollbackException(String message) { super(message); }
}
