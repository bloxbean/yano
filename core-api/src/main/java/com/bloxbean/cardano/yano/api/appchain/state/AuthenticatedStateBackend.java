package com.bloxbean.cardano.yano.api.appchain.state;

import java.util.Optional;

/** Candidate-aware, profile-neutral authenticated-state backend (ADR-025). */
public interface AuthenticatedStateBackend extends AutoCloseable {
    StateCommitmentIdentity identity();

    default StateCommitmentProfile profile() {
        return identity().profile();
    }

    CandidateState beginCandidate(long baseHeight, byte[] baseRoot, long targetHeight);

    Optional<StateSnapshot> snapshot(long height);

    Optional<StateProof> prove(long height, byte[] canonicalKey);

    StateIntegrityReport verifyIntegrity();

    long oldestProvableHeight();

    @Override
    default void close() {
    }
}
