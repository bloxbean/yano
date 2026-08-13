package com.bloxbean.cardano.yano.archive.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Typed query page with explicit coverage rather than silent partial results. */
public record ArchiveQueryResult<T>(List<T> rows, ArchiveCoverage coverage,
                                    boolean complete, Optional<ArchivePageCursor> nextCursor) {
    public ArchiveQueryResult {
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        Objects.requireNonNull(coverage, "coverage");
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        if (!complete && nextCursor.isPresent()) {
            throw new IllegalArgumentException("an incomplete page cannot advertise a continuation cursor");
        }
    }
}
