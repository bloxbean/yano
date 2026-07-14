package com.bloxbean.cardano.yano.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PluginCatalogNativeImageMetadataTest {
    static final String CODEC_REFLECTION_CONFIG =
            "META-INF/native-image/com.bloxbean.cardano/"
                    + "yano-plugin-catalog-codec/reflect-config.json";
    static final String RUNTIME_RESOURCE_CONFIG =
            "META-INF/native-image/com.bloxbean.cardano/"
                    + "yano-plugin-catalog-runtime/resource-config.json";
    static final String APPLICATION_PROVIDER_REFLECTION_CONFIG =
            "META-INF/native-image/com.bloxbean.cardano/"
                    + "yano-plugin-catalog/reflect-config.json";

    @Test
    void reflectionConfigExactlyCoversEveryJacksonBoundRawRecord() throws Exception {
        Set<String> rawRecordNames = Arrays.stream(new Class<?>[]{
                        BundleManifestParser.class,
                        PluginIndexCodec.class
                })
                .flatMap(type -> Arrays.stream(type.getDeclaredClasses()))
                .filter(type -> type.isRecord() && type.getSimpleName().startsWith("Raw"))
                .map(Class::getName)
                .collect(Collectors.toSet());

        Map<String, JsonNode> entriesByName = new HashMap<>();
        ClassLoader loader = PluginIndexCodec.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(CODEC_REFLECTION_CONFIG)) {
            assertThat(input).as("catalog codec native reflection config").isNotNull();
            JsonNode entries = new ObjectMapper().readTree(input);
            assertThat(entries.isArray()).isTrue();
            for (JsonNode entry : entries) {
                String name = entry.path("name").asText();
                assertThat(entriesByName.put(name, entry))
                        .as("duplicate native reflection entry for %s", name)
                        .isNull();
            }
        }

        assertThat(entriesByName.keySet()).containsExactlyInAnyOrderElementsOf(rawRecordNames);
        entriesByName.forEach((name, entry) -> {
            assertThat(entry.properties()).as("reflection keys for %s", name)
                    .extracting(Map.Entry::getKey)
                    .containsExactlyInAnyOrder(
                            "name", "allDeclaredConstructors",
                            "allDeclaredMethods", "allDeclaredFields");
            assertThat(entry.path("allDeclaredConstructors").asBoolean())
                    .as("declared constructors for %s", name)
                    .isTrue();
            assertThat(entry.path("allDeclaredMethods").asBoolean())
                    .as("declared methods for %s", name)
                    .isTrue();
            assertThat(entry.path("allDeclaredFields").asBoolean())
                    .as("declared fields for %s", name)
                    .isTrue();
        });
    }

    @Test
    void nativeJacksonApiRangeRecordsRetainRequiredMinimumLevel() {
        for (Class<?> owner : List.of(BundleManifestParser.class, PluginIndexCodec.class)) {
            Class<?> rawYanoApi = Arrays.stream(owner.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("RawYanoApi"))
                    .findFirst()
                    .orElseThrow();
            assertThat(Arrays.stream(rawYanoApi.getRecordComponents())
                    .map(RecordComponent::getName))
                    .as("native Jackson API range fields for %s", owner.getSimpleName())
                    .containsExactly("min", "max", "minLevel");
        }
    }

    @Test
    void runtimeResourceConfigExactlyRetainsIndexAndSupportedServiceDescriptors()
            throws Exception {
        Set<String> expectedPatterns = Arrays.stream(ContributionKind.values())
                .map(ContributionKind::serviceType)
                .map(Class::getName)
                .map(name -> "\\QMETA-INF/services/" + name + "\\E")
                .collect(Collectors.toCollection(TreeSet::new));
        expectedPatterns.add("\\Q" + PluginIndex.RESOURCE_PATH + "\\E");

        ClassLoader loader = PluginIndexCodec.class.getClassLoader();
        JsonNode config;
        try (InputStream input = loader.getResourceAsStream(RUNTIME_RESOURCE_CONFIG)) {
            assertThat(input).as("catalog runtime native resource config").isNotNull();
            config = new ObjectMapper().readTree(input);
        }

        assertThat(config.properties()).extracting(Map.Entry::getKey)
                .containsExactly("resources");
        JsonNode resources = config.path("resources");
        assertThat(resources.properties()).extracting(Map.Entry::getKey)
                .containsExactly("includes");
        JsonNode includes = resources.path("includes");
        assertThat(includes.isArray()).isTrue();

        Set<String> actualPatterns = new TreeSet<>();
        includes.forEach(entry -> {
            assertThat(entry.properties()).extracting(Map.Entry::getKey)
                    .containsExactly("pattern");
            assertThat(actualPatterns.add(entry.path("pattern").asText()))
                    .as("duplicate native resource pattern %s", entry)
                    .isTrue();
        });
        assertThat(actualPatterns).containsExactlyElementsOf(expectedPatterns);
    }

    @Test
    void libraryMetadataDoesNotClaimApplicationGeneratedProviderReflectionPath() {
        ClassLoader loader = PluginIndexCodec.class.getClassLoader();
        assertThat(loader.getResource(CODEC_REFLECTION_CONFIG)).isNotNull();
        assertThat(loader.getResource(RUNTIME_RESOURCE_CONFIG)).isNotNull();
        assertThat(loader.getResource(APPLICATION_PROVIDER_REFLECTION_CONFIG)).isNull();
    }
}
