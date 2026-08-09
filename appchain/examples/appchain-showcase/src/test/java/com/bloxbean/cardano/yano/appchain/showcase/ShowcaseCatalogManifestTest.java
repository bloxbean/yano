package com.bloxbean.cardano.yano.appchain.showcase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ShowcaseCatalogManifestTest {
    private static final Path CATALOG = Path.of(
            "src/main/showcase/config/showcase-catalog-v1.json");
    private static final Path YAML = Path.of(
            "src/main/showcase/config/application-appchain.yml");

    @Test
    void catalogIsTheOrderedThirteenChainSourceOfTruthForLightYaml() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(CATALOG));
        String capabilityGuide = Files.readString(Path.of("docs/CAPABILITY_CATALOG.md"));
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(root.path("profileId").asText()).isEqualTo("light-v1");
        List<String> catalogIds = new ArrayList<>();
        Map<String, Integer> classifications = new HashMap<>();
        for (JsonNode chain : root.path("chains")) {
            catalogIds.add(chain.path("chainId").asText());
            assertThat(capabilityGuide).contains("`" + chain.path("chainId").asText() + "`");
            assertThat(chain.path("scenarioId").asText()).isNotBlank();
            assertThat(chain.path("classification").asText()).isNotBlank();
            classifications.merge(chain.path("classification").asText(), 1, Integer::sum);
            assertThat(chain.path("proofBackend").asText()).isIn("mpf", "jmt");
            assertThat(chain.path("expectedCapabilities").isArray()).isTrue();
            assertThat(chain.path("expectedCapabilities").size()).isPositive();
        }
        assertThat(classifications).containsExactlyInAnyOrderEntriesOf(Map.of(
                "foundation", 7,
                "reference", 3,
                "backend-comparison", 1,
                "l1-boundary", 1,
                "product", 1));
        Matcher matcher = Pattern.compile(
                "(?m)^      chain-id: \\\"?([^\\\"\\s]+)\\\"?$")
                .matcher(Files.readString(YAML));
        List<String> yamlIds = new ArrayList<>();
        while (matcher.find()) yamlIds.add(matcher.group(1));
        assertThat(catalogIds).hasSize(13).containsExactlyElementsOf(yamlIds);
    }
}
