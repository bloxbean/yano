package com.bloxbean.cardano.yano.app.archive;

import java.util.Optional;

/**
 * Boundary facts an epoch row needs but the artifact reference does not carry.
 *
 * <p>Resolved from the same two canonical sources the projection uses - the block reference
 * for the hash, the ledger's slot clock for the time - so both paths write identical rows. The
 * sink cannot do this itself: it reaches artifacts only through the reader interface and has no
 * access to chain or ledger state.
 */
public interface ArtifactBoundaryFacts {

    /** Canonical hash of the boundary block, or empty if the chain cannot produce it. */
    Optional<byte[]> blockHash(long blockNumber);

    /** Wall-clock seconds for a slot. */
    long blockTimeSeconds(long slot);
}
