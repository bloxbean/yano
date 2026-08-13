package com.bloxbean.cardano.yano.archive.api;

import java.time.Duration;
import java.util.Objects;

/** Hard bounds for optional maintenance work. */
public record ArchiveMaintenanceBudget(Duration timeLimit, long maxBytesToRewrite) {
    public ArchiveMaintenanceBudget {
        Objects.requireNonNull(timeLimit, "timeLimit");
        if (timeLimit.isNegative() || timeLimit.isZero() || maxBytesToRewrite < 0) {
            throw new IllegalArgumentException("invalid archive maintenance budget");
        }
    }
}
