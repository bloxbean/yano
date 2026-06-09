package com.bloxbean.cardano.yano.runtime.peer;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerSessionSupervisorTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong now = new AtomicLong(10_000);

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void doesNotRecoverWhileProgressIsFresh() {
        PeerHealth health = runningHealth();
        health.recordHeaderProgress(100, 10, now.get() - 100);
        health.recordKeepAliveResponse(now.get() - 100);
        TestPeerSession session = new TestPeerSession(health, now);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();

        supervisor(session, recoveries).checkNow();

        assertTrue(recoveries.isEmpty());
    }

    @Test
    void recoversWhenProgressAndKeepAliveAreStale() {
        PeerHealth health = runningHealth();
        health.recordHeaderProgress(100, 10, now.get() - 2_000);
        health.recordKeepAliveResponse(now.get() - 2_000);
        TestPeerSession session = new TestPeerSession(health, now);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();

        supervisor(session, recoveries).checkNow();

        assertEquals(List.of(PeerRecoveryReason.KEEPALIVE_STALE), recoveries);
    }

    @Test
    void recoversWhenProgressIsStaleEvenIfKeepAliveIsFresh() {
        PeerHealth health = runningHealth();
        health.recordHeaderProgress(100, 10, now.get() - 2_000);
        health.recordKeepAliveResponse(now.get() - 100);
        TestPeerSession session = new TestPeerSession(health, now);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();

        supervisor(session, recoveries).checkNow();

        assertEquals(List.of(PeerRecoveryReason.NO_PROGRESS), recoveries);
    }

    @Test
    void disconnectSignalWaitsForGraceEvenWhenProgressIsFresh() {
        PeerHealth health = runningHealth();
        health.recordHeaderProgress(100, 10, now.get() - 700);
        health.recordDisconnect(now.get() - 500);
        TestPeerSession session = new TestPeerSession(health, now);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        PeerSessionSupervisor supervisor = supervisor(session, recoveries);

        supervisor.checkNow();
        assertTrue(recoveries.isEmpty());

        now.addAndGet(600);
        supervisor.checkNow();
        assertEquals(List.of(PeerRecoveryReason.DISCONNECT_STALE), recoveries);
    }

    @Test
    void explicitDisconnectNotificationRecoversImmediatelyWithoutGrace() {
        PeerHealth health = runningHealth();
        health.recordHeaderProgress(100, 10, now.get() - 100);
        health.recordDisconnect(now.get());
        TestPeerSession session = new TestPeerSession(health, now);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();

        supervisor(session, recoveries).notifyDisconnect();

        assertEquals(List.of(PeerRecoveryReason.DISCONNECT_STALE), recoveries);
    }

    @Test
    void explicitDisconnectNotificationUsesFastQuotaBeforeNormalCooldown() {
        PeerHealth health = runningHealth();
        TestPeerSession session = new TestPeerSession(health, now);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        PeerSessionSupervisor supervisor = new PeerSessionSupervisor(
                scheduler,
                () -> session,
                recoveries::add,
                fastDisconnectPolicy(),
                now::get);

        health.recordDisconnect(now.get());
        supervisor.notifyDisconnect();
        health.recordDisconnect(now.get());
        supervisor.notifyDisconnect();
        health.recordDisconnect(now.get());
        supervisor.notifyDisconnect();

        assertEquals(List.of(PeerRecoveryReason.DISCONNECT_STALE, PeerRecoveryReason.DISCONNECT_STALE), recoveries);

        now.addAndGet(500);
        health.recordDisconnect(now.get());
        supervisor.notifyDisconnect();

        assertEquals(List.of(
                PeerRecoveryReason.DISCONNECT_STALE,
                PeerRecoveryReason.DISCONNECT_STALE,
                PeerRecoveryReason.DISCONNECT_STALE), recoveries);
    }

    @Test
    void progressAfterDisconnectSuppressesStaleDisconnectRecovery() {
        PeerHealth health = runningHealth();
        health.recordDisconnect(now.get() - 2_000);
        health.recordHeaderProgress(100, 10, now.get() - 100);
        TestPeerSession session = new TestPeerSession(health, now);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();

        supervisor(session, recoveries).checkNow();

        assertTrue(recoveries.isEmpty());
    }

    @Test
    void recoversDisconnectedSessionAfterProgressIsStale() {
        PeerHealth health = runningHealth();
        health.recordHeaderProgress(100, 10, now.get() - 2_000);
        TestPeerSession session = new TestPeerSession(health, now);
        session.running = false;
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();

        supervisor(session, recoveries).checkNow();

        assertEquals(List.of(PeerRecoveryReason.DISCONNECT_STALE), recoveries);
    }

    @Test
    void bodyFetchStuckTriggersRecoveryWhenNoBodyArrives() {
        PeerHealth health = runningHealth();
        health.recordHeaderProgress(100, 10, now.get() - 100);
        health.markBodyFetchStarted(now.get() - 2_000);
        TestPeerSession session = new TestPeerSession(health, now);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();

        supervisor(session, recoveries).checkNow();

        assertEquals(List.of(PeerRecoveryReason.BODY_FETCH_STUCK), recoveries);
    }

    @Test
    void bodyFetchWithBodySinceStartDoesNotTriggerStuckRecovery() {
        PeerHealth health = runningHealth();
        health.markBodyFetchStarted(now.get() - 2_000);
        health.recordBodyReceived(101, 11, now.get() - 100);
        TestPeerSession session = new TestPeerSession(health, now);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();

        supervisor(session, recoveries).checkNow();

        assertTrue(recoveries.isEmpty());
    }

    @Test
    void bodyFetchPartialProgressThenStallTriggersRecovery() {
        PeerHealth health = runningHealth();
        health.markBodyFetchStarted(now.get() - 5_000);
        health.recordBodyReceived(101, 11, now.get() - 2_000);
        TestPeerSession session = new TestPeerSession(health, now);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();

        supervisor(session, recoveries).checkNow();

        assertEquals(List.of(PeerRecoveryReason.BODY_FETCH_STUCK), recoveries);
    }

    @Test
    void rollbackGuardDefersRecoveryWithoutStartingCooldown() {
        PeerHealth health = runningHealth();
        health.recordHeaderProgress(100, 10, now.get() - 2_000);
        health.recordKeepAliveResponse(now.get() - 2_000);
        TestPeerSession session = new TestPeerSession(health, now);
        AtomicBoolean rollbackInProgress = new AtomicBoolean(true);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        PeerSessionSupervisor supervisor = new PeerSessionSupervisor(
                scheduler,
                () -> session,
                recoveries::add,
                policy(),
                now::get,
                rollbackInProgress::get);

        supervisor.checkNow();
        rollbackInProgress.set(false);
        supervisor.checkNow();

        assertEquals(List.of(PeerRecoveryReason.KEEPALIVE_STALE), recoveries);
    }

    @Test
    void terminalSessionTriggersRecoveryRetryAfterCooldown() {
        PeerHealth health = runningHealth();
        health.markTerminalFailure(PeerRecoveryReason.KEEPALIVE_STALE, "previous recovery failed");
        TestPeerSession session = new TestPeerSession(health, now);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();

        supervisor(session, recoveries).checkNow();

        assertEquals(List.of(PeerRecoveryReason.KEEPALIVE_STALE), recoveries);
    }

    @Test
    void cooldownPreventsRepeatedRecoveries() {
        PeerHealth health = runningHealth();
        health.recordHeaderProgress(100, 10, now.get() - 2_000);
        health.recordKeepAliveResponse(now.get() - 2_000);
        TestPeerSession session = new TestPeerSession(health, now);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        PeerSessionSupervisor supervisor = supervisor(session, recoveries);

        supervisor.checkNow();
        now.addAndGet(100);
        supervisor.checkNow();

        assertEquals(List.of(PeerRecoveryReason.KEEPALIVE_STALE), recoveries);
    }

    @Test
    void cooldownIncludesConfiguredJitter() {
        PeerHealth health = runningHealth();
        health.recordHeaderProgress(100, 10, now.get() - 2_000);
        health.recordKeepAliveResponse(now.get() - 2_000);
        TestPeerSession session = new TestPeerSession(health, now);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        PeerSessionSupervisor supervisor = new PeerSessionSupervisor(
                scheduler,
                () -> session,
                recoveries::add,
                policy(),
                now::get,
                () -> 250);

        supervisor.checkNow();
        now.addAndGet(600);
        supervisor.checkNow();
        now.addAndGet(151);
        supervisor.checkNow();

        assertEquals(List.of(PeerRecoveryReason.KEEPALIVE_STALE, PeerRecoveryReason.KEEPALIVE_STALE), recoveries);
    }

    @Test
    void concurrentChecksTriggerSingleRecovery() throws Exception {
        PeerHealth health = runningHealth();
        health.recordHeaderProgress(100, 10, now.get() - 2_000);
        health.recordKeepAliveResponse(now.get() - 2_000);
        TestPeerSession session = new TestPeerSession(health, now);
        CountDownLatch enteredRecovery = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        PeerSessionSupervisor supervisor = new PeerSessionSupervisor(
                scheduler,
                () -> session,
                reason -> {
                    recoveries.add(reason);
                    enteredRecovery.countDown();
                    try {
                        releaseRecovery.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                policy(),
                now::get);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            executor.submit(supervisor::checkNow);
            assertTrue(enteredRecovery.await(2, TimeUnit.SECONDS));
            executor.submit(supervisor::checkNow).get(2, TimeUnit.SECONDS);
            releaseRecovery.countDown();
        }

        assertEquals(List.of(PeerRecoveryReason.KEEPALIVE_STALE), recoveries);
    }

    @Test
    void asyncRecoveryExecutorDoesNotBlockSupervisorCheck() throws Exception {
        PeerHealth health = runningHealth();
        health.recordHeaderProgress(100, 10, now.get() - 2_000);
        health.recordKeepAliveResponse(now.get() - 2_000);
        TestPeerSession session = new TestPeerSession(health, now);
        CountDownLatch enteredRecovery = new CountDownLatch(1);
        CountDownLatch releaseRecovery = new CountDownLatch(1);
        List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();
        ExecutorService recoveryExecutor = Executors.newSingleThreadExecutor();
        PeerSessionSupervisor supervisor = new PeerSessionSupervisor(
                scheduler,
                () -> session,
                reason -> {
                    recoveries.add(reason);
                    enteredRecovery.countDown();
                    try {
                        releaseRecovery.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                policy(),
                now::get,
                () -> false,
                recoveryExecutor);

        try {
            supervisor.checkNow();
            assertTrue(enteredRecovery.await(2, TimeUnit.SECONDS));
            supervisor.checkNow();
            assertEquals(List.of(PeerRecoveryReason.KEEPALIVE_STALE), recoveries);
        } finally {
            releaseRecovery.countDown();
            recoveryExecutor.shutdownNow();
        }
    }

    private PeerHealth runningHealth() {
        PeerHealth health = new PeerHealth("relay-1", now.get() - 5_000);
        health.markState(PeerSessionState.RUNNING);
        return health;
    }

    private PeerSessionSupervisor supervisor(TestPeerSession session, List<PeerRecoveryReason> recoveries) {
        return new PeerSessionSupervisor(
                scheduler,
                () -> session,
                recoveries::add,
                policy(),
                now::get);
    }

    private PeerSessionSupervisor.Policy policy() {
        return new PeerSessionSupervisor.Policy(
                10,
                1_000,
                1_000,
                1_000,
                1_000,
                1_000,
                1_000,
                500,
                0);
    }

    private PeerSessionSupervisor.Policy fastDisconnectPolicy() {
        return new PeerSessionSupervisor.Policy(
                10,
                1_000,
                1_000,
                1_000,
                1_000,
                1_000,
                1_000,
                500,
                0,
                2,
                0,
                10_000);
    }

    private static class TestPeerSession extends PeerSession {
        private final PeerHealth health;
        private final AtomicLong now;
        private boolean running = true;

        TestPeerSession(PeerHealth health, AtomicLong now) {
            super("relay-1", 3001, 1, new InMemoryChainState(), new SimpleEventBus(), new NoopCallbacks(), null);
            this.health = health;
            this.now = now;
        }

        @Override
        public PeerHealth getPeerHealth() {
            return health;
        }

        @Override
        public PeerSessionStatus getStatus() {
            return health.snapshot(now.get());
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    private static class NoopCallbacks implements PeerSessionCallbacks {
        @Override
        public void resumeBodyFetchOnHeaderFlow() {
        }

        @Override
        public void updateSyncProgress(long slot, long blockNumber) {
        }

        @Override
        public void notifyServerNewBlockStored() {
        }

        @Override
        public void onIntersectionFound() {
        }

        @Override
        public void maybeFastTransitionToSteadyState(Tip remoteTip) {
        }

        @Override
        public void handleChainSyncRollback(Point point) {
        }
    }
}
