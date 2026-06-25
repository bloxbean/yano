package com.bloxbean.cardano.yano.testkit.external;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * File helpers for external devnet compatibility tests.
 */
public final class YanoGenesisFiles {
    public static final List<String> REQUIRED_GENESIS_FILES = List.of(
            "shelley-genesis.json",
            "byron-genesis.json",
            "alonzo-genesis.json",
            "conway-genesis.json"
    );
    public static final String DIJKSTRA_GENESIS_FILE = "dijkstra-genesis.json";

    private YanoGenesisFiles() {
    }

    /**
     * Copies all required genesis files from one directory to another.
     *
     * @param sourceDir directory containing Yano devnet genesis files
     * @param targetDir destination directory
     * @throws IOException if a required file is missing or cannot be copied
     */
    public static void copyRequiredGenesis(Path sourceDir, Path targetDir) throws IOException {
        Objects.requireNonNull(sourceDir, "sourceDir");
        Objects.requireNonNull(targetDir, "targetDir");
        Files.createDirectories(targetDir);

        for (String fileName : REQUIRED_GENESIS_FILES) {
            Path source = sourceDir.resolve(fileName);
            if (!Files.isRegularFile(source)) {
                throw new IOException("Required genesis file not found: " + source);
            }
            Files.copy(source, targetDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        }

        Path dijkstra = sourceDir.resolve(DIJKSTRA_GENESIS_FILE);
        if (Files.isRegularFile(dijkstra)) {
            Files.copy(dijkstra, targetDir.resolve(DIJKSTRA_GENESIS_FILE),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Writes the minimal Dijkstra genesis file required by cardano-node 11.x
     * compatibility tests when the caller did not provide one.
     *
     * @param targetDir destination genesis directory
     * @throws IOException if the file cannot be written
     */
    public static void writeDefaultDijkstraGenesis(Path targetDir) throws IOException {
        Objects.requireNonNull(targetDir, "targetDir");
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(DIJKSTRA_GENESIS_FILE);
        if (Files.exists(target)) {
            return;
        }
        Files.writeString(target, """
                {
                  "maxRefScriptSizePerBlock": 1048576,
                  "maxRefScriptSizePerTx": 204800,
                  "refScriptCostStride": 25600,
                  "refScriptCostMultiplier": 1.2
                }
                """);
    }

    /**
     * Copies a configuration tree.
     *
     * @param source source directory
     * @param target target directory
     * @throws IOException if copy fails
     */
    public static void copyDirectory(Path source, Path target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (!Files.isDirectory(source)) {
            throw new IOException("Config source directory not found: " + source);
        }

        try (var walk = Files.walk(source)) {
            walk.forEach(src -> {
                try {
                    Path dst = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Applies the protocol-10 devnet overlay expected by current Haskell
     * cardano-node compatibility tests.
     *
     * @param configDir copied Yano app config directory
     * @return true when the overlay was present and applied
     * @throws IOException if overlay copy fails
     */
    public static boolean applyProtocol10Overlay(Path configDir) throws IOException {
        Objects.requireNonNull(configDir, "configDir");
        Path devnetDir = devnetGenesisDir(configDir);
        Path protocol10Dir = devnetDir.resolve("pv10");
        if (!Files.isDirectory(protocol10Dir)) {
            return false;
        }

        copyDirectory(protocol10Dir, devnetDir);
        return true;
    }

    /**
     * Resolves the devnet network genesis directory inside a copied app config
     * directory.
     *
     * @param configDir Yano app config directory
     * @return {@code config/network/devnet}
     */
    public static Path devnetGenesisDir(Path configDir) {
        return Objects.requireNonNull(configDir, "configDir")
                .resolve("network")
                .resolve("devnet");
    }
}
