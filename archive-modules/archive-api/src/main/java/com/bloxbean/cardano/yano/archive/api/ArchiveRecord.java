package com.bloxbean.cardano.yano.archive.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Backend-neutral immutable result row. Binary values are defensively copied
 * so a request cannot retain or mutate a JDBC driver's reusable buffers.
 */
public record ArchiveRecord(String table, Map<String, Object> values) {
    public ArchiveRecord {
        Objects.requireNonNull(table, "table");
        if (table.isBlank()) throw new IllegalArgumentException("table is required");
        Objects.requireNonNull(values, "values");
        var copy = new LinkedHashMap<String, Object>();
        values.forEach((name, value) -> copy.put(name,
                value instanceof byte[] bytes ? bytes.clone() : value));
        values = java.util.Collections.unmodifiableMap(copy);
    }

    @Override
    public Map<String, Object> values() {
        var copy = new LinkedHashMap<String, Object>();
        values.forEach((name, value) -> copy.put(name,
                value instanceof byte[] bytes ? bytes.clone() : value));
        return java.util.Collections.unmodifiableMap(copy);
    }

    public Object value(String column) {
        Object value = values.get(column);
        return value instanceof byte[] bytes ? bytes.clone() : value;
    }
}
