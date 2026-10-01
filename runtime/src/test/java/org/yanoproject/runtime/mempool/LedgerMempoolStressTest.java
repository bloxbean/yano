package org.yanoproject.runtime.mempool;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.view.LedgerView;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.ADA;

/**
 * Review MINOR: concurrent admitters, the real rebuild worker, a canonical publisher (new generations, blocks
 * confirming transactions, rollbacks), an evictor (evict, TTL, clear, invalidation) and a consistency checker, for
 * a bounded, randomised run. Every published state stays internally consistent, the lock order is never violated,
 * and no base leaks at close. Runs 3 s by default; {@code -Dyano.mempool.stress.seconds=N} runs longer.
 */
@Tag("stress")
class LedgerMempoolStressTest {

    private static final int TXS = 400;

    @Test
    void concurrentAdmissionRemovalRebuildAndPublicationStayConsistent() throws Exception {
        long seconds = Long.getLong("yano.mempool.stress.seconds", 3);
        LedgerView world = MempoolTestWorld.world(MutationWorld.protocolParams(), TXS);
        List<byte[]> txs = new ArrayList<>(TXS);
        for (int i = 0; i < TXS; i++) {
            txs.add(MempoolTestWorld.build(MempoolTestWorld.payment(MempoolTestWorld.extraInput(i),
                    ADA.multiply(BigInteger.TWO)), world));
        }
        MempoolTestWorld.StubEvaluator evaluator = new MempoolTestWorld.StubEvaluator();
        LedgerView confirmedPrefix = MempoolTestWorld.confirm(world, evaluator, txs.subList(0, 40).toArray(byte[][]::new));

        MempoolTestWorld.Chain chain = new MempoolTestWorld.Chain(world);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        LedgerMempool mempool = MempoolTestWorld.mempool(chain, evaluator, worker, LedgerMempool.Settings.defaults());
        chain.laneHeld = mempool::laneHeldByCurrentThread;
        mempool.start();

        AtomicBoolean stop = new AtomicBoolean();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        AtomicLong accepted = new AtomicLong();
        AtomicLong checks = new AtomicLong();
        List<Thread> threads = new ArrayList<>();
        for (int a = 0; a < 4; a++) {
            long seed = 17L * (a + 1);
            threads.add(thread("admitter-" + a, stop, failures, () -> {
                Random random = new Random(seed + System.nanoTime());
                return () -> {
                    var result = MempoolTestWorld.admit(mempool, txs.get(random.nextInt(TXS)));
                    if (result.accepted()) {
                        accepted.incrementAndGet();
                    }
                };
            }));
        }
        threads.add(thread("publisher", stop, failures, () -> {
            Random random = new Random(3);
            return () -> {
                switch (random.nextInt(3)) {
                    case 0 -> chain.publish(world);            // a block without mempool transactions / rollback
                    case 1 -> chain.publish(confirmedPrefix);  // a block confirming the first 40 transactions
                    default -> chain.crossEpoch();
                }
                sleep(random.nextInt(5));
            };
        }));
        threads.add(thread("evictor", stop, failures, () -> {
            Random random = new Random(5);
            return () -> {
                switch (random.nextInt(20)) {
                    case 0 -> mempool.clear();
                    case 1, 2 -> mempool.removeOlderThan(System.currentTimeMillis() - random.nextInt(20));
                    case 3, 4, 5 -> mempool.removeInvalidated(Set.of(
                            MempoolTestWorld.hash(txs.get(random.nextInt(TXS)))));
                    default -> {
                        try {
                            mempool.evictTransaction(MempoolTestWorld.hash(txs.get(random.nextInt(TXS))));
                        } catch (IllegalStateException busy) {
                            // "Mempool busy; retry eviction" (tryLock), as in production
                        }
                    }
                }
                sleep(random.nextInt(3));
            };
        }));
        threads.add(thread("checker", stop, failures, () -> () -> {
            MempoolLedgerState state = mempool.published();
            if (state != null) {
                state.checkConsistent();
                checks.incrementAndGet();
            }
        }));
        threads.forEach(Thread::start);
        Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
        stop.set(true);
        for (Thread t : threads) {
            t.join(10_000);
        }
        // Quiesce: a final rebuild against the last publication.
        mempool.rebuildNow();
        mempool.published().checkConsistent();
        assertThat(mempool.published().mark()).isEqualTo(chain.current());
        assertThat(failures).as("failures in the concurrent actors").isEmpty();
        assertThat(mempool.ledgerStatus().lockOrderViolations()).isZero();
        assertThat(chain.acquiredUnderLane.get()).isZero();
        assertThat(accepted.get()).isPositive();
        assertThat(checks.get()).isPositive();
        System.out.printf("stress: %d s, %d accepted, %d consistency checks, %s%n", seconds, accepted.get(),
                checks.get(), mempool.ledgerStatus());

        mempool.close();
        worker.shutdown();
        assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(chain.liveBases()).as("no leaked bases after close").isZero();
    }

    private interface Step {
        void run() throws Exception;
    }

    private interface StepFactory {
        Step create();
    }

    private static Thread thread(String name, AtomicBoolean stop, Queue<Throwable> failures, StepFactory factory) {
        Step step = factory.create();
        Thread thread = new Thread(() -> {
            while (!stop.get()) {
                try {
                    step.run();
                } catch (Throwable t) {
                    failures.add(new AssertionError(name + ": " + t, t));
                    return;
                }
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
