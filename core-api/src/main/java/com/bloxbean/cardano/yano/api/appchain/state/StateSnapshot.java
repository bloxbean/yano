package com.bloxbean.cardano.yano.api.appchain.state;

import java.util.Objects;

/** Root and profile identity at one finalized application height. */
public record StateSnapshot(
        StateCommitmentIdentity identity,
        long height,
        byte[] stateRoot
) {
    public StateSnapshot {
        identity = Objects.requireNonNull(identity, "identity");
        if (height < 0) {
            throw new IllegalArgumentException("state snapshot height must be nonnegative");
        }
        stateRoot = Objects.requireNonNull(stateRoot, "stateRoot").clone();
        if (stateRoot.length != identity.profile().rootLength()) {
            throw new IllegalArgumentException("state snapshot root length differs from profile");
        }
    }

    @Override public byte[] stateRoot() { return stateRoot.clone(); }
}
