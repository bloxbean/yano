package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.vault.WalletVaultException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStoredWalletRepositoryTest {
    private static final String MNEMONIC =
            "drive useless envelope shine range ability time copper alarm museum near flee wrist live type device meadow allow churn purity wisdom praise drop code";

    @TempDir
    Path tempDir;

    @Test
    void importsMnemonicIntoEncryptedNetworkWalletDirectoryAndUnlocksIt() throws Exception {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);

        StoredWallet stored = repository.importMnemonic(
                "Preprod test wallet",
                WalletNetwork.PREPROD,
                MNEMONIC,
                "passphrase".toCharArray());

        assertThat(stored.networkId()).isEqualTo("preprod");
        assertThat(stored.baseAddress()).startsWith("addr_test");
        assertThat(stored.stakeAddress()).startsWith("stake_test");
        assertThat(stored.drepId()).isNotBlank();
        assertThat(repository.list()).containsExactly(stored);

        Path networkDir = tempDir.resolve("preprod").resolve("wallets");
        String indexJson = Files.readString(networkDir.resolve("index.json"));
        String vaultJson = Files.readString(networkDir.resolve(stored.vaultFile()));
        assertThat(indexJson).contains("Preprod test wallet");
        assertThat(indexJson).doesNotContain(MNEMONIC);
        assertThat(vaultJson).doesNotContain(MNEMONIC);
        assertThat(vaultJson).contains("ciphertext");

        UnlockedWallet unlocked = repository.unlock(stored.id(), "passphrase".toCharArray());
        assertThat(unlocked.profile()).isEqualTo(stored);
        assertThat(unlocked.wallet().getBaseAddressString(0)).isEqualTo(stored.baseAddress());
    }

    @Test
    void buildsMultiAddressAccountViewWithStakeAddressAndDrepId() {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet stored = repository.importMnemonic("Wallet", WalletNetwork.PREPROD, MNEMONIC, "passphrase".toCharArray());
        UnlockedWallet unlocked = repository.unlock(stored.id(), "passphrase".toCharArray());

        WalletAccountView view = new WalletAddressService().accountView(stored, unlocked.wallet(), 5);

        assertThat(view.stakeAddress()).isEqualTo(stored.stakeAddress());
        assertThat(view.drepId()).isEqualTo(stored.drepId());
        assertThat(view.receiveAddresses()).hasSize(5);
        assertThat(view.receiveAddresses())
                .extracting(WalletAddressView::addressIndex)
                .containsExactly(0, 1, 2, 3, 4);
        assertThat(view.receiveAddresses())
                .extracting(WalletAddressView::role)
                .containsOnly("receive");
        assertThat(view.receiveAddresses())
                .extracting(WalletAddressView::derivationPath)
                .containsExactly(
                        "m/1852'/1815'/0'/0/0",
                        "m/1852'/1815'/0'/0/1",
                        "m/1852'/1815'/0'/0/2",
                        "m/1852'/1815'/0'/0/3",
                        "m/1852'/1815'/0'/0/4");
        assertThat(view.receiveAddresses())
                .extracting(WalletAddressView::baseAddress)
                .allSatisfy(address -> assertThat(address).startsWith("addr_test"));
        assertThat(view.receiveAddresses())
                .extracting(WalletAddressView::enterpriseAddress)
                .allSatisfy(address -> assertThat(address).startsWith("addr_test"));
    }

    @Test
    void createsAdditionalAccountUnderSameEncryptedWalletSeed() throws Exception {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet account0 = repository.importMnemonic("Wallet", WalletNetwork.PREPROD, MNEMONIC, "passphrase".toCharArray());

        StoredWallet account1 = repository.createAccount(account0.seedId(), "Trading", WalletNetwork.PREPROD, MNEMONIC);

        assertThat(account0.seedId()).isEqualTo(account0.id());
        assertThat(account1.seedId()).isEqualTo(account0.seedId());
        assertThat(account1.accountIndex()).isEqualTo(1);
        assertThat(account1.vaultFile()).isEqualTo(account0.vaultFile());
        assertThat(account1.baseAddress()).isNotEqualTo(account0.baseAddress());
        assertThat(repository.listAccounts(account0.seedId()))
                .extracting(StoredWallet::accountIndex)
                .containsExactly(0, 1);

        UnlockedWallet unlocked = repository.unlock(account1.id(), "passphrase".toCharArray());
        assertThat(unlocked.profile()).isEqualTo(account1);
        assertThat(unlocked.wallet().getAccountNo()).isEqualTo(1);
        assertThat(unlocked.wallet().getBaseAddressString(0)).isEqualTo(account1.baseAddress());

        Path networkDir = tempDir.resolve("preprod").resolve("wallets");
        String vaultJson = Files.readString(networkDir.resolve(account1.vaultFile()));
        assertThat(vaultJson).doesNotContain(MNEMONIC);
    }

    @Test
    void rejectsDuplicateWalletForSameNetwork() {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        repository.importMnemonic("One", WalletNetwork.PREPROD, MNEMONIC, "passphrase".toCharArray());

        assertThatThrownBy(() -> repository.importMnemonic("Two", WalletNetwork.PREPROD, MNEMONIC, "passphrase".toCharArray()))
                .isInstanceOf(WalletVaultException.class)
                .hasMessageContaining("Wallet already exists");
    }

    @Test
    void separatesWalletsByNetworkDirectory() {
        FileStoredWalletRepository preprod = repository(WalletNetwork.PREPROD);
        FileStoredWalletRepository preview = repository(WalletNetwork.PREVIEW);

        StoredWallet preprodWallet = preprod.importMnemonic("Preprod", WalletNetwork.PREPROD, MNEMONIC, "passphrase".toCharArray());
        StoredWallet previewWallet = preview.importMnemonic("Preview", WalletNetwork.PREVIEW, MNEMONIC, "passphrase".toCharArray());

        assertThat(preprodWallet.networkId()).isEqualTo("preprod");
        assertThat(previewWallet.networkId()).isEqualTo("preview");
        assertThat(Files.exists(tempDir.resolve("preprod").resolve("wallets").resolve("index.json"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("preview").resolve("wallets").resolve("index.json"))).isTrue();
    }

    @Test
    void createsRandomWalletAndReturnsMnemonicForUserBackup() {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);

        StoredWalletCreation created = repository.createRandomWallet(
                "Generated wallet",
                WalletNetwork.PREPROD,
                "passphrase".toCharArray());

        assertThat(created.mnemonic().split("\\s+")).hasSize(24);
        assertThat(created.wallet().name()).isEqualTo("Generated wallet");
        assertThat(repository.unlock(created.wallet().id(), "passphrase".toCharArray())
                .wallet()
                .getBaseAddressString(0)).isEqualTo(created.wallet().baseAddress());
    }

    @Test
    void wrongPassphraseDoesNotUnlockStoredWallet() {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet stored = repository.importMnemonic("Wallet", WalletNetwork.PREPROD, MNEMONIC, "right".toCharArray());

        assertThatThrownBy(() -> repository.unlock(stored.id(), "wrong".toCharArray()))
                .isInstanceOf(WalletVaultException.class);
    }

    private FileStoredWalletRepository repository(WalletNetwork network) {
        return new FileStoredWalletRepository(tempDir.resolve(network.id()), network);
    }
}
