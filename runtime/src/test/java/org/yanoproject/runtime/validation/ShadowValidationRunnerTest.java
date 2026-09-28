package org.yanoproject.runtime.validation;

import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.spec.NetworkId;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle.RecordedOutcome;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.runtime.validation.EngineAdmissionTest.awaitTrue;

/** ADR-056 §3/§7: bounded shadow work, the max-age cancel and exactly-once release of frozen views. */
class ShadowValidationRunnerTest {

    private static final byte[] TX = {(byte) 0x84, (byte) 0xa0, (byte) 0xa0, (byte) 0xf5, (byte) 0xf6};
    private static final TxValidationOutcome REJECTED = TxValidationOutcome.Invalid.of(
            LedgerFailure.ledgerStateUnavailable("x"));

    @Test
    void anExpiredJobIsCancelledReleasedAndNotCounted() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        LedgerValidationEngine stuck = engine("amaru", request -> {
            entered.countDown();
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return TxValidationOutcome.Invalid.of(LedgerFailure.eraNotSupported("late"));
        });
        AtomicInteger released = new AtomicInteger();
        try (ShadowValidationRunner runner = new ShadowValidationRunner(List.of(stuck), null, 200, 1, 4)) {
            assertThat(runner.submit(job(released::incrementAndGet, 0))).isTrue();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            awaitTrue(() -> runner.stats().expired() == 1 && released.get() == 1);
            awaitTrue(() -> runner.stats().inFlight() == 0);
            assertThat(runner.stats().disagreementTotal()).isZero();
            assertThat(runner.stats().compared()).isZero();
            assertThat(released.get()).isEqualTo(1);
        }
    }

    @Test
    void aJobAlreadyOlderThanTheMaximumIsDroppedAtOnce() {
        AtomicInteger released = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        try (ShadowValidationRunner runner = new ShadowValidationRunner(
                List.of(engine("amaru", r -> {
                    calls.incrementAndGet();
                    return REJECTED;
                })), null, 1_000, 1, 4)) {
            assertThat(runner.submit(job(released::incrementAndGet, 5_000))).isFalse();
            assertThat(runner.stats().expired()).isEqualTo(1);
            assertThat(released.get()).isEqualTo(1);
            assertThat(calls.get()).isZero();
        }
    }

    @Test
    void aFullQueueDropsTheJobAndReleasesIt() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        LedgerValidationEngine slow = engine("amaru", r -> {
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return REJECTED;
        });
        AtomicInteger released = new AtomicInteger();
        try (ShadowValidationRunner runner = new ShadowValidationRunner(List.of(slow), null, 30_000, 1, 1)) {
            assertThat(runner.submit(job(released::incrementAndGet, 0))).isTrue();  // running
            awaitTrue(() -> runner.stats().inFlight() == 1);
            assertThat(runner.submit(job(released::incrementAndGet, 0))).isTrue();  // queued
            assertThat(runner.submit(job(released::incrementAndGet, 0))).isFalse(); // dropped
            assertThat(runner.stats().droppedQueueFull()).isEqualTo(1);
            assertThat(released.get()).isEqualTo(1);
            hold.countDown();
            awaitTrue(() -> released.get() == 3);
            assertThat(runner.stats().compared()).isEqualTo(2);
            assertThat(runner.stats().agreements()).isEqualTo(2);
        }
    }

    @Test
    void anEngineThatThrowsIsCountedAndTheViewReleased() throws Exception {
        AtomicInteger released = new AtomicInteger();
        try (ShadowValidationRunner runner = new ShadowValidationRunner(List.of(engine("amaru", r -> {
            throw new IllegalStateException("boom");
        })), null, 30_000, 1, 4)) {
            runner.submit(job(released::incrementAndGet, 0));
            awaitTrue(() -> runner.stats().engineErrors() == 1 && released.get() == 1);
            assertThat(runner.stats().disagreementTotal()).isZero();
        }
    }

    @Test
    void closeReleasesQueuedJobs() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        LedgerValidationEngine slow = engine("amaru", r -> {
            entered.countDown();
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return REJECTED;
        });
        AtomicInteger released = new AtomicInteger();
        ShadowValidationRunner runner = new ShadowValidationRunner(List.of(slow), null, 30_000, 1, 4);
        runner.submit(job(released::incrementAndGet, 0));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        runner.submit(job(released::incrementAndGet, 0));
        runner.submit(job(released::incrementAndGet, 0));

        runner.close();

        awaitTrue(() -> released.get() == 3);
        assertThat(runner.stats().inFlight()).isZero();
    }

    @Test
    void aJobExpiringInTheQueueLeavesItAtOnce() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        LedgerValidationEngine slow = engine("amaru", r -> {
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return REJECTED;
        });
        AtomicInteger released = new AtomicInteger();
        try (ShadowValidationRunner runner = new ShadowValidationRunner(List.of(slow), null, 30_000, 1, 1)) {
            runner.submit(job(released::incrementAndGet, 0));           // occupies the worker
            awaitTrue(() -> runner.stats().inFlight() == 1);
            assertThat(runner.submit(job(released::incrementAndGet, 29_950))).isTrue(); // queued, expires soon
            awaitTrue(() -> runner.stats().expired() == 1 && released.get() == 1);
            // The expired job no longer occupies the queue: a new one fits.
            assertThat(runner.submit(job(released::incrementAndGet, 0))).isTrue();
            hold.countDown();
            awaitTrue(() -> released.get() == 3);
        }
    }

    private static ShadowValidationRunner.ShadowJob job(Runnable release, long snapshotAgeMs) {
        ValidationEnv env = new ValidationEnv(1, 0, 10, 0, NetworkId.TESTNET, new SlotConfig(1000, 0, 0),
                new byte[32]);
        TxValidationRequest request = new TxValidationRequest(TX, InMemoryLedgerView.builder().build(), env,
                TxValidationRequest.Rule.MEMPOOL, TxValidationRequest.Origin.LOCAL, null);
        return new ShadowValidationRunner.ShadowJob("ab".repeat(32), request, release::run, snapshotAgeMs,
                RecordedOutcome.of("scalus", REJECTED), List.of());
    }

    private static LedgerValidationEngine engine(String name,
                                                 Function<TxValidationRequest,
                                                         TxValidationOutcome> body) {
        return new LedgerValidationEngine() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public TxValidationOutcome validate(TxValidationRequest request) {
                return body.apply(request);
            }
        };
    }
}
