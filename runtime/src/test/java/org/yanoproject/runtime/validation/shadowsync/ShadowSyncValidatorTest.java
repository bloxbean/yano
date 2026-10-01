package org.yanoproject.runtime.validation.shadowsync;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.agreeing;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.engine;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.indexOf;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.invalid;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.params;
import static org.yanoproject.runtime.validation.shadowsync.ShadowSyncTestSupport.valid;

/** ADR-056 Phase 7a: the apply-thread hook, bounded in-flight blocks, reporting and state release. */
class ShadowSyncValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    /** Pre-block states over an in-memory view that count captures and closes. */
    private static final class CountingSource implements PreBlockState.Source {
        final AtomicInteger captured = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final int pv;

        CountingSource(int pv) {
            this.pv = pv;
        }

        @Override
        public Lookup<PreBlockState> capture(long blockSlot) {
            captured.incrementAndGet();
            LedgerView view = InMemoryLedgerView.builder().protocolParams(params(pv)).build();
            AtomicBoolean once = new AtomicBoolean();
            return Lookup.present(new PreBlockState() {
                @Override
                public LedgerView view() {
                    return view;
                }

                @Override
                public long forecastBasisSlot() {
                    return blockSlot;
                }

                @Override
                public void close() {
                    if (once.compareAndSet(false, true)) {
                        closed.incrementAndGet();
                    }
                }
            });
        }
    }

    @Test
    @Timeout(30)
    void whenEverySlotIsTakenTheApplyThreadWaitsUntilABlockFinishes() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger entered = new AtomicInteger();
        LedgerValidationEngine held = engine("java-julc", request -> {
            entered.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return valid(request.txCbor(), true);
        });
        CountingSource source = new CountingSource(10);
        ExecutorService applyThread = Executors.newSingleThreadExecutor();
        try (ShadowSyncValidator validator = validator(settings(2, 60_000), List.of(held), source, () -> true,
                new ShadowSyncReport(null, null, 0))) {
            validator.onBlockApplied(event(1, 1));
            validator.onBlockApplied(event(2, 1));
            assertThat(validator.status().inFlight()).isEqualTo(2);

            Future<?> third = applyThread.submit(() -> validator.onBlockApplied(event(3, 1)));
            assertThatThrownBy(() -> third.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(source.captured.get()).isEqualTo(2); // nothing captured while waiting

            release.countDown();
            third.get(10, TimeUnit.SECONDS);
            assertThat(validator.awaitIdle(10, TimeUnit.SECONDS)).isTrue();

            ShadowSyncReport.Stats stats = validator.status().report();
            assertThat(stats.blocksValidated()).isEqualTo(3);
            assertThat(stats.agreed("java-julc")).isEqualTo(3);
            assertThat(stats.backpressureWaits()).isEqualTo(1);
            assertThat(stats.backpressureWaitMillis()).isGreaterThanOrEqualTo(250);
            assertThat(source.captured.get()).isEqualTo(3);
            assertThat(source.closed.get()).isEqualTo(3);
        } finally {
            release.countDown();
            applyThread.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    void aBlockThatFindsNoSlotWithinTheMaximumWaitIsSkippedAndReported() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        LedgerValidationEngine held = engine("java-julc", request -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return valid(request.txCbor(), true);
        });
        CountingSource source = new CountingSource(10);
        Path report = dir.resolve("report.jsonl");
        try (ShadowSyncValidator validator = validator(settings(1, 100), List.of(held), source, () -> true,
                new ShadowSyncReport(report, null, 0))) {
            validator.onBlockApplied(event(1, 2));
            validator.onBlockApplied(event(2, 3));
            release.countDown();
            assertThat(validator.awaitIdle(10, TimeUnit.SECONDS)).isTrue();

            ShadowSyncReport.Stats stats = validator.status().report();
            assertThat(stats.blockFailures()).isEqualTo(1);
            assertThat(stats.blocksFailedBeforeSubmit()).isEqualTo(1);
            assertThat(stats.txsInFailedBlocks()).isEqualTo(3);
            assertThat(source.captured.get()).isEqualTo(1);
            assertThat(source.closed.get()).isEqualTo(1);
        } finally {
            release.countDown();
        }
        List<JsonNode> lines = lines(report);
        assertThat(lines.getFirst().get("kind").asText()).isEqualTo("ENGINE_FAILURE");
        assertThat(lines.getFirst().get("reason").asText()).contains("no free shadow-sync slot");
        assertThat(lines.getLast().get("type").asText()).isEqualTo("summary");
    }

    @Test
    void blocksBeforeConwayEmptyBlocksAndBlocksOutsideAWriteSectionAreNotCaptured() {
        CountingSource source = new CountingSource(10);
        AtomicBoolean inSection = new AtomicBoolean(true);
        try (ShadowSyncValidator validator = validator(settings(2, 1000), List.of(agreeing("java-julc")), source,
                inSection::get, new ShadowSyncReport(null, null, 0))) {
            validator.onBlockApplied(new BlockAppliedEvent(Era.Babbage, 1, 1, "aa".repeat(32), block(2)));
            validator.onBlockApplied(new BlockAppliedEvent(Era.Byron, 2, 2, "bb".repeat(32), null));
            validator.onBlockApplied(event(3, 0));
            inSection.set(false);
            validator.onBlockApplied(event(4, 1));

            ShadowSyncReport.Stats stats = validator.status().report();
            assertThat(stats.blocksSkippedPreConway()).as("Babbage and Byron").isEqualTo(2);
            assertThat(stats.txsSkippedPreConway()).isEqualTo(2);
            assertThat(stats.blocksEmpty()).isEqualTo(1);
            assertThat(stats.blockFailures()).isEqualTo(1);
            assertThat(stats.blocksFailedBeforeSubmit()).isEqualTo(1);
            assertThat(stats.blocksSubmitted()).isZero();
            assertThat(validator.status().outsideWriteSection()).isEqualTo(1);
            assertThat(source.captured.get()).isZero();
        }
    }

    @Test
    void aPreConwayProtocolVersionIsSkippedAfterTheCapture() throws Exception {
        CountingSource source = new CountingSource(8);
        try (ShadowSyncValidator validator = validator(settings(2, 1000), List.of(agreeing("java-julc")), source,
                () -> true, new ShadowSyncReport(null, null, 0))) {
            validator.onBlockApplied(event(1, 2));
            assertThat(validator.awaitIdle(10, TimeUnit.SECONDS)).isTrue();
            assertThat(validator.status().report().blocksSkippedPreConway()).isEqualTo(1);
            assertThat(validator.status().report().blocksPreConwayAfterCapture()).isEqualTo(1);
            assertThat(validator.status().report().blocksSubmitted()).isEqualTo(1);
            assertThat(validator.status().report().blocksValidated()).isZero();
            assertThat(source.closed.get()).isEqualTo(1);
        }
    }

    @Test
    void aDisagreementIsCountedPerEngineAndVersionAndWrittenToTheReportWithABundle() throws Exception {
        LedgerValidationEngine rejectsSecond = engine("java-julc", request -> indexOf(request.txCbor()) == 1
                ? invalid(LedgerRuleName.UTXO, "FeeTooSmallUTxO") : valid(request.txCbor(), true));
        LedgerValidationEngine unavailable = engine("amaru", request ->
                invalid(LedgerRuleName.ENGINE, "LedgerStateUnavailable"));
        Path report = dir.resolve("findings.jsonl");
        Path dumps = dir.resolve("dumps");
        CountingSource source = new CountingSource(11);
        try (ShadowSyncValidator validator = validator(settings(2, 1000), List.of(rejectsSecond, unavailable),
                source, () -> true, new ShadowSyncReport(report, dumps, 10))) {
            validator.onBlockApplied(event(7, 2));
            assertThat(validator.awaitIdle(10, TimeUnit.SECONDS)).isTrue();

            ShadowSyncReport.Stats stats = validator.status().report();
            assertThat(stats.byEngine().get("java-julc")).isEqualTo(Map.of(11, new ShadowSyncReport.Counts(2, 1, 1, 0)));
            assertThat(stats.byEngine().get("amaru")).isEqualTo(Map.of(11, new ShadowSyncReport.Counts(2, 0, 0, 2)));
            assertThat(stats.disagreedTotal()).isEqualTo(1);
            assertThat(stats.dumpsWritten()).isEqualTo(3);
        }
        List<JsonNode> lines = lines(report);
        JsonNode disagreement = lines.stream().filter(l -> "DISAGREED".equals(l.path("kind").asText())).findFirst()
                .orElseThrow();
        assertThat(disagreement.get("engine").asText()).isEqualTo("java-julc");
        assertThat(disagreement.get("slot").asLong()).isEqualTo(7);
        assertThat(disagreement.get("txIndex").asInt()).isEqualTo(1);
        assertThat(disagreement.get("txHash").asText()).isEqualTo(
                SyncBlock.parse(HexUtil.decodeHexString(event(7, 2).block().getCbor())).txIds().get(1));
        assertThat(disagreement.get("pv").asInt()).isEqualTo(11);
        assertThat(disagreement.get("expected").asText()).isEqualTo("VALID");
        assertThat(disagreement.get("actual").asText()).isEqualTo("UTXO.FeeTooSmallUTxO");
        assertThat(Files.exists(Path.of(disagreement.get("dump").asText()))).isTrue();
        assertThat(lines.stream().filter(l -> "ENGINE_FAILURE".equals(l.path("kind").asText()))).hasSize(2);
        JsonNode summary = lines.getLast();
        assertThat(summary.get("type").asText()).isEqualTo("summary");
        // Built field by field (no bean introspection, native-image safe).
        assertThat(summary.at("/stats/blocksValidated").asLong()).isEqualTo(1);
        assertThat(summary.at("/stats/byEngine/java-julc/11/disagreed").asLong()).isEqualTo(1);
        assertThat(summary.at("/stats/byEngine/amaru/11/engineFailures").asLong()).isEqualTo(2);
        assertThat(summary.at("/stats/dumpsWritten").asLong()).isEqualTo(3);
    }

    @Test
    @Timeout(30)
    void blocksValidateInParallelUpToTheBoundNeverBeyondEachInTransactionOrder() throws Exception {
        int bound = 3;
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        // One virtual thread per block: the thread identifies the block.
        Map<Thread, List<Integer>> orderByBlock = new ConcurrentHashMap<>();
        LedgerValidationEngine held = engine("java-julc", request -> {
            orderByBlock.computeIfAbsent(Thread.currentThread(), ignored -> new CopyOnWriteArrayList<>())
                    .add(indexOf(request.txCbor()));
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return valid(request.txCbor(), true);
        });
        CountingSource source = new CountingSource(10);
        ExecutorService applyThread = Executors.newSingleThreadExecutor();
        try (ShadowSyncValidator validator = validator(settings(bound, 60_000), List.of(held), source, () -> true,
                new ShadowSyncReport(null, null, 0))) {
            Future<?> applied = applyThread.submit(() -> {
                for (int slot = 1; slot <= 5; slot++) {
                    validator.onBlockApplied(event(slot, 3));
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (active.get() < bound && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            Thread.sleep(200);
            assertThat(active.get()).as("blocks validating at once").isEqualTo(bound);
            assertThat(validator.status().inFlight()).isEqualTo(bound);
            assertThat(source.captured.get()).as("the apply thread waits for a permit").isEqualTo(bound);
            assertThat(applied).isNotDone();

            release.countDown();
            applied.get(10, TimeUnit.SECONDS);
            assertThat(validator.awaitIdle(10, TimeUnit.SECONDS)).isTrue();

            assertThat(peak.get()).isEqualTo(bound);
            assertThat(validator.status().report().blocksValidated()).isEqualTo(5);
            assertThat(orderByBlock).hasSize(5);
            orderByBlock.forEach((thread, order) -> {
                assertThat(thread.isVirtual()).isTrue();
                assertThat(thread.getName()).startsWith("yano-shadow-sync-");
                assertThat(order).containsExactly(0, 1, 2);
            });
        } finally {
            release.countDown();
            applyThread.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    void closingLetsEveryBlockInFlightFinish() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(3);
        LedgerValidationEngine held = engine("java-julc", request -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return valid(request.txCbor(), true);
        });
        CountingSource source = new CountingSource(10);
        Path report = dir.resolve("close.jsonl");
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            ShadowSyncValidator validator = validator(settings(3, 1000), List.of(held), source, () -> true,
                    new ShadowSyncReport(report, null, 0));
            validator.onBlockApplied(event(1, 1));
            validator.onBlockApplied(event(2, 1));
            validator.onBlockApplied(event(3, 1));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> closing = closer.submit(() -> validator.close());
            assertThatThrownBy(() -> closing.get(300, TimeUnit.MILLISECONDS))
                    .as("close waits for the blocks in flight").isInstanceOf(TimeoutException.class);
            release.countDown();
            closing.get(10, TimeUnit.SECONDS);

            ShadowSyncReport.Stats stats = validator.status().report();
            assertThat(stats.blocksSubmitted()).isEqualTo(3);
            assertThat(stats.blocksValidated()).isEqualTo(3);
            assertThat(stats.blocksSkippedAtStop()).isZero();
            assertThat(stats.blockFailures()).isZero();
            assertThat(source.closed.get()).isEqualTo(3);
            assertThat(validator.availablePermits()).isEqualTo(3);
            assertThat(validator.status().inFlight()).isZero();

            validator.onBlockApplied(event(4, 1));
            assertThat(source.captured.get()).as("nothing is taken after close").isEqualTo(3);
        } finally {
            release.countDown();
            closer.shutdownNow();
        }
        JsonNode summary = lines(report).getLast();
        assertThat(summary.get("type").asText()).isEqualTo("summary");
        assertThat(summary.at("/stats/blocksSubmitted").asLong()).isEqualTo(3);
        assertThat(summary.at("/stats/blocksValidated").asLong()).isEqualTo(3);
        assertThat(summary.at("/stats/blocksSkippedAtStop").asLong()).isZero();
    }

    @Test
    @Timeout(30)
    void blocksStillValidatingWhenCloseGivesUpAreReportedSkippedAndTheirResultsDiscarded() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(2);
        LedgerValidationEngine stuck = engine("java-julc", request -> {
            entered.countDown();
            while (release.getCount() > 0) { // ignores interrupts: never returns on its own
                Thread.interrupted();
                LockSupport.parkNanos(1_000_000);
            }
            return valid(request.txCbor(), true);
        });
        CountingSource source = new CountingSource(10);
        Path report = dir.resolve("timeout.jsonl");
        ShadowSyncValidator validator = validator(settings(2, 1000), List.of(stuck), source, () -> true,
                new ShadowSyncReport(report, null, 0));
        try {
            validator.onBlockApplied(event(1, 2));
            validator.onBlockApplied(event(2, 3));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            validator.close(200, TimeUnit.MILLISECONDS);

            ShadowSyncReport.Stats stats = validator.status().report();
            assertThat(stats.blocksSubmitted()).isEqualTo(2);
            assertThat(stats.blocksSkippedAtStop()).isEqualTo(2);
            assertThat(stats.blocksFailedAfterSubmit()).isEqualTo(2);
            assertThat(stats.blocksFailedBeforeSubmit()).isZero();
            assertThat(stats.blockFailures()).isEqualTo(2);
            assertThat(stats.txsInFailedBlocks()).isEqualTo(5);
            assertThat(stats.blocksValidated()).isZero();
        } finally {
            release.countDown();
        }
        // The abandoned blocks return in the background: their states are released and their results discarded.
        assertThat(validator.awaitIdle(10, TimeUnit.SECONDS)).isTrue();
        assertThat(source.closed.get()).isEqualTo(2);
        assertThat(validator.status().report().blocksValidated()).isZero();
        assertThat(validator.availablePermits()).as("every permit returned").isEqualTo(2);

        List<JsonNode> lines = lines(report);
        assertThat(lines.stream().filter(l -> "ENGINE_FAILURE".equals(l.path("kind").asText())))
                .hasSize(2)
                .allSatisfy(l -> assertThat(l.get("reason").asText()).contains("still validating"));
        JsonNode summary = lines.getLast();
        assertThat(summary.get("type").asText()).isEqualTo("summary");
        assertThat(summary.at("/stats/blocksSubmitted").asLong()).isEqualTo(2);
        assertThat(summary.at("/stats/blocksSkippedAtStop").asLong()).isEqualTo(2);
        assertThat(summary.at("/stats/blocksFailedAfterSubmit").asLong()).isEqualTo(2);
        assertThat(summary.at("/stats/blocksValidated").asLong()).isZero();
    }

    @Test
    @Timeout(30)
    void aSlotWaitInterruptedByTheApplyWorkerStoppingIsAFailureBeforeSubmit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        LedgerValidationEngine held = engine("java-julc", request -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return valid(request.txCbor(), true);
        });
        CountingSource source = new CountingSource(10);
        Path report = dir.resolve("interrupted.jsonl");
        try (ShadowSyncValidator validator = validator(settings(1, 60_000), List.of(held), source, () -> true,
                new ShadowSyncReport(report, null, 0))) {
            validator.onBlockApplied(event(1, 1));
            Thread apply = new Thread(() -> validator.onBlockApplied(event(2, 2)), "apply");
            apply.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (apply.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.sleep(5); // waiting for the slot block 1 holds
            }
            apply.interrupt(); // what LedgerApplyProcessor does to a worker that does not stop in time
            apply.join(10_000);
            assertThat(apply.isAlive()).isFalse();
            release.countDown();
            assertThat(validator.awaitIdle(10, TimeUnit.SECONDS)).isTrue();

            ShadowSyncReport.Stats stats = validator.status().report();
            assertThat(stats.blocksFailedBeforeSubmit()).isEqualTo(1);
            assertThat(stats.blocksFailedAfterSubmit()).isZero();
            assertThat(stats.txsInFailedBlocks()).isEqualTo(2);
            assertThat(stats.blocksSubmitted()).isEqualTo(1);
            assertThat(stats.blocksValidated()).isEqualTo(1);
            // Coverage: two Conway blocks with transactions = submitted + failed before submit.
            assertThat(stats.blocksSubmitted() + stats.blocksFailedBeforeSubmit()).isEqualTo(2);
            assertThat(source.captured.get()).isEqualTo(1);
            assertThat(validator.availablePermits()).isEqualTo(1);
        } finally {
            release.countDown();
        }
        assertThat(lines(report).getFirst().get("reason").asText()).contains("interrupted");
    }

    @Test
    void aBlockRejectedBecauseCloseBeganDuringItsCaptureIsSkippedAtStop() {
        CountingSource inner = new CountingSource(10);
        AtomicReference<ShadowSyncValidator> self = new AtomicReference<>();
        PreBlockState.Source closingDuringCapture = slot -> {
            self.get().close();
            return inner.capture(slot);
        };
        ShadowSyncValidator validator = validator(settings(2, 1000), List.of(agreeing("java-julc")),
                closingDuringCapture, () -> true, new ShadowSyncReport(null, null, 0));
        self.set(validator);
        validator.onBlockApplied(event(1, 2));

        ShadowSyncReport.Stats stats = validator.status().report();
        assertThat(stats.blocksSubmitted()).isEqualTo(1);
        assertThat(stats.blocksSkippedAtStop()).isEqualTo(1);
        assertThat(stats.blocksFailedAfterSubmit()).isEqualTo(1);
        assertThat(stats.blocksValidated()).isZero();
        assertThat(inner.closed.get()).isEqualTo(1);
        assertThat(validator.status().inFlight()).isZero();
        assertThat(validator.availablePermits()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    static ShadowSyncSettings settings(int maxInFlight, long maxWaitMs) {
        return new ShadowSyncSettings(List.of("java-julc"), null, null, 10, maxInFlight, maxWaitMs, 0);
    }

    private static ShadowSyncValidator validator(ShadowSyncSettings settings, List<LedgerValidationEngine> engines,
                                                 PreBlockState.Source source,
                                                 BooleanSupplier inSection,
                                                 ShadowSyncReport report) {
        return new ShadowSyncValidator(settings, engines, (slot, view) -> ShadowSyncTestSupport.env(
                view.protocolParams().require("pp").getProtocolMajorVer()), source, hash -> null, inSection, report);
    }

    /** A Conway block at {@code slot} with {@code txs} placeholder transactions, carrying its CBOR. */
    static BlockAppliedEvent event(long slot, int txs) {
        return new BlockAppliedEvent(Era.Conway, slot, slot, String.format("%064x", slot), block(txs));
    }

    static Block block(int txs) {
        StringBuilder bodies = new StringBuilder(String.format("%02x", 0x80 + txs));
        StringBuilder witnesses = new StringBuilder(String.format("%02x", 0x80 + txs));
        List<TransactionBody> parsed = new ArrayList<>();
        for (int i = 0; i < txs; i++) {
            // The placeholder body for index i: {} is shared, so give each block body its index to keep ids apart.
            String body = "a1" + "00" + String.format("18%02x", i);
            bodies.append(body);
            witnesses.append("a0");
            parsed.add(TransactionBody.builder().txHash(SyncBlock.parse(HexUtil.decodeHexString(
                    "82" + "07" + "85" + "828080" + "81" + body + "81a0" + "a0" + "80")).txIds().getFirst()).build());
        }
        String cbor = "82" + "07" + "85" + "828080" + bodies + witnesses + "a0" + "80";
        return new Block(Era.Conway, null, parsed, List.of(), Map.of(), List.of(), cbor);
    }

    private static List<JsonNode> lines(Path report) throws Exception {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : Files.readAllLines(report)) {
            lines.add(JSON.readTree(line));
        }
        return lines;
    }
}
