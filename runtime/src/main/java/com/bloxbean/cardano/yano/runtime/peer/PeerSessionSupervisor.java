package com.bloxbean.cardano.yano.runtime.peer;

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
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
    private final BooleanSupplier recoveryDeferredSupplier;
    private final Executor recoveryExecutor;
    private final AtomicBoolean recoveryInProgress = new AtomicBoolean(false);

    private volatile ScheduledFuture<?> task;
    private volatile long nextRecoveryAllowedAtMillis = -1;
    private volatile int fastDisconnectRecoveriesUsed;
    private volatile long lastDisconnectRecoveryAtMillis = -1;

    public PeerSessionSupervisor(ScheduledExecutorService scheduler,
                                 Supplier<PeerSession> sessionSupplier,
                                 Consumer<PeerRecoveryReason> recoveryHandler,
                                 Policy policy) {
        this(scheduler, sessionSupplier, recoveryHandler, policy, System::currentTimeMillis, () -> false);
    }

    public PeerSessionSupervisor(ScheduledExecutorService scheduler,
                                 Supplier<PeerSession> sessionSupplier,
                                 Consumer<PeerRecoveryReason> recoveryHandler,
                                 Policy policy,
                                 BooleanSupplier recoveryDeferredSupplier) {
        this(scheduler, sessionSupplier, recoveryHandler, policy, System::currentTimeMillis, recoveryDeferredSupplier);
    }

    public PeerSessionSupervisor(ScheduledExecutorService scheduler,
                                 Supplier<PeerSession> sessionSupplier,
                                 Consumer<PeerRecoveryReason> recoveryHandler,
                                 Policy policy,
                                 BooleanSupplier recoveryDeferredSupplier,
                                 Executor recoveryExecutor) {
        this(scheduler, sessionSupplier, recoveryHandler, policy, System::currentTimeMillis,
                recoveryDeferredSupplier, recoveryExecutor);
    }

    PeerSessionSupervisor(ScheduledExecutorService scheduler,
                          Supplier<PeerSession> sessionSupplier,
                          Consumer<PeerRecoveryReason> recoveryHandler,
                          Policy policy,
                          LongSupplier nowSupplier) {
        this(scheduler, sessionSupplier, recoveryHandler, policy, nowSupplier, () -> false);
    }

    PeerSessionSupervisor(ScheduledExecutorService scheduler,
                          Supplier<PeerSession> sessionSupplier,
                          Consumer<PeerRecoveryReason> recoveryHandler,
                          Policy policy,
                          LongSupplier nowSupplier,
                          BooleanSupplier recoveryDeferredSupplier) {
        this(scheduler, sessionSupplier, recoveryHandler, policy, nowSupplier,
                () -> policy.recoveryJitterMillis() > 0
                        ? ThreadLocalRandom.current().nextLong(policy.recoveryJitterMillis() + 1)
                        : 0,
                recoveryDeferredSupplier,
                Runnable::run);
    }

    PeerSessionSupervisor(ScheduledExecutorService scheduler,
                          Supplier<PeerSession> sessionSupplier,
                          Consumer<PeerRecoveryReason> recoveryHandler,
                          Policy policy,
                          LongSupplier nowSupplier,
                          BooleanSupplier recoveryDeferredSupplier,
                          Executor recoveryExecutor) {
        this(scheduler, sessionSupplier, recoveryHandler, policy, nowSupplier,
                () -> policy.recoveryJitterMillis() > 0
                        ? ThreadLocalRandom.current().nextLong(policy.recoveryJitterMillis() + 1)
                        : 0,
                recoveryDeferredSupplier,
                recoveryExecutor);
    }

    PeerSessionSupervisor(ScheduledExecutorService scheduler,
                          Supplier<PeerSession> sessionSupplier,
                          Consumer<PeerRecoveryReason> recoveryHandler,
                          Policy policy,
                          LongSupplier nowSupplier,
                          LongSupplier recoveryJitterSupplier) {
        this(scheduler, sessionSupplier, recoveryHandler, policy, nowSupplier, recoveryJitterSupplier, () -> false);
    }

    PeerSessionSupervisor(ScheduledExecutorService scheduler,
                          Supplier<PeerSession> sessionSupplier,
                          Consumer<PeerRecoveryReason> recoveryHandler,
                          Policy policy,
                          LongSupplier nowSupplier,
                          LongSupplier recoveryJitterSupplier,
                          BooleanSupplier recoveryDeferredSupplier) {
        this(scheduler, sessionSupplier, recoveryHandler, policy, nowSupplier, recoveryJitterSupplier,
                recoveryDeferredSupplier, Runnable::run);
    }

    PeerSessionSupervisor(ScheduledExecutorService scheduler,
                          Supplier<PeerSession> sessionSupplier,
                          Consumer<PeerRecoveryReason> recoveryHandler,
                          Policy policy,
                          LongSupplier nowSupplier,
                          LongSupplier recoveryJitterSupplier,
                          BooleanSupplier recoveryDeferredSupplier,
                          Executor recoveryExecutor) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.recoveryHandler = Objects.requireNonNull(recoveryHandler, "recoveryHandler");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nowSupplier = Objects.requireNonNull(nowSupplier, "nowSupplier");
        this.recoveryJitterSupplier = Objects.requireNonNull(recoveryJitterSupplier, "recoveryJitterSupplier");
        this.recoveryDeferredSupplier = Objects.requireNonNull(recoveryDeferredSupplier, "recoveryDeferredSupplier");
        this.recoveryExecutor = Objects.requireNonNull(recoveryExecutor, "recoveryExecutor");
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

    public void notifyDisconnect() {
        try {
            PeerSession session = sessionSupplier.get();
            if (session == null) {
                return;
            }

            long now = nowSupplier.getAsLong();
            PeerSessionStatus status = session.getStatus();
            if (status.state() == PeerSessionState.STOPPED
                    || status.state() == PeerSessionState.STOPPING
                    || status.state() == PeerSessionState.RECOVERING) {
                return;
            }

            if (isCurrentDisconnectSignal(status) || !session.isRunning()) {
                requestRecovery(session, PeerRecoveryReason.DISCONNECT_STALE, now, true);
            }
        } catch (Throwable t) {
            log.warn("Peer session disconnect notification failed: {}", t.toString(), t);
        }
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

        if (isBodyFetchStuck(status, now)) {
            return Optional.of(PeerRecoveryReason.BODY_FETCH_STUCK);
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
        requestRecovery(session, reason, now, false);
    }

    private void requestRecovery(PeerSession session, PeerRecoveryReason reason, long now, boolean explicitDisconnect) {
        if (recoveryDeferredSupplier.getAsBoolean()) {
            log.debug("Peer session recovery deferred: reason={}", reason);
            return;
        }

        boolean fastDisconnectRecovery = explicitDisconnect
                && reason == PeerRecoveryReason.DISCONNECT_STALE
                && canUseFastDisconnectRecovery(now);

        if (!fastDisconnectRecovery && nextRecoveryAllowedAtMillis > 0 && now < nextRecoveryAllowedAtMillis) {
            return;
        }

        if (!recoveryInProgress.compareAndSet(false, true)) {
            return;
        }

        try {
            nextRecoveryAllowedAtMillis = now + nextCooldownMillis(
                    fastDisconnectRecovery,
                    explicitDisconnect && reason == PeerRecoveryReason.DISCONNECT_STALE,
                    now);
            session.getPeerHealth().recordRecoveryAttempt(reason);
            log.warn("Requesting peer session recovery: reason={}, status={}", reason, session.getStatus());
            recoveryExecutor.execute(() -> runRecoveryHandler(reason));
        } catch (RejectedExecutionException e) {
            recoveryInProgress.set(false);
            log.warn("Peer session recovery submission rejected: reason={}", reason, e);
        } catch (RuntimeException e) {
            recoveryInProgress.set(false);
            log.warn("Peer session recovery request failed before dispatch: reason={}", reason, e);
        }
    }

    private boolean canUseFastDisconnectRecovery(long now) {
        if (policy.fastDisconnectRecoveryAttempts() <= 0) {
            return false;
        }

        if (lastDisconnectRecoveryAtMillis > 0
                && ageSince(lastDisconnectRecoveryAtMillis, now) >= policy.fastDisconnectResetMillis()) {
            fastDisconnectRecoveriesUsed = 0;
        }

        return fastDisconnectRecoveriesUsed < policy.fastDisconnectRecoveryAttempts();
    }

    private long nextCooldownMillis(boolean fastDisconnectRecovery, boolean explicitDisconnect, long now) {
        if (explicitDisconnect) {
            lastDisconnectRecoveryAtMillis = now;
        }

        if (!fastDisconnectRecovery) {
            return normalRecoveryCooldownMillis();
        }

        fastDisconnectRecoveriesUsed++;
        if (fastDisconnectRecoveriesUsed >= policy.fastDisconnectRecoveryAttempts()) {
            return normalRecoveryCooldownMillis();
        }

        return policy.fastDisconnectRecoveryCooldownMillis();
    }

    private long normalRecoveryCooldownMillis() {
        return policy.recoveryCooldownMillis() + Math.max(0, recoveryJitterSupplier.getAsLong());
    }

    private void runRecoveryHandler(PeerRecoveryReason reason) {
        try {
            recoveryHandler.accept(reason);
        } catch (Throwable t) {
            log.warn("Peer session recovery handler failed: reason={}, error={}", reason, t.toString(), t);
        } finally {
            recoveryInProgress.set(false);
        }
    }

    private boolean isBodyFetchStuck(PeerSessionStatus status, long now) {
        if (!status.bodyFetchInProgress()) {
            return false;
        }

        long bodyFetchStartedAt = status.bodyFetchStartedAtMillis();
        if (bodyFetchStartedAt <= 0) {
            return false;
        }

        long lastBodyActivity = Math.max(bodyFetchStartedAt, status.lastBodyReceivedAtMillis());
        return ageSince(lastBodyActivity, now) >= policy.bodyFetchStuckTimeoutMillis();
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
            long bodyFetchStuckTimeoutMillis,
            long disconnectGraceMillis,
            long startupTimeoutMillis,
            long recoveryCooldownMillis,
            long recoveryJitterMillis,
            int fastDisconnectRecoveryAttempts,
            long fastDisconnectRecoveryCooldownMillis,
            long fastDisconnectResetMillis
    ) {
        public Policy(long checkIntervalMillis,
                      long noProgressTimeoutMillis,
                      long keepAliveTimeoutMillis,
                      long missingKeepAliveNoProgressTimeoutMillis,
                      long bodyFetchStuckTimeoutMillis,
                      long disconnectGraceMillis,
                      long startupTimeoutMillis,
                      long recoveryCooldownMillis,
                      long recoveryJitterMillis) {
            this(checkIntervalMillis,
                    noProgressTimeoutMillis,
                    keepAliveTimeoutMillis,
                    missingKeepAliveNoProgressTimeoutMillis,
                    bodyFetchStuckTimeoutMillis,
                    disconnectGraceMillis,
                    startupTimeoutMillis,
                    recoveryCooldownMillis,
                    recoveryJitterMillis,
                    2,
                    0,
                    TimeUnit.MINUTES.toMillis(5));
        }

        public Policy(long checkIntervalMillis,
                      long noProgressTimeoutMillis,
                      long keepAliveTimeoutMillis,
                      long missingKeepAliveNoProgressTimeoutMillis,
                      long disconnectGraceMillis,
                      long startupTimeoutMillis,
                      long recoveryCooldownMillis,
                      long recoveryJitterMillis) {
            this(checkIntervalMillis,
                    noProgressTimeoutMillis,
                    keepAliveTimeoutMillis,
                    missingKeepAliveNoProgressTimeoutMillis,
                    TimeUnit.MINUTES.toMillis(5),
                    disconnectGraceMillis,
                    startupTimeoutMillis,
                    recoveryCooldownMillis,
                    recoveryJitterMillis,
                    2,
                    0,
                    TimeUnit.MINUTES.toMillis(5));
        }

        public static Policy defaults() {
            return new Policy(
                    TimeUnit.SECONDS.toMillis(30),
                    TimeUnit.MINUTES.toMillis(10),
                    TimeUnit.MINUTES.toMillis(2),
                    TimeUnit.MINUTES.toMillis(10),
                    TimeUnit.MINUTES.toMillis(5),
                    TimeUnit.SECONDS.toMillis(30),
                    TimeUnit.SECONDS.toMillis(60),
                    TimeUnit.SECONDS.toMillis(30),
                    TimeUnit.SECONDS.toMillis(5),
                    2,
                    0,
                    TimeUnit.MINUTES.toMillis(5));
        }

        private void validate() {
            if (checkIntervalMillis <= 0
                    || noProgressTimeoutMillis <= 0
                    || keepAliveTimeoutMillis <= 0
                    || missingKeepAliveNoProgressTimeoutMillis <= 0
                    || bodyFetchStuckTimeoutMillis <= 0
                    || disconnectGraceMillis <= 0
                    || startupTimeoutMillis <= 0
                    || recoveryCooldownMillis <= 0
                    || recoveryJitterMillis < 0
                    || fastDisconnectRecoveryAttempts < 0
                    || fastDisconnectRecoveryCooldownMillis < 0
                    || fastDisconnectResetMillis <= 0) {
                throw new IllegalArgumentException("Peer supervisor policy durations must be positive; jitter must be non-negative");
            }
        }
    }
}
