package com.bloxbean.cardano.yano.api.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Existing NodePlugin discovery policy and compatibility configuration.
 *
 * <p>The configuration graph is snapshotted recursively at construction.
 * Preview v1 accepts only the bounded JSON-like values documented by
 * {@link PluginConfigValues}; unsupported mutable values are rejected.</p>
 */
public record PluginsOptions(
        boolean enabled,
        boolean autoRegisterAnnotated,
        Set<String> allowList,
        Set<String> denyList,
        Map<String, Object> config
) {
    public PluginsOptions {
        allowList = allowList == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(allowList));
        denyList = denyList == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(denyList));
        config = config == null ? Map.of()
                : PluginConfigValues.immutableCopy(config);
    }

    public static PluginsOptions defaults() {
        return new PluginsOptions(true, false, Set.of(), Set.of(), Map.of());
    }

    /** Never include plugin identifiers, configuration keys, or values in logs. */
    @Override
    public String toString() {
        return "PluginsOptions[enabled=" + enabled
                + ", autoRegisterAnnotated=" + autoRegisterAnnotated
                + ", allowListEntries=" + allowList.size()
                + ", denyListEntries=" + denyList.size()
                + ", configEntries=" + config.size() + "]";
    }
}
