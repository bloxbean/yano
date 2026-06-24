package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.assembly.YanoAssembly;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoDevnetTestKitTest {
    @Test
    void buildsOnToolkitAssembly() {
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(YanoConfig.devnetDefault(0))) {
            assertNotNull(kit.node());
            assertNotNull(kit.devnet());
            assertTrue(kit.node().devnetControl().isPresent());
        }
    }

    @Test
    void rejectsRuntimeOnlyNodeWithoutDevnetControl() {
        var node = YanoAssembly.devnet(YanoConfig.devnetDefault(0)).build();
        try {
            assertThrows(IllegalArgumentException.class, () -> YanoDevnetTestKit.from(node));
        } finally {
            node.close();
        }
    }
}
