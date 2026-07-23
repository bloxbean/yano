package com.bloxbean.cardano.yano.appchain.devtools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Converts resolved dotted property paths into deterministic, human-readable YAML. */
final class AppChainYamlConfigRenderer {
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
            .build())
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private AppChainYamlConfigRenderer() {
    }

    static String render(Map<String, String> values, String header) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(header, "header");
        TreeMap<String, Object> root = new TreeMap<>();
        new TreeMap<>(values).forEach((path, value) ->
                insert(root, tokens(path), Objects.requireNonNull(value, path)));
        requireCompleteLists(root, "");
        try {
            return header + YAML.writeValueAsString(root);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not render generated app-chain YAML", failure);
        }
    }

    private static List<Object> tokens(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Configuration path must not be blank");
        }
        List<Object> tokens = new ArrayList<>();
        int offset = 0;
        while (offset < path.length()) {
            int segmentEnd = offset;
            while (segmentEnd < path.length()
                    && path.charAt(segmentEnd) != '.'
                    && path.charAt(segmentEnd) != '[') {
                segmentEnd++;
            }
            if (segmentEnd == offset) throw invalidPath(path);
            tokens.add(path.substring(offset, segmentEnd));
            offset = segmentEnd;
            while (offset < path.length() && path.charAt(offset) == '[') {
                int close = path.indexOf(']', offset + 1);
                if (close < 0 || close == offset + 1) throw invalidPath(path);
                String index = path.substring(offset + 1, close);
                if (!index.chars().allMatch(Character::isDigit)) throw invalidPath(path);
                try {
                    int parsed = Integer.parseInt(index);
                    if (parsed >= AppChainConfigFileLoader.MAX_PROPERTIES) {
                        throw invalidPath(path);
                    }
                    tokens.add(parsed);
                } catch (NumberFormatException failure) {
                    throw invalidPath(path);
                }
                offset = close + 1;
            }
            if (offset < path.length()) {
                if (path.charAt(offset) != '.' || offset + 1 == path.length()) {
                    throw invalidPath(path);
                }
                offset++;
            }
        }
        return List.copyOf(tokens);
    }

    @SuppressWarnings("unchecked")
    private static void insert(Map<String, Object> root, List<Object> tokens, String value) {
        Object current = root;
        for (int index = 0; index < tokens.size(); index++) {
            Object token = tokens.get(index);
            boolean leaf = index == tokens.size() - 1;
            Object next = leaf ? null : tokens.get(index + 1);
            if (token instanceof String key) {
                if (!(current instanceof Map<?, ?>)) throw conflictingPath(tokens);
                Map<String, Object> map = (Map<String, Object>) current;
                if (leaf) {
                    if (map.putIfAbsent(key, value) != null) throw conflictingPath(tokens);
                } else {
                    Object child = map.get(key);
                    if (child == null) {
                        child = container(next);
                        map.put(key, child);
                    } else if (!compatible(child, next)) {
                        throw conflictingPath(tokens);
                    }
                    current = child;
                }
            } else {
                int position = (Integer) token;
                if (!(current instanceof List<?>)) throw conflictingPath(tokens);
                List<Object> list = (List<Object>) current;
                while (list.size() <= position) list.add(null);
                if (leaf) {
                    if (list.get(position) != null) throw conflictingPath(tokens);
                    list.set(position, value);
                } else {
                    Object child = list.get(position);
                    if (child == null) {
                        child = container(next);
                        list.set(position, child);
                    } else if (!compatible(child, next)) {
                        throw conflictingPath(tokens);
                    }
                    current = child;
                }
            }
        }
    }

    private static Object container(Object next) {
        return next instanceof Integer ? new ArrayList<>() : new TreeMap<String, Object>();
    }

    private static boolean compatible(Object value, Object next) {
        return next instanceof Integer ? value instanceof List<?> : value instanceof Map<?, ?>;
    }

    private static void requireCompleteLists(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> requireCompleteLists(child,
                    path.isEmpty() ? String.valueOf(key) : path + "." + key));
        } else if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                Object child = list.get(index);
                if (child == null) {
                    throw new IllegalArgumentException(
                            "Sparse generated configuration list at " + path + "[" + index + "]");
                }
                requireCompleteLists(child, path + "[" + index + "]");
            }
        }
    }

    private static IllegalArgumentException invalidPath(String path) {
        return new IllegalArgumentException("Invalid generated configuration path: " + path);
    }

    private static IllegalArgumentException conflictingPath(List<Object> tokens) {
        return new IllegalArgumentException(
                "Conflicting generated configuration path: " + tokens);
    }
}
