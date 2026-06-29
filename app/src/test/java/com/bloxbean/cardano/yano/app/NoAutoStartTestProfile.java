package com.bloxbean.cardano.yano.app;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps HTTP resource tests isolated from local operator configs in app/config.
 */
public class NoAutoStartTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("yano.api-prefix", "/api/v1");
        overrides.put("yano.auto-sync-start", "false");
        overrides.put("yano.client.enabled", "false");
        overrides.put("yano.server.enabled", "true");
        overrides.put("yano.storage.rocksdb", "false");
        overrides.put("yano.account-state.enabled", "false");
        overrides.put("yano.epoch-snapshot.amounts-enabled", "false");
        overrides.put("yano.adapot.enabled", "false");
        overrides.put("yano.rewards.enabled", "false");
        overrides.put("yano.epoch-params.tracking-enabled", "false");
        overrides.put("yano.governance.enabled", "false");
        overrides.put("yano.upstream.mode", "trusted-single");
        overrides.put("yano.upstream.discovery.enabled", "false");
        return overrides;
    }
}
