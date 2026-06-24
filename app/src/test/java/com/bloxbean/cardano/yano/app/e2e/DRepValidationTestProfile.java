package com.bloxbean.cardano.yano.app.e2e;

import java.util.HashMap;
import java.util.Map;

public class DRepValidationTestProfile extends DevnetTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("yano.validation.supplementary-rules-enabled", "true");
        return overrides;
    }
}
