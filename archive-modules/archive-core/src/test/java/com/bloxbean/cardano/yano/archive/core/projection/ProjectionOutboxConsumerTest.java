package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveSafetyWindows;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBlockKind;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelopeHeader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionReceipt;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionReceiptMismatchException;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSection;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers the outbox-side crash boundaries of ADR-039 §11 using a synthetic sink. */
class ProjectionOutboxConsumerTest {
    static { RocksDB.loadLibrary(); }

    private static final ArchiveNetworkIdentity PREPROD = new ArchiveNetworkIdentity(1, "162d29c4");
    private static final Set<ProjectionSectionType> REQUIRED =
            Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY);
    /** k=10 keeps the finality window small enough to reason about in a test. */
    private static final ArchiveSafetyWindows WINDOWS = ArchiveSafetyWindows.resolve(10, 10L, 10L);

    @TempDir Path directory;

    private RocksDB db;
    private DBOptions dbOptions;
    private List<ColumnFamilyHandle> handles;
    private ProjectionOutboxStore store;
    private SyntheticProjectionSink sink;
    private NoopArtifactReader artifacts;
    private ProjectionIdentity identity;
    private final AtomicLong tip = new AtomicLong(0);
    private final AtomicLong rollbackFloor = new AtomicLong(0);

    @BeforeEach
    void setUp() {
        openDatabase();
        sink = new SyntheticProjectionSink();
        artifacts = new NoopArtifactReader();
        identity = new ProjectionIdentity(PREPROD, "synthetic", 1, REQUIRED);
        sink.initialize(identity);
    }

    private void openDatabase() {
        try {
            dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
            List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
            descriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY));
            for (String name : ProjectionCfNames.ALL) {
                descriptors.add(new ColumnFamilyDescriptor(name.getBytes(StandardCharsets.UTF_8)));
            }
            handles = new ArrayList<>();
            db = RocksDB.open(dbOptions, directory.resolve("db").toString(), descriptors, handles);
            store = new ProjectionOutboxStore(db, handles.get(1), handles.get(2), handles.get(3), handles.get(4));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void restartNode() {
        handles.forEach(ColumnFamilyHandle::close);
        db.close();
        dbOptions.close();
        openDatabase();
        sink.simulateRestart();
    }

    @AfterEach
    void tearDown() {
        handles.forEach(ColumnFamilyHandle::close);
        db.close();
        dbOptions.close();
    }

    // ------------------------------------------------------------------ helpers

    private ProjectionOutboxConsumer consumer() {
        return consumer(ProjectionConsumerBounds.defaults());
    }

    private ProjectionOutboxConsumer consumer(ProjectionConsumerBounds bounds) {
        return new ProjectionOutboxConsumer(store, sink, identity, new ProjectionFinalityGate(WINDOWS),
                bounds, artifacts, tip::get, rollbackFloor::get, COMMIT_EVERY_PASS);
    }

    private ProjectionOutboxConsumer consumer(ProjectionBatchPolicy policy) {
        return consumer(ProjectionConsumerBounds.defaults(), policy);
    }

    private ProjectionOutboxConsumer consumer(ProjectionConsumerBounds bounds, ProjectionBatchPolicy policy) {
        return new ProjectionOutboxConsumer(store, sink, identity, new ProjectionFinalityGate(WINDOWS),
                bounds, artifacts, tip::get, rollbackFloor::get, policy);
    }

    /** Production-shaped near-tip policy: preferred target 50 envelopes, hard bound 15 minutes. */
    private static final ProjectionBatchPolicy NEAR_TIP = new ProjectionBatchPolicy(
            10_000, 50, 20_000, Duration.ofSeconds(30), Duration.ofMinutes(15),
            256L << 20, 2_000_000, 512L << 20, 600, 64L << 20, 4_000_000);

    /**
     * These tests exercise the eligibility gate, receipt identity, retention health and
     * artifact handshake, not batch sizing. A one-block target commits on every pass so each
     * of those behaviours is observed directly; batch sizing has its own tests.
     */
    private static final ProjectionBatchPolicy COMMIT_EVERY_PASS = new ProjectionBatchPolicy(
            1, 1, 20_000, Duration.ofSeconds(30), Duration.ofMinutes(15),
            256L << 20, 2_000_000, 512L << 20, 600, 64L << 20, 4_000_000);

    private void commitBlock(long blockNumber) {
        var header = new ProjectionEnvelopeHeader(PREPROD, ProjectionBlockKind.SHELLEY_PLUS, blockNumber,
                new byte[]{(byte) (blockNumber >> 8), (byte) blockNumber}, new byte[]{(byte) (blockNumber - 1)},
                blockNumber * 20, 1, 1L, 1, List.of(), List.of());
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            store.putBlockIdentity(ProjectionOutboxStore.batchWriter(batch, store.handles()), header);
            // Real encoded facts: the consumer materialises rows through the fact codec,
            // so a placeholder payload would (correctly) be rejected as corrupt.
            store.putSection(ProjectionOutboxStore.batchWriter(batch, store.handles()), blockNumber,
                    new ProjectionSection(ProjectionSectionType.TRANSACTION,
                            ProjectionSectionType.TRANSACTION.version(),
                            List.of(ProjectionFactCodec.encodeTransactions(List.of(
                                    new com.bloxbean.cardano.yano.archive.core.dataset.TransactionFact(
                                            new byte[]{(byte) blockNumber}, 0, true, 100L)))), 1));
            store.putSection(ProjectionOutboxStore.batchWriter(batch, store.handles()), blockNumber,
                    new ProjectionSection(ProjectionSectionType.UTXO_HISTORY,
                            ProjectionSectionType.UTXO_HISTORY.version(),
                            List.of(ProjectionFactCodec.encodeUtxoHistory(
                                    new com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryFact(
                                            6, List.of(), List.of(), List.of(), List.of(), List.of(),
                                            List.of(), List.of(), List.of()))), 0));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void commitBlocks(long from, long to) {
        for (long block = from; block <= to; block++) commitBlock(block);
    }

    // ---------------------------------------------------------- finality gating

    @Test
    void nothingIsConsumedUntilBlocksAreRollbackSafe() {
        commitBlocks(0, 5);
        tip.set(5);
        assertThat(consumer().drainOnce().outcome()).isEqualTo(ProjectionConsumerResult.Outcome.IDLE);
        assertThat(sink.appendCalls()).isZero();

        tip.set(15); // eligible through 15 - 10 = 5
        var result = consumer().drainOnce();
        assertThat(result.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.COMMITTED);
        assertThat(result.lastBlock()).isEqualTo(5);
    }

    @Test
    void theGateNeverExceedsTheFinalityDepth() {
        commitBlocks(0, 20);
        tip.set(18); // eligible through 8
        var result = consumer().drainOnce();
        assertThat(result.lastBlock()).isEqualTo(8);
        assertThat(sink.committedBlocks()).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }

    @Test
    void anIncompleteEnvelopeStopsTheBatchEvenWhenItIsRollbackSafe() {
        commitBlocks(0, 3);
        // Block 4 has identity but no sections.
        var header = new ProjectionEnvelopeHeader(PREPROD, ProjectionBlockKind.SHELLEY_PLUS, 4,
                new byte[]{4}, new byte[]{3}, 80, 1, 1L, 1, List.of(), List.of());
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            store.putBlockIdentity(ProjectionOutboxStore.batchWriter(batch, store.handles()), header);
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        commitBlocks(5, 8);
        tip.set(30);

        var result = consumer().drainOnce();
        assertThat(result.lastBlock()).isEqualTo(3);
        assertThat(sink.committedBlocks()).doesNotContain(4L, 5L);
    }

    // ------------------------------------------------------- receipts and retry

    /** ADR-039 §11 case 7: crash after sink commit but before outbox acknowledgement. */
    @Test
    void aCrashBetweenSinkCommitAndAcknowledgementReplaysWithoutDuplicating() {
        commitBlocks(0, 5);
        tip.set(15);
        sink.throwAfterNextCommit();

        assertThatThrownBy(() -> consumer().drainOnce()).isInstanceOf(ProjectionSinkException.class);
        assertThat(store.acknowledgedThrough()).isEqualTo(-1); // not acknowledged
        assertThat(sink.committedBlocks()).hasSize(6);         // but durably committed

        restartNode();
        var result = consumer().drainOnce();
        assertThat(result.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.REPLAYED);
        assertThat(store.acknowledgedThrough()).isEqualTo(5);
        assertThat(sink.appendCalls()).isEqualTo(1); // no second write
        assertThat(sink.committedBlocks()).hasSize(6);
    }

    @Test
    void aDifferentlyShapedRetryForTheSameRangeIsRejected() {
        commitBlocks(0, 5);
        tip.set(15);
        var foreign = new ProjectionReceipt(identity.fingerprint(), 0, 5, "aa", "bb", 6,
                Map.of(), "deadbeef", Instant.EPOCH);
        sink.forceReceipt(0, foreign);

        assertThatThrownBy(() -> consumer().drainOnce())
                .isInstanceOf(ProjectionReceiptMismatchException.class)
                .hasMessageContaining("describes a different job");
        assertThat(store.acknowledgedThrough()).isEqualTo(-1);
    }

    /** ADR-039 §11 case 6: sink commit failure leaves the outbox intact and retryable. */
    @Test
    void aSinkFailureBeforeCommitLeavesTheOutboxIntact() {
        commitBlocks(0, 5);
        tip.set(15);
        sink.failNextAppends(1);

        assertThatThrownBy(() -> consumer().drainOnce()).isInstanceOf(ProjectionSinkException.class);
        assertThat(store.acknowledgedThrough()).isEqualTo(-1);
        assertThat(sink.committedBlocks()).isEmpty();
        assertThat(store.stats(REQUIRED).pendingBlocks()).isEqualTo(6);

        var result = consumer().drainOnce();
        assertThat(result.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.COMMITTED);
        assertThat(store.acknowledgedThrough()).isEqualTo(5);
    }

    @Test
    void repeatedSinkOutageKeepsTheBacklogBoundedAndOrderedOnRecovery() {
        commitBlocks(0, 20);
        tip.set(40);
        sink.failNextAppends(3);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> consumer().drainOnce()).isInstanceOf(ProjectionSinkException.class);
        }
        assertThat(store.acknowledgedThrough()).isEqualTo(-1);

        var result = consumer().drainOnce();
        assertThat(result.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.COMMITTED);
        assertThat(sink.committedBlocks()).isSorted();
        assertThat(sink.committedBlocks()).containsExactlyElementsOf(
                java.util.stream.LongStream.rangeClosed(0, 20).boxed().toList());
    }

    // ------------------------------------------------------------ progress loop

    @Test
    void repeatedDrainsAdvanceContiguouslyAndThenGoIdle() {
        commitBlocks(0, 100);
        tip.set(120);
        var bounds = new ProjectionConsumerBounds(10, 1 << 20, 1000, 5000, 1 << 30, 4L << 30);

        List<Long> lastBlocks = new ArrayList<>();
        ProjectionConsumerResult result;
        while ((result = consumer(bounds).drainOnce()).madeProgress()) {
            lastBlocks.add(result.lastBlock());
        }
        assertThat(result.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.IDLE);
        assertThat(lastBlocks).isSorted();
        assertThat(store.acknowledgedThrough()).isEqualTo(110 - 10);
        assertThat(sink.committedBlocks()).containsExactlyElementsOf(
                java.util.stream.LongStream.rangeClosed(0, 100).boxed().toList());
        assertThat(store.stats(REQUIRED).pendingBlocks()).isZero();
    }

    @Test
    void acknowledgedChunksAreDeletedSoTheOutboxDoesNotGrowWithoutBound() {
        commitBlocks(0, 50);
        tip.set(100);
        consumer().drainOnce();
        var stats = store.stats(REQUIRED);
        assertThat(stats.pendingBlocks()).isZero();
        assertThat(stats.pendingBytes()).isZero();
    }

    // ------------------------------------------------------------ backpressure

    @Test
    void softAndHardBacklogBoundsAreReportedRatherThanDiscardingData() {
        commitBlocks(0, 20);
        var bounds = new ProjectionConsumerBounds(10, 1 << 20, 10, 15, 1L << 40, 2L << 40);
        var pressure = consumer(bounds).backpressure();
        assertThat(pressure.level()).isEqualTo(ProjectionBackpressure.Level.PAUSE_INGEST);
        assertThat(pressure.pausesIngest()).isTrue();
        assertThat(pressure.reason()).isPresent();
        // Nothing was dropped to satisfy the bound.
        assertThat(store.stats(REQUIRED).pendingBlocks()).isEqualTo(21);
    }

    @Test
    void aSmallBacklogIsNormal() {
        commitBlocks(0, 3);
        assertThat(consumer().backpressure().level()).isEqualTo(ProjectionBackpressure.Level.NORMAL);
    }

    // -------------------------------------------------------- retention health

    @Test
    void retentionViolationPausesRatherThanNarrowingEligibility() {
        // An artifact requiring slot 500 while the common floor has advanced to 900.
        var artifact = new com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef(
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE, 7, 3, 60,
                com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRepresentation.IMMUTABLE_GENERATION,
                "gen/7", 1, "v1", java.util.OptionalLong.empty(), "", 500);
        var header = new ProjectionEnvelopeHeader(PREPROD, ProjectionBlockKind.SHELLEY_PLUS, 0,
                new byte[]{0}, new byte[]{0}, 0, 0, 1L, 1, List.of(), List.of(artifact));
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            store.putBlockIdentity(ProjectionOutboxStore.batchWriter(batch, store.handles()), header);
            store.putSection(ProjectionOutboxStore.batchWriter(batch, store.handles()), 0,
                    new ProjectionSection(ProjectionSectionType.TRANSACTION,
                            ProjectionSectionType.TRANSACTION.version(),
                            List.of(ProjectionFactCodec.encodeTransactions(List.of())), 0));
            store.putSection(ProjectionOutboxStore.batchWriter(batch, store.handles()), 0,
                    new ProjectionSection(ProjectionSectionType.UTXO_HISTORY,
                            ProjectionSectionType.UTXO_HISTORY.version(),
                            List.of(ProjectionFactCodec.encodeUtxoHistory(
                                    new com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryFact(
                                            6, List.of(), List.of(), List.of(), List.of(), List.of(),
                                            List.of(), List.of(), List.of()))), 0));
            batch.put(handles.get(4), ProjectionOutboxKeys.artifactKey(0, "EPOCH_STAKE", 7, artifact.sourceGeneration()),
                    ProjectionSectionCodec.encodeArtifact(artifact));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        tip.set(20);
        rollbackFloor.set(900);

        var result = consumer().drainOnce();
        assertThat(result.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.PAUSED);
        assertThat(result.detail()).isPresent();
        assertThat(sink.appendCalls()).isZero();
        assertThat(store.acknowledgedThrough()).isEqualTo(-1);
        // The source was not pruned and eligibility was not narrowed.
        assertThat(store.stats(REQUIRED).pendingBlocks()).isEqualTo(1);
    }

    @Test
    void aHealthyRetentionMarginAllowsProgress() {
        commitBlocks(0, 5);
        tip.set(20);
        rollbackFloor.set(0); // unbounded retention must not stall the archive
        assertThat(consumer().drainOnce().outcome()).isEqualTo(ProjectionConsumerResult.Outcome.COMMITTED);
    }

    @Test
    void artifactAcknowledgementHappensOnlyAfterTheReceiptIsVerified() {
        commitBlocks(0, 5);
        tip.set(20);
        sink.failNextAppends(1);
        assertThatThrownBy(() -> consumer().drainOnce()).isInstanceOf(ProjectionSinkException.class);
        assertThat(artifacts.acknowledged()).isEmpty();
    }

    // --------------------------------------------------- batching inside the drain loop

    @Test
    void arrivalsBelowTheTargetAccumulateWithoutWritingAnything() {
        commitBlocks(0, 19);
        tip.set(200);
        var consumer = consumer(NEAR_TIP);

        var result = consumer.drainOnce(Instant.EPOCH);

        assertThat(result.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.ACCUMULATING);
        assertThat(result.workPending()).isFalse();
        assertThat(consumer.pendingBatchBlocks()).isEqualTo(20);
        assertThat(sink.appendCalls()).isZero();
        assertThat(store.acknowledgedThrough()).isEqualTo(-1);
    }

    @Test
    void aPassWithNoNewArrivalsStillCommitsWhenTheDeadlineArrives() {
        // The freshness deadline exists precisely for the case where arrivals stop. A drain
        // pass that reads nothing must still evaluate the pending batch, or the deadline is
        // unreachable and the batch lingers forever.
        commitBlocks(0, 19);
        tip.set(200);
        var consumer = consumer(NEAR_TIP);

        assertThat(consumer.drainOnce(Instant.EPOCH).outcome())
                .isEqualTo(ProjectionConsumerResult.Outcome.ACCUMULATING);
        // Many passes with no new blocks at all.
        for (int pass = 1; pass <= 10; pass++) {
            assertThat(consumer.drainOnce(Instant.EPOCH.plusSeconds(pass)).outcome())
                    .isEqualTo(ProjectionConsumerResult.Outcome.ACCUMULATING);
        }

        var result = consumer.drainOnce(Instant.EPOCH.plus(Duration.ofMinutes(15)));

        assertThat(result.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.COMMITTED);
        assertThat(result.firstBlock()).isEqualTo(0);
        assertThat(result.lastBlock()).isEqualTo(19);
        assertThat(consumer.lastBatchDecision().reason())
                .isEqualTo(ProjectionBatchDecision.Reason.LINGER_EXPIRED);
        assertThat(store.acknowledgedThrough()).isEqualTo(19);
    }

    @Test
    void reachingTheTargetCommitsWithoutWaitingForTheDeadline() {
        commitBlocks(0, 49);
        tip.set(200);
        var consumer = consumer(NEAR_TIP);

        var result = consumer.drainOnce(Instant.EPOCH);

        assertThat(result.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.COMMITTED);
        assertThat(result.lastBlock()).isEqualTo(49);
        assertThat(consumer.lastBatchDecision().reason())
                .isEqualTo(ProjectionBatchDecision.Reason.MIN_BLOCKS);
    }

    @Test
    void aLargeBacklogUsesTheBootstrapRegimeEvenThoughTheProducerIsAtTip() {
        commitBlocks(0, 59);
        tip.set(200);
        var consumer = consumer(NEAR_TIP);
        assertThat(consumer.nearTip()).isTrue();

        // A backlog past the bootstrap target switches the regime back, so a sink that falls
        // behind at tip is not throttled to 50-block commits.
        var bootstrapPolicy = new ProjectionBatchPolicy(40, 50, 20_000,
                Duration.ofSeconds(30), Duration.ofMinutes(15),
                256L << 20, 2_000_000, 512L << 20, 600, 64L << 20, 4_000_000);
        var bootstrapConsumer = consumer(bootstrapPolicy);
        assertThat(bootstrapConsumer.nearTip()).isFalse();

        var result = bootstrapConsumer.drainOnce(Instant.EPOCH);
        assertThat(result.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.COMMITTED);
        assertThat(bootstrapConsumer.lastBatchDecision().reason())
                .isEqualTo(ProjectionBatchDecision.Reason.MIN_BLOCKS);
        assertThat(result.lastBlock()).isGreaterThanOrEqualTo(40);
    }

    @Test
    void aRollbackDiscardsWhateverTheDrainThreadHadBuffered() {
        commitBlocks(0, 19);
        tip.set(200);
        var consumer = consumer(NEAR_TIP);
        assertThat(consumer.drainOnce(Instant.EPOCH).outcome())
                .isEqualTo(ProjectionConsumerResult.Outcome.ACCUMULATING);
        assertThat(consumer.pendingBatchBlocks()).isEqualTo(20);

        // Blocks 16..19 are rolled back; the buffered copies describe a discarded fork.
        store.rollbackToSlot(15 * 20L, REQUIRED);
        consumer.discardPendingBatch();

        assertThat(consumer.drainOnce(Instant.EPOCH.plusSeconds(1)).outcome())
                .as("the pass that observes the rollback drops the buffer and reads nothing")
                .isEqualTo(ProjectionConsumerResult.Outcome.IDLE);
        assertThat(consumer.pendingBatchBlocks()).isZero();

        // The surviving chain is re-read from durable state and buffered afresh, so its
        // freshness deadline runs from the moment it was re-read.
        Instant reopened = Instant.EPOCH.plusSeconds(2);
        assertThat(consumer.drainOnce(reopened).outcome())
                .isEqualTo(ProjectionConsumerResult.Outcome.ACCUMULATING);
        assertThat(consumer.pendingBatchBlocks()).isEqualTo(16);

        // Nothing above 15 survives from the discarded fork.
        var reread = consumer.drainOnce(reopened.plus(Duration.ofMinutes(15)));
        assertThat(reread.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.COMMITTED);
        assertThat(reread.firstBlock()).isEqualTo(0);
        assertThat(reread.lastBlock()).isEqualTo(15);
        assertThat(sink.committedBlocks()).containsExactlyElementsOf(
                java.util.stream.LongStream.rangeClosed(0, 15).boxed().toList());
    }

    @Test
    void aBatchThatCanNoLongerGrowCommitsInsteadOfSpinning() {
        // A byte ceiling far below the block target: without a saturation trigger the
        // coordinator would re-offer an envelope that can never fit, forever.
        commitBlocks(0, 59);
        tip.set(200);
        var tinyBytes = new ProjectionBatchPolicy(10_000, 50, 20_000,
                Duration.ofSeconds(30), Duration.ofMinutes(15), 200, 2_000_000, 512L << 20, 600,
                64L << 20, 4_000_000);
        var consumer = consumer(tinyBytes);

        var result = consumer.drainOnce(Instant.EPOCH);

        assertThat(result.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.COMMITTED);
        assertThat(consumer.lastBatchDecision().reason())
                .isEqualTo(ProjectionBatchDecision.Reason.MAX_BYTES);
        assertThat(result.lastBlock()).isLessThan(49);
    }

    @Test
    void moreEligibleWorkKeepsTheCoordinatorDrainingRatherThanBackingOff() {
        commitBlocks(0, 59);
        tip.set(200);
        var smallBatches = new ProjectionBatchPolicy(10_000, 10, 20_000,
                Duration.ofSeconds(30), Duration.ofMinutes(15),
                256L << 20, 2_000_000, 512L << 20, 600, 64L << 20, 4_000_000);
        // A read bound below the eligible range, so one pass cannot consume everything.
        var narrowReads = new ProjectionConsumerBounds(10, 1 << 20, 1000, 5000, 1 << 30, 4L << 30);
        var consumer = consumer(narrowReads, smallBatches);

        var first = consumer.drainOnce(Instant.EPOCH);
        assertThat(first.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.COMMITTED);
        assertThat(first.workPending())
                .as("more eligible envelopes remain, so the loop must not sleep")
                .isTrue();
    }

    // ------------------------------- artifact cleanup ordering across crash boundaries

    private static ProjectionArtifactRef artifactAt(long block, int epoch) {
        return new ProjectionArtifactRef(
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE, epoch, block,
                block * 20, com.bloxbean.cardano.yano.archive.api.projection
                        .ProjectionArtifactRepresentation.IMMUTABLE_GENERATION,
                "gen-" + epoch, 1, "state-1", java.util.OptionalLong.of(10), "", block * 20);
    }

    /**
     * The interleaving that a sink-coordinate check cannot see.
     *
     * <p>Staged evidence becomes durable outside any batch, so its reference can be written while
     * its block is already sitting in the accumulator. The accumulator holds envelopes across
     * drain passes, and the sink lags it - so the reference is inserted against a sink that is
     * legitimately still behind, and the buffered envelope that later commits does not carry it.
     * acknowledgeThrough then deletes a reference the sink never received, destroying reward,
     * DRep or governance evidence that cannot be recomputed.
     *
     * <p>The fix is to re-read artifacts at flush rather than trust the buffered copy, so this
     * asserts the late reference is committed and acknowledged rather than silently dropped.
     */
    @Test
    void anArtifactStagedWhileItsBlockIsBufferedIsStillCommitted() {
        tip.set(10_000);
        commitBlock(0);
        commitBlock(1);

        // A policy that will not flush yet, so block 1 and 2 sit buffered in the accumulator.
        var consumer = consumer(ProjectionConsumerBounds.defaults(), NEAR_TIP);
        var buffering = consumer.drainOnce(Instant.EPOCH);
        assertThat(buffering.outcome())
                .as("the envelopes must still be buffered for this to be the race under test")
                .isEqualTo(ProjectionConsumerResult.Outcome.ACCUMULATING);

        // Staging completes now: the evidence is durable and its reference is written directly,
        // against a sink that has committed nothing at all.
        var late = artifactAt(1, 77);
        store.putArtifactDirect(1, late);

        // Flush the buffered range.
        var committed = consumer.drainOnce(Instant.EPOCH.plus(java.time.Duration.ofMinutes(20)));

        assertThat(committed.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.COMMITTED);
        assertThat(artifacts.acknowledged())
                .as("the late artifact must be committed and released, not deleted unwritten")
                .contains(late);
        assertThat(store.readArtifacts(1))
                .as("and its outbox reference is gone only because it was acknowledged")
                .isEmpty();
    }

    @Test
    void anArtifactCannotSlipInAfterTheFlushSnapshot() {
        tip.set(10_000);
        commitBlock(0);
        var late = artifactAt(0, 77);
        var refused = new AtomicReference<RuntimeException>();

        // This hook runs after the consumer has built the row batch but before the synthetic
        // sink makes its receipt durable. Without a seal, the direct write succeeds here and
        // acknowledgeThrough deletes a reference that was absent from the committed batch.
        sink.beforeNextCommit(() -> {
            try {
                store.putArtifactDirect(0, late);
            } catch (RuntimeException expected) {
                refused.set(expected);
            }
        });

        var committed = consumer().drainOnce(Instant.EPOCH);

        assertThat(committed.outcome()).isEqualTo(ProjectionConsumerResult.Outcome.COMMITTED);
        assertThat(refused.get())
                .as("a direct artifact write must be refused once the batch snapshot is sealed")
                .isInstanceOf(ProjectionOutboxException.class)
                .hasMessageContaining("sealed through block 0");
        assertThat(artifacts.acknowledged()).doesNotContain(late);
        assertThat(store.artifactsSealedThrough()).isZero();
    }

    @Test
    void artifactSealSurvivesRestart() {
        store.sealArtifactsThrough(5);
        restartNode();

        assertThat(store.artifactsSealedThrough()).isEqualTo(5);
        assertThatThrownBy(() -> store.putArtifactDirect(5, artifactAt(5, 77)))
                .isInstanceOf(ProjectionOutboxException.class)
                .hasMessageContaining("sealed through block 5");
    }

    /** Stage an artifact reference against a block, the way an epoch transition would. */
    private void commitArtifact(long blockNumber, ProjectionArtifactRef ref) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            store.putArtifact(ProjectionOutboxStore.batchWriter(batch, store.handles()), blockNumber, ref);
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void artifactsAreAcknowledgedBeforeTheOutboxDropsTheirReference() {
        // acknowledgeThrough() deletes the artifact references along with the range, so
        // acknowledging the range first would destroy the only record of what still needs
        // releasing. A crash in that gap pins the source forever with nothing to reconcile from.
        commitBlocks(0, 5);
        commitArtifact(3, artifactAt(3, 100));
        tip.set(200);

        var consumer = consumer();
        // Crash before any artifact is acknowledged: the range must NOT have been acknowledged,
        // so the reference survives and the next pass can retry.
        artifacts.failAcknowledgeAfter(0);
        assertThatThrownBy(() -> consumer.drainOnce(Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated crash");

        assertThat(store.acknowledgedThrough())
                .as("the range must not be acknowledged while an artifact is unreleased")
                .isEqualTo(-1);
        assertThat(artifacts.acknowledged()).isEmpty();
    }

    @Test
    void aRetryAfterACrashAcknowledgesIdempotentlyAndThenCompletes() {
        commitBlocks(0, 5);
        commitArtifact(3, artifactAt(3, 100));
        tip.set(200);
        var consumer = consumer();

        artifacts.failAcknowledgeAfter(0);
        assertThatThrownBy(() -> consumer.drainOnce(Instant.EPOCH)).isInstanceOf(IllegalStateException.class);

        // The sink already holds a durable receipt for this batch, so the retry recognises it and
        // does not rewrite rows - but it must still finish releasing the artifact.
        artifacts.failAcknowledgeAfter(Integer.MAX_VALUE);
        var result = consumer.drainOnce(Instant.EPOCH.plusSeconds(1));

        assertThat(result.outcome()).isIn(ProjectionConsumerResult.Outcome.COMMITTED,
                ProjectionConsumerResult.Outcome.REPLAYED);
        assertThat(artifacts.acknowledged()).hasSize(1);
        assertThat(store.acknowledgedThrough()).isEqualTo(5);
        assertThat(sink.appendCalls())
                .as("the receipt must have prevented a second row write")
                .isEqualTo(1);
    }

    @Test
    void acknowledgingTheSameArtifactTwiceIsHarmless() {
        // The ordering guarantees a replayed acknowledgement, so the contract must absorb it.
        var ref = artifactAt(3, 100);
        artifacts.acknowledge(ref);
        artifacts.acknowledge(ref);

        assertThat(artifacts.acknowledged()).hasSize(1);
        assertThat(artifacts.acknowledgeCalls())
                .as("it really was called twice; the reader absorbed the repeat")
                .isEqualTo(2);
    }
}
