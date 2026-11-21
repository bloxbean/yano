package org.yanoproject.api.plugin.spi;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.api.plugin.spi.FacadeSupport.MAX_STATUS_DEPTH;
import static org.yanoproject.api.plugin.spi.FacadeSupport.MAX_STATUS_ENTRIES;
import static org.yanoproject.api.plugin.spi.FacadeSupport.MAX_STATUS_NODES;
import static org.yanoproject.api.plugin.spi.FacadeSupport.MAX_STATUS_STRING_LENGTH;
import static org.yanoproject.api.plugin.spi.FacadeSupport.MAX_STATUS_TOTAL_CHARACTERS;

/** Each {@code MAX_STATUS_*} limit accepts its exact value and rejects one more. */
class FacadeSupportTest {
    private static final PluginCallbackScope DIRECT = new PluginCallbackScope() {
        @Override public String bundleId() { return "com.example.status"; }
        @Override public String selector() { return "status"; }
        @Override public ClassLoader loader() { return getClass().getClassLoader(); }
        @Override public <T, X extends Throwable> T call(Callback<T, X> callback) throws X { return callback.get(); }
        @Override public <X extends Throwable> void run(Action<X> action) throws X { action.run(); }
        @Override public <X extends Throwable> void runCleanup(Action<X> action) throws X { action.run(); }
        @Override public <T> T activation(String action, Supplier<T> callback) { return callback.get(); }
        @Override public <T> T product(T value, UnaryOperator<T> facade) { return facade.apply(value); }
        @Override public <T> List<T> products(List<T> values, UnaryOperator<T> facade) {
            return values.stream().map(facade).toList();
        }
    };

    @Test
    void mapAndListEntries() {
        assertThat(FacadeSupport.snapshotStatus(entries(MAX_STATUS_ENTRIES), DIRECT)).hasSize(MAX_STATUS_ENTRIES);
        assertRejected(entries(MAX_STATUS_ENTRIES + 1), "at most " + MAX_STATUS_ENTRIES + " entries");

        assertThat(FacadeSupport.snapshotStatus(Map.of("list", ints(MAX_STATUS_ENTRIES)), DIRECT).get("list"))
                .asList().hasSize(MAX_STATUS_ENTRIES);
        assertRejected(Map.of("list", ints(MAX_STATUS_ENTRIES + 1)),
                "list must contain at most " + MAX_STATUS_ENTRIES + " entries");
    }

    @Test
    void nestingDepth() {
        assertThat(FacadeSupport.snapshotStatus(nested(MAX_STATUS_DEPTH), DIRECT)).isNotEmpty();
        assertRejected(nested(MAX_STATUS_DEPTH + 1), "nesting exceeds " + MAX_STATUS_DEPTH);
    }

    @Test
    void totalValues() {
        assertThat(FacadeSupport.snapshotStatus(values(MAX_STATUS_NODES), DIRECT)).hasSize(16);
        assertRejected(values(MAX_STATUS_NODES + 1), "exceeds " + MAX_STATUS_NODES + " values");
    }

    @Test
    void stringLength() {
        assertThat(FacadeSupport.snapshotStatus(Map.of("s", "x".repeat(MAX_STATUS_STRING_LENGTH)), DIRECT))
                .containsKey("s");
        assertRejected(Map.of("s", "x".repeat(MAX_STATUS_STRING_LENGTH + 1)),
                "text exceeds " + MAX_STATUS_STRING_LENGTH + " characters");
        assertRejected(Map.of("k".repeat(MAX_STATUS_STRING_LENGTH + 1), 1),
                "text exceeds " + MAX_STATUS_STRING_LENGTH + " characters");
    }

    @Test
    void aggregateCharacters() {
        assertThat(FacadeSupport.snapshotStatus(characters(MAX_STATUS_TOTAL_CHARACTERS), DIRECT)).containsKey("l");
        assertRejected(characters(MAX_STATUS_TOTAL_CHARACTERS + 1), "aggregate character limit");
    }

    @Test
    void listSnapshotSize() {
        assertThat(FacadeSupport.snapshotList(ints(3), DIRECT, 3, "too many")).hasSize(3);
        assertThatThrownBy(() -> FacadeSupport.snapshotList(ints(4), DIRECT, 3, "too many"))
                .isInstanceOf(IllegalStateException.class).hasMessage("too many");
    }

    private static void assertRejected(Map<String, Object> status, String message) {
        assertThatThrownBy(() -> FacadeSupport.snapshotStatus(status, DIRECT)).hasMessageContaining(message);
    }

    private static Map<String, Object> entries(int count) {
        Map<String, Object> map = new LinkedHashMap<>();
        IntStream.range(0, count).forEach(index -> map.put("k" + index, index));
        return map;
    }

    private static List<Object> ints(int count) {
        return new ArrayList<>(IntStream.range(0, count).boxed().toList());
    }

    /** {@code containers} nested maps, the root included. */
    private static Map<String, Object> nested(int containers) {
        Map<String, Object> map = Map.of("leaf", 1);
        for (int depth = 1; depth < containers; depth++) {
            map = Map.of("child", map);
        }
        return map;
    }

    /** A root map of 16 lists holding {@code total} values in all, the root and the lists included. */
    private static Map<String, Object> values(int total) {
        int items = total - 17;
        Map<String, Object> map = new LinkedHashMap<>();
        for (int list = 0; list < 16; list++) {
            int size = Math.min(MAX_STATUS_ENTRIES - 1, items);
            map.put("l" + list, ints(size));
            items -= size;
        }
        assertThat(items).isZero();
        return map;
    }

    /** One key "l" plus strings summing to {@code total} characters. */
    private static Map<String, Object> characters(int total) {
        List<Object> strings = new ArrayList<>();
        int remaining = total - 1;
        while (remaining > 0) {
            int length = Math.min(MAX_STATUS_STRING_LENGTH, remaining);
            strings.add("x".repeat(length));
            remaining -= length;
        }
        return Collections.singletonMap("l", strings);
    }
}
