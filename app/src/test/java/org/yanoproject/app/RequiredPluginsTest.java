package org.yanoproject.app;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.api.plugin.PluginBundleInfo;
import org.yanoproject.api.plugin.PluginCatalogView;
import org.yanoproject.api.plugin.PluginSelectionStatus;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-064 D2: a bundle in yano.plugins.required activates although optional plugins are disabled. */
@QuarkusTest
@TestProfile(RequiredPluginsTest.RequiredProfile.class)
class RequiredPluginsTest {

    static final String LOGGING_BUNDLE = "org.yanoproject.plugins.logging";

    public static class RequiredProfile extends NoAutoStartTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put(YanoPropertyKeys.Plugins.ENABLED, "false");
            overrides.put(YanoPropertyKeys.Plugins.REQUIRED, LOGGING_BUNDLE);
            return overrides;
        }
    }

    @Inject
    PluginCatalogView catalog;

    @Test
    void requiredEmbeddedBundleIsSelectedWithOptionalPluginsDisabled() {
        assertThat(catalog.selectedBundleOrder()).containsExactly(LOGGING_BUNDLE);
        assertThat(catalog.bundles())
                .filteredOn(bundle -> bundle.id().equals(LOGGING_BUNDLE))
                .extracting(PluginBundleInfo::selectionStatus)
                .containsExactly(PluginSelectionStatus.SELECTED);
    }
}
