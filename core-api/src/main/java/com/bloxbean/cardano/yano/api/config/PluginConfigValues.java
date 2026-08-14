package com.bloxbean.cardano.yano.api.config;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded immutable snapshots of JSON-like plugin configuration values.
 *
 * <p>Supported values are strings, booleans, finite immutable numbers,
 * string-keyed maps and lists. Nulls, arrays, sets, mutable/custom number
 * implementations and arbitrary objects are rejected. Diagnostics never
 * render keys or values because either may identify secret material.</p>
 */
public final class PluginConfigValues {
    public static final Limits DEFAULT_LIMITS = new Limits(
            16, 32_768, 2 * 1024 * 1024, 4_096, 1_024, 256 * 1024);

    private PluginConfigValues() {
    }

    /** Deep-copy a plugin configuration map using the default host limits. */
    public static Map<String, Object> immutableCopy(Map<?, ?> source) {
        return immutableCopy(source, DEFAULT_LIMITS);
    }

    /** Deep-copy a plugin configuration map using an explicitly tighter policy. */
    public static Map<String, Object> immutableCopy(Map<?, ?> source, Limits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        return new Copier(limits).copyRoot(source);
    }

    /** Resource limits applied during one complete graph copy. */
    public record Limits(
            int maxDepth,
            int maxNodes,
            int maxTextCharacters,
            int maxCollectionEntries,
            int maxKeyCharacters,
            int maxStringCharacters
    ) {
        public Limits {
            requirePositive(maxDepth, "maxDepth");
            requirePositive(maxNodes, "maxNodes");
            requirePositive(maxTextCharacters, "maxTextCharacters");
            requirePositive(maxCollectionEntries, "maxCollectionEntries");
            requirePositive(maxKeyCharacters, "maxKeyCharacters");
            requirePositive(maxStringCharacters, "maxStringCharacters");
        }

        private static void requirePositive(int value, String field) {
            if (value <= 0) {
                throw new IllegalArgumentException(field + " must be positive");
            }
        }
    }

    private static final class Copier {
        private final Limits limits;
        private final IdentityHashMap<Object, Boolean> activeContainers =
                new IdentityHashMap<>();
        private int nodes;
        private long textCharacters;

        private Copier(Limits limits) {
            this.limits = limits;
        }

        private Map<String, Object> copyRoot(Map<?, ?> source) {
            return copyMap(source, 0);
        }

        private Map<String, Object> copyMap(Map<?, ?> source, int depth) {
            requireDepth(depth);
            requireCollectionSize(source.size(), "map");
            enter(source);
            try {
                Map<String, Object> copy = new LinkedHashMap<>(source.size());
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw invalid("Configuration maps must use string keys");
                    }
                    if (key.length() > limits.maxKeyCharacters()) {
                        throw invalid("Configuration keys exceed the per-key character limit");
                    }
                    addText(key.length());
                    copy.put(key, copyValue(entry.getValue(), depth + 1));
                }
                return Collections.unmodifiableMap(copy);
            } finally {
                leave(source);
            }
        }

        private List<Object> copyList(List<?> source, int depth) {
            requireDepth(depth);
            requireCollectionSize(source.size(), "list");
            enter(source);
            try {
                List<Object> copy = new ArrayList<>(source.size());
                for (Object value : source) {
                    copy.add(copyValue(value, depth + 1));
                }
                return List.copyOf(copy);
            } finally {
                leave(source);
            }
        }

        private Object copyValue(Object value, int depth) {
            requireDepth(depth);
            addNode();
            if (value == null) {
                throw invalid("Configuration values must not be null");
            }
            if (value instanceof String text) {
                if (text.length() > limits.maxStringCharacters()) {
                    throw invalid("Configuration strings exceed the per-value character limit");
                }
                addText(text.length());
                return text;
            }
            if (value instanceof Boolean) {
                addText(value.toString().length());
                return value;
            }
            if (isImmutableIntegral(value)) {
                addText(value.toString().length());
                return value;
            }
            if (value instanceof Float number) {
                if (!Float.isFinite(number)) {
                    throw invalid("Configuration numbers must be finite");
                }
                addText(number.toString().length());
                return number;
            }
            if (value instanceof Double number) {
                if (!Double.isFinite(number)) {
                    throw invalid("Configuration numbers must be finite");
                }
                addText(number.toString().length());
                return number;
            }
            if (value.getClass() == BigDecimal.class) {
                addText(value.toString().length());
                return value;
            }
            if (value instanceof Map<?, ?> map) {
                return copyMap(map, depth);
            }
            if (value instanceof List<?> list) {
                return copyList(list, depth);
            }
            throw invalid("Configuration values must be immutable scalars, "
                    + "string-keyed maps, or lists");
        }

        private static boolean isImmutableIntegral(Object value) {
            return value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long
                    || value.getClass() == BigInteger.class;
        }

        private void enter(Object container) {
            if (activeContainers.put(container, Boolean.TRUE) != null) {
                throw invalid("Configuration contains a reference cycle");
            }
        }

        private void leave(Object container) {
            activeContainers.remove(container);
        }

        private void requireDepth(int depth) {
            if (depth > limits.maxDepth()) {
                throw invalid("Configuration nesting exceeds the maximum depth");
            }
        }

        private void requireCollectionSize(int size, String kind) {
            if (size > limits.maxCollectionEntries()) {
                throw invalid("Configuration " + kind + " exceeds the entry limit");
            }
        }

        private void addNode() {
            if (++nodes > limits.maxNodes()) {
                throw invalid("Configuration contains more values than allowed");
            }
        }

        private void addText(int characters) {
            textCharacters += characters;
            if (textCharacters > limits.maxTextCharacters()) {
                throw invalid("Configuration exceeds the aggregate character limit");
            }
        }

        private static IllegalArgumentException invalid(String message) {
            return new IllegalArgumentException(message);
        }
    }
}
