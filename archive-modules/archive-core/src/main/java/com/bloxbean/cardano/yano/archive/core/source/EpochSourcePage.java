package com.bloxbean.cardano.yano.archive.core.source;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record EpochSourcePage<T>(EpochArchiveJob job, List<T> rows, int requestedLimit,
                                 Optional<String> nextCursor) {
    public EpochSourcePage {
        Objects.requireNonNull(job, "job");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        if (requestedLimit < 1 || rows.size() > requestedLimit) {
            throw new IllegalArgumentException("epoch source page exceeds its requested bound");
        }
    }
}
