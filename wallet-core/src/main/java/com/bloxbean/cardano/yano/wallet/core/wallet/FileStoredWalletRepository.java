package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.client.crypto.MnemonicUtil;
import com.bloxbean.cardano.client.crypto.bip39.Words;
import com.bloxbean.cardano.hdwallet.Wallet;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.vault.FileWalletSecretStore;
import com.bloxbean.cardano.yano.wallet.core.vault.SecretKind;
import com.bloxbean.cardano.yano.wallet.core.vault.WalletSecret;
import com.bloxbean.cardano.yano.wallet.core.vault.WalletVaultException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class FileStoredWalletRepository implements StoredWalletRepository {
    private static final TypeReference<List<StoredWalletIndexEntry>> WALLET_LIST =
            new TypeReference<>() {
            };
    private static final int DEFAULT_ACCOUNT_INDEX = 0;

    private final Path networkWalletDir;
    private final Path indexFile;
    private final WalletNetwork network;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;

    public FileStoredWalletRepository(Path networkDataDir, WalletNetwork network) {
        this(networkDataDir, network, new SecureRandom());
    }

    FileStoredWalletRepository(Path networkDataDir, WalletNetwork network, SecureRandom secureRandom) {
        this.network = Objects.requireNonNull(network, "network is required");
        this.networkWalletDir = Objects.requireNonNull(networkDataDir, "networkDataDir is required")
                .toAbsolutePath()
                .normalize()
                .resolve("wallets");
        this.indexFile = networkWalletDir.resolve("index.json");
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom is required");
    }

    @Override
    public String generateMnemonic() {
        return MnemonicUtil.generateNew(Words.TWENTY_FOUR);
    }

    @Override
    public synchronized StoredWalletCreation createRandomWallet(String name, WalletNetwork network, char[] passphrase) {
        String mnemonic = generateMnemonic();
        StoredWallet wallet = importMnemonic(name, network, mnemonic, passphrase);
        return new StoredWalletCreation(wallet, mnemonic);
    }

    @Override
    public synchronized StoredWallet importMnemonic(String name, WalletNetwork network, String mnemonic, char[] passphrase) {
        requireNetwork(network);
        requireName(name);
        requirePassphrase(passphrase);
        String normalizedMnemonic = normalizeMnemonic(mnemonic);
        MnemonicUtil.validateMnemonic(normalizedMnemonic);

        Wallet wallet = Wallet.createFromMnemonic(network.toCclNetwork(), normalizedMnemonic, DEFAULT_ACCOUNT_INDEX);
        String baseAddress = wallet.getBaseAddressString(0);
        String stakeAddress = wallet.getStakeAddress();
        String drepId = wallet.getAccountAtIndex(0).drepId();

        List<StoredWallet> wallets = new ArrayList<>(readIndex());
        wallets.stream()
                .filter(existing -> existing.baseAddress().equals(baseAddress))
                .findFirst()
                .ifPresent(existing -> {
                    throw new WalletVaultException("Wallet already exists for this network: " + existing.name());
                });

        String walletId = nextWalletId(wallets);
        String seedId = walletId;
        String vaultFile = walletId + "/vault.json";
        Instant now = Instant.now();
        StoredWallet profile = new StoredWallet(
                walletId,
                seedId,
                name.trim(),
                network.id(),
                DEFAULT_ACCOUNT_INDEX,
                baseAddress,
                stakeAddress,
                drepId,
                vaultFile,
                now,
                now);

        byte[] mnemonicBytes = normalizedMnemonic.getBytes(StandardCharsets.UTF_8);
        WalletSecret secret = new WalletSecret(
                SecretKind.MNEMONIC,
                mnemonicBytes,
                network.id(),
                DEFAULT_ACCOUNT_INDEX,
                now);
        try {
            new FileWalletSecretStore(networkWalletDir.resolve(vaultFile)).create(secret, passphrase);
        } finally {
            secret.destroy();
            Arrays.fill(mnemonicBytes, (byte) 0);
        }

        wallets.add(profile);
        writeIndex(wallets);
        return profile;
    }

    @Override
    public synchronized StoredWallet createAccount(String seedId, String name, WalletNetwork network, String mnemonic) {
        requireNetwork(network);
        requireName(name);
        if (seedId == null || seedId.isBlank()) {
            throw new IllegalArgumentException("seedId is required");
        }
        String normalizedMnemonic = normalizeMnemonic(mnemonic);
        MnemonicUtil.validateMnemonic(normalizedMnemonic);

        List<StoredWallet> wallets = new ArrayList<>(readIndex());
        StoredWallet seedWallet = wallets.stream()
                .filter(wallet -> wallet.seedId().equals(seedId))
                .min(Comparator.comparingInt(StoredWallet::accountIndex))
                .orElseThrow(() -> new WalletVaultException("Wallet seed not found: " + seedId));

        int accountIndex = wallets.stream()
                .filter(wallet -> wallet.seedId().equals(seedId))
                .mapToInt(StoredWallet::accountIndex)
                .max()
                .orElse(DEFAULT_ACCOUNT_INDEX) + 1;
        Wallet wallet = Wallet.createFromMnemonic(network.toCclNetwork(), normalizedMnemonic, accountIndex);
        String baseAddress = wallet.getBaseAddressString(0);
        wallets.stream()
                .filter(existing -> existing.baseAddress().equals(baseAddress))
                .findFirst()
                .ifPresent(existing -> {
                    throw new WalletVaultException("Account already exists for this wallet: " + existing.name());
                });

        String accountId = nextWalletId(wallets);
        Instant now = Instant.now();
        StoredWallet account = new StoredWallet(
                accountId,
                seedWallet.seedId(),
                name.trim(),
                network.id(),
                accountIndex,
                baseAddress,
                wallet.getStakeAddress(),
                wallet.getAccountAtIndex(0).drepId(),
                seedWallet.vaultFile(),
                now,
                now);
        wallets.add(account);
        writeIndex(wallets);
        return account;
    }

    @Override
    public synchronized UnlockedWallet unlock(String walletId, char[] passphrase) {
        requirePassphrase(passphrase);
        StoredWallet profile = find(walletId)
                .orElseThrow(() -> new WalletVaultException("Wallet not found: " + walletId));
        WalletSecret secret = new FileWalletSecretStore(networkWalletDir.resolve(profile.vaultFile()))
                .unlock(passphrase);
        try {
            if (secret.kind() != SecretKind.MNEMONIC) {
                throw new WalletVaultException("Unsupported wallet secret kind: " + secret.kind());
            }
            String mnemonic = new String(secret.secretBytes(), StandardCharsets.UTF_8);
            Wallet wallet = Wallet.createFromMnemonic(network.toCclNetwork(), mnemonic, profile.accountIndex());
            return new UnlockedWallet(profile, wallet);
        } finally {
            secret.destroy();
        }
    }

    @Override
    public synchronized Optional<StoredWallet> find(String walletId) {
        if (walletId == null || walletId.isBlank()) {
            return Optional.empty();
        }
        return readIndex().stream()
                .filter(wallet -> wallet.id().equals(walletId))
                .findFirst();
    }

    @Override
    public synchronized List<StoredWallet> list() {
        return readIndex().stream()
                .sorted(Comparator.comparing(StoredWallet::createdAt))
                .toList();
    }

    private List<StoredWallet> readIndex() {
        if (!Files.exists(indexFile)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(indexFile.toFile(), WALLET_LIST).stream()
                    .map(entry -> new StoredWallet(
                            entry.id(),
                            entry.seedId() == null || entry.seedId().isBlank() ? entry.id() : entry.seedId(),
                            entry.name(),
                            entry.networkId(),
                            entry.accountIndex(),
                            entry.baseAddress(),
                            entry.stakeAddress(),
                            entry.drepId(),
                            entry.vaultFile(),
                            Instant.parse(entry.createdAt()),
                            Instant.parse(entry.updatedAt())))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read wallet index: " + indexFile, e);
        }
    }

    private void writeIndex(List<StoredWallet> wallets) {
        try {
            Files.createDirectories(networkWalletDir);
            Path tmp = indexFile.resolveSibling(indexFile.getFileName() + ".tmp");
            objectMapper.writeValue(tmp.toFile(), wallets.stream()
                    .map(wallet -> new StoredWalletIndexEntry(
                            wallet.id(),
                            wallet.seedId(),
                            wallet.name(),
                            wallet.networkId(),
                            wallet.accountIndex(),
                            wallet.baseAddress(),
                            wallet.stakeAddress(),
                            wallet.drepId(),
                            wallet.vaultFile(),
                            wallet.createdAt().toString(),
                            wallet.updatedAt().toString()))
                    .toList());
            try {
                Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write wallet index: " + indexFile, e);
        }
    }

    private String nextWalletId(List<StoredWallet> wallets) {
        Map<String, StoredWallet> existing = new LinkedHashMap<>();
        wallets.forEach(wallet -> existing.put(wallet.id(), wallet));
        String id;
        do {
            byte[] bytes = new byte[16];
            secureRandom.nextBytes(bytes);
            id = "wlt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (existing.containsKey(id));
        return id;
    }

    private void requireNetwork(WalletNetwork network) {
        if (network != this.network) {
            throw new IllegalArgumentException("Repository is for " + this.network.id() + ", not " + network.id());
        }
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Wallet name is required");
        }
    }

    private void requirePassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length == 0) {
            throw new WalletVaultException("Wallet passphrase is required");
        }
    }

    private String normalizeMnemonic(String mnemonic) {
        if (mnemonic == null || mnemonic.isBlank()) {
            throw new IllegalArgumentException("Mnemonic is required");
        }
        return mnemonic.trim().replaceAll("\\s+", " ");
    }

    private record StoredWalletIndexEntry(
            String id,
            String seedId,
            String name,
            String networkId,
            int accountIndex,
            String baseAddress,
            String stakeAddress,
            String drepId,
            String vaultFile,
            String createdAt,
            String updatedAt) {
    }
}
