package com.bloxbean.cardano.yano.api.config;

import java.util.Locale;

/**
 * User-facing upstream behavior preset.
 */
public enum UpstreamPreset {
    TRUSTED_SINGLE("trusted-single"),
    TRUSTED_FAILOVER("trusted-failover"),
    STATIC_MULTI("static-multi"),
    ROOTED_RELAY("rooted-relay"),
    P2P_RELAY("p2p-relay");

    private final String configValue;

    UpstreamPreset(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public boolean singleActive() {
        return this == TRUSTED_SINGLE || this == TRUSTED_FAILOVER;
    }

    public boolean multiPeer() {
        return !singleActive();
    }

    public static UpstreamPreset fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return TRUSTED_SINGLE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (UpstreamPreset preset : values()) {
            if (preset.configValue.equals(normalized) || preset.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return preset;
            }
        }
        throw new IllegalArgumentException("Unsupported upstream preset: " + value);
    }
}
