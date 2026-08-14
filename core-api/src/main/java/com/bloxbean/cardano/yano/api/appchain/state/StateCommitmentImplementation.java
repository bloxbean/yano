package com.bloxbean.cardano.yano.api.appchain.state;

import java.util.List;
import java.util.Objects;

/**
 * Non-consensus implementation/conformance information for a commitment profile.
 * None of these fields participates in chain genesis or the format fingerprint.
 */
public record StateCommitmentImplementation(
        String profileId,
        String compatibility,
        List<String> testedImplementations,
        boolean verifierAvailable,
        VerificationTarget verificationTarget
) {
    public StateCommitmentImplementation {
        profileId = Objects.requireNonNull(profileId, "profileId");
        compatibility = Objects.requireNonNull(compatibility, "compatibility");
        testedImplementations = List.copyOf(testedImplementations);
        verificationTarget = Objects.requireNonNull(verificationTarget, "verificationTarget");
    }

    public enum VerificationTarget {
        OFF_CHAIN_AND_ON_CHAIN,
        OFF_CHAIN_ONLY,
        UNAVAILABLE
    }
}
