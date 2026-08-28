package com.bloxbean.cardano.yano.app.e2e;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

public class DevnetTestProfile implements QuarkusTestProfile {

    protected static final Path TEMP_STORAGE_DIR;
    protected static final Path TEMP_HISTORY_DIR;
    protected static final Path TEMP_SHELLEY_GENESIS;

    static {
        try {
            TEMP_STORAGE_DIR = Files.createTempDirectory("yano-e2etest-chainstate");
            TEMP_HISTORY_DIR = Files.createTempDirectory("yano-e2etest-history");
            TEMP_SHELLEY_GENESIS = TEMP_STORAGE_DIR.resolve("shelley-genesis.json");
            Files.copy(configFile("shelley-genesis.json"), TEMP_SHELLEY_GENESIS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            deleteTree(TEMP_STORAGE_DIR);
            deleteTree(TEMP_HISTORY_DIR);
        }));
    }

    private static void deleteTree(Path directory) {
        try {
            if (Files.exists(directory)) {
                try (var paths = Files.walk(directory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Best-effort shutdown cleanup.
                        }
                    });
                }
            }
        } catch (IOException ignored) {
            // Best-effort shutdown cleanup.
        }
    }

    protected static Path configFile(String name) {
        if (Path.of(name).getNameCount() != 1) {
            throw new IllegalArgumentException("invalid devnet config file name");
        }
        String configuredDirectory = System.getProperty("yano.e2e.devnetConfigDir");
        if (configuredDirectory != null && !configuredDirectory.isBlank()) {
            Path directory = Path.of(configuredDirectory).toAbsolutePath().normalize();
            return directory.resolve(name).normalize();
        }

        Path extracted = TEMP_STORAGE_DIR.resolve("config").resolve(name);
        if (Files.notExists(extracted)) {
            try {
                Files.createDirectories(extracted.getParent());
                String resource = "/META-INF/yano/testkit/devnet/" + name;
                try (InputStream input = DevnetTestProfile.class.getResourceAsStream(resource)) {
                    if (input == null) {
                        throw new IOException("missing packaged devnet resource " + resource);
                    }
                    Files.copy(input, extracted);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return extracted;
    }

    @Override
    public String getConfigProfile() {
        return System.getProperty("yano.e2e.baseUrl") == null ? "devnet" : "test";
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        if (System.getProperty("yano.e2e.baseUrl") != null) {
            return Map.of();
        }
        return Map.of(
                "yano.storage.rocksdb", "true",
                "yano.storage.path", TEMP_STORAGE_DIR.toString(),
                // Devnet runs the projection archive, so the test writes one. Left at its
                // default it lands in ./history inside the source tree - 53 MB of DuckLake per
                // run, untracked and uncleaned. Keep it outside the RocksDB directory: devnet
                // snapshot restore replaces that directory and the archive must remain intact.
                "yano.history.dir", TEMP_HISTORY_DIR.toString(),
                "yano.genesis.shelley-genesis-file", TEMP_SHELLEY_GENESIS.toString(),
                "yano.plugins.enabled", "false",
                "yano.server.port", "23337"
        );
    }
}
