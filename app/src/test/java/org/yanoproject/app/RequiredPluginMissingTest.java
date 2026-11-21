package org.yanoproject.app;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.config.YanoPropertyKeys;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-064 D2: a bundle listed in yano.plugins.required that is not on the host class path aborts startup. */
@QuarkusMainTest
@TestProfile(RequiredPluginMissingTest.MissingProfile.class)
class RequiredPluginMissingTest {

    static final String MISSING_BUNDLE = "org.example.missing-required";

    public static class MissingProfile extends NoAutoStartTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put(YanoPropertyKeys.Plugins.ENABLED, "false");
            overrides.put(YanoPropertyKeys.Plugins.REQUIRED, MISSING_BUNDLE);
            return overrides;
        }
    }

    @Test
    @Launch(value = {}, exitCode = 1)
    void missingRequiredBundleFailsStartup(LaunchResult result) {
        assertThat(result.getOutput() + result.getErrorOutput())
                .contains("YANO_STARTUP_FAILURE code=PLUGIN_ACTIVATION_FAILED");
    }
}
