package com.bloxbean.cardano.yano.catalog;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleManifestParserTest {
    private static final String ID = "com.example.product-passport";
    private static final String RESOURCE = "META-INF/yano/plugins/" + ID + ".json";
    private static final BundleManifestParser PARSER = new BundleManifestParser();

    @Test
    void parsesAFullManifest() {
        BundleManifest manifest = parse("""
                {
                  "schemaVersion": 1,
                  "id": "com.example.product-passport",
                  "version": "1.2.0-beta.1+build.7",
                  "yanoApi": { "min": 1, "max": 2 },
                  "dependencies": [
                    {
                      "id": "com.example.shared-claims",
                      "minVersion": "1.0.0",
                      "maxVersionExclusive": "2.0.0"
                    }
                  ],
                  "contributions": [
                    {
                      "kind": "app-state-machine",
                      "name": "product-passport",
                      "provider": "com.example.passport.PassportStateMachineProvider"
                    }
                  ]
                }
                """);

        assertThat(manifest.id()).isEqualTo(ID);
        assertThat(manifest.version().toString()).isEqualTo("1.2.0-beta.1+build.7");
        assertThat(manifest.yanoApi().supports(2)).isTrue();
        assertThat(manifest.dependencies()).containsExactly(new BundleDependency(
                "com.example.shared-claims", SemVersion.parse("1.0.0"), SemVersion.parse("2.0.0")));
        assertThat(manifest.contributions()).containsExactly(new BundleContribution(
                ContributionKind.APP_STATE_MACHINE,
                "product-passport",
                "com.example.passport.PassportStateMachineProvider"));
        assertThat(manifest.resourcePath()).isEqualTo(RESOURCE);
    }

    @Test
    void treatsMissingArraysAsEmptyAndSupportsInputStreams() {
        byte[] json = minimalManifest().getBytes(StandardCharsets.UTF_8);
        BundleManifest manifest = PARSER.parse(RESOURCE, new ByteArrayInputStream(json));

        assertThat(manifest.dependencies()).isEmpty();
        assertThat(manifest.contributions()).isEmpty();
    }

    @Test
    void modelDefensivelyCopiesLists() {
        List<BundleDependency> dependencies = new ArrayList<>();
        List<BundleContribution> contributions = new ArrayList<>();
        BundleManifest manifest = new BundleManifest(
                1, ID, SemVersion.parse("1.0.0"), new YanoApiRange(1, 1), dependencies, contributions);
        dependencies.add(new BundleDependency("com.example.other", null, null));
        contributions.add(new BundleContribution(
                ContributionKind.FINALIZED_SINK, "sink", "com.example.SinkProvider"));

        assertThat(manifest.dependencies()).isEmpty();
        assertThat(manifest.contributions()).isEmpty();
        assertThatThrownBy(() -> manifest.dependencies().add(new BundleDependency(
                "com.example.another", null, null))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnknownAndDuplicateJsonFields() {
        String unknown = minimalManifest().replace(
                "\"version\": \"1.0.0\",",
                "\"version\": \"1.0.0\", \"mystery\": true,");
        assertThatThrownBy(() -> parse(unknown))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("at $.mystery: unknown field")
                .hasMessageNotContaining("$.mystery.mystery");
        assertInvalid(minimalManifest().replace(
                "\"version\": \"1.0.0\",",
                "\"version\": \"1.0.0\", \"version\": \"2.0.0\","),
                "version", "malformed JSON");
    }

    @Test
    void rejectsScalarCoercionAndTrailingContent() {
        assertInvalid(replace(minimalManifest(), "\"schemaVersion\": 1", "\"schemaVersion\": \"1\""),
                "schemaVersion", "wrong JSON type");
        assertInvalid(minimalManifest() + " {}", "$", "wrong JSON type");
    }

    @Test
    void rejectsOversizeAndNonUtf8ManifestsWithoutEchoingContent() {
        byte[] oversized = new byte[BundleManifestParser.MAX_MANIFEST_BYTES + 1];
        assertThatThrownBy(() -> PARSER.parse(RESOURCE, oversized))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining(RESOURCE)
                .hasMessageContaining("exceeds 65536 bytes");

        byte[] nonUtf8 = {(byte) 0xc3, (byte) 0x28};
        assertThatThrownBy(() -> PARSER.parse(RESOURCE, nonUtf8))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining(RESOURCE)
                .hasMessageContaining("valid UTF-8")
                .hasMessageNotContaining("c3");
    }

    @Test
    void enforcesResourceContractAndBundleIdMatch() {
        assertThatThrownBy(() -> PARSER.parse("plugin.json", bytes(minimalManifest())))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("META-INF/yano/plugins/<id>.json");
        assertThatThrownBy(() -> PARSER.parse(
                "META-INF/yano/plugins/com.example.other.json", bytes(minimalManifest())))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("at id")
                .hasMessageContaining("match the resource filename");
    }

    @Test
    void rejectsInvalidSchemaIdentityAndApiRanges() {
        assertInvalid(replace(minimalManifest(), "\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                "schemaVersion", "must equal 1");
        assertInvalid(replace(minimalManifest(), ID, "Example Plugin"), "id", "reverse-DNS");
        assertInvalid(replace(minimalManifest(), "\"min\": 1", "\"min\": 0"),
                "yanoApi.min", "positive");
        assertInvalid(replace(minimalManifest(), "\"min\": 1, \"max\": 1", "\"min\": 2, \"max\": 1"),
                "yanoApi", "min <= max");
    }

    @Test
    void rejectsInvalidSemVerAndDependencyRanges() {
        assertInvalid(replace(minimalManifest(), "1.0.0", "01.0.0"), "version", "SemVer");
        String dependency = """
                , "dependencies": [{
                  "id": "com.example.shared",
                  "minVersion": "2.0.0",
                  "maxVersionExclusive": "2.0.0"
                }]
                """;
        assertInvalid(minimalManifest().replace("\n}", dependency + "\n}"),
                "dependency", "minVersion < maxVersionExclusive");
    }

    @Test
    void rejectsInvalidContributionKindNameAndProvider() {
        String template = """
                , "contributions": [{
                  "kind": "%s",
                  "name": "%s",
                  "provider": "%s"
                }]
                """;
        assertInvalid(withContribution(template.formatted(
                "unknown", "valid", "com.example.Provider")), "contribution", "not supported");
        assertInvalid(withContribution(template.formatted(
                "finalized-sink", " leading", "com.example.Provider")), "contributions[].name", "selector");
        assertInvalid(withContribution(template.formatted(
                        "finalized-sink", "com.example.kafka", "com.example.Provider")),
                "contributions[].name", "must not contain '.'");
        assertInvalid(withContribution(template.formatted(
                        "effect-executor", "com.example.cardano", "com.example.Provider")),
                "contributions[].name", "must not contain '.'");
        assertInvalid(withContribution(template.formatted(
                        "signer-provider", "vault:prod", "com.example.Provider")),
                "contributions[].name", "must not contain ':'");
        assertInvalid(withContribution(template.formatted(
                        "finalized-sink", "valid", "not-qualified")),
                "contributions[].provider", "fully qualified");
        assertInvalid(withContribution(template.formatted(
                        "finalized-sink", "valid", "com.example.Providér")),
                "contributions[].provider", "fully qualified");
        assertThat(new BundleContribution(
                ContributionKind.FINALIZED_SINK, "valid", "com.example.Outer$Provider").provider())
                .isEqualTo("com.example.Outer$Provider");
        assertThat(new BundleContribution(ContributionKind.APP_STATE_MACHINE,
                "com.example:query", "com.example.Provider").name())
                .isEqualTo("com.example:query");
    }

    @Test
    void rejectsDuplicateEntriesInsideOneManifest() {
        String duplicateDependencies = """
                , "dependencies": [
                  {"id": "com.example.shared"},
                  {"id": "com.example.shared"}
                ]
                """;
        assertInvalid(minimalManifest().replace("\n}", duplicateDependencies + "\n}"),
                "dependencies", "duplicate ids");

        String duplicateContributions = """
                , "contributions": [
                  {"kind": "finalized-sink", "name": "kafka", "provider": "com.example.One"},
                  {"kind": "finalized-sink", "name": "kafka", "provider": "com.example.Two"}
                ]
                """;
        assertInvalid(minimalManifest().replace("\n}", duplicateContributions + "\n}"),
                "contributions", "duplicate kind/name");
    }

    @Test
    void domainApiIsManifestedOnceAndOwnedByTheContainingBundleId() {
        String valid = withContribution("""
                , "contributions": [{
                  "kind": "domain-api",
                  "name": "com.example.product-passport",
                  "provider": "com.example.passport.PassportDomainApiProvider"
                }]
                """);

        assertThat(parse(valid).contributions()).containsExactly(new BundleContribution(
                ContributionKind.DOMAIN_API,
                ID,
                "com.example.passport.PassportDomainApiProvider"));
        assertThat(ContributionKind.DOMAIN_API.manifestRequired()).isTrue();
        assertThat(ContributionKind.DOMAIN_API.serviceType().getName())
                .isEqualTo("com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider");

        assertInvalid(valid.replace(
                        "\"name\": \"com.example.product-passport\"",
                        "\"name\": \"com.example.other\""),
                "domain-api", "must equal the containing bundle id");

        String duplicate = minimalManifest().replace("\n}", """
                , "contributions": [
                  {
                    "kind": "domain-api",
                    "name": "com.example.product-passport",
                    "provider": "com.example.passport.FirstDomainApiProvider"
                  },
                  {
                    "kind": "domain-api",
                    "name": "com.example.product-passport",
                    "provider": "com.example.passport.SecondDomainApiProvider"
                  }
                ]
                }
                """);
        assertInvalid(duplicate, "contributions", "at most one domain-api");
    }

    @Test
    void domainApiSelectorUsesTheFullBundleIdGrammarAndLength() {
        String longId = "com." + "a".repeat(63) + "." + "b".repeat(63) + ".example";
        BundleContribution contribution = new BundleContribution(
                ContributionKind.DOMAIN_API, longId, "com.example.DomainProvider");
        BundleManifest manifest = new BundleManifest(
                1,
                longId,
                SemVersion.parse("1.0.0"),
                new YanoApiRange(1, 1),
                List.of(),
                List.of(contribution));

        assertThat(longId.length()).isGreaterThan(CatalogValidation.MAX_NAME_LENGTH);
        assertThat(manifest.contributions()).containsExactly(contribution);
        assertThatThrownBy(() -> new BundleContribution(
                ContributionKind.DOMAIN_API, "domain-api", "com.example.DomainProvider"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reverse-DNS");
    }

    private static BundleManifest parse(String json) {
        return PARSER.parse(RESOURCE, bytes(json));
    }

    private static void assertInvalid(String json, String field, String reason) {
        assertThatThrownBy(() -> parse(json))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining(RESOURCE)
                .hasMessageContaining(field)
                .hasMessageContaining(reason)
                .hasMessageNotContaining(json);
    }

    private static String withContribution(String contribution) {
        return minimalManifest().replace("\n}", contribution + "\n}");
    }

    private static String replace(String value, String target, String replacement) {
        return value.replace(target, replacement);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String minimalManifest() {
        return """
                {
                  "schemaVersion": 1,
                  "id": "com.example.product-passport",
                  "version": "1.0.0",
                  "yanoApi": { "min": 1, "max": 1 }
                }
                """;
    }
}
