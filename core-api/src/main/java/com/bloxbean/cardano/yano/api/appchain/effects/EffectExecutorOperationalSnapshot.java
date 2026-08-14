package com.bloxbean.cardano.yano.api.appchain.effects;

import java.util.Objects;

/**
 * Immutable, bounded, secret-free operational view of one effect executor.
 *
 * <p>The values are node-local observations and never enter consensus. The
 * snapshot intentionally has no labels, endpoints, exception messages,
 * extension map, client reference, or mutation method.</p>
 */
public record EffectExecutorOperationalSnapshot(
        Readiness readiness,
        long attempts,
        long successes,
        long retryableFailures,
        long terminalFailures,
        int inFlight,
        AgeBucket lastSuccessAge,
        AgeBucket lastFailureAge,
        FailureCode failureCode) {

    /** Bounded connector readiness. */
    public enum Readiness {
        READY,
        DEGRADED,
        UNAVAILABLE,
        UNKNOWN
    }

    /** Monotonic age normalized before crossing the plugin boundary. */
    public enum AgeBucket {
        NEVER,
        LESS_THAN_ONE_MINUTE,
        LESS_THAN_FIVE_MINUTES,
        LESS_THAN_FIFTEEN_MINUTES,
        LESS_THAN_ONE_HOUR,
        LESS_THAN_SIX_HOURS,
        SIX_HOURS_OR_MORE,
        UNKNOWN
    }

    /** Framework-defined failure classes; arbitrary plugin text is excluded. */
    public enum FailureCode {
        NONE,
        INVALID_REQUEST,
        CONFIGURATION,
        AUTHENTICATION,
        POLICY,
        RATE_LIMITED,
        SERVICE_UNAVAILABLE,
        ACKNOWLEDGEMENT_UNKNOWN,
        CONTENT_UNAVAILABLE,
        CONFLICT,
        PROVIDER_REJECTED,
        ARCHIVE_UNAVAILABLE,
        SHUTDOWN,
        INTERNAL,
        BUSY,
        CALLBACK_FAILED,
        CALLBACK_INVALID,
        CALLBACK_TIMEOUT,
        STALE,
        UNKNOWN
    }

    public EffectExecutorOperationalSnapshot {
        Objects.requireNonNull(readiness, "readiness");
        Objects.requireNonNull(lastSuccessAge, "lastSuccessAge");
        Objects.requireNonNull(lastFailureAge, "lastFailureAge");
        Objects.requireNonNull(failureCode, "failureCode");
        if (attempts < 0 || successes < 0 || retryableFailures < 0
                || terminalFailures < 0 || inFlight < 0
                || exceedsAttempts(attempts, successes, retryableFailures, terminalFailures)) {
            throw new IllegalArgumentException("executor operational counters are invalid");
        }
    }

    /** Default for legacy executors and products with no observation yet. */
    public static EffectExecutorOperationalSnapshot unknown() {
        return new EffectExecutorOperationalSnapshot(
                Readiness.UNKNOWN, 0, 0, 0, 0, 0,
                AgeBucket.NEVER, AgeBucket.NEVER, FailureCode.NONE);
    }

    private static boolean exceedsAttempts(long attempts, long first, long second, long third) {
        if (first > attempts || second > attempts || third > attempts) {
            return true;
        }
        return first > attempts - second || first + second > attempts - third;
    }
}
