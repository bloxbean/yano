package com.bloxbean.cardano.yano.archive.api;

import java.util.List;
import java.util.Objects;

/** Ordered values for one table schema; concrete dataset modules provide typed adapters. */
public record ArchiveRow(String table, List<Object> values) {
    public ArchiveRow {
        Objects.requireNonNull(table, "table");
        values = List.copyOf(values);
    }
}
