package org.yanoproject.runtime.plugins.fixture;

import java.util.Map;

/** Product of a {@link FixtureProvider}. */
public interface FixtureProduct extends AutoCloseable {
    default String id() {
        return "product";
    }

    default String call(String input) throws Exception {
        return input;
    }

    default Map<String, Object> status() {
        return Map.of();
    }

    @Override
    default void close() {
    }
}
