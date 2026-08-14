package com.bloxbean.cardano.yano.api.appchain.state;

import java.util.Map;
import java.util.Optional;

/** Release metadata kept deliberately separate from consensus profile identities. */
public final class StateCommitmentImplementations {
    private static final Map<String, StateCommitmentImplementation> BY_PROFILE = Map.of(
            StateCommitmentProfiles.MPF_BLAKE2B256_V1,
            new StateCommitmentImplementation(
                    StateCommitmentProfiles.MPF_BLAKE2B256_V1,
                    "Cardano Client Lib MPF compatible",
                    java.util.List.of("cardano-client-lib"), true,
                    StateCommitmentImplementation.VerificationTarget.OFF_CHAIN_AND_ON_CHAIN),
            StateCommitmentProfiles.JMT_BLAKE2B256_V1,
            new StateCommitmentImplementation(
                    StateCommitmentProfiles.JMT_BLAKE2B256_V1,
                    "Cardano Client Lib classic JMT compatible",
                    java.util.List.of("cardano-client-lib"), true,
                    StateCommitmentImplementation.VerificationTarget.OFF_CHAIN_ONLY),
            StateCommitmentProfiles.JMT_POSEIDON_BLS12381_V1,
            new StateCommitmentImplementation(
                    StateCommitmentProfiles.JMT_POSEIDON_BLS12381_V1,
                    "ZeroJ Poseidon JMT compatible",
                    java.util.List.of("zeroj"), false,
                    StateCommitmentImplementation.VerificationTarget.UNAVAILABLE));

    private StateCommitmentImplementations() {
    }

    public static Optional<StateCommitmentImplementation> find(String profileId) {
        return Optional.ofNullable(BY_PROFILE.get(profileId));
    }

    public static StateCommitmentImplementation require(String profileId) {
        return find(profileId).orElseThrow(() ->
                new IllegalArgumentException("Unsupported state commitment profile: " + profileId));
    }
}
