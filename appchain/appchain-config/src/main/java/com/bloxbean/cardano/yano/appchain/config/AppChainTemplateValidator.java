package com.bloxbean.cardano.yano.appchain.config;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** M0a structural validator for flattened app-chain YAML/properties templates. */
public final class AppChainTemplateValidator {
    static final int MAX_CHAIN_COUNT = 50;
    private static final Pattern PROFILE_PREFIX = Pattern.compile("^%[^.]+\\.");
    private static final Pattern INDEXED_PROPERTY = Pattern.compile(
            "^yano\\.app-chain\\.chains\\[(\\d+)]\\.(.+)$");
    private static final Pattern CONTRACT_INDEXED_PROPERTY = Pattern.compile(
            "^yano\\.app-chain\\.chains\\[\\*]\\.(.+)$");

    private final AppChainPropertyRegistry registry;

    public AppChainTemplateValidator() {
        this(AppChainPropertyRegistry.framework());
    }

    public AppChainTemplateValidator(AppChainPropertyRegistry registry) {
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
    }

    /** Validate flattened source values without pretending the template is startable. */
    public TemplateValidationResult validate(Map<String, ?> flattenedValues) {
        return validate(flattenedValues, null);
    }

    /** Validate a source using an optional, value-free launcher/deployment contract. */
    public TemplateValidationResult validate(
            Map<String, ?> flattenedValues,
            TemplateContract templateContract) {
        if (flattenedValues == null) {
            throw new IllegalArgumentException("flattenedValues must not be null");
        }

        List<ValidationDiagnostic> diagnostics = new ArrayList<>();
        Set<Integer> chainIndices = new TreeSet<>();
        Set<Integer> chainIdIndices = new TreeSet<>();
        int appChainProperties = 0;
        int recognized = 0;
        boolean sawFlatChainProperty = false;
        boolean sawIndexedChainProperty = false;

        for (Map.Entry<String, ?> entry : flattenedValues.entrySet()) {
            String sourceKey = stripProfile(entry.getKey());
            if (!sourceKey.startsWith(AppChainPropertyRegistry.APP_CHAIN_PREFIX)) {
                continue;
            }
            appChainProperties++;
            Matcher indexedPath = INDEXED_PROPERTY.matcher(sourceKey);
            if (indexedPath.matches()) {
                sawIndexedChainProperty = true;
                Integer index = chainIndex(indexedPath.group(1));
                if (index == null || index >= MAX_CHAIN_COUNT) {
                    diagnostics.add(error("DX_CONFIG_CHAIN_INDEX_RANGE", sourceKey,
                            "Chain index must be between 0 and " + (MAX_CHAIN_COUNT - 1)
                                    + " to match the runtime adapter"));
                    continue;
                }
                chainIndices.add(index);
                if ("chain-id".equals(indexedPath.group(2))) {
                    chainIdIndices.add(index);
                }
            }

            if (!indexedPath.matches() && sourceKey.startsWith("yano.app-chain.chains")
                    && !sourceKey.equals("yano.app-chain.chains")) {
                diagnostics.add(error("DX_CONFIG_MALFORMED_INDEXED_PATH", sourceKey,
                        "Indexed chain properties must use chains[<0-49>].<property>"));
                continue;
            }

            var match = registry.find(sourceKey);
            if (match.isPresent()) {
                recognized++;
                AppChainPropertyRegistry.PropertyMatch propertyMatch = match.orElseThrow();
                if (!propertyMatch.indexed()
                        && propertyMatch.definition().indexed()
                        && !sourceKey.equals("yano.app-chain.enabled")) {
                    sawFlatChainProperty = true;
                }
                if (propertyMatch.indexed() && !propertyMatch.definition().indexed()) {
                    diagnostics.add(error("DX_CONFIG_NOT_INDEXED", sourceKey,
                            "Property is global-only and cannot appear below chains[i]"));
                    continue;
                }
                if (propertyMatch.definition().secret()
                        && !isSecretReference(entry.getValue())) {
                    diagnostics.add(error("DX_CONFIG_SECRET_IN_TEMPLATE", sourceKey,
                            "Shared templates must not contain secret values; supply this "
                                    + "property through a private overlay or secret provider"));
                    continue;
                }
                validateValue(sourceKey, entry.getValue(), propertyMatch.definition(), diagnostics);
                continue;
            }

            var namespace = registry.dynamicNamespace(sourceKey);
            if (namespace.isPresent()) {
                recognized++;
                if (!indexedPath.matches()) {
                    sawFlatChainProperty = true;
                }
                DynamicNamespaceDefinition owner = namespace.orElseThrow();
                diagnostics.add(warning("DX_CONFIG_PARTIAL_NAMESPACE", sourceKey,
                        "Namespace is owned by " + owner.owner() + " with "
                                + owner.coverage() + " metadata coverage; exact suffix validation "
                                + "is not available in M0a"));
                continue;
            }

            String suggestion = registry.nearestKey(sourceKey)
                    .map(key -> "; did you mean '" + key + "'?")
                    .orElse("");
            diagnostics.add(error("DX_CONFIG_UNKNOWN_PROPERTY", sourceKey,
                    "Unknown app-chain property" + suggestion));
        }

        validateChainIndices(chainIndices, chainIdIndices, diagnostics);
        if (sawFlatChainProperty && sawIndexedChainProperty) {
            diagnostics.add(error("DX_CONFIG_MIXED_CHAIN_FORMS", "yano.app-chain",
                    "Flat single-chain properties and chains[i] properties cannot be mixed "
                            + "in one template"));
        }
        validateCrossFields(flattenedValues, chainIndices, sawFlatChainProperty, diagnostics);
        validateTemplateContract(flattenedValues, chainIndices, templateContract, diagnostics);
        if (recognized > 0) {
            diagnostics.add(warning("DX_CONFIG_PARTIAL_COVERAGE", "yano.app-chain",
                    "M0a registry coverage is PARTIAL; complete runtime semantic parity "
                            + "arrives with resolved-mode validation"));
            if (templateContract == null) {
                diagnostics.add(warning("UNRESOLVED_NO_TEMPLATE_CONTRACT", "yano.app-chain",
                        "No template contract was supplied; completeness and injected values "
                                + "cannot be proven"));
            }
        } else if (appChainProperties == 0) {
            diagnostics.add(warning("DX_CONFIG_NO_APPCHAIN_PROPERTIES", "",
                    "No yano.app-chain properties were found"));
        }

        diagnostics.sort(java.util.Comparator
                .comparing(ValidationDiagnostic::severity)
                .thenComparing(ValidationDiagnostic::key)
                .thenComparing(ValidationDiagnostic::code));
        return new TemplateValidationResult(appChainProperties, recognized, diagnostics);
    }

    private void validateTemplateContract(
            Map<String, ?> values,
            Set<Integer> chainIndices,
            TemplateContract contract,
            List<ValidationDiagnostic> diagnostics) {
        if (contract == null) {
            return;
        }
        for (TemplateContractRequirement requirement : contract.suppliedProperties()) {
            Matcher wildcard = CONTRACT_INDEXED_PROPERTY.matcher(requirement.propertyPattern());
            if (wildcard.matches()) {
                String canonical = AppChainPropertyRegistry.APP_CHAIN_PREFIX + wildcard.group(1);
                var definition = registry.find(canonical);
                if (definition.isEmpty() && registry.dynamicNamespace(canonical).isEmpty()) {
                    diagnostics.add(error("DX_TEMPLATE_CONTRACT_UNKNOWN_PROPERTY",
                            requirement.propertyPattern(), "Template contract '" + contract.id()
                                    + "' references an unknown property pattern"));
                    continue;
                }
                if (definition.isPresent() && !definition.orElseThrow().definition().indexed()) {
                    diagnostics.add(error("DX_TEMPLATE_CONTRACT_NOT_INDEXED",
                            requirement.propertyPattern(), "Template contract '" + contract.id()
                                    + "' indexes a global-only property"));
                    continue;
                }
                if (requirement.requiredBeforeStartup()) {
                    for (int index : chainIndices) {
                        String concrete = "yano.app-chain.chains[" + index + "]."
                                + wildcard.group(1);
                        if (!containsNormalizedKey(values, concrete)) {
                            diagnostics.add(info("UNRESOLVED_TEMPLATE_OVERLAY", concrete,
                                    "Supplied before startup by " + requirement.suppliedBy()
                                            + " under template contract '" + contract.id() + "'"));
                        }
                    }
                }
                continue;
            }

            var definition = registry.find(requirement.propertyPattern());
            if (definition.isEmpty()
                    && registry.dynamicNamespace(requirement.propertyPattern()).isEmpty()) {
                diagnostics.add(error("DX_TEMPLATE_CONTRACT_UNKNOWN_PROPERTY",
                        requirement.propertyPattern(), "Template contract '" + contract.id()
                                + "' references an unknown property"));
            } else if (requirement.requiredBeforeStartup()
                    && !containsNormalizedKey(values, requirement.propertyPattern())) {
                diagnostics.add(info("UNRESOLVED_TEMPLATE_OVERLAY",
                        requirement.propertyPattern(), "Supplied before startup by "
                                + requirement.suppliedBy() + " under template contract '"
                                + contract.id() + "'"));
            }
        }
    }

    private static boolean containsNormalizedKey(Map<String, ?> values, String key) {
        return values.keySet().stream().map(AppChainTemplateValidator::stripProfile)
                .anyMatch(key::equals);
    }

    private static void validateChainIndices(
            Set<Integer> indices,
            Set<Integer> chainIdIndices,
            List<ValidationDiagnostic> diagnostics) {
        if (indices.isEmpty()) {
            return;
        }
        int expected = 0;
        for (int index : indices) {
            if (index != expected) {
                diagnostics.add(error("DX_CONFIG_NON_CONTIGUOUS_CHAINS", "yano.app-chain.chains",
                        "Chain indices must start at 0 and be contiguous; expected chains["
                                + expected + "] but found chains[" + index + "]"));
                break;
            }
            expected++;
        }
        for (int index : indices) {
            if (!chainIdIndices.contains(index)) {
                diagnostics.add(error("DX_CONFIG_MISSING_CHAIN_ID",
                        "yano.app-chain.chains[" + index + "].chain-id",
                        "Every configured chain index requires chain-id"));
            }
        }
    }

    private static void validateValue(
            String key,
            Object value,
            AppChainPropertyDefinition definition,
            List<ValidationDiagnostic> diagnostics) {
        if (!matchesType(value, definition.type())) {
            diagnostics.add(error("DX_CONFIG_TYPE", key,
                    "Expected " + definition.type() + " but found " + typeName(value)));
            return;
        }

        if (!definition.allowedValues().isEmpty()) {
            String normalized = String.valueOf(value);
            if (!definition.allowedValues().contains(normalized)) {
                addConstraintDiagnostic(key, definition,
                        "Value must be one of " + definition.allowedValues(), diagnostics);
            }
        }

        if (value instanceof String text
                && (definition.minimumUtf8Bytes() != null
                || definition.maximumUtf8Bytes() != null)) {
            int length = text.getBytes(StandardCharsets.UTF_8).length;
            if (text.indexOf('\0') >= 0) {
                addConstraintDiagnostic(key, definition,
                        "Value must not contain NUL", diagnostics);
            }
            if (definition.minimumUtf8Bytes() != null
                    && length < definition.minimumUtf8Bytes()) {
                addConstraintDiagnostic(key, definition,
                        "UTF-8 value must contain at least "
                                + definition.minimumUtf8Bytes() + " bytes", diagnostics);
            }
            if (definition.maximumUtf8Bytes() != null
                    && length > definition.maximumUtf8Bytes()) {
                addConstraintDiagnostic(key, definition,
                        "UTF-8 value must contain at most "
                                + definition.maximumUtf8Bytes() + " bytes", diagnostics);
            }
        }
        if (definition.maximumItems() != null) {
            int size = collectionSize(value);
            if (size > definition.maximumItems()) {
                addConstraintDiagnostic(key, definition,
                        "Collection must contain at most " + definition.maximumItems()
                                + " items", diagnostics);
            }
        }

        Long numeric = numericValue(value, definition.type());
        if (numeric != null && definition.minimum() != null && numeric < definition.minimum()) {
            addConstraintDiagnostic(key, definition,
                    "Value must be >= " + definition.minimum(), diagnostics);
        }
        if (numeric != null && definition.maximum() != null && numeric > definition.maximum()) {
            addConstraintDiagnostic(key, definition,
                    "Value must be <= " + definition.maximum(), diagnostics);
        }
    }

    private static int collectionSize(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? 0 : text.split(",", -1).length;
    }

    private static void validateCrossFields(
            Map<String, ?> values,
            Set<Integer> indices,
            boolean flat,
            List<ValidationDiagnostic> diagnostics) {
        if (flat) {
            validateCrossFields(values, "yano.app-chain.", diagnostics);
        }
        for (int index : indices) {
            validateCrossFields(values, "yano.app-chain.chains[" + index + "].", diagnostics);
        }
    }

    private static void validateCrossFields(
            Map<String, ?> values,
            String prefix,
            List<ValidationDiagnostic> diagnostics) {
        Long maxMessages = numeric(values, prefix + "block.max-messages",
                AppChainConfig.DEFAULT_MAX_BLOCK_MESSAGES);
        Long poolMessages = numeric(values, prefix + "pool.max-messages",
                AppChainConfig.DEFAULT_POOL_MAX_MESSAGES);
        if (maxMessages != null && poolMessages != null && poolMessages < maxMessages) {
            diagnostics.add(error("DX_CONFIG_CROSS_FIELD", prefix + "pool.max-messages",
                    "pool.max-messages must be >= block.max-messages [provenance="
                            + ConstraintProvenance.PUBLIC_RUNTIME_DEFINITION + "]"));
        }

        Long maxMessageBytes = numeric(values, prefix + "max-message-bytes",
                AppChainConfig.DEFAULT_MAX_MESSAGE_BYTES);
        Long blockMaxBytes = numeric(values, prefix + "block.max-bytes",
                AppChainConfig.DEFAULT_BLOCK_MAX_BYTES);
        if (maxMessageBytes != null && blockMaxBytes != null) {
            long minimum = maxMessageBytes + AppChainConfig.BLOCK_ENVELOPE_HEADROOM_BYTES
                    + AppChainConfig.MAX_FINALITY_CERT_HEADROOM_BYTES;
            if (blockMaxBytes < minimum) {
                diagnostics.add(error("DX_CONFIG_CROSS_FIELD", prefix + "block.max-bytes",
                        "block.max-bytes must be >= " + minimum
                                + " for max-message-bytes and v1 finality headroom [provenance="
                                + ConstraintProvenance.PUBLIC_RUNTIME_DEFINITION + "]"));
            }
        }
    }

    private static Long numeric(Map<String, ?> values, String key, long defaultValue) {
        Object value = defaultValue;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (key.equals(stripProfile(entry.getKey()))) {
                value = entry.getValue();
                break;
            }
        }
        return numericValue(value, PropertyType.LONG);
    }

    private static void addConstraintDiagnostic(
            String key,
            AppChainPropertyDefinition definition,
            String message,
            List<ValidationDiagnostic> diagnostics) {
        String evidence = " [provenance=" + definition.constraintProvenance() + "]";
        if (definition.constraintsEnforceable()) {
            diagnostics.add(error("DX_CONFIG_CONSTRAINT", key, message + evidence));
        } else {
            diagnostics.add(warning("DX_CONFIG_UNVERIFIED_CONSTRAINT", key, message + evidence));
        }
    }

    private static boolean matchesType(Object value, PropertyType type) {
        if (value == null) {
            return false;
        }
        return switch (type) {
            case BOOLEAN -> value instanceof Boolean
                    || value instanceof String text
                    && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text));
            case INTEGER -> integral(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case LONG -> integral(value, Long.MIN_VALUE, Long.MAX_VALUE);
            case STRING -> !(value instanceof Map<?, ?>) && !(value instanceof Collection<?>);
            case STRING_LIST -> value instanceof String
                    || value instanceof Collection<?> collection
                    && collection.stream().allMatch(String.class::isInstance);
            case OBJECT -> value instanceof Map<?, ?> || value instanceof Collection<?>;
        };
    }

    private static boolean integral(Object value, long minimum, long maximum) {
        try {
            String text = String.valueOf(value);
            if (value instanceof Float || value instanceof Double
                    || value instanceof BigDecimal || !text.matches("[+-]?\\d+")) {
                return false;
            }
            BigDecimal decimal = new BigDecimal(text);
            return decimal.compareTo(BigDecimal.valueOf(minimum)) >= 0
                    && decimal.compareTo(BigDecimal.valueOf(maximum)) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Long numericValue(Object value, PropertyType type) {
        if (type != PropertyType.INTEGER && type != PropertyType.LONG) {
            return null;
        }
        try {
            String text = String.valueOf(value);
            if (value instanceof Float || value instanceof Double
                    || value instanceof BigDecimal || !text.matches("[+-]?\\d+")) {
                return null;
            }
            return new BigDecimal(text).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private static boolean isSecretReference(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        String candidate = text.trim();
        return candidate.matches("^\\$\\{[^{}]+}$")
                || candidate.matches("^(file|vault|kms|hsm|secret):.+$");
    }

    private static Integer chainIndex(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static String stripProfile(String key) {
        return PROFILE_PREFIX.matcher(key == null ? "" : key.trim()).replaceFirst("");
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
