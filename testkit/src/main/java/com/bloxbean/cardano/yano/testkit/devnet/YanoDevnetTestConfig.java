package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Test-oriented devnet configuration with explicit storage ownership.
 */
public final class YanoDevnetTestConfig implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEVNET_RESOURCE_DIR = "genesis/devnet";
    private static final String DEVNET_PROFILE_DIR = "config/network/devnet";
    private static final List<String> DEVNET_PROFILE_FILES = List.of(
            "shelley-genesis.json",
            "byron-genesis.json",
            "alonzo-genesis.json",
            "conway-genesis.json",
            "protocol-param.json",
            "vrf.skey",
            "kes.skey",
            "opcert.cert"
    );

    private final YanoConfig yanoConfig;
    private final RuntimeOptions runtimeOptions;
    private final Path storageRoot;
    private final boolean cleanupStorage;
    private final boolean timeTravel;

    private YanoDevnetTestConfig(YanoConfig yanoConfig,
                                 RuntimeOptions runtimeOptions,
                                 Path storageRoot,
                                 boolean cleanupStorage,
                                 boolean timeTravel) {
        this.yanoConfig = Objects.requireNonNull(yanoConfig, "yanoConfig");
        this.runtimeOptions = runtimeOptions != null ? runtimeOptions : defaultRuntimeOptions();
        this.storageRoot = storageRoot;
        this.cleanupStorage = cleanupStorage;
        this.timeTravel = timeTravel;
    }

    /**
     * Creates a builder with test-safe defaults.
     *
     * @return config builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the runtime configuration used to assemble the devnet.
     *
     * @return mutable Yano runtime config
     */
    public YanoConfig yanoConfig() {
        return yanoConfig;
    }

    /**
     * Returns runtime options used by the assembled test devnet.
     *
     * @return runtime options
     */
    public RuntimeOptions runtimeOptions() {
        return runtimeOptions;
    }

    /**
     * Returns the storage root owned by this test config, if storage ownership is
     * known.
     *
     * @return storage root
     */
    public Optional<Path> storageRoot() {
        return Optional.ofNullable(storageRoot);
    }

    /**
     * Whether this config should assemble a past-time-travel devnet.
     *
     * @return true for past-time-travel assembly
     */
    public boolean timeTravel() {
        return timeTravel;
    }

    /**
     * Whether storage is cleaned up when this config closes.
     *
     * @return true when temporary storage is owned by the test
     */
    public boolean cleanupStorage() {
        return cleanupStorage;
    }

    /**
     * Returns the copied devnet profile directory used by file-backed testkit
     * defaults, when one exists.
     *
     * @return copied devnet profile directory
     */
    public Optional<Path> devnetProfileDir() {
        return storageRoot != null
                ? Optional.of(storageRoot.resolve(DEVNET_PROFILE_DIR))
                : Optional.empty();
    }

    @Override
    public void close() {
        if (cleanupStorage && storageRoot != null) {
            deleteRecursively(storageRoot);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(YanoDevnetTestConfig::deleteIfExists);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to clean temporary Yano devnet storage: " + root, e);
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + path, e);
        }
    }

    /**
     * Builder for {@link YanoDevnetTestConfig}. The default storage mode is a
     * temporary RocksDB chainstate directory owned by the test fixture.
     */
    public static final class Builder {
        private YanoConfig yanoConfig = YanoConfig.devnetDefault(0);
        private RuntimeOptions runtimeOptions = defaultRuntimeOptions();
        private StorageMode storageMode = StorageMode.TEMPORARY_ROCKSDB;
        private Path persistentStoragePath;
        private boolean timeTravel;
        private Long epochLengthOverride;

        private Builder() {
        }

        /**
         * Uses a supplied runtime config as the base before test storage options
         * are applied.
         *
         * @param yanoConfig runtime config
         * @return this builder
         */
        public Builder yanoConfig(YanoConfig yanoConfig) {
            this.yanoConfig = YanoConfig.copyOf(Objects.requireNonNull(yanoConfig, "yanoConfig"));
            return this;
        }

        /**
         * Uses temporary RocksDB-backed storage. This is the default.
         *
         * @return this builder
         */
        public Builder temporaryRocksDbStorage() {
            this.storageMode = StorageMode.TEMPORARY_ROCKSDB;
            this.persistentStoragePath = null;
            return this;
        }

        /**
         * Uses RocksDB-backed storage at a caller-owned path.
         *
         * @param path chainstate directory
         * @return this builder
         */
        public Builder persistentRocksDbStorage(Path path) {
            this.storageMode = StorageMode.PERSISTENT_ROCKSDB;
            this.persistentStoragePath = Objects.requireNonNull(path, "path");
            return this;
        }

        /**
         * Uses caller-supplied runtime options. Testkit defaults enable the
         * public UTXO query/write path; replacing runtime options is an advanced
         * escape hatch.
         *
         * @param runtimeOptions runtime options
         * @return this builder
         */
        public Builder runtimeOptions(RuntimeOptions runtimeOptions) {
            this.runtimeOptions = runtimeOptions != null ? runtimeOptions : RuntimeOptions.defaults();
            return this;
        }

        /**
         * Adds or overrides a runtime global option.
         *
         * @param key option key
         * @param value option value
         * @return this builder
         */
        public Builder runtimeOption(String key, Object value) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("runtime option key must not be blank");
            }
            Map<String, Object> globals = new HashMap<>(runtimeOptions.globals());
            if (value == null) {
                globals.remove(key);
            } else {
                globals.put(key, value);
            }
            runtimeOptions = new RuntimeOptions(runtimeOptions.events(), runtimeOptions.plugins(), globals);
            return this;
        }

        /**
         * Configures server port on the underlying runtime config.
         *
         * @param serverPort server port, or 0 for an ephemeral port
         * @return this builder
         */
        public Builder serverPort(int serverPort) {
            yanoConfig.setServerPort(serverPort);
            return this;
        }

        /**
         * Configures devnet block production interval.
         *
         * @param blockTimeMillis block interval in milliseconds
         * @return this builder
         */
        public Builder blockTimeMillis(int blockTimeMillis) {
            yanoConfig.setBlockTimeMillis(blockTimeMillis);
            return this;
        }

        /**
         * Configures epoch length directly on the runtime config.
         *
         * @param epochLength epoch length in slots
         * @return this builder
         */
        public Builder epochLength(long epochLength) {
            if (epochLength <= 0) {
                throw new IllegalArgumentException("epoch length must be greater than 0");
            }
            yanoConfig.setEpochLength(epochLength);
            this.epochLengthOverride = epochLength;
            return this;
        }

        /**
         * Configures whether this test devnet should use past-time-travel
         * assembly.
         *
         * @param timeTravel true for past-time-travel assembly
         * @return this builder
         */
        public Builder timeTravel(boolean timeTravel) {
            this.timeTravel = timeTravel;
            yanoConfig.setPastTimeTravelMode(timeTravel);
            return this;
        }

        /**
         * Builds the config and allocates temporary storage when requested.
         *
         * @return test devnet config
         */
        public YanoDevnetTestConfig build() {
            YanoConfig runtimeConfig = YanoConfig.copyOf(yanoConfig);
            Path storageRoot = null;
            boolean cleanupStorage = false;

            switch (storageMode) {
                case TEMPORARY_ROCKSDB -> {
                    storageRoot = createTempStorageRoot();
                    Path rocksDbPath = storageRoot.resolve("chainstate");
                    runtimeConfig.setUseRocksDB(true);
                    runtimeConfig.setRocksDBPath(rocksDbPath.toString());
                    cleanupStorage = true;
                }
                case PERSISTENT_ROCKSDB -> {
                    storageRoot = persistentProfileRoot(persistentStoragePath);
                    runtimeConfig.setUseRocksDB(true);
                    runtimeConfig.setRocksDBPath(persistentStoragePath.toString());
                }
                default -> throw new IllegalStateException("Unhandled storage mode: " + storageMode);
            }

            Path profileDir = copyDefaultDevnetProfile(storageRoot);
            if (epochLengthOverride != null) {
                patchShelleyEpochLength(profileDir.resolve("shelley-genesis.json"), epochLengthOverride);
            }
            applyProfilePaths(runtimeConfig, profileDir);

            if (runtimeConfig.isEnableServer() && runtimeConfig.getServerPort() == 0) {
                runtimeConfig.setServerPort(findFreePort());
            }

            return new YanoDevnetTestConfig(runtimeConfig, runtimeOptions, storageRoot, cleanupStorage, timeTravel);
        }

        private static Path createTempStorageRoot() {
            try {
                return Files.createTempDirectory("yano-testkit-");
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create temporary Yano devnet storage", e);
            }
        }

        private static Path persistentProfileRoot(Path rocksDbPath) {
            Objects.requireNonNull(rocksDbPath, "rocksDbPath");
            Path absolute = rocksDbPath.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent == null) {
                return createTempStorageRoot();
            }
            return parent;
        }

        private static int findFreePort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                socket.setReuseAddress(false);
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to allocate a free Yano devnet server port", e);
            }
        }
    }

    private enum StorageMode {
        TEMPORARY_ROCKSDB,
        PERSISTENT_ROCKSDB
    }

    static RuntimeOptions defaultRuntimeOptions() {
        return new RuntimeOptions(
                RuntimeOptions.defaults().events(),
                RuntimeOptions.defaults().plugins(),
                Map.of(
                        YanoPropertyKeys.Utxo.ENABLED, true,
                        YanoPropertyKeys.AccountState.ENABLED, true,
                        YanoPropertyKeys.EpochSnapshot.AMOUNTS_ENABLED, true,
                        YanoPropertyKeys.Ledger.ADAPOT_ENABLED, true,
                        YanoPropertyKeys.Ledger.REWARDS_ENABLED, true,
                        YanoPropertyKeys.Ledger.EPOCH_PARAMS_TRACKING_ENABLED, true,
                        YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED, true));
    }

    private static Path copyDefaultDevnetProfile(Path storageRoot) {
        if (storageRoot == null) {
            throw new IllegalStateException("A storage root is required for file-backed devnet profile resources");
        }
        Path targetDir = storageRoot.resolve(DEVNET_PROFILE_DIR);
        try {
            Files.createDirectories(targetDir);
            for (String fileName : DEVNET_PROFILE_FILES) {
                copyResource(DEVNET_RESOURCE_DIR + "/" + fileName, targetDir.resolve(fileName));
            }
            return targetDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to copy Yano devnet profile resources to " + targetDir, e);
        }
    }

    private static void copyResource(String resource, Path target) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        try (InputStream in = YanoDevnetTestConfig.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Classpath resource not found: " + resource);
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
        }
    }

    private static void applyProfilePaths(YanoConfig config, Path profileDir) {
        if (isBlank(config.getNetwork())) {
            config.setNetwork("devnet");
        }
        if (isBlank(config.getShelleyGenesisFile())) {
            config.setShelleyGenesisFile(profileDir.resolve("shelley-genesis.json").toString());
        }
        if (isBlank(config.getShelleyGenesisHash())) {
            config.setShelleyGenesisHash("");
        }
        if (isBlank(config.getByronGenesisFile())) {
            config.setByronGenesisFile(profileDir.resolve("byron-genesis.json").toString());
        }
        if (isBlank(config.getAlonzoGenesisFile())) {
            config.setAlonzoGenesisFile(profileDir.resolve("alonzo-genesis.json").toString());
        }
        if (isBlank(config.getConwayGenesisFile())) {
            config.setConwayGenesisFile(profileDir.resolve("conway-genesis.json").toString());
        }
        if (isBlank(config.getProtocolParametersFile())) {
            config.setProtocolParametersFile(profileDir.resolve("protocol-param.json").toString());
        }
        if (isBlank(config.getVrfSkeyFile())) {
            config.setVrfSkeyFile(profileDir.resolve("vrf.skey").toString());
        }
        if (isBlank(config.getKesSkeyFile())) {
            config.setKesSkeyFile(profileDir.resolve("kes.skey").toString());
        }
        if (isBlank(config.getOpCertFile())) {
            config.setOpCertFile(profileDir.resolve("opcert.cert").toString());
        }
    }

    private static void patchShelleyEpochLength(Path shelleyGenesisFile, long epochLength) {
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(shelleyGenesisFile.toFile());
            root.put("epochLength", epochLength);
            JSON.writerWithDefaultPrettyPrinter().writeValue(shelleyGenesisFile.toFile(), root);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to patch epochLength in " + shelleyGenesisFile, e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
