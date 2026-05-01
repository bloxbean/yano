package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;

import java.util.List;
import java.util.Optional;

public interface StoredWalletRepository {
    String generateMnemonic();

    StoredWalletCreation createRandomWallet(String name, WalletNetwork network, char[] passphrase);

    StoredWallet importMnemonic(String name, WalletNetwork network, String mnemonic, char[] passphrase);

    StoredWallet createAccount(String seedId, String name, WalletNetwork network, String mnemonic);

    UnlockedWallet unlock(String walletId, char[] passphrase);

    Optional<StoredWallet> find(String walletId);

    List<StoredWallet> list();

    default List<StoredWallet> listAccounts(String seedId) {
        return list().stream()
                .filter(wallet -> wallet.seedId().equals(seedId))
                .toList();
    }
}
