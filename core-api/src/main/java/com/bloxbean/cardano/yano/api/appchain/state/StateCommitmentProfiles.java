package com.bloxbean.cardano.yano.api.appchain.state;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Closed ADR-025 v1 profile catalog. */
public final class StateCommitmentProfiles {
    public static final String MPF_BLAKE2B256_V1 = "mpf-blake2b256-v1";
    public static final String JMT_BLAKE2B256_V1 = "jmt-blake2b256-v1";
    public static final String JMT_POSEIDON_BLS12381_V1 = "jmt-poseidon-bls12381-v1";

    /** CCL has no named MPF descriptor yet; this is Yano's frozen normalization of the legacy format. */
    public static final StateCommitmentProfile MPF = new StateCommitmentProfile(
            StateCommitmentProfile.SCHEMA_VERSION,
            MPF_BLAKE2B256_V1,
            StateCommitmentProfile.BackendFamily.MPF,
            "ccl-mpf-legacy-blake2b256-v1",
            "ccl-mpf-proof-wire-v1",
            32,
            false,
            true);

    public static final StateCommitmentProfile CLASSIC_JMT = new StateCommitmentProfile(
            StateCommitmentProfile.SCHEMA_VERSION,
            JMT_BLAKE2B256_V1,
            StateCommitmentProfile.BackendFamily.JMT,
            "classic-radix16-blake2b256-v1",
            "ccl-classic-jmt-proof-cbor-v1",
            32,
            true,
            false);

    /** Declared consensus identity; runtime availability remains gated until Phase 4. */
    public static final StateCommitmentProfile POSEIDON_JMT = new StateCommitmentProfile(
            StateCommitmentProfile.SCHEMA_VERSION,
            JMT_POSEIDON_BLS12381_V1,
            StateCommitmentProfile.BackendFamily.JMT,
            "zeroj-poseidon-jmt-v1",
            "zeroj-poseidon-jmt-proof-v1",
            32,
            true,
            false);

    private static final List<StateCommitmentProfile> ALL =
            List.of(MPF, CLASSIC_JMT, POSEIDON_JMT);
    private static final Map<String, StateCommitmentProfile> BY_ID = Map.of(
            MPF.id(), MPF,
            CLASSIC_JMT.id(), CLASSIC_JMT,
            POSEIDON_JMT.id(), POSEIDON_JMT);

    private StateCommitmentProfiles() {
    }

    public static List<StateCommitmentProfile> all() {
        return ALL;
    }

    public static Optional<StateCommitmentProfile> find(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static StateCommitmentProfile require(String id) {
        return find(id).orElseThrow(() ->
                new IllegalArgumentException("Unsupported state commitment profile: " + id));
    }
}
