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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
        LedgerValidationEngine held = engine("java", request -> {
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
        try (ShadowSyncValidator validator = validator(settings(2, 1, 60_000), List.of(held), source, () -> true,
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
            assertThat(stats.agreed("java")).isEqualTo(3);
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
        LedgerValidationEngine held = engine("java", request -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return valid(request.txCbor(), true);
        });
        CountingSource source = new CountingSource(10);
        Path report = dir.resolve("report.jsonl");
        try (ShadowSyncValidator validator = validator(settings(1, 1, 100), List.of(held), source, () -> true,
                new ShadowSyncReport(report, null, 0))) {
            validator.onBlockApplied(event(1, 2));
            validator.onBlockApplied(event(2, 3));
            release.countDown();
            assertThat(validator.awaitIdle(10, TimeUnit.SECONDS)).isTrue();

            ShadowSyncReport.Stats stats = validator.status().report();
            assertThat(stats.blockFailures()).isEqualTo(1);
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
        try (ShadowSyncValidator validator = validator(settings(2, 1, 1000), List.of(agreeing("java")), source,
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
            assertThat(validator.status().outsideWriteSection()).isEqualTo(1);
            assertThat(source.captured.get()).isZero();
        }
    }

    @Test
    void aPreConwayProtocolVersionIsSkippedAfterTheCapture() throws Exception {
        CountingSource source = new CountingSource(8);
        try (ShadowSyncValidator validator = validator(settings(2, 1, 1000), List.of(agreeing("java")), source,
                () -> true, new ShadowSyncReport(null, null, 0))) {
            validator.onBlockApplied(event(1, 2));
            assertThat(validator.awaitIdle(10, TimeUnit.SECONDS)).isTrue();
            assertThat(validator.status().report().blocksSkippedPreConway()).isEqualTo(1);
            assertThat(validator.status().report().blocksValidated()).isZero();
            assertThat(source.closed.get()).isEqualTo(1);
        }
    }

    @Test
    void aDisagreementIsCountedPerEngineAndVersionAndWrittenToTheReportWithABundle() throws Exception {
        LedgerValidationEngine rejectsSecond = engine("java", request -> indexOf(request.txCbor()) == 1
                ? invalid(LedgerRuleName.UTXO, "FeeTooSmallUTxO") : valid(request.txCbor(), true));
        LedgerValidationEngine unavailable = engine("amaru", request ->
                invalid(LedgerRuleName.ENGINE, "LedgerStateUnavailable"));
        Path report = dir.resolve("findings.jsonl");
        Path dumps = dir.resolve("dumps");
        CountingSource source = new CountingSource(11);
        try (ShadowSyncValidator validator = validator(settings(2, 2, 1000), List.of(rejectsSecond, unavailable),
                source, () -> true, new ShadowSyncReport(report, dumps, 10))) {
            validator.onBlockApplied(event(7, 2));
            assertThat(validator.awaitIdle(10, TimeUnit.SECONDS)).isTrue();

            ShadowSyncReport.Stats stats = validator.status().report();
            assertThat(stats.byEngine().get("java")).isEqualTo(Map.of(11, new ShadowSyncReport.Counts(2, 1, 1, 0)));
            assertThat(stats.byEngine().get("amaru")).isEqualTo(Map.of(11, new ShadowSyncReport.Counts(2, 0, 0, 2)));
            assertThat(stats.disagreedTotal()).isEqualTo(1);
            assertThat(stats.dumpsWritten()).isEqualTo(3);
        }
        List<JsonNode> lines = lines(report);
        JsonNode disagreement = lines.stream().filter(l -> "DISAGREED".equals(l.path("kind").asText())).findFirst()
                .orElseThrow();
        assertThat(disagreement.get("engine").asText()).isEqualTo("java");
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
        assertThat(summary.at("/stats/byEngine/java/11/disagreed").asLong()).isEqualTo(1);
        assertThat(summary.at("/stats/byEngine/amaru/11/engineFailures").asLong()).isEqualTo(2);
        assertThat(summary.at("/stats/dumpsWritten").asLong()).isEqualTo(3);
    }

    @Test
    void closingReleasesTheStatesOfQueuedBlocks() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        LedgerValidationEngine held = engine("java", request -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return valid(request.txCbor(), true);
        });
        CountingSource source = new CountingSource(10);
        ShadowSyncValidator validator = validator(settings(3, 1, 1000), List.of(held), source, () -> true,
                new ShadowSyncReport(null, null, 0));
        validator.onBlockApplied(event(1, 1));
        validator.onBlockApplied(event(2, 1));
        validator.onBlockApplied(event(3, 1));
        release.countDown();
        validator.close();
        assertThat(source.closed.get()).isEqualTo(3);
        assertThat(validator.status().inFlight()).isZero();
        validator.onBlockApplied(event(4, 1));
        assertThat(source.captured.get()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ helpers

    static ShadowSyncSettings settings(int maxInFlight, int threads, long maxWaitMs) {
        return new ShadowSyncSettings(List.of("java"), null, null, 10, maxInFlight, threads, maxWaitMs, 0);
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
