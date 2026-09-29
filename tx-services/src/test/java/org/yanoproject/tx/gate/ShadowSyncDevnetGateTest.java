package org.yanoproject.tx.gate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.runtime.events.PropagatingEventBus;
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncReport;
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncValidator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 7a gate: shadow sync on a devnet.
 *
 * <ul>
 *   <li>A producer ({@link DevnetGateNode}, admission engine {@code java}) runs the Phase 6 matrix
 *       ({@link LedgerRulesDevnetMatrix}: dependent chains in one block and across blocks, a rollback, an epoch
 *       crossing with chains pending, 400 chained payments while forging), with shadow sync on: its blocks are
 *       captured in the producer's store section, after its boundary section.</li>
 *   <li>An in-process follower ({@link DevnetFollowerNode}, admission on the legacy default) syncs the chain over
 *       node-to-node from genesis with shadow sync on: its blocks are captured on the follower apply path, where the
 *       epoch boundary and the block share one write section.</li>
 * </ul>
 * Both must validate every transaction of every Conway block with no disagreement, no engine failure and no block
 * that could not be validated, and release every snapshot. Runs with {@code -PledgerRulesGate=true}.
 */
@EnabledIfSystemProperty(named = "yano.gate.devnet", matches = "true")
class ShadowSyncDevnetGateTest {

    private static final Logger log = LoggerFactory.getLogger(ShadowSyncDevnetGateTest.class);

    @TempDir
    Path dir;

    @Test
    void everyTransactionOfEverySyncedBlockValidatesOnTheProducerAndOnAFollower() throws Exception {
        Path producerReport = dir.resolve("producer-shadow-sync.jsonl");
        Path followerReport = dir.resolve("follower-shadow-sync.jsonl");
        Path followerDumps = dir.resolve("follower-dumps");
        Map<String, Object> producerOptions = Map.of(
                YanoPropertyKeys.Validation.SHADOW_SYNC, "true",
                YanoPropertyKeys.Validation.SHADOW_SYNC_REPORT, producerReport.toString(),
                YanoPropertyKeys.Validation.SHADOW_SYNC_SUMMARY_SECONDS, "10");
        try (DevnetGateNode producer = DevnetGateNode.start(new DevnetGateNode.Settings("java", 500, 100,
                producerOptions));
             DevnetFollowerNode follower = DevnetFollowerNode.start(producer, Map.of(
                     YanoPropertyKeys.Validation.ENGINE, "scalus",
                     YanoPropertyKeys.Validation.SHADOW_SYNC, "true",
                     YanoPropertyKeys.Validation.SHADOW_SYNC_REPORT, followerReport.toString(),
                     YanoPropertyKeys.Validation.SHADOW_SYNC_DUMP_DIR, followerDumps.toString(),
                     YanoPropertyKeys.Validation.SHADOW_SYNC_SUMMARY_SECONDS, "10"))) {
            long started = System.nanoTime();
            LedgerRulesDevnetMatrix.Report matrix = new LedgerRulesDevnetMatrix(producer,
                    LedgerRulesDevnetMatrix.DEVNET_MNEMONIC).run();
            matrix.lines().forEach(line -> log.info("GATE shadow-sync matrix | {}", line));
            assertThat(matrix.problems()).isEmpty();

            follower.awaitTip(producer, 120_000);
            ShadowSyncValidator producerSync = producer.engines().shadowSync();
            ShadowSyncValidator followerSync = follower.shadowSync();
            assertThat(producerSync).as("producer shadow sync").isNotNull();
            assertThat(followerSync).as("follower shadow sync").isNotNull();
            assertThat(producerSync.awaitIdle(60, TimeUnit.SECONDS)).isTrue();
            assertThat(followerSync.awaitIdle(60, TimeUnit.SECONDS)).isTrue();
            double seconds = (System.nanoTime() - started) / 1e9;

            ShadowSyncReport.Stats p = producerSync.status().report();
            ShadowSyncReport.Stats f = followerSync.status().report();
            log.info("GATE shadow-sync producer | {}", ShadowSyncReport.summary(p));
            log.info("GATE shadow-sync follower | {}", ShadowSyncReport.summary(f));
            log.info("GATE shadow-sync throughput | follower {} txs in {} blocks, validation {} ms "
                            + "({} ms/tx), backpressure waits {} ({} ms), run {} s",
                    f.validated("java"), f.blocksValidated(), f.validationMillis(),
                    f.validated("java") == 0 ? 0 : String.format("%.2f", f.validationMillis()
                            / (double) f.validated("java")), f.backpressureWaits(), f.backpressureWaitMillis(),
                    String.format("%.1f", seconds));

            int pv = producer.protocolParams().getProtocolMajorVer();
            for (ShadowSyncReport.Stats stats : List.of(p, f)) {
                assertThat(stats.bodyChecks()).isEqualTo(stats.blocksValidated());
                assertThat(stats.bodyViolations()).isZero();
                assertThat(stats.exUnitsChecks()).isEqualTo(stats.blocksValidated());
                assertThat(stats.exUnitsViolations()).isZero();
                assertThat(stats.validated("java")).isGreaterThan(400);
                assertThat(stats.agreed("java")).isEqualTo(stats.validated("java"));
                assertThat(stats.disagreedTotal()).isZero();
                assertThat(stats.engineFailuresTotal()).isZero();
                assertThat(stats.blockFailures()).isZero();
                assertThat(stats.idMismatches()).isZero();
                assertThat(stats.refScriptViolations()).isZero();
                assertThat(stats.refScriptChecks()).isEqualTo(stats.blocksValidated());
                assertThat(stats.byEngine().get("java")).containsOnlyKeys(pv);
            }
            assertThat(followerSync.status().outsideWriteSection()).isZero();
            for (var node : List.of(producer.node(), follower.node())) {
                PropagatingEventBus bus = (PropagatingEventBus) node.kernel().orElseThrow().context().eventBus();
                List<PropagatingEventBus.SubscriberInfo> subscribers = bus.subscribers(BlockAppliedEvent.class);
                log.info("GATE shadow-sync BlockAppliedEvent subscribers | {}", subscribers);
                ShadowSyncOrderingGuard.assertCaptureRunsFirst(subscribers);
            }
            // Case C forges transactions in the first block of an epoch (its boundary and the block share the
            // follower's write section): they were validated against the post-boundary state.
            assertThat(f.boundaryBlocks()).isPositive();
            assertThat(Files.exists(followerDumps)).isFalse();
            // The follower's admission stays legacy (no snapshots): once idle, every pre-block snapshot is released.
            assertThat(follower.gate().liveSnapshotCount()).isZero();
        }
        // Closing wrote the summaries and nothing else.
        assertThat(Files.readAllLines(followerReport)).hasSize(1).first().asString().contains("\"type\":\"summary\"");
        assertThat(Files.readAllLines(producerReport)).hasSize(1).first().asString().contains("\"type\":\"summary\"");
    }
}
