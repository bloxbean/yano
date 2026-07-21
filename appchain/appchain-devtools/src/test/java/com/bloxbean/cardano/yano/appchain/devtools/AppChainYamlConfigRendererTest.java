package com.bloxbean.cardano.yano.appchain.devtools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainYamlConfigRendererTest {
    @TempDir
    Path temporary;

    @Test
    void rendersIndexedPathsDeterministicallyAndRoundTripsWithoutTypeChanges() throws Exception {
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("yano.app-chain.chains[0].chain-id", "orders");
        forward.put("yano.app-chain.chains[0].enabled", "true");
        forward.put("yano.app-chain.chains[1].chain-id", "registry");
        forward.put("yano.app-chain.chains[1].threshold", "2");
        Map<String, String> reverse = new LinkedHashMap<>();
        forward.entrySet().stream().toList().reversed()
                .forEach(entry -> reverse.put(entry.getKey(), entry.getValue()));

        String first = AppChainYamlConfigRenderer.render(forward, "# generated\n");
        String second = AppChainYamlConfigRenderer.render(reverse, "# generated\n");
        Path config = temporary.resolve("generated.yaml");
        Files.writeString(config, first);

        assertThat(first).isEqualTo(second)
                .startsWith("# generated\n")
                .contains("yano:", "app-chain:", "chains:")
                .doesNotContain("yano.app-chain.", "---");
        Map<String, Object> flattened = new AppChainConfigFileLoader().load(config);
        assertThat(flattened).containsExactlyInAnyOrderEntriesOf(Map.of(
                "yano.app-chain.chains[0].chain-id", "orders",
                "yano.app-chain.chains[0].enabled", "true",
                "yano.app-chain.chains[1].chain-id", "registry",
                "yano.app-chain.chains[1].threshold", "2"));
    }

    @Test
    void rejectsAmbiguousMalformedAndSparseExtensionPaths() {
        assertThatThrownBy(() -> AppChainYamlConfigRenderer.render(
                Map.of("component", "scalar", "component.option", "nested"), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting");
        assertThatThrownBy(() -> AppChainYamlConfigRenderer.render(
                Map.of("component.executors[1].enabled", "true"), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sparse");
        assertThatThrownBy(() -> AppChainYamlConfigRenderer.render(
                Map.of("component.executors[10000].enabled", "true"), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid");
    }
}
