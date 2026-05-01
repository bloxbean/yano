package com.bloxbean.cardano.yano.wallet.core.vault;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

public class FileWalletSecretStore implements WalletSecretStore {
    public static final int CURRENT_VERSION = 1;
    public static final int DEFAULT_PBKDF2_ITERATIONS = 210_000;

    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String AES = "AES";
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;

    private final Path vaultFile;
    private final SecureRandom secureRandom;
    private final int iterations;
    private final ObjectMapper objectMapper;

    public FileWalletSecretStore(Path vaultFile) {
        this(vaultFile, SecureRandomHolder.INSTANCE, DEFAULT_PBKDF2_ITERATIONS);
    }

    public FileWalletSecretStore(Path vaultFile, SecureRandom secureRandom, int iterations) {
        this.vaultFile = Objects.requireNonNull(vaultFile, "vaultFile is required");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom is required");
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        this.iterations = iterations;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }

    @Override
    public boolean exists() {
        return Files.exists(vaultFile);
    }

    @Override
    public void create(WalletSecret secret, char[] passphrase) {
        if (exists()) {
            throw new WalletVaultException("Wallet vault already exists: " + vaultFile);
        }
        writeEncrypted(secret, passphrase);
    }

    @Override
    public WalletSecret unlock(char[] passphrase) {
        requirePassphrase(passphrase);
        if (!exists()) {
            throw new WalletVaultException("Wallet vault does not exist: " + vaultFile);
        }

        try {
            EncryptedVaultFile encrypted = objectMapper.readValue(vaultFile.toFile(), EncryptedVaultFile.class);
            validateVaultFile(encrypted);

            byte[] salt = decode(encrypted.salt());
            byte[] nonce = decode(encrypted.nonce());
            byte[] ciphertext = decode(encrypted.ciphertext());
            SecretKeySpec key = deriveKey(passphrase, salt, encrypted.iterations());
            try {
                byte[] plaintext = decrypt(ciphertext, key, nonce);
                try {
                    WalletSecretPayload payload = objectMapper.readValue(plaintext, WalletSecretPayload.class);
                    byte[] secret = decode(payload.secretBase64());
                    return new WalletSecret(
                            SecretKind.valueOf(payload.kind()),
                            secret,
                            payload.network(),
                            payload.accountIndex(),
                            Instant.parse(payload.createdAt()));
                } finally {
                    Arrays.fill(plaintext, (byte) 0);
                }
            } finally {
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(nonce, (byte) 0);
                Arrays.fill(ciphertext, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new WalletVaultException("Unable to unlock wallet vault", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new WalletVaultException("Invalid wallet vault file", e);
        }
    }

    @Override
    public void lock() {
        // File-backed store has no long-lived unlocked state.
    }

    @Override
    public void rotatePassphrase(char[] oldPassphrase, char[] newPassphrase) {
        WalletSecret secret = unlock(oldPassphrase);
        try {
            writeEncrypted(secret, newPassphrase);
        } finally {
            secret.destroy();
        }
    }

    private void writeEncrypted(WalletSecret secret, char[] passphrase) {
        Objects.requireNonNull(secret, "secret is required");
        requirePassphrase(passphrase);

        byte[] salt = randomBytes(SALT_BYTES);
        byte[] nonce = randomBytes(NONCE_BYTES);
        SecretKeySpec key = deriveKey(passphrase, salt, iterations);
        byte[] plaintext = null;
        byte[] ciphertext = null;

        try {
            WalletSecretPayload payload = new WalletSecretPayload(
                    secret.kind().name(),
                    encode(secret.secretBytes()),
                    secret.network(),
                    secret.accountIndex(),
                    secret.createdAt().toString());
            plaintext = objectMapper.writeValueAsBytes(payload);
            ciphertext = encrypt(plaintext, key, nonce);

            EncryptedVaultFile encrypted = new EncryptedVaultFile(
                    CURRENT_VERSION,
                    KDF,
                    iterations,
                    CIPHER,
                    encode(salt),
                    encode(nonce),
                    encode(ciphertext));
            writeAtomically(encrypted);
        } catch (GeneralSecurityException | IOException e) {
            throw new WalletVaultException("Unable to write wallet vault", e);
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
        }
    }

    private void writeAtomically(EncryptedVaultFile encrypted) throws IOException {
        Path parent = vaultFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tempFile = parent != null
                ? Files.createTempFile(parent, vaultFile.getFileName().toString(), ".tmp")
                : Files.createTempFile(vaultFile.getFileName().toString(), ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), encrypted);
            try {
                Files.move(tempFile, vaultFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, vaultFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private byte[] encrypt(byte[] plaintext, SecretKeySpec key, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return cipher.doFinal(plaintext);
    }

    private byte[] decrypt(byte[] ciphertext, SecretKeySpec key, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return cipher.doFinal(ciphertext);
    }

    private SecretKeySpec deriveKey(char[] passphrase, byte[] salt, int iterations) {
        PBEKeySpec keySpec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        byte[] encoded = null;
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF);
            encoded = factory.generateSecret(keySpec).getEncoded();
            return new SecretKeySpec(encoded, AES);
        } catch (GeneralSecurityException e) {
            throw new WalletVaultException("Unable to derive wallet vault key", e);
        } finally {
            keySpec.clearPassword();
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    private void validateVaultFile(EncryptedVaultFile encrypted) {
        if (encrypted.version() != CURRENT_VERSION) {
            throw new WalletVaultException("Unsupported wallet vault version: " + encrypted.version());
        }
        if (!KDF.equals(encrypted.kdf())) {
            throw new WalletVaultException("Unsupported wallet vault KDF: " + encrypted.kdf());
        }
        if (!CIPHER.equals(encrypted.cipher())) {
            throw new WalletVaultException("Unsupported wallet vault cipher: " + encrypted.cipher());
        }
        if (encrypted.iterations() <= 0) {
            throw new WalletVaultException("Invalid wallet vault KDF iterations");
        }
    }

    private void requirePassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length == 0) {
            throw new WalletVaultException("Wallet passphrase is required");
        }
    }

    private String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private byte[] decode(String value) {
        return Base64.getDecoder().decode(value.getBytes(StandardCharsets.US_ASCII));
    }

    private record EncryptedVaultFile(
            int version,
            String kdf,
            int iterations,
            String cipher,
            String salt,
            String nonce,
            String ciphertext) {
    }

    private record WalletSecretPayload(
            String kind,
            String secretBase64,
            String network,
            int accountIndex,
            String createdAt) {
    }

    private static final class SecureRandomHolder {
        private static final SecureRandom INSTANCE = new SecureRandom();
    }
}
