package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginIndexCodecTest {
    private static final String DIGEST_A = "sha256:" + "a".repeat(64);
    private static final String DIGEST_B = "sha256:" + "b".repeat(64);

    private final PluginIndexCodec codec = new PluginIndexCodec();

    @Test
    void canonicalRoundTripSortsEveryRepeatedField() throws Exception {
        BundleManifest manifest = new BundleManifest(
                1,
                "com.example.bundle",
                SemVersion.parse("2.1.0"),
                new YanoApiRange(1, 2, 1),
                List.of(
                        new BundleDependency("com.example.zeta", SemVersion.parse("1.0.0"), null),
                        new BundleDependency("com.example.alpha", null, SemVersion.parse("3.0.0"))),
                List.of(
                        new BundleContribution(
                                ContributionKind.DOMAIN_API,
                                "com.example.bundle",
                                "com.example.BundleDomainApiProvider"),
                        new BundleContribution(
                                ContributionKind.HEALTH,
                                "com.example.bundle",
                                "com.example.BundleHealthProvider"),
                        new BundleContribution(
                                ContributionKind.METRICS,
                                "com.example.bundle",
                                "com.example.BundleMetricsProvider"),
                        new BundleContribution(
                                ContributionKind.FINALIZED_SINK, "zeta", "com.example.ZetaProvider"),
                        new BundleContribution(
                                ContributionKind.APP_STATE_MACHINE, "alpha", "com.example.AlphaProvider")));
        PluginIndex index = new PluginIndex(
                1,
                List.of(new IndexedBundle(manifest, DIGEST_A, PluginDigestMode.JAR)),
                List.of(new IndexedLegacyProvider(
                        ContributionKind.SIGNER_PROVIDER,
                        "com.example.LegacySigner",
                        DIGEST_B,
                        PluginDigestMode.LEGACY_CLASS)));

        byte[] encoded = codec.write(index);
        PluginIndex decoded = codec.read(new ByteArrayInputStream(encoded));

        assertThat(decoded).isEqualTo(index);
        assertThat(codec.write(decoded)).isEqualTo(encoded);
        String json = new String(encoded, StandardCharsets.UTF_8);
        assertThat(json.indexOf("com.example.alpha")).isLessThan(json.indexOf("com.example.zeta"));
        assertThat(json).doesNotContain("\n").startsWith("{\"schemaVersion\":1");
    }

    @Test
    void writesStableEmptyDocument() {
        assertThat(new String(codec.write(PluginIndex.empty()), StandardCharsets.UTF_8))
                .isEqualTo("{\"schemaVersion\":1,\"bundles\":[],\"legacyProviders\":[]}");
    }

    @Test
    @SuppressWarnings("deprecation")
    void sharedProviderBudgetAcceptsExactLimitAndRejectsNextEvidence() {
        assertThat(PluginIndex.MAX_PROVIDERS).isEqualTo(32_768);
        assertThat(PluginIndex.MAX_LEGACY_PROVIDERS).isEqualTo(PluginIndex.MAX_PROVIDERS);

        assertThatCode(() -> PluginIndex.validateProviderLimit(
                PluginIndex.MAX_PROVIDERS - 1L, 1L)).doesNotThrowAnyException();
        assertThatCode(() -> PluginIndex.validateProviderLimit(
                PluginIndex.MAX_PROVIDERS, 0L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> PluginIndex.validateProviderLimit(
                PluginIndex.MAX_PROVIDERS - 1L, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("plugin index contains too many providers; maximum 32768 covers "
                        + "manifested contributions plus legacy-provider evidence");
        assertThatThrownBy(() -> PluginIndex.validateProviderLimit(
                PluginIndex.MAX_PROVIDERS, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum 32768");
    }

    @Test
    void rejectsUnknownDuplicateMissingAndTrailingFields() {
        String valid = """
                {"schemaVersion":1,"bundles":[],"legacyProviders":[]}
                """;
        assertThatThrownBy(() -> codec.read(valid.replace(
                        "\"bundles\":[]", "\"unknown\":true,\"bundles\":[]").getBytes()))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("unknown field");
        assertThatThrownBy(() -> codec.read(valid.replace(
                        "\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1").getBytes()))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("duplicate-key");
        assertThatThrownBy(() -> codec.read(
                        "{\"schemaVersion\":1,\"bundles\":[]}".getBytes()))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("legacyProviders must be present");
        assertThatThrownBy(() -> codec.read((valid + "{}").getBytes()))
                .isInstanceOf(PluginCatalogException.class);
    }

    @Test
    void rejectsUnsupportedModesAndSchema() {
        String legacy = """
                {
                  "schemaVersion":1,
                  "bundles":[],
                  "legacyProviders":[{
                    "kind":"finalized-sink",
                    "provider":"com.example.Provider",
                    "digest":"%s",
                    "digestMode":"WEAK"
                  }]
                }
                """.formatted(DIGEST_A);
        assertThatThrownBy(() -> codec.read(legacy.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("digestMode is not supported");
        assertThatThrownBy(() -> codec.read(legacy.replace(
                        "WEAK", "ARTIFACT_CLOSURE").getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("legacy provider digestMode must be JAR");
        assertThatThrownBy(() -> codec.read(
                        "{\"schemaVersion\":2,\"bundles\":[],\"legacyProviders\":[]}".getBytes()))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("schemaVersion must equal 1");

        BundleManifest manifest = new BundleManifest(
                1,
                "com.example.invalid-mode",
                SemVersion.parse("1.0.0"),
                new YanoApiRange(1, 1, 1),
                List.of(),
                List.of());
        assertThatThrownBy(() -> new IndexedBundle(
                        manifest, DIGEST_A, PluginDigestMode.LEGACY_CLASS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be JAR");
        assertThatThrownBy(() -> new IndexedLegacyProvider(
                        ContributionKind.FINALIZED_SINK,
                        "com.example.LegacyProvider",
                        DIGEST_A,
                        PluginDigestMode.ARTIFACT_CLOSURE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("legacy provider digestMode must be JAR");
    }

    @Test
    void domainApiCannotBeEncodedOrDecodedAsALegacyProvider() {
        assertThatThrownBy(() -> new IndexedLegacyProvider(
                ContributionKind.DOMAIN_API,
                "com.example.LegacyDomainApiProvider",
                DIGEST_A,
                PluginDigestMode.JAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domain-api providers require a bundle manifest");

        String raw = """
                {
                  "schemaVersion":1,
                  "bundles":[],
                  "legacyProviders":[{
                    "kind":"domain-api",
                    "provider":"com.example.LegacyDomainApiProvider",
                    "digest":"%s",
                    "digestMode":"JAR"
                  }]
                }
                """.formatted(DIGEST_A);
        assertThatThrownBy(() -> codec.read(raw.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("domain-api providers require a bundle manifest");
    }

    @Test
    void healthAndMetricsCannotBeRepresentedAsLegacyProviders() {
        for (ContributionKind kind : List.of(
                ContributionKind.HEALTH, ContributionKind.METRICS)) {
            assertThatThrownBy(() -> new IndexedLegacyProvider(
                    kind,
                    "com.example.LegacyObservabilityProvider",
                    DIGEST_A,
                    PluginDigestMode.JAR))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(kind.manifestKey()
                            + " providers require a bundle manifest");
        }
    }

    @Test
    void rejectsNonUtf8AndImpossibleManifestSnapshots() {
        String valid = "{\"schemaVersion\":1,\"bundles\":[],\"legacyProviders\":[]}";
        assertThatThrownBy(() -> codec.read(valid.getBytes(StandardCharsets.UTF_16)))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("must be valid UTF-8");

        List<BundleContribution> contributions = new ArrayList<>();
        for (int i = 0; i < CatalogValidation.MAX_CONTRIBUTIONS; i++) {
            String provider = "com.example.P" + i + "x".repeat(470);
            contributions.add(new BundleContribution(
                    ContributionKind.FINALIZED_SINK, "sink" + i, provider));
        }
        BundleManifest oversized = new BundleManifest(
                1,
                "com.example.oversized",
                SemVersion.parse("1.0.0"),
                new YanoApiRange(1, 1, 1),
                List.of(),
                contributions);
        PluginIndex index = new PluginIndex(
                1,
                List.of(new IndexedBundle(oversized, DIGEST_A, PluginDigestMode.JAR)),
                List.of());
        assertThatThrownBy(() -> codec.write(index))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("manifest snapshot")
                .hasMessageContaining("exceeds 65536 bytes");
    }

    @Test
    void rejectsOneProviderClassAttributedToDifferentArtifacts() {
        BundleManifest first = manifestWithProvider(
                "com.example.first", ContributionKind.FINALIZED_SINK, "sink");
        BundleManifest second = manifestWithProvider(
                "com.example.second", ContributionKind.L1_OBSERVER, "observer");

        assertThatThrownBy(() -> new PluginIndex(
                        1,
                        List.of(
                                new IndexedBundle(first, DIGEST_A, PluginDigestMode.JAR),
                                new IndexedBundle(second, DIGEST_B, PluginDigestMode.JAR)),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiple artifact sources");
    }

    @Test
    void rejectsOversizedRawManifestArraysBeforeModelConstruction() {
        StringBuilder contributions = new StringBuilder();
        for (int i = 0; i <= CatalogValidation.MAX_CONTRIBUTIONS; i++) {
            if (i > 0) {
                contributions.append(',');
            }
            contributions.append("""
                    {"kind":"finalized-sink","name":"sink%s","provider":"com.example.Provider%s"}
                    """.formatted(i, i).trim());
        }
        String json = """
                {
                  "schemaVersion":1,
                  "bundles":[{
                    "manifest":{
                      "schemaVersion":1,
                      "id":"com.example.too-many",
                      "version":"1.0.0",
                      "yanoApi":{"min":1,"max":1,"minLevel":1},
                      "dependencies":[],
                      "contributions":[%s]
                    },
                    "digest":"%s",
                    "digestMode":"JAR"
                  }],
                  "legacyProviders":[]
                }
                """.formatted(contributions, DIGEST_A);

        assertThatThrownBy(() -> codec.read(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("contributions must contain at most 256 entries");
    }

    @Test
    void aggregateIndexRequiresManifestMinimumApiLevel() {
        String json = """
                {
                  "schemaVersion":1,
                  "bundles":[{
                    "manifest":{
                      "schemaVersion":1,
                      "id":"com.example.missing-level",
                      "version":"1.0.0",
                      "yanoApi":{"min":1,"max":1},
                      "dependencies":[],
                      "contributions":[]
                    },
                    "digest":"%s",
                    "digestMode":"JAR"
                  }],
                  "legacyProviders":[]
                }
                """.formatted(DIGEST_A);

        assertThatThrownBy(() -> codec.read(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("yanoApi.minLevel must be present");
    }

    private static BundleManifest manifestWithProvider(
            String id,
            ContributionKind kind,
            String name
    ) {
        return new BundleManifest(
                1,
                id,
                SemVersion.parse("1.0.0"),
                new YanoApiRange(1, 1, 1),
                List.of(),
                List.of(new BundleContribution(kind, name, "com.example.SharedProvider")));
    }
}
