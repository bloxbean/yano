package com.bloxbean.cardano.yano.api;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Read-only chain block access for components that need replay/reconciliation
 * without receiving the mutable chain-state implementation.
 */
public interface ChainBlockReader {
    ChainTip getLocalTip();

    byte[] getBlockByNumber(long blockNumber);

    Era getBlockEra(long blockNumber);

    /**
     * Returns the canonical coordinate for a block number without decoding the
     * block body. Implementations return {@link Optional#empty()} when the
     * number is not on the current canonical chain.
     */
    default Optional<CanonicalBlockReference> getCanonicalBlockReference(long blockNumber) {
        return Optional.empty();
    }

    /**
     * Latest canonical Byron epoch-boundary block at or before {@code slot}.
     * A main block can follow an epoch boundary several empty Byron slots later,
     * while still naming that boundary block as its direct parent.
     */
    default Optional<ByronEpochBoundaryReference> getByronEpochBoundaryBlockAtOrBefore(long slot) {
        return Optional.empty();
    }

    /**
     * Lowest canonical block number whose body is currently retained.
     * Empty means that no body is retained. This is a capability value, not a
     * promise that a future pruning pass will keep the body indefinitely.
     */
    default OptionalLong getEarliestRetainedBodyBlockNumber() {
        return OptionalLong.empty();
    }
}
