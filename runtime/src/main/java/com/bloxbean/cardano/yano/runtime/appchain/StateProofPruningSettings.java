package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;

import java.util.Map;

/** Node-local, restart-required proof-retention policy. It never enters consensus identity. */
record StateProofPruningSettings(boolean enabled, long retainHeights, long intervalSeconds) {
    static final String ENABLED = "state.proof-pruning.enabled";
    static final String RETAIN_HEIGHTS = "state.proof-pruning.retain-heights";
    static final String INTERVAL_SECONDS = "state.proof-pruning.interval-seconds";
    static final long DEFAULT_RETAIN_HEIGHTS = 10_000;
    static final long DEFAULT_INTERVAL_SECONDS = 3_600;

    static StateProofPruningSettings from(AppChainConfig config) {
        Map<String, String> settings = config.pluginSettings();
        boolean enabled = Boolean.parseBoolean(settings.getOrDefault(ENABLED, "false"));
        long retainHeights = parsePositive(settings, RETAIN_HEIGHTS,
                DEFAULT_RETAIN_HEIGHTS);
        long intervalSeconds = parsePositive(settings, INTERVAL_SECONDS,
                DEFAULT_INTERVAL_SECONDS);
        if (enabled && !StateCommitmentProfiles.MPF.id().equals(
                StateCommitmentIdentity.fromSettings(settings).profile().id())) {
            throw new IllegalArgumentException(
                    "state.proof-pruning.enabled=true is supported only by mpf-blake2b256-v1");
        }
        return new StateProofPruningSettings(enabled, retainHeights, intervalSeconds);
    }

    long retainFrom(long tipHeight) {
        if (tipHeight <= 0) {
            return 0;
        }
        return Math.max(1, tipHeight - retainHeights + 1);
    }

    private static long parsePositive(Map<String, String> settings, String key,
                                      long defaultValue) {
        String configured = settings.get(key);
        long value;
        try {
            value = configured == null || configured.isBlank()
                    ? defaultValue : Long.parseLong(configured.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " must be a positive integer", failure);
        }
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
        return value;
    }
}
