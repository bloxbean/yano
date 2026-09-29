package org.yanoproject.runtime.mempool;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.runtime.chain.MempoolAdmissionResult;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.ADA;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.admit;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.build;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.extraInput;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.hash;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.payment;

/**
 * ADR-056 decision 3, the mempool budget, measured on the java engine at {@code yano.tx.mempool.max-txs} = 10,000
 * (JVM, after a warm-up round), with signed non-Plutus Conway payments from independent inputs:
 *
 * <ul>
 *   <li>admission p99 ≤ 20 ms (filling the mempool to 10,000);</li>
 *   <li>synchronous truncate-and-reapply ≤ 50 ms for a 1,000-transaction suffix, ≤ 500 ms for 10,000;</li>
 *   <li>off-lane rebuild with re-application only ≤ 2 s for 10,000 transactions.</li>
 * </ul>
 *
 * A microbenchmark, not JMH: disabled by default, run with
 * {@code ./gradlew :runtime:test --tests '*LedgerMempoolBudgetTest' -PmempoolBenchmark=true}.
 */
@Tag("benchmark")
@EnabledIfSystemProperty(named = "yano.mempool.benchmark", matches = "true")
class LedgerMempoolBudgetTest {

    private static final int MAX_TXS = 10_000;

    @Test
    void mempoolBudget() {
        LedgerView world = MempoolTestWorld.world(MutationWorld.protocolParams(), MAX_TXS + 1);
        long buildStarted = System.nanoTime();
        List<byte[]> txs = new ArrayList<>(MAX_TXS);
        for (int i = 0; i < MAX_TXS; i++) {
            txs.add(build(payment(extraInput(i), ADA.multiply(BigInteger.TWO)), world));
        }
        System.out.printf("built %d signed transactions in %d ms%n", MAX_TXS, millis(buildStarted));

        // Warm-up round: the same work on a throw-away mempool, so the measured round runs JIT-compiled code.
        for (int round = 0; round < 2; round++) {
            run(world, txs, round == 0 ? 3_000 : MAX_TXS, round == 1);
        }
    }

    private void run(LedgerView world, List<byte[]> txs, int count, boolean measured) {
        MempoolTestWorld.Chain chain = new MempoolTestWorld.Chain(world);
        MempoolTestWorld.HeldExecutor worker = new MempoolTestWorld.HeldExecutor();
        try (LedgerMempool mempool = MempoolTestWorld.mempool(chain, new MempoolTestWorld.StubEvaluator(), worker,
                LedgerMempool.Settings.defaults())) {
            mempool.start();
            long[] admission = new long[count];
            for (int i = 0; i < count; i++) {
                long started = System.nanoTime();
                MempoolAdmissionResult result = admit(mempool, txs.get(i));
                admission[i] = System.nanoTime() - started;
                assertThat(result.status()).isEqualTo(MempoolAdmissionResult.Status.ACCEPTED);
            }
            assertThat(mempool.size()).isEqualTo(count);

            chain.publish(world);   // a new generation with the same state: every transaction re-applies
            long rebuildStarted = System.nanoTime();
            assertThat(mempool.rebuildNow()).isTrue();
            long rebuild = System.nanoTime() - rebuildStarted;
            assertThat(mempool.size()).isEqualTo(count);
            assertThat(mempool.ledgerStatus().reapplications()).isEqualTo(count);

            // Suffix of 1,000: remove the transaction just before the last 999.
            long removeStarted = System.nanoTime();
            assertThat(mempool.evictTransaction(hash(txs.get(count - 1_000)))).hasSize(1);
            long suffix1000 = System.nanoTime() - removeStarted;

            // Suffix of the whole mempool: remove the oldest.
            removeStarted = System.nanoTime();
            assertThat(mempool.evictTransaction(hash(txs.get(0)))).hasSize(1);
            long suffixAll = System.nanoTime() - removeStarted;
            assertThat(mempool.size()).isEqualTo(count - 2);
            mempool.published().checkConsistent();

            // Informational: a rebuild that validates in full (no latency target; CATCHING_UP is the fallback).
            chain.publish(MempoolTestWorld.world(MutationWorld.protocolParams(11), MAX_TXS + 1));
            long fullStarted = System.nanoTime();
            assertThat(mempool.rebuildNow()).isTrue();
            long fullRebuild = System.nanoTime() - fullStarted;

            if (!measured) {
                return;
            }
            Arrays.sort(admission);
            double p50 = admission[count / 2] / 1e6;
            double p99 = admission[(int) Math.ceil(count * 0.99) - 1] / 1e6;
            double max = admission[count - 1] / 1e6;
            System.out.printf("admission (to %d txs): p50 %.3f ms, p99 %.3f ms, max %.3f ms (target p99 <= 20 ms)%n",
                    count, p50, p99, max);
            System.out.printf("truncate-and-reapply: 1,000 suffix %.1f ms (<= 50), %d suffix %.1f ms (<= 500)%n",
                    suffix1000 / 1e6, count - 2, suffixAll / 1e6);
            System.out.printf("off-lane rebuild, re-application only, %d txs: %.1f ms (<= 2000)%n", count,
                    rebuild / 1e6);
            System.out.printf("rebuild with full validation (protocol major change), %d txs: %.1f ms (no target)%n",
                    count - 2, fullRebuild / 1e6);
            assertThat(p99).as("admission p99 ms").isLessThanOrEqualTo(20.0);
            assertThat(suffix1000 / 1e6).as("1,000-suffix reapply ms").isLessThanOrEqualTo(50.0);
            assertThat(suffixAll / 1e6).as("10,000-suffix reapply ms").isLessThanOrEqualTo(500.0);
            assertThat(rebuild / 1e6).as("10,000 re-application rebuild ms").isLessThanOrEqualTo(2_000.0);
        }
    }

    private static long millis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
