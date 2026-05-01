package com.bloxbean.cardano.yano.wallet.core.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileWalletSecretStoreTest {
    private static final int TEST_ITERATIONS = 10_000;

    @TempDir
    Path tempDir;

    @Test
    void createAndUnlockRoundTripsEncryptedSecret() {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        FileWalletSecretStore store = newStore(vaultFile);
        byte[] secretBytes = "plain-wallet-secret".getBytes(StandardCharsets.UTF_8);
        WalletSecret secret = new WalletSecret(
                SecretKind.MNEMONIC,
                secretBytes,
                "preprod",
                0,
                Instant.parse("2026-05-01T00:00:00Z"));

        store.create(secret, "correct horse battery staple".toCharArray());

        WalletSecret unlocked = store.unlock("correct horse battery staple".toCharArray());
        assertThat(unlocked.kind()).isEqualTo(SecretKind.MNEMONIC);
        assertThat(unlocked.secretBytes()).containsExactly(secretBytes);
        assertThat(unlocked.network()).isEqualTo("preprod");
        assertThat(unlocked.accountIndex()).isZero();
        assertThat(unlocked.createdAt()).isEqualTo(secret.createdAt());
    }

    @Test
    void wrongPassphraseFailsUnlock() {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        FileWalletSecretStore store = newStore(vaultFile);
        store.create(secret("plain-wallet-secret"), "right-passphrase".toCharArray());

        assertThatThrownBy(() -> store.unlock("wrong-passphrase".toCharArray()))
                .isInstanceOf(WalletVaultException.class)
                .hasMessageContaining("Unable to unlock wallet vault");
    }

    @Test
    void vaultFileDoesNotContainPlaintextSecret() throws Exception {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        FileWalletSecretStore store = newStore(vaultFile);
        byte[] secretBytes = "plain-wallet-secret".getBytes(StandardCharsets.UTF_8);

        store.create(secret("plain-wallet-secret"), "passphrase".toCharArray());

        String vaultJson = Files.readString(vaultFile);
        assertThat(vaultJson).doesNotContain("plain-wallet-secret");
        assertThat(vaultJson).doesNotContain(Base64.getEncoder().encodeToString(secretBytes));
        assertThat(vaultJson).contains("ciphertext");
    }

    @Test
    void rotatePassphraseReencryptsVault() {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        FileWalletSecretStore store = newStore(vaultFile);
        byte[] secretBytes = "plain-wallet-secret".getBytes(StandardCharsets.UTF_8);
        store.create(secret("plain-wallet-secret"), "old-passphrase".toCharArray());

        store.rotatePassphrase("old-passphrase".toCharArray(), "new-passphrase".toCharArray());

        assertThatThrownBy(() -> store.unlock("old-passphrase".toCharArray()))
                .isInstanceOf(WalletVaultException.class);
        WalletSecret unlocked = store.unlock("new-passphrase".toCharArray());
        assertThat(unlocked.secretBytes()).containsExactly(secretBytes);
    }

    @Test
    void secretBytesAreDefensivelyCopied() {
        byte[] original = "plain-wallet-secret".getBytes(StandardCharsets.UTF_8);
        WalletSecret secret = new WalletSecret(
                SecretKind.ACCOUNT_PRIVATE_KEY,
                original,
                "devnet",
                0,
                Instant.parse("2026-05-01T00:00:00Z"));

        original[0] = 'X';
        byte[] extracted = secret.secretBytes();
        extracted[1] = 'Y';

        assertThat(secret.secretBytes()).containsExactly("plain-wallet-secret".getBytes(StandardCharsets.UTF_8));
    }

    private FileWalletSecretStore newStore(Path vaultFile) {
        return new FileWalletSecretStore(vaultFile, new SecureRandom(), TEST_ITERATIONS);
    }

    private WalletSecret secret(String value) {
        return new WalletSecret(
                SecretKind.MNEMONIC,
                value.getBytes(StandardCharsets.UTF_8),
                "preprod",
                0,
                Instant.parse("2026-05-01T00:00:00Z"));
    }
}
