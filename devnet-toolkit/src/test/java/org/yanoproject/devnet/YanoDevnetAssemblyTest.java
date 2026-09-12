package org.yanoproject.devnet;

import org.yanoproject.api.MempoolQueryGateway;
import org.yanoproject.api.MempoolAdminGateway;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.plugin.domain.DomainApiGateway;
import org.yanoproject.runtime.assembly.YanoAssembly;
import org.yanoproject.runtime.assembly.Yano;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class YanoDevnetAssemblyTest {
    @TempDir
    Path tempDir;

    @Test
    void runtimeDevnetRecipeBuildsNodeOnlyWithoutDevnetControl() {
        Yano node = YanoAssembly.devnet(devnetConfig("runtime")).build();

        try {
            assertTrue(node.producerControl().isPresent());
            assertTrue(node.devnetControl().isEmpty());
        } finally {
            node.close();
        }
    }

    @Test
    void toolkitDevnetRecipeDecoratesNodeWithDevnetControl() {
        Yano node = YanoDevnetAssembly.devnet(devnetConfig("toolkit")).build();

        try {
            assertTrue(node.producerControl().isPresent());
            assertTrue(node.devnetControl().isPresent());
            assertTrue(node.pluginCatalog().isPresent());
            assertTrue(node.pluginOperations().isPresent());
            assertNotSame(DomainApiGateway.empty(), node.domainApis());
            assertNotSame(MempoolQueryGateway.UNAVAILABLE, node.mempoolQueryGateway());
            assertSame(node.txEvaluationGateway(), node.mempoolQueryGateway());
            assertNotSame(MempoolAdminGateway.UNAVAILABLE, node.mempoolAdminGateway());
            assertSame(node.txEvaluationGateway(), node.mempoolAdminGateway());
        } finally {
            node.close();
        }
    }

    @Test
    void toolkitTimeTravelRecipeDecoratesNodeWithDevnetControl() {
        YanoConfig config = devnetConfig("time-travel");
        config.setPastTimeTravelMode(true);

        Yano node = YanoDevnetAssembly.devnetTimeTravel(config).build();

        try {
            assertTrue(node.producerControl().isPresent());
            assertTrue(node.devnetControl().isPresent());
        } finally {
            node.close();
        }
    }

    @Test
    void toolkitFromConfigDecoratesDevnetAndTimeTravelOnly() {
        Yano devnetNode = YanoDevnetAssembly.fromConfig(devnetConfig("from-config-devnet")).build();
        try {
            assertTrue(devnetNode.devnetControl().isPresent());
        } finally {
            devnetNode.close();
        }

        YanoConfig timeTravelConfig = devnetConfig("from-config-time-travel");
        timeTravelConfig.setPastTimeTravelMode(true);
        Yano timeTravelNode = YanoDevnetAssembly.fromConfig(timeTravelConfig).build();
        try {
            assertTrue(timeTravelNode.devnetControl().isPresent());
        } finally {
            timeTravelNode.close();
        }

        YanoConfig slotLeaderConfig = devnetConfig("from-config-slot-leader");
        slotLeaderConfig.setSlotLeaderMode(true);
        Yano slotLeaderNode = YanoDevnetAssembly.fromConfig(slotLeaderConfig).build();
        try {
            assertTrue(slotLeaderNode.devnetControl().isEmpty());
        } finally {
            slotLeaderNode.close();
        }

        Yano relayNode = YanoDevnetAssembly.fromConfig(YanoConfig.serverOnly(0)).build();
        try {
            assertTrue(relayNode.devnetControl().isEmpty());
        } finally {
            relayNode.close();
        }
    }

    private YanoConfig devnetConfig(String name) {
        YanoConfig config = YanoConfig.devnetDefault(0);
        config.setRocksDBPath(tempDir.resolve(name).toString());
        return config;
    }
}
