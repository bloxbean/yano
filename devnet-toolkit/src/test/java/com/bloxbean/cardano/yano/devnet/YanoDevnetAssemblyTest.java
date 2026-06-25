package com.bloxbean.cardano.yano.devnet;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.assembly.YanoAssembly;
import com.bloxbean.cardano.yano.runtime.assembly.Yano;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
