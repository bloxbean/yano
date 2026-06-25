package com.bloxbean.cardano.yano.testkit.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoGenesisFilesTest {
    @TempDir
    Path tempDir;

    @Test
    void copyRequiredGenesisCopiesAllFiles() throws IOException {
        Path source = tempDir.resolve("source");
        Path target = tempDir.resolve("target");
        writeRequiredGenesis(source, "base");
        Files.writeString(source.resolve(YanoGenesisFiles.DIJKSTRA_GENESIS_FILE), "dijkstra");

        YanoGenesisFiles.copyRequiredGenesis(source, target);

        for (String file : YanoGenesisFiles.REQUIRED_GENESIS_FILES) {
            assertEquals("base-" + file, Files.readString(target.resolve(file)));
        }
        assertEquals("dijkstra", Files.readString(target.resolve(YanoGenesisFiles.DIJKSTRA_GENESIS_FILE)));
    }

    @Test
    void copyRequiredGenesisFailsWhenFileIsMissing() throws IOException {
        Path source = tempDir.resolve("source");
        Files.createDirectories(source);

        IOException error = assertThrows(IOException.class,
                () -> YanoGenesisFiles.copyRequiredGenesis(source, tempDir.resolve("target")));

        assertTrue(error.getMessage().contains("Required genesis file not found"));
    }

    @Test
    void writeDefaultDijkstraGenesisCreatesMissingFile() throws IOException {
        Path target = tempDir.resolve("target");

        YanoGenesisFiles.writeDefaultDijkstraGenesis(target);

        String dijkstra = Files.readString(target.resolve(YanoGenesisFiles.DIJKSTRA_GENESIS_FILE));
        assertTrue(dijkstra.contains("\"maxRefScriptSizePerBlock\": 1048576"));
        assertTrue(dijkstra.contains("\"maxRefScriptSizePerTx\": 204800"));
    }

    @Test
    void writeDefaultDijkstraGenesisKeepsCallerProvidedFile() throws IOException {
        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve(YanoGenesisFiles.DIJKSTRA_GENESIS_FILE), "custom");

        YanoGenesisFiles.writeDefaultDijkstraGenesis(target);

        assertEquals("custom", Files.readString(target.resolve(YanoGenesisFiles.DIJKSTRA_GENESIS_FILE)));
    }

    @Test
    void protocol10OverlayReplacesDevnetFilesWhenPresent() throws IOException {
        Path config = tempDir.resolve("config");
        Path devnet = YanoGenesisFiles.devnetGenesisDir(config);
        Path overlay = devnet.resolve("pv10");
        writeRequiredGenesis(devnet, "base");
        writeRequiredGenesis(overlay, "pv10");

        assertTrue(YanoGenesisFiles.applyProtocol10Overlay(config));

        assertEquals("pv10-shelley-genesis.json", Files.readString(devnet.resolve("shelley-genesis.json")));
        assertEquals("pv10-conway-genesis.json", Files.readString(devnet.resolve("conway-genesis.json")));
    }

    private static void writeRequiredGenesis(Path dir, String prefix) throws IOException {
        Files.createDirectories(dir);
        for (String file : YanoGenesisFiles.REQUIRED_GENESIS_FILES) {
            Files.writeString(dir.resolve(file), prefix + "-" + file);
        }
    }
}
