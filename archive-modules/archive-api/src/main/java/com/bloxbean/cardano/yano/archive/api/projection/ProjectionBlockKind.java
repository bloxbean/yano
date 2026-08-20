package com.bloxbean.cardano.yano.archive.api.projection;

/**
 * Canonical block shape behind an envelope.
 *
 * <p>Byron is a strict subset of the Shelley+ projection, not a degraded version of
 * it: no invalid transactions, collateral, reference inputs, datums, redeemers,
 * multi-asset values, or pointer addresses, a null {@code chain_transaction.fee},
 * and base58 raw addresses. Epoch-boundary blocks carry no transactions at all but
 * still occupy a block number, so they emit an empty envelope to keep the projection
 * coordinate contiguous (ADR-039 §3, §16).
 */
public enum ProjectionBlockKind {
    SHELLEY_PLUS(false),
    BYRON_MAIN(true),
    BYRON_EBB(true);

    private final boolean byron;

    ProjectionBlockKind(boolean byron) {
        this.byron = byron;
    }

    public boolean isByron() {
        return byron;
    }

    /** An EBB never carries a section; a required-section policy must not demand one. */
    public boolean allowsEmptyEnvelope() {
        return this == BYRON_EBB;
    }
}
