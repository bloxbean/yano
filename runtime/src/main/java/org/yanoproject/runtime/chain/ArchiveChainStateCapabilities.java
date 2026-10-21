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

    Long getBlockNumberBySlot(Long slot);

    /** The canonical reference whose slot is exactly {@code slot} (app-layer ADR-038, D8b rule 2). */
    default Optional<CanonicalBlockReference> getCanonicalBlockReferenceAtSlot(long slot) {
        Long blockNumber = getBlockNumberBySlot(slot);
        return blockNumber == null ? Optional.empty()
                : getCanonicalBlockReference(blockNumber).filter(reference -> reference.slot() == slot);
    }

    /**
     * The canonical mutation sequence (app-layer ADR-038, D8b rule 7): odd while a mutation that removes or replaces a
     * canonical index entry is in flight, even otherwise. Empty means unsupported, and callers must fail closed.
     */
    default OptionalLong canonicalMutationSequence() {
        return OptionalLong.empty();
    }
}
