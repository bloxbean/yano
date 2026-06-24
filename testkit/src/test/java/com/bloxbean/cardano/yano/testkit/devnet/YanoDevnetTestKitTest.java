package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.assembly.YanoAssembly;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoDevnetTestKitTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsOnToolkitAssembly() {
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(devnetConfig("toolkit"))) {
            assertNotNull(kit.node());
            assertNotNull(kit.devnet());
            assertTrue(kit.node().devnetControl().isPresent());
        }
    }

    @Test
    void rejectsRuntimeOnlyNodeWithoutDevnetControl() {
        var node = YanoAssembly.devnet(devnetConfig("runtime-only")).build();
        try {
            assertThrows(IllegalArgumentException.class, () -> YanoDevnetTestKit.from(node));
        } finally {
            node.close();
        }
    }

    private YanoConfig devnetConfig(String name) {
        YanoConfig config = YanoConfig.devnetDefault(0);
        config.setRocksDBPath(tempDir.resolve(name).toString());
        return config;
    }
}
