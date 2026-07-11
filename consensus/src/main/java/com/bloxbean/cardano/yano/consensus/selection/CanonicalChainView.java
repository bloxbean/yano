package com.bloxbean.cardano.yano.consensus.selection;

import java.util.Optional;

/**
 * Minimal read-only view over canonical headers needed by selection policy.
 */
@FunctionalInterface
public interface CanonicalChainView {
    Optional<CanonicalHeaderPoint> findByHash(String blockHash);
}
