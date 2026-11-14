package org.yanoproject.runtime.plugins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.config.PluginsOptions;
import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.runtime.events.PropagatingEventBus;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class PriorCompiledPluginCompatibilityTest {
    @TempDir Path temporary;

    @Test
    void pre19BytecodeLinksAndSurvivesTwoRealHostLifecycleCycles() throws Exception {
        byte[] fixture;
        try (var input = getClass().getResourceAsStream("/app-ledger/compat/pre19-plugin.jar")) {
            assertThat(input).isNotNull();
            fixture = input.readAllBytes();
        }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture)))
                .isEqualTo("9dfd7573788a170d7c88807676868947798b7e8760489676fa7f50d38241b5b0");
        Path jar = temporary.resolve("pre19-plugin.jar");
        Files.write(jar, fixture);
        List<String> calls = new ArrayList<>();
        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader());
             var scheduler = Executors.newSingleThreadScheduledExecutor();
             var events = new PropagatingEventBus()) {
            NodePlugin plugin = (NodePlugin) loader.loadClass("compat.Pre19Plugin").getConstructor().newInstance();
            assertThat(plugin.getClass().getClassLoader()).isSameAs(loader);
            var options = new PluginsOptions(true, false, Set.of(), Set.of(), Map.of("compat.mode", "legacy"));
            try (var manager = new PluginManager(events, scheduler, options, loader, List.of(plugin))) {
                manager.registerHostService("compat.calls", calls);
                manager.discoverAndInit();
                manager.startAll();
                manager.stopAll();
                manager.startAll();
                manager.stopAll();
            }
        }
        assertThat(calls).containsExactly("init:ARTIFACT_CLOSURE", "start", "stop", "start", "stop", "close");
    }
}
