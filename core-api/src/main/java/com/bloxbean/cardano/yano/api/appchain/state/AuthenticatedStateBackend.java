package com.bloxbean.cardano.yano.api.appchain.state;

import java.util.Optional;

/** Candidate-aware, profile-neutral authenticated-state backend (ADR-025). */
public interface AuthenticatedStateBackend extends AutoCloseable {
    StateCommitmentIdentity identity();

    default StateCommitmentProfile profile() {
        return identity().profile();
    }

    CandidateState beginCandidate(long baseHeight, byte[] baseRoot, long targetHeight);

    /** Logical value at an exact retained height (tombstones read as absent). */
    Optional<byte[]> get(long height, byte[] canonicalKey);

    Optional<StateSnapshot> snapshot(long height);

    Optional<StateProof> prove(long height, byte[] canonicalKey);

    StateIntegrityReport verifyIntegrity();

    long oldestProvableHeight();

    /**
     * Prune proof history strictly below {@code retainFromHeight}. The retained
     * height and every newer height remain queryable. Backends without physical
     * pruning may reject the operation.
     *
     * @return number of backend records removed
     */
    default int pruneBefore(long retainFromHeight) {
        throw new UnsupportedOperationException(
                "authenticated-state backend does not support pruning");
    }

    @Override
    default void close() {
    }
}
