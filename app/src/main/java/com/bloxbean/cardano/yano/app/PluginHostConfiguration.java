package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import org.eclipse.microprofile.config.Config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolves the canonical plugin host namespace.
 */
record PluginHostConfiguration(
        boolean enabled,
        String directory,
        Set<String> allowList,
        Set<String> denyList,
        boolean autoRegisterAnnotated,
        boolean loggingEnabled
) {
    static PluginHostConfiguration from(Config config) {
        Objects.requireNonNull(config, "config");
        return new PluginHostConfiguration(
                resolveBoolean(config, YanoPropertyKeys.Plugins.ENABLED, true),
                resolve(config, YanoPropertyKeys.Plugins.DIRECTORY,
                        Function.identity()).orElse("plugins"),
                Set.copyOf(resolve(config, YanoPropertyKeys.Plugins.ALLOW_LIST,
                        PluginHostConfiguration::parseList).orElseGet(Set::of)),
                Set.copyOf(resolve(config, YanoPropertyKeys.Plugins.DENY_LIST,
                        PluginHostConfiguration::parseList).orElseGet(Set::of)),
                resolveBoolean(config, YanoPropertyKeys.Plugins.AUTO_REGISTER_ANNOTATED,
                        false),
                resolveBoolean(config, YanoPropertyKeys.Plugins.LOGGING_ENABLED, false));
    }

    private static boolean resolveBoolean(
            Config config,
            String key,
            boolean defaultValue
    ) {
        return resolve(config, key,
                value -> parseBoolean(key, value)).orElse(defaultValue);
    }

    private static boolean parseBoolean(String key, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw PluginConfigurationException.invalidBoolean(key);
    }

    private static Set<String> parseList(String value) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .forEach(values::add);
        return values;
    }

    private static <T> Optional<T> resolve(
            Config config,
            String key,
            Function<String, T> parser
    ) {
        return config.getOptionalValue(key, String.class).map(parser);
    }
}
