package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.genesis.ShelleyGenesisBootstrap;
import com.bloxbean.cardano.yano.runtime.config.InMemoryDevnetGenesis;
import com.bloxbean.cardano.yano.runtime.genesis.ConwayGenesisData;
import com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisData;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.math.BigDecimal;
import java.math.BigInteger;
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
    private final YanoConfig yanoConfig;
    private final InMemoryDevnetGenesis inMemoryGenesis;
    private final RuntimeOptions runtimeOptions;
    private final Path storageRoot;
    private final boolean cleanupStorage;
    private final boolean timeTravel;

    private YanoDevnetTestConfig(YanoConfig yanoConfig,
                                 InMemoryDevnetGenesis inMemoryGenesis,
                                 RuntimeOptions runtimeOptions,
                                 Path storageRoot,
                                 boolean cleanupStorage,
                                 boolean timeTravel) {
        this.yanoConfig = Objects.requireNonNull(yanoConfig, "yanoConfig");
        this.inMemoryGenesis = inMemoryGenesis;
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

    Optional<InMemoryDevnetGenesis> inMemoryGenesis() {
        return Optional.ofNullable(inMemoryGenesis);
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
        private InMemoryDevnetGenesis inMemoryGenesis = defaultInMemoryGenesis();
        private RuntimeOptions runtimeOptions = defaultRuntimeOptions();
        private StorageMode storageMode = StorageMode.TEMPORARY_ROCKSDB;
        private Path persistentStoragePath;
        private boolean timeTravel;
        private boolean defaultGenesis = true;

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
         * Uses the runtime's in-memory storage implementation. This is an
         * explicit non-production shortcut.
         *
         * @return this builder
         */
        public Builder inMemoryStorage() {
            this.storageMode = StorageMode.IN_MEMORY;
            this.persistentStoragePath = null;
            return this;
        }

        /**
         * Configures a deterministic in-memory genesis for the devnet.
         *
         * @return this builder
         */
        public Builder inMemoryGenesis() {
            this.inMemoryGenesis = defaultInMemoryGenesis();
            this.defaultGenesis = true;
            return this;
        }

        /**
         * Configures in-memory genesis data for the devnet. This overload is
         * package-private to avoid exposing runtime genesis DTOs in the public
         * testkit API.
         *
         * @param inMemoryGenesis genesis data
         * @return this builder
         */
        Builder inMemoryGenesis(InMemoryDevnetGenesis inMemoryGenesis) {
            this.inMemoryGenesis = Objects.requireNonNull(inMemoryGenesis, "inMemoryGenesis");
            this.defaultGenesis = false;
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
            yanoConfig.setEpochLength(epochLength);
            if (defaultGenesis) {
                this.inMemoryGenesis = defaultInMemoryGenesis(epochLength);
            }
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
                    storageRoot = persistentStoragePath;
                    runtimeConfig.setUseRocksDB(true);
                    runtimeConfig.setRocksDBPath(persistentStoragePath.toString());
                }
                case IN_MEMORY -> {
                    runtimeConfig.setUseRocksDB(false);
                    runtimeConfig.setRocksDBPath(null);
                }
                default -> throw new IllegalStateException("Unhandled storage mode: " + storageMode);
            }

            if (runtimeConfig.isEnableServer() && runtimeConfig.getServerPort() == 0) {
                runtimeConfig.setServerPort(findFreePort());
            }

            return new YanoDevnetTestConfig(runtimeConfig, inMemoryGenesis, runtimeOptions,
                    storageRoot, cleanupStorage, timeTravel);
        }

        private static Path createTempStorageRoot() {
            try {
                return Files.createTempDirectory("yano-testkit-");
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create temporary Yano devnet storage", e);
            }
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
        PERSISTENT_ROCKSDB,
        IN_MEMORY
    }

    static RuntimeOptions defaultRuntimeOptions() {
        return new RuntimeOptions(
                RuntimeOptions.defaults().events(),
                RuntimeOptions.defaults().plugins(),
                Map.of(YanoPropertyKeys.Utxo.ENABLED, true));
    }

    private static InMemoryDevnetGenesis defaultInMemoryGenesis() {
        return defaultInMemoryGenesis(600);
    }

    private static InMemoryDevnetGenesis defaultInMemoryGenesis(long epochLength) {
        ShelleyGenesisData shelley = new ShelleyGenesisData(
                Map.of(),
                42,
                epochLength,
                1.0,
                "2026-01-01T00:00:00Z",
                45_000_000_000_000_000L,
                1.0,
                100,
                62,
                129600,
                5,
                10,
                0,
                new BigDecimal("0.003"),
                new BigDecimal("0.2"),
                BigDecimal.ZERO,
                100,
                0,
                2_000_000,
                500_000_000,
                BigDecimal.ZERO,
                44,
                155381,
                65536,
                16384,
                1100,
                18,
                null,
                1_000_000,
                ShelleyGenesisBootstrap.empty());
        ConwayGenesisData conway = new ConwayGenesisData(
                30,
                BigInteger.valueOf(100_000_000_000L),
                BigInteger.valueOf(500_000_000),
                20,
                0,
                365,
                null,
                null,
                null,
                null,
                null);
        return new InMemoryDevnetGenesis(shelley, null, conway, null);
    }
}
