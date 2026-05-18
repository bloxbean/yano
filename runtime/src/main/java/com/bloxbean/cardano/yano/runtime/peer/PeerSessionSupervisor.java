package com.bloxbean.cardano.yano.runtime.peer;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Supervises the active upstream peer session and requests replacement when it
 * becomes stale.
 */
@Slf4j
public final class PeerSessionSupervisor implements AutoCloseable {
    private final ScheduledExecutorService scheduler;
    private final Supplier<PeerSession> sessionSupplier;
    private final Consumer<PeerRecoveryReason> recoveryHandler;
    private final Policy policy;
    private final LongSupplier nowSupplier;
    private final LongSupplier recoveryJitterSupplier;
    private final AtomicBoolean recoveryInProgress = new AtomicBoolean(false);

    private volatile ScheduledFuture<?> task;
    private volatile long nextRecoveryAllowedAtMillis = -1;

    public PeerSessionSupervisor(ScheduledExecutorService scheduler,
                                 Supplier<PeerSession> sessionSupplier,
                                 Consumer<PeerRecoveryReason> recoveryHandler,
                                 Policy policy) {
        this(scheduler, sessionSupplier, recoveryHandler, policy, System::currentTimeMillis);
    }

    PeerSessionSupervisor(ScheduledExecutorService scheduler,
                          Supplier<PeerSession> sessionSupplier,
                          Consumer<PeerRecoveryReason> recoveryHandler,
                          Policy policy,
                          LongSupplier nowSupplier) {
        this(scheduler, sessionSupplier, recoveryHandler, policy, nowSupplier,
                () -> policy.recoveryJitterMillis() > 0
                        ? ThreadLocalRandom.current().nextLong(policy.recoveryJitterMillis() + 1)
                        : 0);
    }

    PeerSessionSupervisor(ScheduledExecutorService scheduler,
                          Supplier<PeerSession> sessionSupplier,
                          Consumer<PeerRecoveryReason> recoveryHandler,
                          Policy policy,
                          LongSupplier nowSupplier,
                          LongSupplier recoveryJitterSupplier) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.recoveryHandler = Objects.requireNonNull(recoveryHandler, "recoveryHandler");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nowSupplier = Objects.requireNonNull(nowSupplier, "nowSupplier");
        this.recoveryJitterSupplier = Objects.requireNonNull(recoveryJitterSupplier, "recoveryJitterSupplier");
        this.policy.validate();
    }

    public synchronized void start() {
        if (task != null && !task.isCancelled() && !task.isDone()) {
            return;
        }

        task = scheduler.scheduleWithFixedDelay(
                this::checkSafely,
                policy.checkIntervalMillis(),
                policy.checkIntervalMillis(),
                TimeUnit.MILLISECONDS);
        log.info("Peer session supervisor started: {}", policy);
    }

    public void checkNow() {
        checkSafely();
    }

    @Override
    public synchronized void close() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    private void checkSafely() {
        try {
            checkAndRecover();
        } catch (Throwable t) {
            log.warn("Peer session supervisor check failed: {}", t.toString(), t);
        }
    }

    private void checkAndRecover() {
        PeerSession session = sessionSupplier.get();
        if (session == null) {
            return;
        }

        long now = nowSupplier.getAsLong();
        PeerSessionStatus status = session.getStatus();
        Optional<PeerRecoveryReason> reason = evaluate(session, status, now);
        reason.ifPresent(recoveryReason -> requestRecovery(session, recoveryReason, now));
    }

    private Optional<PeerRecoveryReason> evaluate(PeerSession session, PeerSessionStatus status, long now) {
        if (status.state() == PeerSessionState.STOPPED
                || status.state() == PeerSessionState.STOPPING
                || status.state() == PeerSessionState.RECOVERING) {
            return Optional.empty();
        }

        if (status.state() == PeerSessionState.STARTING) {
            return ageSinceCreated(status, now) >= policy.startupTimeoutMillis()
                    ? Optional.of(PeerRecoveryReason.STARTUP_FAILED)
                    : Optional.empty();
        }

        if (status.state() == PeerSessionState.TERMINAL_FAILURE) {
            return Optional.of(status.lastRecoveryReason() != null
                    ? status.lastRecoveryReason()
                    : PeerRecoveryReason.TERMINAL_FAILURE);
        }

        long appProgressAge = applicationProgressAge(status, now);

        if (isCurrentDisconnectSignal(status)) {
            return ageSince(status.lastDisconnectAtMillis(), now) >= policy.disconnectGraceMillis()
                    ? Optional.of(PeerRecoveryReason.DISCONNECT_STALE)
                    : Optional.empty();
        }

        if (!session.isRunning()) {
            return appProgressAge >= policy.disconnectGraceMillis()
                    ? Optional.of(PeerRecoveryReason.DISCONNECT_STALE)
                    : Optional.empty();
        }

        if (appProgressAge < policy.noProgressTimeoutMillis()) {
            return Optional.empty();
        }

        if (status.keepAliveAgeMillis() >= policy.keepAliveTimeoutMillis()) {
            return Optional.of(PeerRecoveryReason.KEEPALIVE_STALE);
        }

        if (status.keepAliveAgeMillis() < 0
                && appProgressAge >= policy.missingKeepAliveNoProgressTimeoutMillis()) {
            return Optional.of(PeerRecoveryReason.NO_PROGRESS);
        }

        return Optional.of(PeerRecoveryReason.NO_PROGRESS);
    }

    private void requestRecovery(PeerSession session, PeerRecoveryReason reason, long now) {
        if (nextRecoveryAllowedAtMillis > 0 && now < nextRecoveryAllowedAtMillis) {
            return;
        }

        if (!recoveryInProgress.compareAndSet(false, true)) {
            return;
        }

        try {
            nextRecoveryAllowedAtMillis = now + policy.recoveryCooldownMillis()
                    + Math.max(0, recoveryJitterSupplier.getAsLong());
            session.getPeerHealth().recordRecoveryAttempt(reason);
            log.warn("Requesting peer session recovery: reason={}, status={}", reason, session.getStatus());
            recoveryHandler.accept(reason);
        } finally {
            recoveryInProgress.set(false);
        }
    }

    private long applicationProgressAge(PeerSessionStatus status, long now) {
        if (status.applicationProgressAgeMillis() >= 0) {
            return status.applicationProgressAgeMillis();
        }
        return ageSinceCreated(status, now);
    }

    private boolean isCurrentDisconnectSignal(PeerSessionStatus status) {
        if (status.lastDisconnectAtMillis() <= 0) {
            return false;
        }

        long lastActivity = Math.max(status.lastKeepAliveResponseAtMillis(),
                Math.max(status.lastHeaderReceivedAtMillis(),
                        Math.max(status.lastBodyReceivedAtMillis(), status.lastBodyAppliedAtMillis())));
        return status.lastDisconnectAtMillis() >= lastActivity;
    }

    private long ageSinceCreated(PeerSessionStatus status, long now) {
        return ageSince(status.createdAtMillis(), now);
    }

    private long ageSince(long timestampMillis, long now) {
        if (timestampMillis <= 0) {
            return 0;
        }
        return Math.max(0, now - timestampMillis);
    }

    public record Policy(
            long checkIntervalMillis,
            long noProgressTimeoutMillis,
            long keepAliveTimeoutMillis,
            long missingKeepAliveNoProgressTimeoutMillis,
            long disconnectGraceMillis,
            long startupTimeoutMillis,
            long recoveryCooldownMillis,
            long recoveryJitterMillis
    ) {
        public static Policy defaults() {
            return new Policy(
                    TimeUnit.SECONDS.toMillis(30),
                    TimeUnit.MINUTES.toMillis(10),
                    TimeUnit.MINUTES.toMillis(2),
                    TimeUnit.MINUTES.toMillis(10),
                    TimeUnit.SECONDS.toMillis(30),
                    TimeUnit.SECONDS.toMillis(60),
                    TimeUnit.SECONDS.toMillis(30),
                    TimeUnit.SECONDS.toMillis(5));
        }

        private void validate() {
            if (checkIntervalMillis <= 0
                    || noProgressTimeoutMillis <= 0
                    || keepAliveTimeoutMillis <= 0
                    || missingKeepAliveNoProgressTimeoutMillis <= 0
                    || disconnectGraceMillis <= 0
                    || startupTimeoutMillis <= 0
                    || recoveryCooldownMillis <= 0
                    || recoveryJitterMillis < 0) {
                throw new IllegalArgumentException("Peer supervisor policy durations must be positive; jitter must be non-negative");
            }
        }
    }
}
