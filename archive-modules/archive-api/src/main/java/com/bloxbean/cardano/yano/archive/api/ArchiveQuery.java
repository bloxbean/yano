package com.bloxbean.cardano.yano.archive.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Backend-neutral bounded query shape used by typed repository adapters. */
public record ArchiveQuery(ArchiveRange range, Map<String, Object> filters,
                           ArchivePageCursor.Order order, int limit,
                           Optional<ArchivePageCursor> cursor) {
    public ArchiveQuery {
        Objects.requireNonNull(range, "range");
        filters = Map.copyOf(Objects.requireNonNull(filters, "filters"));
        Objects.requireNonNull(order, "order");
        cursor = Objects.requireNonNull(cursor, "cursor");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
    }
}
