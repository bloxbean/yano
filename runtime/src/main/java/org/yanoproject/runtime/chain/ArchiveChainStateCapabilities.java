package org.yanoproject.runtime.chain;

import org.yanoproject.api.CanonicalBlockReference;
import org.yanoproject.api.ByronEpochBoundaryReference;

import java.util.Optional;
import java.util.OptionalLong;

/** Read-only chain-index capabilities required by asynchronous archive consumers. */
public interface ArchiveChainStateCapabilities {
    Optional<CanonicalBlockReference> getCanonicalBlockReference(long blockNumber);

    default Optional<ByronEpochBoundaryReference> getByronEpochBoundaryBlockAtOrBefore(long slot) {
        return Optional.empty();
    }

    OptionalLong getEarliestRetainedBodyBlockNumber();
}
