package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyDefinition;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.bloxbean.cardano.yano.appchain.config.ConfigSourceKind;
import com.bloxbean.cardano.yano.appchain.config.EffectiveConfigValue;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.SysPropConfigSource;
import io.smallrye.config.source.yaml.YamlConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SmallRye-backed source resolver used by resolved validation and effective output. */
final class AppChainResolvedConfigResolver {
    static final int DECLARED_SOURCE_BASE_ORDINAL = 250;
    static final int MAX_DECLARED_SOURCES = 32;

    private static final Pattern INDEXED = Pattern.compile(
            "^yano\\.app-chain\\.chains\\[(\\d+)]\\.(.+)$");
    private static final Pattern PROFILED = Pattern.compile("^%[^.]+\\.(.+)$");
    private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private final AppChainConfigFileLoader yamlValidator;

    AppChainResolvedConfigResolver() {
        this(new AppChainConfigFileLoader());
    }

    AppChainResolvedConfigResolver(AppChainConfigFileLoader yamlValidator) {
        this.yamlValidator = java.util.Objects.requireNonNull(yamlValidator, "yamlValidator");
    }

    ResolvedAppChainConfiguration resolve(
            List<Path> declaredFiles,
            String profile,
            boolean includeEnvironment,
            boolean includeSystemProperties,
            AppChainPropertyRegistry registry) throws IOException {
        if (declaredFiles == null || declaredFiles.isEmpty()) {
            throw new IllegalArgumentException("at least one --config source is required");
        }
        if (declaredFiles.size() > MAX_DECLARED_SOURCES) {
            throw new IllegalArgumentException(
                    "at most " + MAX_DECLARED_SOURCES + " --config sources are supported");
        }
        String activeProfile = profile == null ? "" : profile.trim();
        if (!activeProfile.isEmpty() && !PROFILE_NAME.matcher(activeProfile).matches()) {
            throw new IllegalArgumentException("profile contains unsupported characters");
        }

        List<ConfigSource> sources = new ArrayList<>();
        Map<String, ConfigSourceKind> sourceKinds = new LinkedHashMap<>();
        List<ResolvedConfigSource> summaries = new ArrayList<>();
        for (int index = 0; index < declaredFiles.size(); index++) {
            ConfigSource source = loadSource(declaredFiles.get(index), index);
            sources.add(source);
            sourceKinds.put(source.getName(), ConfigSourceKind.DECLARED_FILE);
            summaries.add(new ResolvedConfigSource(
                    source.getName(), ConfigSourceKind.DECLARED_FILE, source.getOrdinal()));
        }
        if (includeEnvironment) {
            ConfigSource environment = new EnvConfigSource(System.getenv(), EnvConfigSource.ORDINAL);
            sources.add(environment);
            sourceKinds.put(environment.getName(), ConfigSourceKind.ENVIRONMENT);
            summaries.add(new ResolvedConfigSource(environment.getName(),
                    ConfigSourceKind.ENVIRONMENT, environment.getOrdinal()));
        }
        if (includeSystemProperties) {
            ConfigSource system = new SysPropConfigSource();
            sources.add(system);
            sourceKinds.put(system.getName(), ConfigSourceKind.SYSTEM_PROPERTIES);
            summaries.add(new ResolvedConfigSource(system.getName(),
                    ConfigSourceKind.SYSTEM_PROPERTIES, system.getOrdinal()));
        }

        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder()
                .withSources(sources)
                .addDefaultInterceptors();
        if (!activeProfile.isEmpty()) {
            builder.withProfile(activeProfile);
        }
        SmallRyeConfig config = builder.build();
        TreeSet<String> candidates = candidates(config, registry);
        Map<String, EffectiveConfigValue> effective = new TreeMap<>();
        for (String key : candidates) {
            ConfigValue value;
            try {
                value = config.getConfigValue(key);
            } catch (RuntimeException failure) {
                throw new IOException("configuration resolution failed for property " + key, failure);
            }
            if (value.getValue() == null) {
                continue;
            }
            String normalizedValue = normalizeFrameworkValue(config, key, value.getValue(), registry);
            ConfigSourceKind kind = sourceKinds.getOrDefault(
                    value.getConfigSourceName(), ConfigSourceKind.DECLARED_FILE);
            effective.put(key, new EffectiveConfigValue(
                    key, normalizedValue, value.getConfigSourceName(), kind,
                    value.getConfigSourceOrdinal(), true,
                    value.getProfile() == null ? activeProfile : value.getProfile()));
        }
        materializeRuntimeDefaults(effective, registry, activeProfile);
        summaries.sort(java.util.Comparator.comparingInt(ResolvedConfigSource::ordinal).reversed()
                .thenComparing(ResolvedConfigSource::name));
        return new ResolvedAppChainConfiguration(
                effective, summaries, activeProfile, includeEnvironment, includeSystemProperties);
    }

    private static String normalizeFrameworkValue(
            SmallRyeConfig config,
            String key,
            String resolvedValue,
            AppChainPropertyRegistry registry) throws IOException {
        if (INDEXED.matcher(key).matches()) {
            return resolvedValue;
        }
        var match = registry.find(key);
        if (match.isEmpty()
                || !AppChainPropertyRegistry.OWNER_CORE.equals(
                match.orElseThrow().definition().owner())) {
            return resolvedValue;
        }
        try {
            return switch (match.orElseThrow().definition().type()) {
                case BOOLEAN -> Boolean.toString(config.getValue(key, Boolean.class));
                case INTEGER -> Integer.toString(config.getValue(key, Integer.class));
                case LONG -> Long.toString(config.getValue(key, Long.class));
                default -> resolvedValue;
            };
        } catch (RuntimeException failure) {
            throw new IOException("configuration type conversion failed for property " + key,
                    failure);
        }
    }

    private ConfigSource loadSource(Path path, int index) throws IOException {
        requireReadable(path);
        String name = "file[" + index + "]:" + fileName(path);
        String extension = extension(path);
        int defaultOrdinal = DECLARED_SOURCE_BASE_ORDINAL + index;
        if (extension.equals("yml") || extension.equals("yaml")) {
            yamlValidator.load(path);
            String content = Files.readString(path, StandardCharsets.UTF_8);
            int ordinal = declaredOrdinal(content, defaultOrdinal, true);
            return new YamlConfigSource(name, content, ordinal);
        }
        if (extension.equals("properties")) {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            int ordinal = propertyOrdinal(properties.getProperty("config_ordinal"), defaultOrdinal);
            return new PropertiesConfigSource(properties, name, ordinal);
        }
        throw new IOException("configuration sources must use .yml, .yaml, or .properties");
    }

    private static TreeSet<String> candidates(
            SmallRyeConfig config,
            AppChainPropertyRegistry registry) {
        TreeSet<String> result = new TreeSet<>();
        for (AppChainPropertyDefinition definition : registry.definitions()) {
            result.add(definition.key());
        }
        for (String property : config.getPropertyNames()) {
            String normalized = stripProfile(property);
            if (normalized.startsWith(AppChainPropertyRegistry.APP_CHAIN_PREFIX)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static void materializeRuntimeDefaults(
            Map<String, EffectiveConfigValue> values,
            AppChainPropertyRegistry registry,
            String profile) {
        Set<Integer> indices = new TreeSet<>();
        boolean flat = false;
        for (String key : List.copyOf(values.keySet())) {
            Matcher indexed = INDEXED.matcher(key);
            if (indexed.matches()) {
                indices.add(Integer.parseInt(indexed.group(1)));
            } else if (isFlatChainProperty(key)) {
                flat = true;
            }
        }

        EffectiveConfigValue explicitEnabled = values.get("yano.app-chain.enabled");
        if (explicitEnabled == null) {
            boolean enabled = !indices.isEmpty();
            values.put("yano.app-chain.enabled", derived(
                    "yano.app-chain.enabled", Boolean.toString(enabled), profile));
        }
        for (AppChainPropertyDefinition definition : registry.definitions()) {
            if (definition.defaultValue() == null
                    || definition.key().equals("yano.app-chain.enabled")
                    || definition.key().equals("yano.app-chain.chains")) {
                continue;
            }
            if (!definition.indexed()) {
                values.putIfAbsent(definition.key(), runtimeDefault(
                        definition.key(), definition.defaultValue(), profile));
            } else if (!indices.isEmpty()) {
                for (int index : indices) {
                    String key = "yano.app-chain.chains[" + index + "]." + definition.suffix();
                    values.putIfAbsent(key, runtimeDefault(key, definition.defaultValue(), profile));
                }
            } else if (flat) {
                values.putIfAbsent(definition.key(), runtimeDefault(
                        definition.key(), definition.defaultValue(), profile));
            }
        }
    }

    private static EffectiveConfigValue runtimeDefault(String key, String value, String profile) {
        return new EffectiveConfigValue(key, value, "runtime-default",
                ConfigSourceKind.RUNTIME_DEFAULT, Integer.MIN_VALUE, false, profile);
    }

    private static EffectiveConfigValue derived(String key, String value, String profile) {
        return new EffectiveConfigValue(key, value, "runtime-derived",
                ConfigSourceKind.RUNTIME_DERIVED, Integer.MIN_VALUE + 1, false, profile);
    }

    private static boolean isFlatChainProperty(String key) {
        return key.startsWith(AppChainPropertyRegistry.APP_CHAIN_PREFIX)
                && !key.equals("yano.app-chain.enabled")
                && !key.equals("yano.app-chain.api.auth.enabled")
                && !key.equals("yano.app-chain.api.keys")
                && !key.startsWith("yano.app-chain.chains[");
    }

    private static String stripProfile(String property) {
        Matcher matcher = PROFILED.matcher(property);
        return matcher.matches() ? matcher.group(1) : property;
    }

    private static int declaredOrdinal(String content, int defaultOrdinal, boolean yaml) {
        if (!yaml) {
            return defaultOrdinal;
        }
        Pattern pattern = Pattern.compile("(?m)^config_ordinal\\s*:\\s*['\"]?(-?\\d+)['\"]?\\s*$");
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? propertyOrdinal(matcher.group(1), defaultOrdinal) : defaultOrdinal;
    }

    private static int propertyOrdinal(String value, int defaultOrdinal) {
        if (value == null || value.isBlank()) {
            return defaultOrdinal;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("config_ordinal must be a 32-bit integer");
        }
    }

    private static void requireReadable(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("configuration file does not exist or is not a regular file");
        }
        long size = Files.size(path);
        if (size > AppChainConfigFileLoader.MAX_FILE_BYTES) {
            throw new IOException("configuration file exceeds "
                    + AppChainConfigFileLoader.MAX_FILE_BYTES + " bytes");
        }
    }

    private static String extension(Path path) {
        String name = fileName(path).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "<config>" : fileName.toString();
    }
}
