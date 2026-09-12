package org.yanoproject.runtime.plugins;

import org.yanoproject.api.plugin.PluginDigestMode;
import org.yanoproject.api.plugin.PluginSourceCategory;
import org.yanoproject.catalog.BundleContribution;
import org.yanoproject.catalog.BundleManifest;
import org.yanoproject.catalog.ContributionKind;
import org.yanoproject.catalog.IndexedBundle;
import org.yanoproject.catalog.IndexedLegacyProvider;
import org.yanoproject.catalog.PluginIndex;
import org.yanoproject.catalog.SemVersion;
import org.yanoproject.catalog.YanoApiRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginCatalogProviderBudgetTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @Test
    void runtimeUsesSharedLimitAcrossManifestedAndLegacyCatalogInputs() {
        assertThat(PluginCatalogBuilder.MAX_DISCOVERED_PROVIDERS)
                .isEqualTo(PluginIndex.MAX_PROVIDERS);

        List<PluginCatalogBuilder.CatalogInput> exact = List.of(
                input(manifested("com.example.first", "first", "com.example.FirstProvider")),
                input(legacy("com.example.LegacyProvider")));
        assertThatCode(() -> PluginCatalogBuilder.validateProviderBudget(exact, 2))
                .doesNotThrowAnyException();

        List<PluginCatalogBuilder.CatalogInput> over = List.of(
                exact.get(0),
                exact.get(1),
                input(manifested("com.example.third", "third", "com.example.ThirdProvider")));
        assertThatThrownBy(() -> PluginCatalogBuilder.validateProviderBudget(over, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Plugin catalog provider evidence exceeds the global limit of 2 "
                        + "across manifested contributions and legacy providers");
    }

    private static PluginCatalogBuilder.CatalogInput input(PluginIndex index) {
        return new PluginCatalogBuilder.CatalogInput(index, PluginSourceCategory.CLASSPATH);
    }

    private static PluginIndex manifested(String id, String name, String provider) {
        BundleManifest manifest = new BundleManifest(
                BundleManifest.CURRENT_SCHEMA_VERSION,
                id,
                SemVersion.parse("1.0.0"),
                new YanoApiRange(
                        PluginCatalogBuilder.PLUGIN_API_MAJOR,
                        PluginCatalogBuilder.PLUGIN_API_MAJOR,
                        PluginCatalogBuilder.PLUGIN_API_LEVEL),
                List.of(),
                List.of(new BundleContribution(
                        ContributionKind.FINALIZED_SINK, name, provider)));
        return new PluginIndex(
                PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(new IndexedBundle(manifest, DIGEST, PluginDigestMode.JAR)),
                List.of());
    }

    private static PluginIndex legacy(String provider) {
        return new PluginIndex(
                PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(),
                List.of(new IndexedLegacyProvider(
                        ContributionKind.SIGNER_PROVIDER,
                        provider,
                        DIGEST,
                        PluginDigestMode.JAR)));
    }
}
