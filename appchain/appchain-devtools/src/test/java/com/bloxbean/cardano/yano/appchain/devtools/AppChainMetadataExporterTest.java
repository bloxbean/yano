package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class AppChainMetadataExporterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AppChainMetadataExporter exporter = new AppChainMetadataExporter();

    @Test
    void generatedSchemaAndCatalogAreDeterministicPackagedResources() throws Exception {
        AppChainPropertyRegistry registry = AppChainPropertyRegistry.framework();
        String schema = exporter.runtimeSchemaJson(registry);
        String catalog = exporter.propertyCatalogJson(registry);

        assertThat(schema).isEqualTo(exporter.runtimeSchemaJson(registry));
        assertThat(catalog).isEqualTo(exporter.propertyCatalogJson(registry));
        assertThat(resource(AppChainMetadataExporter.RUNTIME_SCHEMA)).isEqualTo(schema);
        assertThat(resource(AppChainMetadataExporter.PROPERTY_CATALOG)).isEqualTo(catalog);
        assertThat(sha256(schema)).isEqualTo(goldenHash(AppChainMetadataExporter.RUNTIME_SCHEMA));
        assertThat(sha256(catalog))
                .isEqualTo(goldenHash(AppChainMetadataExporter.PROPERTY_CATALOG));

        JsonNode schemaJson = json.readTree(schema);
        JsonNode appChain = schemaJson.path("properties").path("yano")
                .path("properties").path("app-chain");
        assertThat(appChain.path("x-yano-validation-coverage").asText())
                .isEqualTo("PARTIAL");
        assertThat(appChain.path("patternProperties").path("^chains\\[[0-9]+]$")
                .path("properties").path("block").path("properties")
                .path("max-bytes").path("maximum").asLong()).isPositive();
        JsonNode effects = appChain.path("patternProperties").path("^chains\\[[0-9]+]$")
                .path("properties").path("effects");
        assertThat(effects.path("additionalProperties").asBoolean()).isTrue();
        assertThat(effects.path("properties").path("result")
                .path("additionalProperties").asBoolean()).isFalse();

        JsonNode catalogJson = json.readTree(catalog);
        assertThat(catalogJson.path("properties")).hasSize(registry.definitions().size());
        assertThat(catalogJson.path("dynamicNamespaces"))
                .hasSize(registry.dynamicNamespaces().size());
    }

    private String resource(String name) throws Exception {
        String path = "/" + AppChainMetadataExporter.RESOURCE_DIRECTORY + "/" + name;
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String goldenHash(String name) throws Exception {
        String golden = resource("metadata.sha256");
        return golden.lines()
                .filter(line -> line.endsWith("  " + name))
                .map(line -> line.substring(0, line.indexOf(' ')))
                .findFirst()
                .orElseThrow();
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
