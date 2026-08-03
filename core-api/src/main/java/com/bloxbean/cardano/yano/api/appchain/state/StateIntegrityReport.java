package com.bloxbean.cardano.yano.api.appchain.state;

import java.util.Objects;

/** Bounded operational integrity result for one backend head. */
public record StateIntegrityReport(
        StateCommitmentIdentity identity,
        long height,
        byte[] stateRoot,
        boolean valid,
        String detail
) {
    public StateIntegrityReport {
        identity = Objects.requireNonNull(identity, "identity");
        if (height < 0) {
            throw new IllegalArgumentException("integrity height must be nonnegative");
        }
        stateRoot = Objects.requireNonNull(stateRoot, "stateRoot").clone();
        if (stateRoot.length != identity.profile().rootLength()) {
            throw new IllegalArgumentException("integrity root length differs from profile");
        }
        detail = Objects.requireNonNull(detail, "detail");
    }

    @Override public byte[] stateRoot() { return stateRoot.clone(); }
}
