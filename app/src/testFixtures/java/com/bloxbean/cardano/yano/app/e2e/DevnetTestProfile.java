package com.bloxbean.cardano.yano.app.e2e;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

public class DevnetTestProfile implements QuarkusTestProfile {

    private static final Path DEVNET_CONFIG_DIR = Path.of(System.getProperty(
            "yano.e2e.devnetConfigDir", "config/network/devnet"))
            .toAbsolutePath().normalize();
    static final Path TEMP_STORAGE_DIR;
    static final Path TEMP_SHELLEY_GENESIS;

    static {
        try {
            TEMP_STORAGE_DIR = Files.createTempDirectory("yaci-e2etest-chainstate");
            TEMP_SHELLEY_GENESIS = TEMP_STORAGE_DIR.resolve("shelley-genesis.json");
            Files.copy(
                    configFile("shelley-genesis.json"),
                    TEMP_SHELLEY_GENESIS);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Use Files.walk + stream instead of anonymous SimpleFileVisitor subclass
        // to avoid cross-classloader IllegalAccessError with Quarkus's ParentLastURLClassLoader
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (Files.exists(TEMP_STORAGE_DIR)) {
                    try (var paths = Files.walk(TEMP_STORAGE_DIR)) {
                        paths.sorted(Comparator.reverseOrder())
                                .forEach(p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
                    }
                }
            } catch (IOException ignored) {
            }
        }));
    }

    static Path configFile(String name) {
        Path file = DEVNET_CONFIG_DIR.resolve(name).normalize();
        if (!file.getParent().equals(DEVNET_CONFIG_DIR)) {
            throw new IllegalArgumentException("invalid devnet config file name");
        }
        return file;
    }

    @Override
    public String getConfigProfile() {
        if (System.getProperty("yano.e2e.baseUrl") != null) {
            return "test";   // lightweight — no devnet infrastructure
        }
        return "devnet";
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        if (System.getProperty("yano.e2e.baseUrl") != null) {
            return Map.of();  // %test defaults are fine
        }
        return Map.of(
                "yano.storage.rocksdb", "true",
                "yano.storage.path", TEMP_STORAGE_DIR.toString(),
                "yano.genesis.shelley-genesis-file", TEMP_SHELLEY_GENESIS.toString(),
                "yano.plugins.enabled", "false",
                "yano.server.port", "23337"
        );
    }
}
