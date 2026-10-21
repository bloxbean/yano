package org.yanoproject.app.e2e;

import java.util.HashMap;
import java.util.Map;

public class DRepValidationTestProfile extends DevnetTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        // The CCL supplementary rules layer on the legacy validator only (ADR-056 §7), so this profile keeps it.
        overrides.put("yano.validation.engine", "scalus");
        overrides.put("yano.validation.supplementary-rules-enabled", "true");
        return overrides;
    }
}
