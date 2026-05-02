package com.bloxbean.cardano.yano.app.e2e.haskellsync;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base class for Haskell sync integration tests.
 * Provides Yano + cardano-node lifecycle management and common assertions.
 */
@Tag("haskell-sync")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class HaskellSyncTestBase {

    private static final Logger log = LoggerFactory.getLogger(HaskellSyncTestBase.class);

    protected YanoManager yaci;
    protected CardanoNodeManager haskell;
    protected Path tempDir;
    protected Path uberJarPath;

    @BeforeAll
    void setupBase() throws IOException {
        tempDir = Files.createTempDirectory("yaci-haskell-sync-test");
        log.info("Test working directory: {}", tempDir);

        // Locate the uber-jar relative to app module
        // The jar is at app/build/yano.jar
        uberJarPath = locateUberJar();
        log.info("Using uber-jar: {}", uberJarPath);
    }

    @AfterAll
    void teardownBase() {
        try {
            if (haskell != null) {
                haskell.stop();
            }
        } catch (Exception e) {
            log.warn("Error stopping cardano-node", e);
        }

        try {
            if (yaci != null) {
                yaci.stop();
            }
        } catch (Exception e) {
            log.warn("Error stopping Yano", e);
        }

        if (tempDir != null) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(f -> {
                            if (!f.delete()) {
                                log.debug("Could not delete: {}", f);
                            }
                        });
            } catch (IOException e) {
                log.warn("Could not clean up temp dir: {}", tempDir, e);
            }
        }
    }

    /**
     * Asserts that Yano and Haskell tips are within the given slot tolerance.
     */
    protected void assertTipsSynced(int toleranceSlots) throws Exception {
        JsonNode yaciTip = yaci.getTip();
        long yaciSlot = yaciTip.get("slot").asLong();
        long haskellSlot = haskell.getLatestSyncedSlot();
        log.info("Tip comparison — Yano slot: {}, Haskell slot: {}", yaciSlot, haskellSlot);
        assertTrue(Math.abs(yaciSlot - haskellSlot) <= toleranceSlots,
                "Slot difference between Yano (" + yaciSlot + ") and Haskell (" + haskellSlot
                        + ") exceeds tolerance of " + toleranceSlots);
    }

    private Path locateUberJar() {
        // Try from system property first
        String jarPath = System.getProperty("yano.uber.jar");
        if (jarPath == null || jarPath.isBlank()) {
            // Backward-compatible alias for existing local commands.
            jarPath = System.getProperty("yaci.uber.jar");
        }
        if (jarPath != null && !jarPath.isBlank()) {
            Path p = Path.of(jarPath);
            if (Files.exists(p)) {
                return p;
            }
        }

        // Locate relative to the project directory
        // When running via Gradle, user.dir is typically the project root
        Path projectRoot = Path.of(System.getProperty("user.dir"));

        // Try app/build/yano.jar
        Path candidate = projectRoot.resolve("app").resolve("build").resolve("yano.jar");
        if (Files.exists(candidate)) {
            return candidate;
        }

        // If running from app directory
        candidate = projectRoot.resolve("build").resolve("yano.jar");
        if (Files.exists(candidate)) {
            return candidate;
        }

        throw new RuntimeException(
                "Uber-jar not found. Run './gradlew :app:quarkusBuild' first, or set -Dyano.uber.jar=<path>");
    }
}
