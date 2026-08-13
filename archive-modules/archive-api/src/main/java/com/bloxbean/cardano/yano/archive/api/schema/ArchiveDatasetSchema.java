package com.bloxbean.cardano.yano.archive.api.schema;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.List;
import java.util.Objects;

public record ArchiveDatasetSchema(ArchiveDatasetId dataset, int projectionVersion,
                                   List<ArchiveTableSchema> tables,
                                   List<String> paginationOrder) {
    public ArchiveDatasetSchema {
        Objects.requireNonNull(dataset, "dataset");
        tables = List.copyOf(tables);
        paginationOrder = List.copyOf(paginationOrder);
        if (projectionVersion < 1 || tables.isEmpty() || paginationOrder.isEmpty()) {
            throw new IllegalArgumentException("incomplete dataset schema");
        }
    }
}
