package org.yanoproject.runtime;

import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;
import org.yanoproject.api.config.PluginsOptions;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.runtime.internal.RuntimeNode;
import org.yanoproject.runtime.plugins.PluginCatalogActivationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeNodePluginConstructionTest {
    @TempDir
    Path tempDir;

    @Test
    void pluginPolicyFailureReleasesRocksDbForNextRuntimeConstruction() {
        Path chainStatePath = tempDir.resolve("chainstate");
        YanoConfig config = YanoConfig.serverOnly(0).toBuilder()
                .useRocksDB(true)
                .rocksDBPath(chainStatePath.toString())
                .build();

        RuntimeOptions missingRequiredPlugin = runtimeOptions(
                new PluginsOptions(true, false, Set.of("missing.required.plugin"), Set.of(), Map.of()));

        assertThatThrownBy(() -> new RuntimeNode(config, missingRequiredPlugin))
                .isInstanceOf(PluginCatalogActivationException.class)
                .hasMessageContaining("Allow-listed plugin bundles were not discovered")
                .hasMessageContaining("missing.required.plugin")
                .hasCauseInstanceOf(IllegalStateException.class);

        RuntimeOptions pluginsDisabled = runtimeOptions(
                new PluginsOptions(false, false, Set.of(), Set.of(), Map.of()));
        try (RuntimeNode recovered = new RuntimeNode(config, pluginsDisabled)) {
            assertThat(recovered).isNotNull();
        }
    }

    private static RuntimeOptions runtimeOptions(PluginsOptions plugins) {
        return new RuntimeOptions(EventsOptions.defaults(), plugins, Map.of());
    }
}
