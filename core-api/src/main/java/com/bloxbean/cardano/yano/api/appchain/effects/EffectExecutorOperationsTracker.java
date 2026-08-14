package com.bloxbean.cardano.yano.api.appchain.effects;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Small thread-safe helper for first-party executors implementing
 * {@link AppEffectExecutor#operationalSnapshot()}.
 */
public final class EffectExecutorOperationsTracker {
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong retryableFailures = new AtomicLong();
    private final AtomicLong terminalFailures = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong lastSuccessNanos = new AtomicLong();
    private final AtomicLong lastFailureNanos = new AtomicLong();
    private final AtomicReference<EffectExecutorOperationalSnapshot.Readiness> readiness =
            new AtomicReference<>(EffectExecutorOperationalSnapshot.Readiness.UNKNOWN);
    private final AtomicReference<EffectExecutorOperationalSnapshot.FailureCode> failureCode =
            new AtomicReference<>(EffectExecutorOperationalSnapshot.FailureCode.NONE);

    /** Executes and records one complete executor callback. */
    public EffectExecution observe(Supplier<EffectExecution> callback) {
        Objects.requireNonNull(callback, "callback");
        try {
            return observeChecked(callback::get);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception impossible) {
            throw new IllegalStateException("supplier raised a checked exception", impossible);
        }
    }

    /** Executes and records one callback that may propagate a checked failure. */
    public EffectExecution observeChecked(CheckedExecution callback) throws Exception {
        Objects.requireNonNull(callback, "callback");
        attempts.incrementAndGet();
        inFlight.incrementAndGet();
        try {
            EffectExecution outcome = Objects.requireNonNull(
                    callback.execute(), "effect execution outcome");
            record(outcome);
            return outcome;
        } catch (Exception failure) {
            recordThrownFailure();
            throw failure;
        } catch (Error failure) {
            recordThrownFailure();
            throw failure;
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void recordThrownFailure() {
        retryableFailures.incrementAndGet();
        lastFailureNanos.set(System.nanoTime());
        readiness.set(EffectExecutorOperationalSnapshot.Readiness.DEGRADED);
        failureCode.set(EffectExecutorOperationalSnapshot.FailureCode.INTERNAL);
    }

    /** Returns a memory-only immutable observation. */
    public EffectExecutorOperationalSnapshot snapshot() {
        long now = System.nanoTime();
        return new EffectExecutorOperationalSnapshot(
                readiness.get(),
                attempts.get(),
                successes.get(),
                retryableFailures.get(),
                terminalFailures.get(),
                inFlight.get(),
                age(now, lastSuccessNanos.get()),
                age(now, lastFailureNanos.get()),
                failureCode.get());
    }

    private void record(EffectExecution outcome) {
        switch (outcome) {
            case EffectExecution.Confirmed ignored -> succeeded();
            case EffectExecution.Submitted ignored -> succeeded();
            case EffectExecution.Retry ignored -> failed(true,
                    EffectExecutorOperationalSnapshot.FailureCode.BUSY);
            case EffectExecution.Failed failed -> failed(
                    failed.retryable(), classify(failed.reason()));
        }
    }

    private void succeeded() {
        successes.incrementAndGet();
        lastSuccessNanos.set(System.nanoTime());
        readiness.set(EffectExecutorOperationalSnapshot.Readiness.READY);
        failureCode.set(EffectExecutorOperationalSnapshot.FailureCode.NONE);
    }

    private void failed(boolean retryable,
                        EffectExecutorOperationalSnapshot.FailureCode code) {
        if (retryable) {
            retryableFailures.incrementAndGet();
        } else {
            terminalFailures.incrementAndGet();
        }
        lastFailureNanos.set(System.nanoTime());
        failureCode.set(code);
        readiness.set(switch (code) {
            case AUTHENTICATION, CONFIGURATION, SHUTDOWN ->
                    EffectExecutorOperationalSnapshot.Readiness.UNAVAILABLE;
            default -> EffectExecutorOperationalSnapshot.Readiness.DEGRADED;
        });
    }

    private static EffectExecutorOperationalSnapshot.FailureCode classify(String reason) {
        if (reason == null) {
            return EffectExecutorOperationalSnapshot.FailureCode.UNKNOWN;
        }
        return switch (reason) {
            case "INVALID_PAYLOAD", "UNSUPPORTED_VERSION" ->
                    EffectExecutorOperationalSnapshot.FailureCode.INVALID_REQUEST;
            case "UNKNOWN_TARGET", "TARGET_DISABLED", "TARGET_CHANGED" ->
                    EffectExecutorOperationalSnapshot.FailureCode.CONFIGURATION;
            case "AUTH_UNAVAILABLE" ->
                    EffectExecutorOperationalSnapshot.FailureCode.AUTHENTICATION;
            case "POLICY_DENIED" -> EffectExecutorOperationalSnapshot.FailureCode.POLICY;
            case "RATE_LIMITED" -> EffectExecutorOperationalSnapshot.FailureCode.RATE_LIMITED;
            case "SERVICE_UNAVAILABLE", "SOURCE_UNAVAILABLE" ->
                    EffectExecutorOperationalSnapshot.FailureCode.SERVICE_UNAVAILABLE;
            case "ACK_UNKNOWN" ->
                    EffectExecutorOperationalSnapshot.FailureCode.ACKNOWLEDGEMENT_UNKNOWN;
            case "CONTENT_UNAVAILABLE", "CONTENT_NOT_FOUND", "SOURCE_MISMATCH" ->
                    EffectExecutorOperationalSnapshot.FailureCode.CONTENT_UNAVAILABLE;
            case "DESTINATION_CONFLICT" -> EffectExecutorOperationalSnapshot.FailureCode.CONFLICT;
            case "PROVIDER_REJECTED" ->
                    EffectExecutorOperationalSnapshot.FailureCode.PROVIDER_REJECTED;
            case "DETAIL_ARCHIVE_FAILED" ->
                    EffectExecutorOperationalSnapshot.FailureCode.ARCHIVE_UNAVAILABLE;
            case "SHUTDOWN" -> EffectExecutorOperationalSnapshot.FailureCode.SHUTDOWN;
            case "INTERNAL_ERROR" -> EffectExecutorOperationalSnapshot.FailureCode.INTERNAL;
            default -> EffectExecutorOperationalSnapshot.FailureCode.UNKNOWN;
        };
    }

    private static EffectExecutorOperationalSnapshot.AgeBucket age(long now, long event) {
        if (event == 0) {
            return EffectExecutorOperationalSnapshot.AgeBucket.NEVER;
        }
        long elapsed = Math.max(0, now - event);
        if (elapsed < Duration.ofMinutes(1).toNanos()) {
            return EffectExecutorOperationalSnapshot.AgeBucket.LESS_THAN_ONE_MINUTE;
        }
        if (elapsed < Duration.ofMinutes(5).toNanos()) {
            return EffectExecutorOperationalSnapshot.AgeBucket.LESS_THAN_FIVE_MINUTES;
        }
        if (elapsed < Duration.ofMinutes(15).toNanos()) {
            return EffectExecutorOperationalSnapshot.AgeBucket.LESS_THAN_FIFTEEN_MINUTES;
        }
        if (elapsed < Duration.ofHours(1).toNanos()) {
            return EffectExecutorOperationalSnapshot.AgeBucket.LESS_THAN_ONE_HOUR;
        }
        if (elapsed < Duration.ofHours(6).toNanos()) {
            return EffectExecutorOperationalSnapshot.AgeBucket.LESS_THAN_SIX_HOURS;
        }
        return EffectExecutorOperationalSnapshot.AgeBucket.SIX_HOURS_OR_MORE;
    }

    /** Checked execution callback used by executors whose SPI method throws. */
    @FunctionalInterface
    public interface CheckedExecution {
        EffectExecution execute() throws Exception;
    }
}
