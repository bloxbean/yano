package com.bloxbean.cardano.yano.archive.api.projection;

import java.util.Objects;
import java.util.Optional;

/** Visible sink state; a degraded sink must be observable rather than merely slow. */
public record ProjectionSinkHealth(State state, Optional<String> lastFailure) {
    public enum State { READY, DEGRADED, UNAVAILABLE }

    public ProjectionSinkHealth {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(lastFailure, "lastFailure");
    }

    public static ProjectionSinkHealth ready() {
        return new ProjectionSinkHealth(State.READY, Optional.empty());
    }

    public static ProjectionSinkHealth unavailable(String reason) {
        return new ProjectionSinkHealth(State.UNAVAILABLE, Optional.of(reason));
    }
}
