package com.bloxbean.cardano.yano.appchain.testkit.effects;

import org.junit.jupiter.api.Assertions;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Shared bounded-cardinality and secret-canary checks for existing Effect Runtime views. */
public final class EffectRuntimeSnapshotAssertions {
    private static final int MAX_INSPECTED_VALUES = 10_000;
    private static final int MAX_FORBIDDEN_SENTINELS = 64;
    private static final int MAX_SENTINEL_CHARACTERS = 256;
    private static final int MAX_TOTAL_SENTINEL_CHARACTERS = 4 * 1024;
    private static final int MAX_LEAF_BYTES = 64 * 1024;
    private static final int MAX_LEAF_CHARACTERS = 64 * 1024;
    private static final long MAX_TOTAL_INSPECTED_UNITS = 1024 * 1024;
    private static final Set<String> STATUS_COUNT_KEYS = Set.of(
            "PENDING", "RETRY", "SUBMITTED", "EXTERNAL",
            "DONE", "PARKED", "QUARANTINED", "SKIPPED");
    private static final Set<String> EXECUTION_TOTAL_KEYS = Set.of(
            "confirmed", "failed", "parked");
    private static final Set<String> LATENCY_KEYS = Set.of("count", "totalMillis");

    private EffectRuntimeSnapshotAssertions() {
    }

    /**
     * Validates stable runtime metric/status shapes and rejects secret canaries.
     *
     * @param stats the Effect Runtime stats snapshot
     * @param status the effect-specific status snapshot
     * @param allowedMetricTypes the bounded action-type label allowlist
     * @param forbiddenSentinels secret canaries forbidden in either snapshot
     */
    public static void assertSafe(Map<String, Object> stats,
                                  Map<String, Object> status,
                                  Set<String> allowedMetricTypes,
                                  Set<String> forbiddenSentinels) {
        Assertions.assertNotNull(stats, "stats");
        Assertions.assertNotNull(status, "status");
        Assertions.assertFalse(stats.isEmpty(), "effect runtime stats must not be empty");
        Assertions.assertFalse(status.isEmpty(), "effect runtime status must not be empty");
        for (String gauge : Set.of("queueDepth", "inFlight", "resultBacklog")) {
            Object value = stats.get(gauge);
            assertNonNegativeNumber(value, gauge + " must be present as a number",
                    gauge + " must be a non-negative signed-long integer");
        }
        assertCounterMap(stats.get("statusCounts"), "statusCounts", STATUS_COUNT_KEYS, false);
        assertCounterMap(stats.get("executionTotals"), "executionTotals",
                EXECUTION_TOTAL_KEYS, true);
        String runtimeState = Assertions.assertInstanceOf(String.class, status.get("status"),
                "runtime status must name a state");
        Assertions.assertFalse(runtimeState.isBlank(), "runtime status state must not be blank");
        Number attempts = Assertions.assertInstanceOf(Number.class, status.get("attempts"),
                "runtime status must expose attempts");
        Assertions.assertTrue(exactNonNegativeLong(attempts,
                        "runtime status attempts must be a positive signed-long integer") > 0,
                "runtime status attempts must be positive");
        Set<String> metricKeys = metricKeys(allowedMetricTypes);
        assertPerTypeCounters(stats.get("resultBacklogByType"), metricKeys);
        assertPerTypeLatency(stats.get("latencyByType"), metricKeys);
        assertNoSentinels(stats, forbiddenSentinels);
        assertNoSentinels(status, forbiddenSentinels);
    }

    /**
     * Traverses a bounded snapshot and fails if any text or bytes contain a secret canary.
     *
     * @param value the supported snapshot value to inspect
     * @param forbiddenSentinels bounded non-empty canary strings
     */
    public static void assertNoSentinels(Object value, Set<String> forbiddenSentinels) {
        List<Sentinel> sentinels = validateSentinels(forbiddenSentinels);
        if (value == null || sentinels.isEmpty()) {
            return;
        }
        InspectionQueue pending = new InspectionQueue();
        InspectionBudget budget = new InspectionBudget();
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        pending.add(value);
        while (!pending.isEmpty()) {
            Object current = pending.removeFirst();
            if (isIdentityTracked(current) && visited.put(current, Boolean.TRUE) != null) {
                continue;
            }
            if (current instanceof byte[] bytes) {
                budget.inspectBytes(bytes.length);
                assertBytesSafe(bytes, sentinels);
                continue;
            }
            if (current instanceof char[] characters) {
                budget.inspectCharacters(characters.length);
                assertCharactersSafe(characters, sentinels);
                continue;
            }
            if (current instanceof CharSequence characters) {
                int length;
                try {
                    length = characters.length();
                } catch (RuntimeException failure) {
                    throw new AssertionError(
                            "could not measure snapshot text for bounded redaction", failure);
                }
                budget.inspectCharacters(length);
                assertTextSafe(characters, sentinels);
                continue;
            }
            if (current instanceof Map<?, ?> map) {
                assertContainerBound(map.size());
                map.forEach((key, child) -> {
                    enqueue(pending, key);
                    enqueue(pending, child);
                });
                continue;
            }
            if (current instanceof Collection<?> collection) {
                assertContainerBound(collection.size());
                collection.forEach(child -> enqueue(pending, child));
                continue;
            }
            if (current instanceof Iterable<?> iterable) {
                enqueueBounded(pending, iterable.iterator());
                continue;
            }
            if (current instanceof Optional<?> optional) {
                optional.ifPresent(child -> enqueue(pending, child));
                continue;
            }
            if (current.getClass().isArray()) {
                int length = Array.getLength(current);
                assertContainerBound(length);
                for (int index = 0; index < length; index++) {
                    enqueue(pending, Array.get(current, index));
                }
                continue;
            }
            if (current.getClass().isRecord()) {
                for (java.lang.reflect.RecordComponent component
                        : current.getClass().getRecordComponents()) {
                    try {
                        enqueue(pending, component.getAccessor().invoke(current));
                    } catch (ReflectiveOperationException | RuntimeException exception) {
                        throw new AssertionError("could not inspect record for redaction", exception);
                    }
                }
                continue;
            }
            if (current instanceof Throwable throwable) {
                enqueue(pending, throwable.getMessage());
                enqueue(pending, throwable.getCause());
                for (Throwable suppressed : throwable.getSuppressed()) {
                    enqueue(pending, suppressed);
                }
                continue;
            }
            if (current instanceof Number || current instanceof Boolean
                    || current instanceof Character || current instanceof Enum<?>) {
                continue;
            }
            throw new AssertionError("unsupported snapshot value type for bounded redaction: "
                    + current.getClass().getName());
        }
    }

    private static List<Sentinel> validateSentinels(Set<String> forbiddenSentinels) {
        if (forbiddenSentinels == null || forbiddenSentinels.isEmpty()) {
            return List.of();
        }
        if (forbiddenSentinels.size() > MAX_FORBIDDEN_SENTINELS) {
            throw new AssertionError("too many forbidden sentinels for bounded redaction");
        }
        List<Sentinel> sentinels = new ArrayList<>(forbiddenSentinels.size());
        int totalCharacters = 0;
        int count = 0;
        for (String sentinel : forbiddenSentinels) {
            if (++count > MAX_FORBIDDEN_SENTINELS) {
                throw new AssertionError("too many forbidden sentinels for bounded redaction");
            }
            if (sentinel == null || sentinel.isEmpty()) {
                continue;
            }
            int characters = sentinel.length();
            if (characters > MAX_SENTINEL_CHARACTERS) {
                throw new AssertionError("forbidden sentinel exceeded the character bound");
            }
            if (totalCharacters > MAX_TOTAL_SENTINEL_CHARACTERS - characters) {
                throw new AssertionError("forbidden sentinels exceeded the cumulative character bound");
            }
            totalCharacters += characters;
        }
        // Only encode after every member and the cumulative set passed the
        // character preflight. UTF-8 uses at most four bytes per UTF-16 unit.
        count = 0;
        for (String sentinel : forbiddenSentinels) {
            if (++count > MAX_FORBIDDEN_SENTINELS) {
                throw new AssertionError("too many forbidden sentinels for bounded redaction");
            }
            if (sentinel != null && !sentinel.isEmpty()) {
                sentinels.add(new Sentinel(sentinel,
                        sentinel.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return List.copyOf(sentinels);
    }

    private static void assertTextSafe(CharSequence text, List<Sentinel> sentinels) {
        for (Sentinel sentinel : sentinels) {
            Assertions.assertFalse(contains(text, sentinel.text()),
                    "snapshot contained a forbidden sentinel");
        }
    }

    private static void assertCharactersSafe(char[] text, List<Sentinel> sentinels) {
        for (Sentinel sentinel : sentinels) {
            Assertions.assertFalse(contains(text, sentinel.text()),
                    "snapshot contained a forbidden sentinel");
        }
    }

    private static void assertBytesSafe(byte[] bytes, List<Sentinel> sentinels) {
        for (Sentinel sentinel : sentinels) {
            Assertions.assertFalse(contains(bytes, sentinel.utf8()),
                    "snapshot contained a forbidden sentinel");
        }
    }

    private static boolean isIdentityTracked(Object value) {
        return !(value instanceof String) && !(value instanceof Number)
                && !(value instanceof Boolean) && !(value instanceof Character)
                && !(value instanceof Enum<?>);
    }

    private static void enqueue(InspectionQueue pending, Object value) {
        pending.add(value);
    }

    private static void enqueueBounded(InspectionQueue pending, Iterator<?> iterator) {
        while (iterator.hasNext()) {
            enqueue(pending, iterator.next());
        }
    }

    private static void assertContainerBound(int size) {
        if (size > MAX_INSPECTED_VALUES) {
            throw new AssertionError("snapshot container exceeded the redaction inspection budget");
        }
    }

    private static void assertCounterMap(Object value,
                                         String name,
                                         Set<String> allowedKeys,
                                         boolean requireAllKeys) {
        Map<?, ?> counters = Assertions.assertInstanceOf(Map.class, value,
                name + " must be present");
        Assertions.assertFalse(counters.isEmpty(), name + " must not be empty");
        for (Map.Entry<?, ?> entry : counters.entrySet()) {
            String key = Assertions.assertInstanceOf(String.class, entry.getKey(),
                    name + " keys must be strings");
            Assertions.assertTrue(allowedKeys.contains(key),
                    name + " contained an unknown key");
            assertNonNegativeNumber(entry.getValue(), name + " values must be numbers",
                    name + " values must be non-negative");
        }
        if (requireAllKeys) {
            Assertions.assertEquals(allowedKeys, counters.keySet(),
                    name + " must expose exactly the stable key set");
        }
    }

    private static void assertNonNegativeNumber(Object value,
                                                String typeMessage,
                                                String rangeMessage) {
        Number number = Assertions.assertInstanceOf(Number.class, value,
                typeMessage);
        exactNonNegativeLong(number, rangeMessage);
    }

    private static long exactNonNegativeLong(Number number, String rangeMessage) {
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long
                || number instanceof AtomicInteger || number instanceof AtomicLong) {
            long integer = number.longValue();
            Assertions.assertTrue(integer >= 0, rangeMessage);
            return integer;
        }
        if (number instanceof BigInteger integer) {
            Assertions.assertTrue(integer.signum() >= 0
                            && integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0,
                    rangeMessage);
            return integer.longValue();
        }
        if (number instanceof BigDecimal decimal) {
            Assertions.assertTrue(decimal.signum() >= 0
                            && decimal.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0,
                    rangeMessage);
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException fraction) {
                throw new AssertionError(rangeMessage, fraction);
            }
        }
        if (number instanceof Double floating) {
            return assertFiniteNonNegativeLong(floating, rangeMessage);
        }
        if (number instanceof Float floating) {
            return assertFiniteNonNegativeLong(floating.doubleValue(), rangeMessage);
        }
        throw new AssertionError("unsupported metric Number type "
                + number.getClass().getName());
    }

    private static long assertFiniteNonNegativeLong(double value, String rangeMessage) {
        Assertions.assertTrue(Double.isFinite(value)
                        && Double.doubleToRawLongBits(value) >= 0
                        && value < 0x1.0p63
                        && value == Math.rint(value),
                rangeMessage);
        return (long) value;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || needle.length > haystack.length) {
            return false;
        }
        outer:
        for (int index = 0; index <= haystack.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean contains(CharSequence haystack, String needle) {
        if (needle.isEmpty() || needle.length() > haystack.length()) {
            return false;
        }
        outer:
        for (int index = 0; index <= haystack.length() - needle.length(); index++) {
            for (int offset = 0; offset < needle.length(); offset++) {
                if (haystack.charAt(index + offset) != needle.charAt(offset)) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean contains(char[] haystack, String needle) {
        if (needle.isEmpty() || needle.length() > haystack.length) {
            return false;
        }
        outer:
        for (int index = 0; index <= haystack.length - needle.length(); index++) {
            for (int offset = 0; offset < needle.length(); offset++) {
                if (haystack[index + offset] != needle.charAt(offset)) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static Set<String> metricKeys(Set<String> allowedMetricTypes) {
        Assertions.assertNotNull(allowedMetricTypes, "allowedMetricTypes");
        Assertions.assertFalse(allowedMetricTypes.isEmpty(),
                "at least one bounded metric type is required");
        Assertions.assertTrue(allowedMetricTypes.size() <= 64,
                "too many metric types were supplied");
        Set<String> allowed = new java.util.HashSet<>();
        for (String type : allowedMetricTypes) {
            Assertions.assertTrue(type != null && type.matches("[a-z][a-z0-9.-]{0,63}"),
                    "metric types must be bounded lowercase identifiers");
            allowed.add(type);
        }
        allowed.add("all");
        allowed.add("other");
        return Set.copyOf(allowed);
    }

    private static void assertPerTypeCounters(Object value, Set<String> allowedKeys) {
        Map<?, ?> map = Assertions.assertInstanceOf(Map.class, value,
                "resultBacklogByType must be present as a map");
        Assertions.assertFalse(map.isEmpty(), "resultBacklogByType must not be empty");
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            assertMetricKey(entry.getKey(), allowedKeys, "resultBacklogByType");
            assertNonNegativeNumber(entry.getValue(),
                    "resultBacklogByType values must be numbers",
                    "resultBacklogByType values must be non-negative");
        }
    }

    private static void assertPerTypeLatency(Object value, Set<String> allowedKeys) {
        Map<?, ?> map = Assertions.assertInstanceOf(Map.class, value,
                "latencyByType must be present as a map");
        Assertions.assertFalse(map.isEmpty(), "latencyByType must not be empty");
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            assertMetricKey(entry.getKey(), allowedKeys, "latencyByType");
            Map<?, ?> latency = Assertions.assertInstanceOf(Map.class, entry.getValue(),
                    "latencyByType values must be maps");
            Assertions.assertEquals(LATENCY_KEYS, latency.keySet(),
                    "latencyByType entries must expose exactly count and totalMillis");
            for (Object metric : LATENCY_KEYS) {
                assertNonNegativeNumber(latency.get(metric),
                        "latencyByType counters must be numbers",
                        "latencyByType counters must be non-negative");
            }
        }
    }

    private static void assertMetricKey(Object value, Set<String> allowedKeys, String name) {
        String key = Assertions.assertInstanceOf(String.class, value,
                name + " keys must be strings");
        Assertions.assertTrue(allowedKeys.contains(key),
                name + " contained an unbounded label");
    }

    private static final class InspectionQueue {
        private final ArrayDeque<Object> pending = new ArrayDeque<>();
        private int scheduled;

        private void add(Object value) {
            if (value == null) {
                return;
            }
            if (scheduled >= MAX_INSPECTED_VALUES) {
                throw new AssertionError(
                        "snapshot exceeded the bounded redaction scheduling budget");
            }
            scheduled++;
            pending.addLast(value);
        }

        private boolean isEmpty() {
            return pending.isEmpty();
        }

        private Object removeFirst() {
            return pending.removeFirst();
        }
    }

    private static final class InspectionBudget {
        private long inspectedUnits;

        private void inspectBytes(int length) {
            if (length > MAX_LEAF_BYTES) {
                throw new AssertionError("snapshot byte leaf exceeded the redaction inspection bound");
            }
            inspect(length);
        }

        private void inspectCharacters(int length) {
            if (length > MAX_LEAF_CHARACTERS) {
                throw new AssertionError("snapshot text leaf exceeded the redaction inspection bound");
            }
            inspect(length);
        }

        private void inspect(int units) {
            if (units < 0 || inspectedUnits > MAX_TOTAL_INSPECTED_UNITS - units) {
                throw new AssertionError(
                        "snapshot exceeded the cumulative redaction inspection budget");
            }
            inspectedUnits += units;
        }
    }

    private record Sentinel(String text, byte[] utf8) {
    }
}
