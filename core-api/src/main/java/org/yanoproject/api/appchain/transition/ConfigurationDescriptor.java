package org.yanoproject.api.appchain.transition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Accepted component-local scalar settings, normalized before inclusion in the committed profile.
 * A setting without a default is required. Unknown settings fail closed instead of silently escaping the
 * profile commitment. Descriptors must be stable for the selected machine implementation/version.
 */
public record ConfigurationDescriptor(List<Setting> settings) {
    /** One typed setting; a {@code null} default means required, not a nullable scalar value. */
    public record Setting(String name, TransitionScalars.Type type, Object defaultValue) {
        public Setting {
            CommandDescriptor.requireName(name);
            Objects.requireNonNull(type, "type");
            if (defaultValue != null && !type.accepts(defaultValue)) {
                throw new IllegalArgumentException("configuration default has incorrect type");
            }
            if (defaultValue instanceof byte[] bytes) defaultValue = bytes.clone();
        }
        @Override public Object defaultValue() {
            return defaultValue instanceof byte[] bytes ? bytes.clone() : defaultValue;
        }
    }

    public ConfigurationDescriptor {
        settings = List.copyOf(settings);
        if (settings.size() > TransitionScalars.MAX_FIELDS
                || settings.stream().map(Setting::name).distinct().count() != settings.size()) {
            throw new IllegalArgumentException("invalid configuration settings");
        }
    }

    public static ConfigurationDescriptor empty() { return new ConfigurationDescriptor(List.of()); }

    /**
     * Fills declared defaults and verifies exact scalar types without coercion or environment lookups.
     * The returned map is unmodifiable and byte arrays are copied from inputs/defaults. Consumers should
     * encode or defensively copy its byte-array values before retaining a normalized configuration.
     *
     * @param supplied component-local settings, with defaults optionally omitted
     * @return all declared settings in descriptor order
     * @throws IllegalArgumentException for unknown, missing required, or incorrectly typed settings
     */
    public Map<String, Object> normalize(Map<String, ?> supplied) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Setting setting : settings) {
            Object value = supplied.containsKey(setting.name())
                    ? supplied.get(setting.name()) : setting.defaultValue();
            if (!setting.type().accepts(value)) {
                throw new IllegalArgumentException("missing or invalid setting: " + setting.name());
            }
            result.put(setting.name(), value instanceof byte[] bytes ? bytes.clone() : value);
        }
        if (!result.keySet().containsAll(supplied.keySet())) {
            throw new IllegalArgumentException("unknown component setting");
        }
        return Collections.unmodifiableMap(result);
    }
}
