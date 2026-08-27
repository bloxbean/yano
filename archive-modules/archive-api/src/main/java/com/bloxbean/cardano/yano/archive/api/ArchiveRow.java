package com.bloxbean.cardano.yano.archive.api;

import java.util.List;
import java.util.Objects;

/** Ordered values for one table schema; concrete dataset modules provide typed adapters. */
public record ArchiveRow(String table, List<Object> values) {
    public ArchiveRow {
        Objects.requireNonNull(table, "table");
        if (table.isBlank()) throw new IllegalArgumentException("table is required");
        values = copyValues(Objects.requireNonNull(values, "values"));
    }

    @Override
    public List<Object> values() {
        return copyValues(values);
    }

    private static List<Object> copyValues(List<Object> values) {
        return values.stream()
                .map(value -> value instanceof byte[] bytes ? bytes.clone() : value)
                .toList();
    }
}
