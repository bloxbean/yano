package com.bloxbean.cardano.yano.app;

/**
 * Secret-safe startup failure for incompatible plugin host configuration.
 */
public final class PluginConfigurationException extends IllegalStateException {

    private PluginConfigurationException(String message) {
        super(message);
    }

    static PluginConfigurationException conflictingAliases(
            String canonicalKey,
            String legacyKey
    ) {
        return new PluginConfigurationException(
                "Conflicting plugin configuration aliases: " + canonicalKey
                        + " and deprecated " + legacyKey);
    }

    static PluginConfigurationException invalidBoolean(String key) {
        return new PluginConfigurationException(
                "Plugin configuration must be true or false: " + key);
    }
}
