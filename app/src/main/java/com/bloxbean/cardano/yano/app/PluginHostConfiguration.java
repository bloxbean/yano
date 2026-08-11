package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import org.eclipse.microprofile.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolves the canonical plugin host namespace and its one-release aliases.
 */
record PluginHostConfiguration(
        boolean enabled,
        String directory,
        Set<String> allowList,
        Set<String> denyList,
        boolean autoRegisterAnnotated,
        boolean loggingEnabled
) {
    private static final Logger log = LoggerFactory.getLogger(PluginHostConfiguration.class);

    static PluginHostConfiguration from(Config config) {
        Objects.requireNonNull(config, "config");
        return new PluginHostConfiguration(
                resolveBoolean(config, YanoPropertyKeys.Plugins.ENABLED,
                        YanoPropertyKeys.Plugins.Legacy.ENABLED, true),
                resolve(config, YanoPropertyKeys.Plugins.DIRECTORY,
                        YanoPropertyKeys.Plugins.Legacy.DIRECTORY,
                        Function.identity()).orElse("plugins"),
                Set.copyOf(resolve(config, YanoPropertyKeys.Plugins.ALLOW_LIST,
                        YanoPropertyKeys.Plugins.Legacy.ALLOW_LIST,
                        PluginHostConfiguration::parseList).orElseGet(Set::of)),
                Set.copyOf(resolve(config, YanoPropertyKeys.Plugins.DENY_LIST,
                        YanoPropertyKeys.Plugins.Legacy.DENY_LIST,
                        PluginHostConfiguration::parseList).orElseGet(Set::of)),
                resolveBoolean(config, YanoPropertyKeys.Plugins.AUTO_REGISTER_ANNOTATED,
                        YanoPropertyKeys.Plugins.Legacy.AUTO_REGISTER_ANNOTATED, false),
                resolveBoolean(config, YanoPropertyKeys.Plugins.LOGGING_ENABLED,
                        YanoPropertyKeys.Plugins.Legacy.LOGGING_ENABLED, false));
    }

    private static boolean resolveBoolean(
            Config config,
            String canonicalKey,
            String legacyKey,
            boolean defaultValue
    ) {
        return resolve(config, canonicalKey, legacyKey,
                value -> parseBoolean(canonicalKey, value)).orElse(defaultValue);
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
            String canonicalKey,
            String legacyKey,
            Function<String, T> parser
    ) {
        Optional<String> canonical = config.getOptionalValue(canonicalKey, String.class);
        Optional<String> legacy = config.getOptionalValue(legacyKey, String.class);
        Optional<T> canonicalValue = canonical.map(parser);
        Optional<T> legacyValue = legacy.map(parser);
        if (canonicalValue.isPresent() && legacyValue.isPresent()
                && !canonicalValue.get().equals(legacyValue.get())) {
            throw PluginConfigurationException.conflictingAliases(canonicalKey, legacyKey);
        }
        if (legacyValue.isPresent()) {
            log.warn("Plugin configuration key {} is deprecated; use {}",
                    legacyKey, canonicalKey);
        }
        return canonicalValue.isPresent() ? canonicalValue : legacyValue;
    }
}
