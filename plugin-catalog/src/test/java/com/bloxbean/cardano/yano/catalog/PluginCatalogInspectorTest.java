package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.PluginApiVersion;
import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import com.bloxbean.cardano.yano.api.plugin.PluginSelectionStatus;
import com.bloxbean.cardano.yano.api.plugin.PluginTrustTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginCatalogInspectorTest {
    private static final String BASE = "com.example.base";
    private static final String FEATURE = "com.example.feature";
    private static final String DIGEST_A = "sha256:" + "a".repeat(64);
    private static final String DIGEST_B = "sha256:" + "b".repeat(64);

    private final PluginCatalogInspector inspector = new PluginCatalogInspector();

    @Test
    void validatesAndPublishesDeterministicDependencyFirstInventory() {
        IndexedBundle base = indexed(
                BASE, "1.2.0", new YanoApiRange(1, 2, 1), List.of(),
                List.of(new BundleContribution(
                        ContributionKind.HEALTH, BASE, "com.example.BaseHealth")),
                DIGEST_A);
        IndexedBundle feature = indexed(
                FEATURE, "2.0.0", new YanoApiRange(1, 1, 1),
                List.of(new BundleDependency(
                        BASE, SemVersion.parse("1.0.0"), SemVersion.parse("2.0.0"))),
                List.of(new BundleContribution(
                        ContributionKind.METRICS, FEATURE, "com.example.FeatureMetrics")),
                DIGEST_B);
        PluginIndex reverseInput = new PluginIndex(
                1, List.of(feature, base), List.of());

        PluginCatalogInspection first = inspector.inspect(
                reverseInput, PluginCatalogInspectionPolicy.current());
        PluginCatalogInspection second = inspector.inspect(
                new PluginIndex(1, List.of(base, feature), List.of()),
                PluginCatalogInspectionPolicy.current());

        assertThat(first).isEqualTo(second);
        assertThat(first.pluginApiMajor()).isEqualTo(PluginApiVersion.CURRENT_MAJOR);
        assertThat(first.pluginApiLevel()).isEqualTo(PluginApiVersion.CURRENT_LEVEL);
        assertThat(first.fingerprint()).matches("sha256:[0-9a-f]{64}");
        assertThat(first.selectedBundleOrder()).containsExactly(BASE, FEATURE);
        assertThat(first.bundles()).extracting(bundle -> bundle.id())
                .containsExactly(BASE, FEATURE);
        assertThat(first.bundles()).allSatisfy(bundle -> {
            assertThat(bundle.selected()).isTrue();
            assertThat(bundle.selectionStatus()).isEqualTo(PluginSelectionStatus.SELECTED);
            assertThat(bundle.legacy()).isFalse();
        });
        assertThat(first.bundles().getFirst().contributions().getFirst().trustTier())
                .isEqualTo(PluginTrustTier.AUXILIARY_LOCAL);
        assertThat(first.bundles().getLast().contributions().getFirst().trustTier())
                .isEqualTo(PluginTrustTier.AUXILIARY_LOCAL);
    }

    @Test
    void appliesDenyBeforeAllowAndRejectsMissingAllowEntries() {
        PluginIndex index = new PluginIndex(1, List.of(
                indexed(BASE, "1.0.0", new YanoApiRange(1, 1, 1), List.of(), List.of(), DIGEST_A),
                indexed(FEATURE, "1.0.0", new YanoApiRange(1, 1, 1),
                        List.of(), List.of(), DIGEST_B)), List.of());
        PluginCatalogInspectionPolicy policy = new PluginCatalogInspectionPolicy(
                1, 1, Set.of(BASE, FEATURE), Set.of(FEATURE));

        PluginCatalogInspection result = inspector.inspect(index, policy);

        assertThat(result.selectedBundleOrder()).containsExactly(BASE);
        assertThat(result.bundles()).extracting(bundle -> bundle.selectionStatus())
                .containsExactly(PluginSelectionStatus.SELECTED, PluginSelectionStatus.DENIED);

        PluginCatalogInspectionPolicy missing = new PluginCatalogInspectionPolicy(
                1, 1, Set.of(BASE, "com.example.missing"), Set.of());
        assertThatThrownBy(() -> inspector.inspect(index, missing))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("Allow-listed plugin bundles were not discovered")
                .hasMessageContaining("com.example.missing");
    }

    @Test
    void rejectsUnsupportedApiEvenWhenBundleIsDenied() {
        PluginIndex index = new PluginIndex(1, List.of(indexed(
                FEATURE, "1.0.0", new YanoApiRange(2, 2, 1),
                List.of(), List.of(), DIGEST_B)), List.of());
        PluginCatalogInspectionPolicy policy = new PluginCatalogInspectionPolicy(
                1, 1, Set.of(), Set.of(FEATURE));

        assertThatThrownBy(() -> inspector.inspect(index, policy))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("does not support Yano plugin API major 1");
    }

    @Test
    void compatibilityMatrixKeepsMajorRangeAndGlobalLevelIndependent() {
        YanoApiRange requiresLevelTwo = new YanoApiRange(1, 2, 2);
        assertThat(requiresLevelTwo.supports(1, 1)).isFalse();
        assertThat(requiresLevelTwo.supports(1, 2)).isTrue();
        assertThat(requiresLevelTwo.supports(2, 1)).isFalse();
        assertThat(requiresLevelTwo.supports(2, 2)).isTrue();
        assertThat(requiresLevelTwo.supports(3, 99)).isFalse();

        PluginIndex index = new PluginIndex(1, List.of(indexed(
                FEATURE, "1.0.0", requiresLevelTwo,
                List.of(), List.of(), DIGEST_B)), List.of());
        PluginCatalogInspectionPolicy oldHost = new PluginCatalogInspectionPolicy(
                1, 1, Set.of(), Set.of());
        assertThatThrownBy(() -> inspector.inspect(index, oldHost))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("API major 1 level 1");

        PluginCatalogInspection levelTwo = inspector.inspect(index,
                new PluginCatalogInspectionPolicy(1, 2, Set.of(), Set.of()));
        PluginCatalogInspection nextMajor = inspector.inspect(index,
                new PluginCatalogInspectionPolicy(2, 2, Set.of(), Set.of()));
        PluginCatalogInspection higherLevel = inspector.inspect(index,
                new PluginCatalogInspectionPolicy(1, 3, Set.of(), Set.of()));
        assertThat(levelTwo.pluginApiLevel()).isEqualTo(2);
        assertThat(nextMajor.pluginApiMajor()).isEqualTo(2);
        assertThat(levelTwo.fingerprint()).isNotEqualTo(nextMajor.fingerprint());
        assertThat(levelTwo.fingerprint()).isNotEqualTo(higherLevel.fingerprint());

        PluginIndex lowerRequirement = new PluginIndex(1, List.of(indexed(
                FEATURE, "1.0.0", new YanoApiRange(1, 2, 1),
                List.of(), List.of(), DIGEST_B)), List.of());
        assertThat(inspector.inspect(lowerRequirement,
                new PluginCatalogInspectionPolicy(1, 2, Set.of(), Set.of())).fingerprint())
                .isNotEqualTo(levelTwo.fingerprint());
    }

    @Test
    void rejectsMissingOrIncompatibleSelectedDependencies() {
        IndexedBundle missingDependency = indexed(
                FEATURE, "1.0.0", new YanoApiRange(1, 1, 1),
                List.of(new BundleDependency(BASE, null, null)), List.of(), DIGEST_B);
        assertThatThrownBy(() -> inspector.inspect(
                new PluginIndex(1, List.of(missingDependency), List.of()),
                PluginCatalogInspectionPolicy.current()))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("requires unavailable selected bundle")
                .hasMessageContaining(BASE);

        IndexedBundle base = indexed(
                BASE, "1.0.0", new YanoApiRange(1, 1, 1), List.of(), List.of(), DIGEST_A);
        IndexedBundle incompatible = indexed(
                FEATURE, "1.0.0", new YanoApiRange(1, 1, 1),
                List.of(new BundleDependency(BASE, SemVersion.parse("2.0.0"), null)),
                List.of(), DIGEST_B);
        assertThatThrownBy(() -> inspector.inspect(
                new PluginIndex(1, List.of(base, incompatible), List.of()),
                PluginCatalogInspectionPolicy.current()))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("requires an incompatible version");
    }

    @Test
    void rejectsCyclesAndSelectedContributionCollisions() {
        IndexedBundle baseCycle = indexed(
                BASE, "1.0.0", new YanoApiRange(1, 1, 1),
                List.of(new BundleDependency(FEATURE, null, null)), List.of(), DIGEST_A);
        IndexedBundle featureCycle = indexed(
                FEATURE, "1.0.0", new YanoApiRange(1, 1, 1),
                List.of(new BundleDependency(BASE, null, null)), List.of(), DIGEST_B);
        assertThatThrownBy(() -> inspector.inspect(
                new PluginIndex(1, List.of(baseCycle, featureCycle), List.of()),
                PluginCatalogInspectionPolicy.current()))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("dependency cycle")
                .hasMessageContaining(BASE)
                .hasMessageContaining(FEATURE);

        IndexedBundle first = indexed(
                BASE, "1.0.0", new YanoApiRange(1, 1, 1), List.of(),
                List.of(new BundleContribution(ContributionKind.FINALIZED_SINK,
                        "shared", "com.example.FirstSink")), DIGEST_A);
        IndexedBundle second = indexed(
                FEATURE, "1.0.0", new YanoApiRange(1, 1, 1), List.of(),
                List.of(new BundleContribution(ContributionKind.FINALIZED_SINK,
                        "shared", "com.example.SecondSink")), DIGEST_B);
        assertThatThrownBy(() -> inspector.inspect(
                new PluginIndex(1, List.of(first, second), List.of()),
                PluginCatalogInspectionPolicy.current()))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("Duplicate selected contribution")
                .hasMessageContaining("finalized-sink/shared");
    }

    @Test
    void rejectsLegacyProvidersBecauseOfflineInspectionNeverLoadsCode() {
        PluginIndex legacy = new PluginIndex(1, List.of(), List.of(
                new IndexedLegacyProvider(
                        ContributionKind.FINALIZED_SINK,
                        "com.example.LegacySink",
                        DIGEST_A,
                        PluginDigestMode.ARTIFACT_TREE)));

        assertThatThrownBy(() -> inspector.inspect(
                legacy, PluginCatalogInspectionPolicy.current()))
                .isInstanceOf(PluginCatalogException.class)
                .hasMessageContaining("requires bundle manifests")
                .hasMessageContaining("without loading code");
    }

    private static IndexedBundle indexed(
            String id,
            String version,
            YanoApiRange api,
            List<BundleDependency> dependencies,
            List<BundleContribution> contributions,
            String digest
    ) {
        return new IndexedBundle(new BundleManifest(
                1, id, SemVersion.parse(version), api, dependencies, contributions),
                digest, PluginDigestMode.JAR);
    }
}
