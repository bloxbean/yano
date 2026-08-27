package com.bloxbean.cardano.yano.archive.api.schema;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ArchiveTableSchema(String physicalName, List<ArchiveColumn> columns,
                                 List<String> primaryKey) {
    public ArchiveTableSchema {
        Objects.requireNonNull(physicalName, "physicalName");
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
        var names = new HashSet<String>();
        for (ArchiveColumn column : columns) {
            if (!names.add(column.name())) throw new IllegalArgumentException("duplicate column " + column.name());
        }
        if (primaryKey.isEmpty() || !names.containsAll(primaryKey)) {
            throw new IllegalArgumentException("primary key must reference table columns");
        }
    }
}
