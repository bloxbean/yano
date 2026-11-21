package org.yanoproject.app;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.api.plugin.PluginActivationException;
import org.yanoproject.runtime.plugins.PluginCatalogActivationException;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I8-N2: a required embedded bundle whose host assembly step throws fails startup exactly like a failed
 * NodePlugin.init: PLUGIN_ACTIVATION_FAILED, a sanitised diagnostic, exit code 1 and no retry.
 */
@QuarkusMainTest
@TestProfile(FailingAssemblyTest.FailingAssemblyProfile.class)
class FailingAssemblyTest {

    public static class FailingAssemblyProfile extends NoAutoStartTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put(YanoPropertyKeys.Plugins.ENABLED, "false");
            overrides.put(YanoPropertyKeys.Plugins.REQUIRED, FailingAssemblyFixture.ID);
            return overrides;
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(FailingAssemblyFixture.LoaderProducer.class);
        }
    }

    @Test
    @Launch(value = {}, exitCode = 1)
    void assemblyFailureOfARequiredEmbeddedBundleStopsTheProcessWithASanitisedDiagnostic(LaunchResult result) {
        String output = result.getOutput() + result.getErrorOutput();
        assertThat(output)
                .contains("YANO_STARTUP_FAILURE code=PLUGIN_ACTIVATION_FAILED")
                // The sanitised boundary names the failure type: an activation failure, not a catalog failure.
                .contains(PluginActivationException.class.getName())
                .doesNotContain(PluginCatalogActivationException.class.getSimpleName())
                .doesNotContain("RUNTIME_INITIALIZATION_FAILED")
                .doesNotContain(FailingAssemblyFixture.SECRET);
        assertThat(output.split("Creating Yano", -1)).as("Yano is assembled once, never retried").hasSize(2);
    }
}
