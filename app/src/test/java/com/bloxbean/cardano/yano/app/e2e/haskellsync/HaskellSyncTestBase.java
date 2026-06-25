package com.bloxbean.cardano.yano.app.e2e.haskellsync;

import com.bloxbean.cardano.yano.testkit.external.HaskellCardanoNodeProcess;
import com.bloxbean.cardano.yano.testkit.external.YanoAppProcess;
import com.bloxbean.cardano.yano.testkit.external.YanoExternalSyncAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

/**
 * Base class for Haskell sync integration tests.
 * Provides Yano + cardano-node lifecycle management and common assertions.
 */
@Tag("haskell-sync")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class HaskellSyncTestBase {

    private static final Logger log = LoggerFactory.getLogger(HaskellSyncTestBase.class);

    protected YanoAppProcess yaci;
    protected HaskellCardanoNodeProcess haskell;
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
        YanoExternalSyncAssertions.assertTipsSynced(yaci, haskell, toleranceSlots);
    }

    private Path locateUberJar() {
        return YanoAppProcess.locateUberJar();
    }
}
