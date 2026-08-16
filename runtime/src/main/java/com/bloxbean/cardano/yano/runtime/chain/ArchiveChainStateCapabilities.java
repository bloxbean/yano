package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.ByronEpochBoundaryReference;

import java.util.Optional;
import java.util.OptionalLong;

/** Read-only chain-index capabilities required by asynchronous archive consumers. */
public interface ArchiveChainStateCapabilities {
    Optional<CanonicalBlockReference> getCanonicalBlockReference(long blockNumber);

    default Optional<ByronEpochBoundaryReference> getByronEpochBoundaryBlock(long slot) {
        return Optional.empty();
    }

    OptionalLong getEarliestRetainedBodyBlockNumber();
}
