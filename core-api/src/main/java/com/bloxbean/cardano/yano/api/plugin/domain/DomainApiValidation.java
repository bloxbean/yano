package com.bloxbean.cardano.yano.api.plugin.domain;

import com.bloxbean.cardano.yano.api.appchain.AppQueryPath;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class DomainApiValidation {
    private static final Pattern ROUTE_ID = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private static final Pattern PARAMETER_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern URI_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._~-]*");
    private static final Pattern QUERY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._~-]{0,63}");
    private static final Pattern CONFIG_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\[\\]-]{0,159}");

    private DomainApiValidation() {
    }

    static String routeId(String value) {
        if (value == null || !ROUTE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "routeId must be a 1-64 character lowercase ASCII identifier");
        }
        return value;
    }

    static Template template(String value) {
        List<String> segments = splitRelativePath(value, DomainApiRoute.MAX_TEMPLATE_LENGTH,
                DomainApiRoute.MAX_SEGMENTS, "template");
        Set<String> parameters = new LinkedHashSet<>();
        for (String segment : segments) {
            if (segment.startsWith("{") || segment.endsWith("}")) {
                if (segment.length() < 3 || segment.charAt(0) != '{'
                        || segment.charAt(segment.length() - 1) != '}') {
                    throw invalidTemplate();
                }
                String parameter = segment.substring(1, segment.length() - 1);
                if (!PARAMETER_NAME.matcher(parameter).matches()) {
                    throw invalidTemplate();
                }
                if (!parameters.add(parameter)) {
                    throw new IllegalArgumentException(
                            "template must not repeat a path parameter name");
                }
            } else if (!URI_SEGMENT.matcher(segment).matches()) {
                throw invalidTemplate();
            }
        }
        return new Template(value, List.copyOf(parameters));
    }

    static String requestPath(String value) {
        List<String> segments = splitRelativePath(value, DomainApiRoute.MAX_TEMPLATE_LENGTH,
                DomainApiRoute.MAX_SEGMENTS, "path");
        if (segments.stream().anyMatch(segment -> !URI_SEGMENT.matcher(segment).matches())) {
            throw new IllegalArgumentException(
                    "path must contain only normalized ASCII URI segments");
        }
        return value;
    }

    static String queryPath(String value) {
        return AppQueryPath.validate(value);
    }

    static String chainId(String value) {
        if (value == null || value.isBlank() || value.length() > DomainQueryService.MAX_CHAIN_ID_LENGTH
                || containsControl(value)) {
            throw new IllegalArgumentException(
                    "chainId must be non-blank, control-free, and at most 160 characters");
        }
        return value;
    }

    static Map<String, String> pathParameters(Map<String, String> values) {
        Objects.requireNonNull(values, "pathParameters");
        if (values.size() > DomainApiRoute.MAX_SEGMENTS) {
            throw new IllegalArgumentException("pathParameters must contain at most 8 entries");
        }
        Map<String, String> copy = new TreeMap<>();
        values.forEach((name, value) -> {
            if (name == null || !PARAMETER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                        "path parameter names must be 1-64 character lowercase ASCII identifiers");
            }
            if (value == null || value.length() > DomainApiRequest.MAX_PATH_PARAMETER_LENGTH
                    || !URI_SEGMENT.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        "path parameter values must be normalized ASCII URI segments of at most 256 characters");
            }
            copy.put(name, value);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(copy));
    }

    static Map<String, List<String>> queryParameters(Map<String, List<String>> values) {
        Objects.requireNonNull(values, "queryParameters");
        if (values.size() > DomainApiRequest.MAX_QUERY_PARAMETER_NAMES) {
            throw new IllegalArgumentException("queryParameters must contain at most 32 names");
        }
        Map<String, List<String>> sorted = new TreeMap<>();
        int totalValues = 0;
        int totalCharacters = 0;
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            String name = entry.getKey();
            if (name == null || !QUERY_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                        "query parameter names must be 1-64 character ASCII identifiers");
            }
            List<String> source = Objects.requireNonNull(
                    entry.getValue(), "query parameter value lists must not be null");
            if (source.isEmpty() || source.size() > DomainApiRequest.MAX_QUERY_VALUES_PER_NAME) {
                throw new IllegalArgumentException(
                        "each query parameter must contain 1-16 values");
            }
            totalValues += source.size();
            if (totalValues > DomainApiRequest.MAX_QUERY_VALUES) {
                throw new IllegalArgumentException(
                        "queryParameters must contain at most 128 values");
            }
            List<String> copiedValues = new ArrayList<>(source.size());
            for (String value : source) {
                if (value == null || value.length() > DomainApiRequest.MAX_QUERY_VALUE_LENGTH
                        || containsControl(value)) {
                    throw new IllegalArgumentException(
                            "query parameter values must be control-free and at most 2048 characters");
                }
                totalCharacters += name.length() + value.length();
                if (totalCharacters > DomainApiRequest.MAX_QUERY_CHARACTERS) {
                    throw new IllegalArgumentException(
                            "queryParameters exceed the 65536 character aggregate limit");
                }
                copiedValues.add(value);
            }
            sorted.put(name, List.copyOf(copiedValues));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    static Map<String, Object> bundleConfig(Map<String, ?> values) {
        Objects.requireNonNull(values, "bundleConfig");
        if (values.size() > DomainApiContext.MAX_CONFIG_ENTRIES) {
            throw new IllegalArgumentException("bundleConfig must contain at most 256 entries");
        }
        ConfigBudget budget = new ConfigBudget();
        return immutableStringMap(values, 0, budget);
    }

    private static Map<String, Object> immutableStringMap(
            Map<?, ?> values,
            int depth,
            ConfigBudget budget
    ) {
        requireConfigDepth(depth);
        Map<String, Object> copy = new TreeMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !CONFIG_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(
                        "bundleConfig keys must be 1-160 character ASCII property names");
            }
            budget.add(key.length());
            copy.put(key, immutableConfigValue(entry.getValue(), depth + 1, budget));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(copy));
    }

    private static Object immutableConfigValue(Object value, int depth, ConfigBudget budget) {
        requireConfigDepth(depth);
        Objects.requireNonNull(value, "bundleConfig values must not be null");
        budget.addNode();
        if (value instanceof String text) {
            if (text.length() > DomainApiContext.MAX_CONFIG_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "bundleConfig string values must contain at most 8192 characters");
            }
            budget.add(text.length());
            return text;
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            budget.add(value.toString().length());
            return value;
        }
        if (value.getClass() == BigInteger.class) {
            budget.add(value.toString().length());
            return value;
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException("bundleConfig numbers must be finite");
            }
            budget.add(number.toString().length());
            return number;
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("bundleConfig numbers must be finite");
            }
            budget.add(number.toString().length());
            return number;
        }
        if (value.getClass() == BigDecimal.class) {
            BigDecimal number = (BigDecimal) value;
            budget.add(number.toString().length());
            return number;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > DomainApiContext.MAX_CONFIG_COLLECTION_ENTRIES) {
                throw new IllegalArgumentException(
                        "nested bundleConfig maps must contain at most 256 entries");
            }
            return immutableStringMap(map, depth, budget);
        }
        if (value instanceof List<?> list) {
            if (list.size() > DomainApiContext.MAX_CONFIG_COLLECTION_ENTRIES) {
                throw new IllegalArgumentException(
                        "nested bundleConfig lists must contain at most 256 entries");
            }
            List<Object> copy = new ArrayList<>(list.size());
            for (Object nested : list) {
                copy.add(immutableConfigValue(nested, depth + 1, budget));
            }
            return List.copyOf(copy);
        }
        throw new IllegalArgumentException(
                "bundleConfig values must be immutable scalars, string-keyed maps, or lists");
    }

    private static List<String> splitRelativePath(
            String value,
            int maxLength,
            int maxSegments,
            String field
    ) {
        if (value == null || value.isEmpty() || value.length() > maxLength
                || value.startsWith("/") || value.endsWith("/")
                || value.indexOf('%') >= 0 || value.indexOf('\0') >= 0
                || containsControl(value)) {
            throw new IllegalArgumentException(field
                    + " must be a normalized relative path of at most " + maxLength + " characters");
        }
        List<String> segments = List.of(value.split("/", -1));
        if (segments.size() > maxSegments || segments.stream().anyMatch(String::isEmpty)
                || segments.stream().anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
            throw new IllegalArgumentException(field + " must contain 1-" + maxSegments
                    + " non-empty segments without dot segments");
        }
        return segments;
    }

    private static void requireConfigDepth(int depth) {
        if (depth > DomainApiContext.MAX_CONFIG_DEPTH) {
            throw new IllegalArgumentException("bundleConfig nesting must not exceed 8 levels");
        }
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static IllegalArgumentException invalidTemplate() {
        return new IllegalArgumentException(
                "template segments must be ASCII literals or whole-segment {parameter} declarations");
    }

    record Template(String value, List<String> parameterNames) {
    }

    private static final class ConfigBudget {
        private int nodes;
        private int characters;

        void addNode() {
            if (++nodes > DomainApiContext.MAX_CONFIG_NODES) {
                throw new IllegalArgumentException("bundleConfig contains more than 2048 values");
            }
        }

        void add(int count) {
            characters += count;
            if (characters > DomainApiContext.MAX_CONFIG_CHARACTERS) {
                throw new IllegalArgumentException(
                        "bundleConfig exceeds the 65536 character aggregate limit");
            }
        }
    }
}
