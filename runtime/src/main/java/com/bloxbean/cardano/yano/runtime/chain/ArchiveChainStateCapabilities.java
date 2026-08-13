package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;

import java.util.Optional;
import java.util.OptionalLong;

/** Read-only chain-index capabilities required by asynchronous archive consumers. */
public interface ArchiveChainStateCapabilities {
    Optional<CanonicalBlockReference> getCanonicalBlockReference(long blockNumber);

    OptionalLong getEarliestRetainedBodyBlockNumber();
}
