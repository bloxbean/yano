package com.bloxbean.cardano.yano.archive.api;

import java.util.List;
import java.util.Objects;

/** Value cursor bound to the exact query and archive coverage revision. */
public record ArchivePageCursor(ArchiveDatasetId dataset, int projectionVersion,
                                String filterDigest, Order order, long archiveBoundary,
                                long coverageRevision, List<String> lastOrderingValues) {
    public enum Order { ASC, DESC }

    public ArchivePageCursor {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(filterDigest, "filterDigest");
        Objects.requireNonNull(order, "order");
        lastOrderingValues = List.copyOf(Objects.requireNonNull(lastOrderingValues, "lastOrderingValues"));
        if (projectionVersion < 1 || archiveBoundary < 0 || coverageRevision < 0 || lastOrderingValues.isEmpty()) {
            throw new IllegalArgumentException("invalid archive page cursor");
        }
    }
}
