package com.bloxbean.cardano.yano.devnet;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.assembly.YanoAssembly;
import com.bloxbean.cardano.yano.runtime.assembly.YanoNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoDevnetAssemblyTest {
    @Test
    void runtimeDevnetRecipeBuildsNodeOnlyWithoutDevnetControl() {
        YanoNode node = YanoAssembly.devnet(YanoConfig.devnetDefault(0)).build();

        try {
            assertTrue(node.producerControl().isPresent());
            assertTrue(node.devnetControl().isEmpty());
        } finally {
            node.close();
        }
    }

    @Test
    void toolkitDevnetRecipeDecoratesNodeWithDevnetControl() {
        YanoNode node = YanoDevnetAssembly.devnet(YanoConfig.devnetDefault(0)).build();

        try {
            assertTrue(node.producerControl().isPresent());
            assertTrue(node.devnetControl().isPresent());
        } finally {
            node.close();
        }
    }

    @Test
    void toolkitTimeTravelRecipeDecoratesNodeWithDevnetControl() {
        YanoConfig config = YanoConfig.devnetDefault(0);
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
        YanoNode devnetNode = YanoDevnetAssembly.fromConfig(YanoConfig.devnetDefault(0)).build();
        try {
            assertTrue(devnetNode.devnetControl().isPresent());
        } finally {
            devnetNode.close();
        }

        YanoConfig timeTravelConfig = YanoConfig.devnetDefault(0);
        timeTravelConfig.setPastTimeTravelMode(true);
        YanoNode timeTravelNode = YanoDevnetAssembly.fromConfig(timeTravelConfig).build();
        try {
            assertTrue(timeTravelNode.devnetControl().isPresent());
        } finally {
            timeTravelNode.close();
        }

        YanoConfig slotLeaderConfig = YanoConfig.devnetDefault(0);
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
}
