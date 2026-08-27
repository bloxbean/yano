package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveRow;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * The genesis distribution, materialised into rows, ready for one atomic sink commit.
 *
 * @param identity      deterministic identity binding network configuration and row digest
 * @param rowDigest     digest of the normalised distribution these rows came from
 * @param totalLovelace sum of the distribution
 * @param rows          already-materialised archive rows, built by the shared output row builder
 */
public record ProjectionGenesisBatch(ProjectionIdentity projectionIdentity, String identity,
                                     String rowDigest, BigInteger totalLovelace,
                                     List<ArchiveRow> rows) {

    public ProjectionGenesisBatch {
        Objects.requireNonNull(projectionIdentity, "projectionIdentity");
        identity = Objects.requireNonNull(identity, "identity").trim().toLowerCase();
        rowDigest = Objects.requireNonNull(rowDigest, "rowDigest").trim().toLowerCase();
        Objects.requireNonNull(totalLovelace, "totalLovelace");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        if (identity.isEmpty() || rowDigest.isEmpty()) {
            throw new IllegalArgumentException("genesis identity and row digest are required");
        }
    }

    /** An empty distribution is legitimate and still gets a receipt. */
    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
