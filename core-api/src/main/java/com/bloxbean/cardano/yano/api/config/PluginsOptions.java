package com.bloxbean.cardano.yano.api.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Existing NodePlugin discovery policy and compatibility configuration. */
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
                : Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }

    public static PluginsOptions defaults() {
        return new PluginsOptions(true, false, Set.of(), Set.of(), Map.of());
    }

    /** Never include plugin configuration values (which may be credentials) in logs. */
    @Override
    public String toString() {
        return "PluginsOptions[enabled=" + enabled
                + ", autoRegisterAnnotated=" + autoRegisterAnnotated
                + ", allowList=" + allowList
                + ", denyList=" + denyList
                + ", configKeys=" + config.keySet() + "]";
    }
}
