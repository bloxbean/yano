package com.bloxbean.cardano.yano.archive.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveResourceGateTest {

    @Test
    void stuckThresholdFailsOnlyTheWaiterAndNamesTheHolder() {
        var gate = new ArchiveResourceGate("writer", 1,
                new ArchiveWaitPolicy(Duration.ofMillis(20), Duration.ofMillis(120)),
                (name, operation, waited, holder) -> { });
        long held = gate.acquire("slow-commit");

        assertThatThrownBy(() -> gate.acquire("blocked-commit"))
                .isInstanceOf(ArchiveStuckOperationException.class)
                .hasMessageContaining("stuck-operation threshold")
                .hasMessageContaining("blocked-commit")
                .hasMessageContaining("slow-commit");

        // The failed waiter must not have taken or released the holder's permit.
        assertThat(gate.availablePermits()).isZero();
        gate.release(held);
        assertThat(gate.availablePermits()).isEqualTo(1);
    }

    @Test
    void releaseIsExactlyOnceAndAnUnknownTicketCannotStealAnotherHoldersPermit() {
        var gate = new ArchiveResourceGate("writer", 1, ArchiveWaitPolicy.defaults(),
                (name, operation, waited, holder) -> { });
        long ticket = gate.acquire("first");
        gate.release(ticket);
        gate.release(ticket);
        assertThat(gate.availablePermits()).isEqualTo(1);

        long other = gate.acquire("second");
        gate.release(ticket);
        assertThat(gate.availablePermits()).isZero();
        gate.release(other);
        assertThat(gate.availablePermits()).isEqualTo(1);
    }

    @Test
    void interruptionFailsTheAcquisitionAndRestoresTheFlag() throws Exception {
        var gate = new ArchiveResourceGate("writer", 1, ArchiveWaitPolicy.defaults(),
                (name, operation, waited, holder) -> { });
        long held = gate.acquire("holder");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                gate.acquire("interrupted-op");
            } catch (Throwable error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (gate.usage().waiters() == 0 && System.nanoTime() < deadline) Thread.onSpinWait();
        waiter.interrupt();
        waiter.join(5_000);

        assertThat(failure.get()).isInstanceOf(ArchiveStoreException.class);
        assertThat(failure.get()).isNotInstanceOf(ArchiveStuckOperationException.class);
        assertThat(interrupted.get()).isTrue();
        assertThat(gate.usage().waiters()).isZero();
        gate.release(held);
    }

    @Test
    void aLongWaiterKeepsItsFairQueuePositionAcrossManyWarnIntervals() throws Exception {
        // Regression: warnings used to be produced by slicing the wait into
        // repeated timed tryAcquire calls, which cancels and re-enqueues the AQS
        // node each interval and demotes the longest waiter to the tail.
        List<String> order = new CopyOnWriteArrayList<>();
        var gate = new ArchiveResourceGate("writer", 1,
                new ArchiveWaitPolicy(Duration.ofMillis(20), Duration.ofSeconds(30)),
                (name, operation, waited, holder) -> { });
        long held = gate.acquire("holder");

        CountDownLatch firstQueued = new CountDownLatch(1);
        Thread first = Thread.ofPlatform().start(() -> {
            firstQueued.countDown();
            long ticket = gate.acquire("first-waiter");
            order.add("first-waiter");
            gate.release(ticket);
        });
        assertThat(firstQueued.await(2, TimeUnit.SECONDS)).isTrue();
        // Let the first waiter cross several warn intervals before the second arrives.
        Thread.sleep(150);
        assertThat(gate.usage().waiters()).isEqualTo(1);

        Thread second = Thread.ofPlatform().start(() -> {
            long ticket = gate.acquire("second-waiter");
            order.add("second-waiter");
            gate.release(ticket);
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (gate.usage().waiters() < 2 && System.nanoTime() < deadline) Thread.onSpinWait();
        // More warn intervals elapse; the earlier waiter must not be overtaken.
        Thread.sleep(150);

        gate.release(held);
        first.join(10_000);
        second.join(10_000);
        assertThat(order).containsExactly("first-waiter", "second-waiter");
    }

    @Test
    void waitDiagnosticsStillFireWhileTheWaiterHoldsItsPosition() throws Exception {
        List<Duration> warnings = new CopyOnWriteArrayList<>();
        var gate = new ArchiveResourceGate("writer", 1,
                new ArchiveWaitPolicy(Duration.ofMillis(40), Duration.ofSeconds(30)),
                (name, operation, waited, holder) -> warnings.add(waited));
        long held = gate.acquire("holder-op");
        Thread waiter = Thread.ofPlatform().start(() -> gate.release(gate.acquire("waiting-op")));

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (warnings.size() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(warnings).hasSizeGreaterThanOrEqualTo(2);
        assertThat(gate.lastWaitWarning()).isPresent();
        assertThat(gate.lastWaitWarning().orElseThrow().operation()).isEqualTo("waiting-op");

        gate.release(held);
        waiter.join(5_000);
        assertThat(gate.usage().waiters()).isZero();
    }

    @Test
    void waitPolicyRejectsAStuckThresholdShorterThanItsWarnInterval() {
        assertThatThrownBy(() -> new ArchiveWaitPolicy(Duration.ofSeconds(30), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ArchiveWaitPolicy.defaults().warnInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(ArchiveWaitPolicy.defaults().stuckThreshold()).isEqualTo(Duration.ofMinutes(5));
        assertThat(ArchiveWaitPolicy.defaults().boundedTo(Duration.ofSeconds(10)).stuckThreshold())
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void boundingAWaitOnlyEverShortensItAndNeverOverridesTheOperatorsStuckThreshold() {
        // A short operator stuck threshold must win over a longer request bound;
        // otherwise a request-facing timeout silently extends the configured limit.
        var strict = new ArchiveWaitPolicy(Duration.ofSeconds(1), Duration.ofSeconds(5));
        var bounded = strict.boundedTo(Duration.ofSeconds(30));
        assertThat(bounded.stuckThreshold()).isEqualTo(Duration.ofSeconds(5));
        assertThat(bounded.warnInterval()).isEqualTo(Duration.ofSeconds(1));

        // A shorter bound still shortens both values.
        var tighter = strict.boundedTo(Duration.ofMillis(500));
        assertThat(tighter.stuckThreshold()).isEqualTo(Duration.ofMillis(500));
        assertThat(tighter.warnInterval()).isEqualTo(Duration.ofMillis(500));

        // Bounding is idempotent and never produces an invalid policy.
        assertThat(strict.boundedTo(Duration.ofSeconds(5))).isEqualTo(strict);
    }
}
