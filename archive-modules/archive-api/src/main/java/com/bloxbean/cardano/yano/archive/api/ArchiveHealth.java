package com.bloxbean.cardano.yano.archive.api;

import java.time.Instant;
import java.util.Objects;

/** Backend health is separate from core node health and never stops core sync. */
public record ArchiveHealth(Status status, String detail, Instant observedAt) {
    public enum Status { HEALTHY, DEGRADED, UNHEALTHY, CLOSED }

    public ArchiveHealth {
        Objects.requireNonNull(status, "status");
        detail = Objects.requireNonNullElse(detail, "");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    public static ArchiveHealth healthy() {
        return new ArchiveHealth(Status.HEALTHY, "", Instant.now());
    }
}
