package com.bloxbean.cardano.yano.testkit.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoAppProcessTest {
    @TempDir
    Path tempDir;

    @Test
    void commandUsesFixedPortsAndNormalizesExtraProperties() throws IOException {
        Path jar = tempDir.resolve("yano.jar");
        Path config = tempDir.resolve("config-source");
        Files.writeString(jar, "jar");
        Files.createDirectories(config);

        YanoAppProcess process = new YanoAppProcess(
                tempDir.resolve("work"), jar, config, false, 10_001, 10_002);

        List<String> command = process.command("alpha=1", "-Dbeta=2");

        assertEquals("java", command.getFirst());
        assertTrue(command.contains("-Dquarkus.http.port=10001"));
        assertTrue(command.contains("-Dyano.server.port=10002"));
        assertTrue(command.contains("-Dalpha=1"));
        assertTrue(command.contains("-Dbeta=2"));
        assertEquals("-jar", command.get(command.size() - 2));
        assertEquals(jar.toString(), command.getLast());
    }

    @Test
    void commandRejectsNonSystemPropertyArgs() throws IOException {
        Path jar = tempDir.resolve("yano.jar");
        Path config = tempDir.resolve("config-source");
        Files.writeString(jar, "jar");
        Files.createDirectories(config);
        YanoAppProcess process = new YanoAppProcess(
                tempDir.resolve("work"), jar, config, false, 10_001, 10_002);

        assertThrows(IllegalArgumentException.class, () -> process.command("--gamma"));
        assertThrows(IllegalArgumentException.class, () -> process.command("literal"));
    }

    @Test
    void apiBaseUrlPointsAtBlockfrostCompatiblePrefix() throws IOException {
        Path jar = tempDir.resolve("yano.jar");
        Path config = tempDir.resolve("config-source");
        Files.writeString(jar, "jar");
        Files.createDirectories(config);

        YanoAppProcess process = new YanoAppProcess(
                tempDir.resolve("work"), jar, config, false, 12_345, 12_346);

        assertEquals("http://localhost:12345/", process.baseUrl().toString());
        assertEquals("http://localhost:12345/api/v1/", process.apiBaseUrl().toString());
        assertEquals(12_345, process.getHttpPort());
        assertEquals(12_346, process.getN2nPort());
    }

    @Test
    void constructorDoesNotFallbackToRepoConfigForExternalJar() throws IOException {
        Path external = tempDir.resolve("external");
        Path jar = external.resolve("build").resolve("yano.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");

        assertThrows(IllegalStateException.class,
                () -> new YanoAppProcess(tempDir.resolve("work"), jar));
    }

    @Test
    void devnetPreparationDoesNotCopyOperatorApplicationConfig() throws IOException {
        Path jar = tempDir.resolve("yano.jar");
        Files.writeString(jar, "jar");
        Path config = tempDir.resolve("config-source");
        Files.createDirectories(config.resolve("network/devnet/pv10"));
        Files.writeString(config.resolve("application.yml"), "yano:\n  network: preprod\n");
        Files.writeString(config.resolve("application-preprod.yml"), "yano:\n  network: preprod\n");
        Files.writeString(config.resolve("network/devnet/shelley-genesis.json"), "{}");
        Files.writeString(config.resolve("network/devnet/pv10/shelley-genesis.json"), "{}");

        YanoAppProcess process = new YanoAppProcess(
                tempDir.resolve("work"), jar, config, true, 10_001, 10_002);

        process.prepareConfig();

        assertTrue(Files.notExists(process.configDir().resolve("application.yml")));
        assertTrue(Files.notExists(process.configDir().resolve("application-preprod.yml")));
        assertTrue(Files.exists(process.configDir().resolve("network/devnet/shelley-genesis.json")));
    }
}
