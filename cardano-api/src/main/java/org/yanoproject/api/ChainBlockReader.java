package org.yanoproject.api;

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

    /**
     * Canonical coordinate of the block at {@code slot}: the block number for the slot, then the canonical reference
     * for that number, which must carry the same slot. Empty when no canonical block has that slot or the capability
     * is unsupported.
     */
    default Optional<CanonicalBlockReference> getCanonicalBlockReferenceAtSlot(long slot) {
        return Optional.empty();
    }

    /**
     * Sequence lock over canonical index mutations (rollback, header replacement, restore): odd while one is in
     * flight, even otherwise. A reader that observes the same even value before and after a pass knows that no such
     * mutation overlapped it. Empty means unsupported; callers that depend on it must fail closed.
     */
    default OptionalLong canonicalMutationSequence() {
        return OptionalLong.empty();
    }

    /**
     * Slot of the oldest block in this node's canonical index, or empty when unknown. A point older than it cannot
     * be judged here: its history was never indexed or was restored away.
     */
    default OptionalLong getEarliestIndexedSlot() {
        return OptionalLong.empty();
    }
}
