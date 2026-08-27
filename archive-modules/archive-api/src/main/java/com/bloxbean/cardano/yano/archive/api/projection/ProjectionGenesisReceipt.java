package com.bloxbean.cardano.yano.archive.api.projection;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

/**
 * Durable proof that a projection archive captured its genesis distribution.
 *
 * <p>Genesis funds are produced by no block, so nothing in the block receipt log can say whether
 * they were captured. Without this marker an archive that never seeded genesis is
 * indistinguishable from one that did - which is exactly how a preprod projection archive came to
 * be missing an entire 30,000,000,000,000,000 lovelace Byron distribution while reporting itself
 * complete.
 *
 * <p>It is receipt and completion marker at once, written in the same sink transaction as the
 * rows it describes. That makes a crash between "rows committed" and "genesis recorded"
 * impossible rather than merely recoverable.
 *
 * @param identity     deterministic: network binding plus the normalised-row digest
 * @param rowDigest    digest of the exact distribution captured
 * @param rowCount     outputs written; zero is legitimate for an empty distribution
 * @param totalLovelace sum of the distribution, carried so a truncated capture is visible
 * @param committedAt  when the bootstrap committed
 */
public record ProjectionGenesisReceipt(String identity, String rowDigest, long rowCount,
                                       BigInteger totalLovelace, Instant committedAt) {

    public ProjectionGenesisReceipt {
        identity = Objects.requireNonNull(identity, "identity").trim().toLowerCase();
        rowDigest = Objects.requireNonNull(rowDigest, "rowDigest").trim().toLowerCase();
        Objects.requireNonNull(totalLovelace, "totalLovelace");
        Objects.requireNonNull(committedAt, "committedAt");
        if (identity.isEmpty() || rowDigest.isEmpty()) {
            throw new IllegalArgumentException("genesis identity and row digest are required");
        }
        if (rowCount < 0) throw new IllegalArgumentException("rowCount must not be negative");
        if (totalLovelace.signum() < 0) throw new IllegalArgumentException("negative genesis total");
    }

    /** Whether this receipt describes the same bootstrap the node is configured for. */
    public boolean matches(String expectedIdentity) {
        return identity.equals(expectedIdentity == null ? null : expectedIdentity.trim().toLowerCase());
    }
}
