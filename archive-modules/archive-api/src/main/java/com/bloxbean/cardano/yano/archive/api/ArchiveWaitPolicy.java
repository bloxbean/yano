package com.bloxbean.cardano.yano.archive.api;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Separates ordinary contention from a genuinely stuck archive operation.
 *
 * <p>Waiting for the single archive writer or for bounded DuckDB capacity is
 * normal flow control, not a failure. A short acquisition timeout cannot express
 * that: it either reports healthy queueing as a projection failure or hides a
 * hung native operation. This policy therefore carries two independent values.
 *
 * <p>{@code warnInterval} controls how often a still-waiting caller emits
 * structured diagnostics. {@code stuckThreshold} is the only point at which a
 * wait becomes an {@link ArchiveStuckOperationException}. A failed wait never
 * advances a cursor, receipt, or coverage record.
 */
public record ArchiveWaitPolicy(Duration warnInterval, Duration stuckThreshold) {
    public static final Duration DEFAULT_WARN_INTERVAL = Duration.ofSeconds(30);
    public static final Duration DEFAULT_STUCK_THRESHOLD = Duration.ofMinutes(5);

    public ArchiveWaitPolicy {
        Objects.requireNonNull(warnInterval, "warnInterval");
        Objects.requireNonNull(stuckThreshold, "stuckThreshold");
        if (warnInterval.isNegative() || warnInterval.isZero()
                || stuckThreshold.isNegative() || stuckThreshold.isZero()
                || stuckThreshold.compareTo(warnInterval) < 0) {
            throw new IllegalArgumentException(
                    "invalid archive wait policy: warnInterval and stuckThreshold must be positive "
                            + "and stuckThreshold must not be shorter than warnInterval");
        }
    }

    public static ArchiveWaitPolicy defaults() {
        return new ArchiveWaitPolicy(DEFAULT_WARN_INTERVAL, DEFAULT_STUCK_THRESHOLD);
    }

    /**
     * Single authoritative parser for the operator-facing wait properties.
     *
     * <p>Keeping parsing and validation here means one message for one mistake:
     * the rule was otherwise restated in each backend provider and again in the
     * service, so the same typo produced a different error depending on which
     * path ran first.
     */
    public static ArchiveWaitPolicy fromProperties(Map<String, String> properties,
                                                   ArchiveWaitPolicy fallback) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(fallback, "fallback");
        long warn = seconds(properties, "wait-warn-seconds", fallback.warnInterval().toSeconds());
        long stuck = seconds(properties, "stuck-operation-seconds", fallback.stuckThreshold().toSeconds());
        if (stuck < warn) {
            throw new IllegalArgumentException("stuck-operation-seconds=" + stuck
                    + " must not be shorter than wait-warn-seconds=" + warn
                    + "; the stuck threshold is when a wait fails, the warn interval only when it logs");
        }
        return new ArchiveWaitPolicy(Duration.ofSeconds(warn), Duration.ofSeconds(stuck));
    }

    private static long seconds(Map<String, String> properties, String name, long fallback) {
        String value = properties.get(name);
        if (value == null || value.isBlank()) return fallback;
        long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number of seconds, got: " + value, e);
        }
        if (parsed < 1) {
            throw new IllegalArgumentException(name + "=" + parsed + " must be a positive number of seconds");
        }
        return parsed;
    }

    /**
     * Bounded policy for a caller that must not wait for the full stuck
     * threshold.
     *
     * <p>This only ever shortens the wait. A limit longer than the configured
     * {@code stuckThreshold} must not extend it, or a request-facing bound would
     * silently override the operator's stuck setting.
     */
    public ArchiveWaitPolicy boundedTo(Duration limit) {
        Objects.requireNonNull(limit, "limit");
        if (limit.isNegative() || limit.isZero()) {
            throw new IllegalArgumentException("bounded wait limit must be positive");
        }
        Duration bound = limit.compareTo(stuckThreshold) < 0 ? limit : stuckThreshold;
        Duration warn = warnInterval.compareTo(bound) < 0 ? warnInterval : bound;
        return new ArchiveWaitPolicy(warn, bound);
    }
}
