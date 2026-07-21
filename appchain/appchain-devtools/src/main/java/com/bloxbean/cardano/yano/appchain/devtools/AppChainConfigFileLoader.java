package com.bloxbean.cardano.yano.appchain.devtools;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Strict, bounded YAML loader that preserves scalar/list values and flattens object paths. */
final class AppChainConfigFileLoader {
    static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    static final int MAX_DEPTH = 64;
    static final int MAX_PROPERTIES = 10_000;

    private final ObjectMapper yaml = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    Map<String, Object> load(Path path) throws IOException {
        requireReadableFile(path);
        Object document;
        try {
            document = yaml.readValue(readBounded(path), Object.class);
        } catch (JacksonException failure) {
            throw new IOException(
                    "configuration YAML is malformed or contains duplicate keys", failure);
        }
        if (!(document instanceof Map<?, ?> root)) {
            throw new IOException("configuration root must be a YAML object");
        }
        Map<String, Object> flattened = new LinkedHashMap<>();
        flatten(root, "", 0, flattened);
        return Collections.unmodifiableMap(new LinkedHashMap<>(flattened));
    }

    private static void requireReadableFile(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("configuration file does not exist or is not a regular file");
        }
        long size = Files.size(path);
        if (size > MAX_FILE_BYTES) {
            throw new IOException("configuration file exceeds " + MAX_FILE_BYTES + " bytes");
        }
    }

    private static byte[] readBounded(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes((int) MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IOException("configuration file exceeds "
                        + MAX_FILE_BYTES + " bytes");
            }
            return bytes;
        }
    }

    private static void flatten(
            Map<?, ?> source,
            String prefix,
            int depth,
            Map<String, Object> target) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("configuration nesting exceeds " + MAX_DEPTH + " levels");
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String segment) || segment.isBlank()) {
                throw new IOException("configuration object keys must be non-blank strings");
            }
            String key = prefix.isEmpty() ? segment : prefix + "." + segment;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(nested, key, depth + 1, target);
            } else if (value instanceof Collection<?> collection
                    && collection.stream().anyMatch(Map.class::isInstance)) {
                flattenObjectList(collection, key, depth + 1, target);
            } else {
                putUnique(target, key, value);
            }
        }
    }

    private static void flattenObjectList(
            Collection<?> values,
            String prefix,
            int depth,
            Map<String, Object> target) throws IOException {
        int index = 0;
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> nested)) {
                throw new IOException("configuration collection '" + prefix
                        + "' cannot mix object and scalar values");
            }
            flatten(nested, prefix + "[" + index + "]", depth, target);
            index++;
        }
    }

    private static void putUnique(Map<String, Object> target, String key, Object value)
            throws IOException {
        if (target.size() >= MAX_PROPERTIES) {
            throw new IOException("configuration exceeds " + MAX_PROPERTIES + " properties");
        }
        if (target.containsKey(key)) {
            throw new IOException("ambiguous configuration path appears more than once: " + key);
        }
        target.put(key, value);
    }
}
