package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.runtime.plugins.PluginRuntimeEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginCatalogGeneratedNativeMetadataTest {
    private static final String GENERATED_REFLECTION_CONFIG =
            "META-INF/native-image/com.bloxbean.cardano/"
                    + "yano-plugin-catalog/reflect-config.json";
    private static final String CODEC_REFLECTION_CONFIG =
            "META-INF/native-image/com.bloxbean.cardano/"
                    + "yano-plugin-catalog-codec/reflect-config.json";
    private static final String RUNTIME_RESOURCE_CONFIG =
            "META-INF/native-image/com.bloxbean.cardano/"
                    + "yano-plugin-catalog-runtime/resource-config.json";
    private static final String APP_RESOURCE_CONFIG =
            "META-INF/native-image/com.bloxbean.cardano/yano-app/resource-config.json";

    @Test
    void generatedReflectionConfigExactlyMatchesIndexedProviderConstructors() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Set<String> expectedProviders;
        try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.packagedClasspath(
                PluginsOptions.defaults(), loader)) {
            expectedProviders = environment.catalog().bundles().stream()
                    .flatMap(bundle -> bundle.contributions().stream())
                    .map(contribution -> contribution.providerClass())
                    .collect(Collectors.toSet());
        }

        JsonNode entries;
        try (InputStream input = onlyResource(loader, GENERATED_REFLECTION_CONFIG).openStream()) {
            entries = new ObjectMapper().readTree(input);
        }
        assertTrue(entries.isArray());

        Map<String, JsonNode> entriesByName = new HashMap<>();
        entries.forEach(entry -> {
            assertEquals(Set.of("name", "allDeclaredConstructors"),
                    entry.properties().stream().map(Map.Entry::getKey)
                            .collect(Collectors.toSet()));
            String provider = entry.path("name").asText();
            assertNull(entriesByName.put(provider, entry),
                    () -> "duplicate reflection entry for " + provider);
            assertTrue(entry.path("allDeclaredConstructors").asBoolean(),
                    () -> "constructor reflection for " + provider);
        });

        assertEquals(expectedProviders, entriesByName.keySet());
    }

    @Test
    void catalogLibraryAndApplicationMetadataHaveOneNonCollidingResourceEach()
            throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        URL generated = onlyResource(loader, GENERATED_REFLECTION_CONFIG);
        URL codec = onlyResource(loader, CODEC_REFLECTION_CONFIG);
        URL resources = onlyResource(loader, RUNTIME_RESOURCE_CONFIG);

        assertTrue(!generated.equals(codec) && !generated.equals(resources)
                && !codec.equals(resources));
    }

    @Test
    void applicationNativeResourcesRetainBundledAnchorArtifacts() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        JsonNode config;
        try (InputStream input = onlyResource(loader, APP_RESOURCE_CONFIG).openStream()) {
            config = new ObjectMapper().readTree(input);
        }
        assertTrue(config.path("resources").path("includes").findValuesAsText("pattern")
                .contains("META-INF/plutus/.*\\.json"));
    }

    private static URL onlyResource(ClassLoader loader, String path) throws Exception {
        List<URL> resources = Collections.list(loader.getResources(path));
        assertEquals(1, resources.size(), () -> "expected exactly one resource at " + path);
        URL resource = resources.getFirst();
        assertNotNull(resource);
        return resource;
    }
}
