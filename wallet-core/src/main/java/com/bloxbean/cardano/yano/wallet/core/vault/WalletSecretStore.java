package com.bloxbean.cardano.yano.wallet.core.vault;

public interface WalletSecretStore {
    boolean exists();

    void create(WalletSecret secret, char[] passphrase);

    WalletSecret unlock(char[] passphrase);

    void lock();

    void rotatePassphrase(char[] oldPassphrase, char[] newPassphrase);
}
