package com.bloxbean.cardano.yano.devnet;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.assembly.YanoAssembly;
import com.bloxbean.cardano.yano.runtime.assembly.YanoNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoDevnetAssemblyTest {
    @TempDir
    Path tempDir;

    @Test
    void runtimeDevnetRecipeBuildsNodeOnlyWithoutDevnetControl() {
        YanoNode node = YanoAssembly.devnet(devnetConfig("runtime")).build();

        try {
            assertTrue(node.producerControl().isPresent());
            assertTrue(node.devnetControl().isEmpty());
        } finally {
            node.close();
        }
    }

    @Test
    void toolkitDevnetRecipeDecoratesNodeWithDevnetControl() {
        YanoNode node = YanoDevnetAssembly.devnet(devnetConfig("toolkit")).build();

        try {
            assertTrue(node.producerControl().isPresent());
            assertTrue(node.devnetControl().isPresent());
        } finally {
            node.close();
        }
    }

    @Test
    void toolkitTimeTravelRecipeDecoratesNodeWithDevnetControl() {
        YanoConfig config = devnetConfig("time-travel");
        config.setPastTimeTravelMode(true);

        YanoNode node = YanoDevnetAssembly.devnetTimeTravel(config).build();

        try {
            assertTrue(node.producerControl().isPresent());
            assertTrue(node.devnetControl().isPresent());
        } finally {
            node.close();
        }
    }

    @Test
    void toolkitFromConfigDecoratesDevnetAndTimeTravelOnly() {
        YanoNode devnetNode = YanoDevnetAssembly.fromConfig(devnetConfig("from-config-devnet")).build();
        try {
            assertTrue(devnetNode.devnetControl().isPresent());
        } finally {
            devnetNode.close();
        }

        YanoConfig timeTravelConfig = devnetConfig("from-config-time-travel");
        timeTravelConfig.setPastTimeTravelMode(true);
        YanoNode timeTravelNode = YanoDevnetAssembly.fromConfig(timeTravelConfig).build();
        try {
            assertTrue(timeTravelNode.devnetControl().isPresent());
        } finally {
            timeTravelNode.close();
        }

        YanoConfig slotLeaderConfig = devnetConfig("from-config-slot-leader");
        slotLeaderConfig.setSlotLeaderMode(true);
        YanoNode slotLeaderNode = YanoDevnetAssembly.fromConfig(slotLeaderConfig).build();
        try {
            assertTrue(slotLeaderNode.devnetControl().isEmpty());
        } finally {
            slotLeaderNode.close();
        }

        YanoNode relayNode = YanoDevnetAssembly.fromConfig(YanoConfig.serverOnly(0)).build();
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
