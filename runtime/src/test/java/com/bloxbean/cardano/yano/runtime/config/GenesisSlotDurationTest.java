package com.bloxbean.cardano.yano.runtime.config;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yano.runtime.internal.RuntimeNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenesisSlotDurationTest {
    @TempDir
    Path directory;

    private GenesisConfig genesis(double seconds) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = (ObjectNode) mapper.readTree(Path.of("src/test/resources/devnet/shelley-genesis.json").toFile());
        json.put("slotLength", seconds);
        Path path = directory.resolve("shelley-genesis.json");
        mapper.writeValue(path.toFile(), json);
        return GenesisConfig.load(path.toString(), null, null);
    }

    @Test
    void convertsGenesisSecondsWithoutTruncation() throws Exception {
        for (int milliseconds : new int[]{200, 300, 1000, 1, 29}) {
            assertThat(genesis(milliseconds / 1000.0).getSlotLengthMillis()).isEqualTo(milliseconds);
        }
    }

    @Test
    void rejectsUnsupportedGenesisDurations() throws Exception {
        for (double seconds : new double[]{0, -0.3, 0.0001, 2147484}) {
            GenesisConfig genesis = genesis(seconds);
            assertThatThrownBy(genesis::getSlotLengthMillis).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void missingGenesisDoesNotInventOneSecondSlots() {
        assertThatThrownBy(() -> GenesisConfig.load(null, null, null).getSlotLengthMillis())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Shelley genesis");
    }

    @Test
    void runtimeReplacesLegacyValuesAndPreservesSeparateBlockTimer() throws Exception {
        for (int legacy : new int[]{0, 300, 1000, -1}) {
            for (int expected : new int[]{200, 300, 1000}) {
                assertRuntimeDuration(legacy, expected / 1000.0, expected);
            }
        }
    }

    private void assertRuntimeDuration(int legacy, double seconds, int expected) throws Exception {
        YanoConfig config = YanoConfig.builder().slotLengthMillis(legacy).blockTimeMillis(750).build();
        RuntimeNode node = new RuntimeNode(config);
        try {
            var field = RuntimeNode.class.getDeclaredField("genesisConfig");
            field.setAccessible(true);
            field.set(node, genesis(seconds));
            var resolve = RuntimeNode.class.getDeclaredMethod("autoDeriveSlotLengthMillis");
            resolve.setAccessible(true);
            resolve.invoke(node);
            assertThat(config.getSlotLengthMillis()).isEqualTo(expected);
            assertThat(config.getBlockTimeMillis()).isEqualTo(750);
        } finally {
            node.close();
        }
    }
}
