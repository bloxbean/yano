package com.bloxbean.cardano.yano.appchain.config;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runtime-parser-backed validation for a fully resolved node configuration. */
public final class AppChainResolvedValidator {
    private static final Pattern INDEXED = Pattern.compile(
            "^yano\\.app-chain\\.chains\\[(\\d+)]\\.(.+)$");

    private final AppChainPropertyRegistry registry;
    private final List<AppChainSemanticValidator> extensions;

    public AppChainResolvedValidator() {
        this(AppChainPropertyRegistry.framework(), List.of());
    }

    public AppChainResolvedValidator(
            AppChainPropertyRegistry registry,
            List<AppChainSemanticValidator> extensions) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.extensions = extensions == null ? List.of() : List.copyOf(extensions);
        var ids = new java.util.HashSet<String>();
        for (AppChainSemanticValidator extension : this.extensions) {
            if (extension == null || extension.id() == null || extension.id().isBlank()
                    || !ids.add(extension.id())) {
                throw new IllegalArgumentException("semantic validator ids must be unique");
            }
        }
    }

    public ResolvedValidationResult validate(Map<String, EffectiveConfigValue> effectiveValues) {
        if (effectiveValues == null) {
            throw new IllegalArgumentException("effectiveValues must not be null");
        }
        List<ValidationDiagnostic> diagnostics = new ArrayList<>();
        Map<String, String> values = new TreeMap<>();
        effectiveValues.forEach((key, value) -> values.put(key, value.value()));

        int propertyCount = 0;
        int recognized = 0;
        boolean flat = false;
        boolean indexed = false;
        TreeSet<Integer> indices = new TreeSet<>();
        Map<Integer, Map<String, String>> indexedSettings = new TreeMap<>();
        Map<String, String> flatSettings = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(AppChainPropertyRegistry.APP_CHAIN_PREFIX)) {
                continue;
            }
            propertyCount++;
            Matcher matcher = INDEXED.matcher(key);
            if (matcher.matches()) {
                indexed = true;
                int index = parseIndex(matcher.group(1), key, diagnostics);
                if (index >= 0) {
                    indices.add(index);
                    indexedSettings.computeIfAbsent(index, ignored -> new LinkedHashMap<>())
                            .put(matcher.group(2), entry.getValue());
                }
            } else if (isChainSpecific(key)) {
                flat = true;
                flatSettings.put(key.substring(AppChainPropertyRegistry.APP_CHAIN_PREFIX.length()),
                        entry.getValue());
            }

            if (registry.find(key).isPresent()) {
                recognized++;
            } else if (registry.dynamicNamespace(key).isPresent()) {
                recognized++;
                DynamicNamespaceDefinition namespace = registry.dynamicNamespace(key).orElseThrow();
                if (namespace.coverage() == ValidationCoverage.FULL) {
                    diagnostics.add(error("DX_CONFIG_UNKNOWN_PROPERTY", key,
                            "Unknown property in fully covered namespace owned by "
                                    + namespace.owner()));
                } else {
                    diagnostics.add(warning("DX_CONFIG_PARTIAL_NAMESPACE", key,
                            "Namespace is owned by " + namespace.owner() + " with "
                                    + namespace.coverage() + " validation coverage"));
                }
            } else {
                String suggestion = registry.nearestKey(key)
                        .map(candidate -> "; did you mean '" + candidate + "'?")
                        .orElse("");
                diagnostics.add(error("DX_CONFIG_UNKNOWN_PROPERTY", key,
                        "Unknown app-chain property" + suggestion));
            }
        }

        if (flat && indexed) {
            diagnostics.add(error("DX_CONFIG_MIXED_CHAIN_FORMS", "yano.app-chain",
                    "Flat single-chain properties and chains[i] properties cannot be combined"));
        }
        validateIndices(indices, indexedSettings, diagnostics);

        int chainCount = 0;
        if (indexed && !flat) {
            for (Map.Entry<Integer, Map<String, String>> chain : indexedSettings.entrySet()) {
                validateChain("yano.app-chain.chains[" + chain.getKey() + "]",
                        chain.getValue(), diagnostics);
                chainCount++;
            }
        } else if (flat) {
            validateChain("yano.app-chain", flatSettings, diagnostics);
            chainCount = 1;
        }

        boolean enabled = Boolean.parseBoolean(values.getOrDefault(
                "yano.app-chain.enabled", indexed ? "true" : "false"));
        if (enabled && chainCount == 0) {
            diagnostics.add(error("DX_CONFIG_NO_CHAIN", "yano.app-chain",
                    "App-chain is enabled but no chain configuration is present"));
        } else if (!enabled && chainCount > 0) {
            diagnostics.add(info("DX_CONFIG_APPCHAIN_DISABLED", "yano.app-chain.enabled",
                    "Configuration is valid but this node will not start the declared app-chain"));
        }

        diagnostics.sort(Comparator.comparing(ValidationDiagnostic::severity)
                .thenComparing(ValidationDiagnostic::key)
                .thenComparing(ValidationDiagnostic::code));
        return new ResolvedValidationResult(propertyCount, recognized, chainCount, diagnostics);
    }

    private void validateChain(
            String path,
            Map<String, String> settings,
            List<ValidationDiagnostic> diagnostics) {
        try {
            AppChainConfig config = AppChainConfigParser.parse(settings);
            AppChainConfigSemantics.validate(config);
            AppChainEffectsConfig.from(config).consensusProfile(config);
            AppChainApprovalsConfig.fromSettings(settings);
            AppChainValidationContext context = new AppChainValidationContext(path, config, settings);
            for (AppChainSemanticValidator extension : extensions) {
                List<ValidationDiagnostic> extensionDiagnostics = extension.validate(context);
                if (extensionDiagnostics != null) {
                    diagnostics.addAll(extensionDiagnostics);
                }
            }
        } catch (RuntimeException failure) {
            diagnostics.add(error("DX_CONFIG_RUNTIME_SEMANTICS", path,
                    safeFailure(failure)));
        }
    }

    private boolean isChainSpecific(String key) {
        return registry.find(key)
                .map(match -> match.definition().indexed())
                .orElse(true);
    }

    private static int parseIndex(
            String value,
            String key,
            List<ValidationDiagnostic> diagnostics) {
        try {
            int index = Integer.parseInt(value);
            if (index >= 0 && index < 50) {
                return index;
            }
        } catch (NumberFormatException ignored) {
            // Stable diagnostic below.
        }
        diagnostics.add(error("DX_CONFIG_CHAIN_INDEX_RANGE", key,
                "Chain index must be between 0 and 49"));
        return -1;
    }

    private static void validateIndices(
            TreeSet<Integer> indices,
            Map<Integer, Map<String, String>> settings,
            List<ValidationDiagnostic> diagnostics) {
        int expected = 0;
        for (int index : indices) {
            if (index != expected) {
                diagnostics.add(error("DX_CONFIG_NON_CONTIGUOUS_CHAINS",
                        "yano.app-chain.chains", "Chain indices must start at 0 and be contiguous"));
                break;
            }
            if (!settings.get(index).containsKey("chain-id")) {
                diagnostics.add(error("DX_CONFIG_MISSING_CHAIN_ID",
                        "yano.app-chain.chains[" + index + "].chain-id",
                        "Every configured chain requires chain-id"));
            }
            expected++;
        }
    }

    private static String safeFailure(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "Runtime semantic validation failed";
        }
        int newline = message.indexOf('\n');
        String firstLine = newline < 0 ? message : message.substring(0, newline);
        return firstLine.length() <= 512 ? firstLine : firstLine.substring(0, 512);
    }

    private static ValidationDiagnostic error(String code, String key, String message) {
        return new ValidationDiagnostic(code, ValidationSeverity.ERROR, key, message);
    }

    private static ValidationDiagnostic warning(String code, String key, String message) {
        return new ValidationDiagnostic(code, ValidationSeverity.WARNING, key, message);
    }

    private static ValidationDiagnostic info(String code, String key, String message) {
        return new ValidationDiagnostic(code, ValidationSeverity.INFO, key, message);
    }
}
