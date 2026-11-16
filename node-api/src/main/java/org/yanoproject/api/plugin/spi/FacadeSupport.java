package org.yanoproject.api.plugin.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded snapshot helpers shared by host and framework contribution facades. */
public final class FacadeSupport {
    /** Status is diagnostic metadata, not an unbounded plugin data plane. */
    public static final int MAX_STATUS_ENTRIES = 256;
    /** Maximum values in one status snapshot. */
    public static final int MAX_STATUS_NODES = 4_096;
    /** Maximum container nesting in one status snapshot. */
    public static final int MAX_STATUS_DEPTH = 8;
    /** Maximum characters of one status string. */
    public static final int MAX_STATUS_STRING_LENGTH = 4_096;
    /** Maximum aggregate characters of one status snapshot. */
    public static final int MAX_STATUS_TOTAL_CHARACTERS = 65_536;
    /** A factory activation is intentionally limited to a small, operable product set. */
    public static final int MAX_FACTORY_PRODUCTS_PER_CALLBACK = 256;

    private FacadeSupport() {
    }

    /**
     * Snapshots a plugin-owned list through one bounded iteration, each
     * iterator callback under {@code scope}.
     *
     * @param values plugin list, possibly null
     * @param scope callback boundary
     * @param maximumSize maximum accepted entries
     * @param overflowMessage failure message when the list is longer
     * @param <T> element type
     * @return immutable snapshot, or null when {@code values} is null
     */
    public static <T> List<T> snapshotList(
            List<T> values,
            PluginCallbackScope scope,
            int maximumSize,
            String overflowMessage
    ) {
        if (values == null) {
            return null;
        }
        List<T> snapshot = new ArrayList<>();
        Iterator<T> iterator = scope.call(values::iterator);
        while (scope.call(iterator::hasNext)) {
            if (snapshot.size() == maximumSize) {
                throw new IllegalStateException(overflowMessage);
            }
            snapshot.add(scope.call(iterator::next));
        }
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * Snapshots plugin status metadata into immutable JSON-like values bounded
     * by the {@code MAX_STATUS_*} limits, each callback under {@code scope}.
     *
     * @param values plugin status map
     * @param scope callback boundary
     * @return immutable bounded snapshot
     */
    public static Map<String, Object> snapshotStatus(
            Map<String, Object> values,
            PluginCallbackScope scope
    ) {
        if (values == null) {
            throw new IllegalStateException("Plugin status must not be null");
        }
        StatusBudget budget = new StatusBudget();
        Object normalized = normalizeStatusValue(
                values, scope, budget, new IdentityHashMap<>(), 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) normalized;
        return snapshot;
    }

    private static Object normalizeStatusValue(
            Object value,
            PluginCallbackScope scope,
            StatusBudget budget,
            IdentityHashMap<Object, Boolean> activeContainers,
            int depth
    ) {
        budget.addNode();
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            budget.addText(text);
            return text;
        }
        if (isPlatformNumber(value)) {
            if ((value instanceof Double doubleValue && !Double.isFinite(doubleValue))
                    || (value instanceof Float floatValue && !Float.isFinite(floatValue))) {
                throw new IllegalArgumentException(
                        "Plugin status numbers must be finite");
            }
            return value;
        }
        if (depth >= MAX_STATUS_DEPTH) {
            throw new IllegalArgumentException(
                    "Plugin status nesting exceeds " + MAX_STATUS_DEPTH);
        }
        if (value instanceof Map<?, ?> map) {
            requireAcyclicContainer(value, activeContainers);
            try {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                Object entries = scope.call(map::entrySet);
                @SuppressWarnings("unchecked")
                Iterator<Map.Entry<?, ?>> iterator = (Iterator<Map.Entry<?, ?>>) scope.call(
                        () -> ((Set<?>) entries).iterator());
                int traversedEntries = 0;
                while (scope.call(iterator::hasNext)) {
                    if (traversedEntries++ == MAX_STATUS_ENTRIES) {
                        throw new IllegalStateException(
                                "Plugin status must contain at most "
                                        + MAX_STATUS_ENTRIES + " entries");
                    }
                    Map.Entry<?, ?> entry = Objects.requireNonNull(
                            scope.call(iterator::next),
                            "Plugin status map must not contain null entries");
                    Object rawKey = scope.call(entry::getKey);
                    if (!(rawKey instanceof String key)) {
                        throw new IllegalArgumentException(
                                "Plugin status map keys must be strings");
                    }
                    budget.addText(key);
                    Object rawValue = scope.call(entry::getValue);
                    snapshot.put(key, normalizeStatusValue(
                            rawValue, scope, budget,
                            activeContainers, depth + 1));
                }
                return Collections.unmodifiableMap(snapshot);
            } finally {
                activeContainers.remove(value);
            }
        }
        if (value instanceof List<?> list) {
            requireAcyclicContainer(value, activeContainers);
            try {
                List<Object> snapshot = new ArrayList<>();
                Iterator<?> iterator = scope.call(list::iterator);
                while (scope.call(iterator::hasNext)) {
                    if (snapshot.size() == MAX_STATUS_ENTRIES) {
                        throw new IllegalArgumentException(
                                "Plugin status list must contain at most "
                                        + MAX_STATUS_ENTRIES + " entries");
                    }
                    Object item = scope.call(iterator::next);
                    snapshot.add(normalizeStatusValue(
                            item, scope, budget,
                            activeContainers, depth + 1));
                }
                return Collections.unmodifiableList(new ArrayList<>(snapshot));
            } finally {
                activeContainers.remove(value);
            }
        }
        throw new IllegalArgumentException(
                "Plugin status values must be JSON primitives, maps, or lists");
    }

    private static void requireAcyclicContainer(
            Object value,
            IdentityHashMap<Object, Boolean> activeContainers
    ) {
        if (activeContainers.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException(
                    "Plugin status must not contain container cycles");
        }
    }

    private static boolean isPlatformNumber(Object value) {
        Class<?> type = value.getClass();
        return type == Byte.class || type == Short.class || type == Integer.class
                || type == Long.class || type == Float.class || type == Double.class;
    }

    private static final class StatusBudget {
        private int nodes;
        private int characters;

        private void addNode() {
            if (++nodes > MAX_STATUS_NODES) {
                throw new IllegalArgumentException(
                        "Plugin status exceeds " + MAX_STATUS_NODES + " values");
            }
        }

        private void addText(String text) {
            if (text.length() > MAX_STATUS_STRING_LENGTH) {
                throw new IllegalArgumentException(
                        "Plugin status text exceeds "
                                + MAX_STATUS_STRING_LENGTH + " characters");
            }
            if (characters > MAX_STATUS_TOTAL_CHARACTERS - text.length()) {
                throw new IllegalArgumentException(
                        "Plugin status text exceeds aggregate character limit");
            }
            characters += text.length();
        }
    }
}
