package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBlockKind;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelope;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelopeHeader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSection;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the outbox against a real RocksDB, including the crash boundaries of
 * ADR-039 §11 that can be reached without a sink.
 */
class ProjectionOutboxStoreTest {
    static { RocksDB.loadLibrary(); }

    private static final ArchiveNetworkIdentity PREPROD = new ArchiveNetworkIdentity(1, "162d29c4");
    private static final Set<ProjectionSectionType> REQUIRED =
            Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY);

    @TempDir
    Path directory;

    private RocksDB db;
    private DBOptions dbOptions;
    private List<ColumnFamilyHandle> handles;
    private ProjectionOutboxStore store;

    @BeforeEach
    void open() {
        openDatabase();
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
            store = new ProjectionOutboxStore(db, handles.get(1), handles.get(2), handles.get(3), handles.get(4), handles.get(5));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void closeDatabase() {
        handles.forEach(ColumnFamilyHandle::close);
        db.close();
        dbOptions.close();
    }

    /** Simulates a process restart: everything committed survives, nothing else does. */
    private void restart() {
        closeDatabase();
        openDatabase();
    }

    @AfterEach
    void close() {
        closeDatabase();
    }

    // ------------------------------------------------------------------ helpers

    private static ProjectionEnvelopeHeader identity(long blockNumber, ProjectionBlockKind kind) {
        return new ProjectionEnvelopeHeader(PREPROD, kind, blockNumber,
                new byte[]{(byte) (blockNumber >> 8), (byte) blockNumber}, new byte[]{(byte) (blockNumber - 1)},
                blockNumber * 20, (int) (blockNumber / 100), 1_600_000_000L + blockNumber, 1, List.of(), List.of());
    }

    private static ProjectionSection section(ProjectionSectionType type, long rows, byte[]... chunks) {
        return new ProjectionSection(type, type.version(), List.of(chunks), rows);
    }

    /** One contributor commit: its section and its cursor in a single batch. */
    private void commitSection(long blockNumber, ProjectionSection section) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            store.putSection(ProjectionOutboxStore.batchWriter(batch, store.handles()), blockNumber, section);
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void commitIdentity(long blockNumber, ProjectionBlockKind kind) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            store.putBlockIdentity(ProjectionOutboxStore.batchWriter(batch, store.handles()), identity(blockNumber, kind));
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void commitCompleteBlock(long blockNumber) {
        commitIdentity(blockNumber, ProjectionBlockKind.SHELLEY_PLUS);
        commitSection(blockNumber, section(ProjectionSectionType.TRANSACTION, 2, new byte[]{1, 2}));
        commitSection(blockNumber, section(ProjectionSectionType.UTXO_HISTORY, 3, new byte[]{3, 4, 5}));
    }

    private void commitCompleteBlock(long blockNumber, long slot,
                                     ProjectionBlockKind kind, byte[] hash) {
        var header = new ProjectionEnvelopeHeader(PREPROD, kind, blockNumber,
                hash, new byte[32], slot, 0, 1_600_000_000L + blockNumber,
                1, List.of(), List.of());
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            store.putBlockIdentity(ProjectionOutboxStore.batchWriter(batch, store.handles()), header);
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (!kind.allowsEmptyEnvelope()) {
            commitSection(blockNumber, section(ProjectionSectionType.TRANSACTION, 0));
            commitSection(blockNumber, section(ProjectionSectionType.UTXO_HISTORY, 0));
        }
    }

    // ------------------------------------------------------- completion gating

    @Test
    void anEnvelopeIsIncompleteUntilEveryRequiredContributorReachesIt() {
        commitIdentity(100, ProjectionBlockKind.SHELLEY_PLUS);
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(-1);

        commitSection(100, section(ProjectionSectionType.TRANSACTION, 1, new byte[]{1}));
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(-1);

        commitSection(100, section(ProjectionSectionType.UTXO_HISTORY, 1, new byte[]{2}));
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(100);
    }

    @Test
    void canonicalProgressMayLeadASlowContributor() {
        // Core reaches 103, TRANSACTION reaches 102, UTXO_HISTORY only 101.
        for (long block = 100; block <= 103; block++) commitIdentity(block, ProjectionBlockKind.SHELLEY_PLUS);
        for (long block = 100; block <= 102; block++) {
            commitSection(block, section(ProjectionSectionType.TRANSACTION, 1, new byte[]{1}));
        }
        for (long block = 100; block <= 101; block++) {
            commitSection(block, section(ProjectionSectionType.UTXO_HISTORY, 1, new byte[]{2}));
        }
        assertThat(store.identityCursor()).isEqualTo(103);
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(101);
    }

    @Test
    void aBlockWithNoRowsForADatasetStillAdvancesThatContributor() {
        commitIdentity(100, ProjectionBlockKind.SHELLEY_PLUS);
        commitSection(100, section(ProjectionSectionType.TRANSACTION, 0));
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            store.advanceContributor(ProjectionOutboxStore.batchWriter(batch, store.handles()), ProjectionSectionType.UTXO_HISTORY, 100);
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(100);
    }

    // --------------------------------------------------------------- assembly

    @Test
    void anAssembledEnvelopeMatchesWhatWasContributed() {
        commitCompleteBlock(100);
        ProjectionEnvelope envelope = store.readEnvelope(100, REQUIRED).orElseThrow();
        assertThat(envelope.blockNumber()).isEqualTo(100);
        assertThat(envelope.sections()).hasSize(2);
        assertThat(envelope.section(ProjectionSectionType.TRANSACTION).orElseThrow().rowCount()).isEqualTo(2);
        assertThat(envelope.section(ProjectionSectionType.UTXO_HISTORY).orElseThrow().rowCount()).isEqualTo(3);
        assertThat(envelope.envelopeId()).isEqualTo(identity(100, ProjectionBlockKind.SHELLEY_PLUS).envelopeId());
    }

    @Test
    void multiChunkSectionsReassembleInOrder() {
        commitIdentity(100, ProjectionBlockKind.SHELLEY_PLUS);
        commitSection(100, section(ProjectionSectionType.TRANSACTION, 3,
                new byte[]{1}, new byte[]{2}, new byte[]{3}));
        commitSection(100, section(ProjectionSectionType.UTXO_HISTORY, 1, new byte[]{9}));
        var chunks = store.readEnvelope(100, REQUIRED).orElseThrow()
                .section(ProjectionSectionType.TRANSACTION).orElseThrow().chunks();
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).containsExactly(1);
        assertThat(chunks.get(1)).containsExactly(2);
        assertThat(chunks.get(2)).containsExactly(3);
    }

    @Test
    void anEnvelopeMissingARequiredSectionIsNotReadable() {
        commitIdentity(100, ProjectionBlockKind.SHELLEY_PLUS);
        commitSection(100, section(ProjectionSectionType.TRANSACTION, 1, new byte[]{1}));
        assertThat(store.readEnvelope(100, REQUIRED)).isEmpty();
    }

    @Test
    void anEbbProducesAReadableEmptyEnvelopeSoCoverageStaysContiguous() {
        commitIdentity(100, ProjectionBlockKind.BYRON_EBB);
        ProjectionEnvelope envelope = store.readEnvelope(100, REQUIRED).orElseThrow();
        assertThat(envelope.sections()).isEmpty();
        assertThat(envelope.header().blockKind()).isEqualTo(ProjectionBlockKind.BYRON_EBB);
    }

    // ---------------------------------------------------------- ordered reads

    @Test
    void aRangeStopsAtTheFirstIncompleteBlockRatherThanSkippingIt() {
        commitCompleteBlock(100);
        commitCompleteBlock(101);
        commitIdentity(102, ProjectionBlockKind.SHELLEY_PLUS); // incomplete
        commitCompleteBlock(103);

        var envelopes = store.readRange(100, 103, REQUIRED, 100, Long.MAX_VALUE);
        assertThat(envelopes).extracting(ProjectionEnvelope::blockNumber).containsExactly(100L, 101L);
    }

    @Test
    void aRangeHonoursBlockAndByteBounds() {
        for (long block = 100; block <= 110; block++) commitCompleteBlock(block);

        assertThat(store.readRange(100, 110, REQUIRED, 3, Long.MAX_VALUE)).hasSize(3);
        // 5 bytes per block; a 12-byte budget fits two whole blocks.
        assertThat(store.readRange(100, 110, REQUIRED, 100, 12)).hasSize(2);
    }

    @Test
    void asingleOversizedEnvelopeStillMakesProgress() {
        commitIdentity(100, ProjectionBlockKind.SHELLEY_PLUS);
        commitSection(100, section(ProjectionSectionType.TRANSACTION, 1, new byte[4096]));
        commitSection(100, section(ProjectionSectionType.UTXO_HISTORY, 1, new byte[4096]));
        assertThat(store.readRange(100, 100, REQUIRED, 100, 8)).hasSize(1);
    }

    // ----------------------------------------------- acknowledgement + cleanup

    @Test
    void acknowledgementRemovesExactlyTheAcknowledgedRange() {
        for (long block = 100; block <= 105; block++) commitCompleteBlock(block);
        store.acknowledgeThrough(102);

        assertThat(store.acknowledgedThrough()).isEqualTo(102);
        assertThat(store.readEnvelope(100, REQUIRED)).isEmpty();
        assertThat(store.readEnvelope(102, REQUIRED)).isEmpty();
        assertThat(store.readEnvelope(103, REQUIRED)).isPresent();
        assertThat(store.stats(REQUIRED).pendingBlocks()).isEqualTo(3);
    }

    @Test
    void acknowledgementIsIdempotent() {
        for (long block = 100; block <= 105; block++) commitCompleteBlock(block);
        store.acknowledgeThrough(102);
        store.acknowledgeThrough(102);
        assertThat(store.acknowledgedThrough()).isEqualTo(102);
        assertThat(store.stats(REQUIRED).pendingBlocks()).isEqualTo(3);
    }

    /** ADR-039 §11 case 8: crash after acknowledgement but during chunk cleanup. */
    @Test
    void acknowledgementSurvivesRestartAndDoesNotResurrectCleanedData() {
        for (long block = 100; block <= 105; block++) commitCompleteBlock(block);
        store.acknowledgeThrough(103);
        restart();

        assertThat(store.acknowledgedThrough()).isEqualTo(103);
        assertThat(store.readEnvelope(103, REQUIRED)).isEmpty();
        assertThat(store.readEnvelope(104, REQUIRED)).isPresent();
    }

    // ------------------------------------------------------------- rollback

    @Test
    void rollbackRemovesPendingEnvelopesAndRewindsEveryCursor() {
        for (long block = 100; block <= 105; block++) commitCompleteBlock(block);
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(105);

        store.rollbackFrom(103, REQUIRED);

        assertThat(store.completeThrough(REQUIRED)).isEqualTo(102);
        assertThat(store.identityCursor()).isEqualTo(102);
        assertThat(store.readEnvelope(103, REQUIRED)).isEmpty();
        assertThat(store.readEnvelope(102, REQUIRED)).isPresent();
    }

    @Test
    void aReplacementBlockAfterRollbackGetsADistinctEnvelopeIdentity() {
        commitCompleteBlock(100);
        String original = store.readEnvelope(100, REQUIRED).orElseThrow().envelopeId();

        store.rollbackFrom(100, REQUIRED);
        assertThat(store.readEnvelope(100, REQUIRED)).isEmpty();

        var replacement = new ProjectionEnvelopeHeader(PREPROD, ProjectionBlockKind.SHELLEY_PLUS, 100,
                new byte[]{(byte) 0xAA, (byte) 0xBB}, new byte[]{99}, 2000, 1, 1L, 1, List.of(), List.of());
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            store.putBlockIdentity(ProjectionOutboxStore.batchWriter(batch, store.handles()), replacement);
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        commitSection(100, section(ProjectionSectionType.TRANSACTION, 1, new byte[]{7}));
        commitSection(100, section(ProjectionSectionType.UTXO_HISTORY, 1, new byte[]{8}));

        assertThat(store.readEnvelope(100, REQUIRED).orElseThrow().envelopeId()).isNotEqualTo(original);
    }

    @Test
    void rollbackDoesNotRewindACursorThatIsAlreadyBehind() {
        commitCompleteBlock(100);
        commitIdentity(101, ProjectionBlockKind.SHELLEY_PLUS);
        commitSection(101, section(ProjectionSectionType.TRANSACTION, 1, new byte[]{1}));
        // UTXO_HISTORY is still at 100.
        store.rollbackFrom(101, REQUIRED);
        assertThat(store.contributorCursor(ProjectionSectionType.UTXO_HISTORY)).isEqualTo(100);
        assertThat(store.contributorCursor(ProjectionSectionType.TRANSACTION)).isEqualTo(100);
    }

    // -------------------------------------------------------------- restart

    /** ADR-039 §11 case 3: crash after a complete durable outbox commit, before the sink runs. */
    @Test
    void aCompleteEnvelopeSurvivesRestartIntact() {
        commitCompleteBlock(100);
        String before = store.readEnvelope(100, REQUIRED).orElseThrow().envelopeId();
        restart();
        ProjectionEnvelope after = store.readEnvelope(100, REQUIRED).orElseThrow();
        assertThat(after.envelopeId()).isEqualTo(before);
        assertThat(after.sections()).hasSize(2);
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(100);
    }

    /** ADR-039 §11 case 2: crash after one contributor commits, before the envelope completes. */
    @Test
    void aPartiallyContributedBlockSurvivesRestartAndRemainsIncomplete() {
        commitIdentity(100, ProjectionBlockKind.SHELLEY_PLUS);
        commitSection(100, section(ProjectionSectionType.TRANSACTION, 1, new byte[]{1}));
        restart();

        assertThat(store.completeThrough(REQUIRED)).isEqualTo(-1);
        assertThat(store.readEnvelope(100, REQUIRED)).isEmpty();
        assertThat(store.contributorCursor(ProjectionSectionType.TRANSACTION)).isEqualTo(100);
        assertThat(store.contributorCursor(ProjectionSectionType.UTXO_HISTORY)).isEqualTo(-1);

        // The lagging contributor completes after restart and the envelope becomes eligible.
        commitSection(100, section(ProjectionSectionType.UTXO_HISTORY, 1, new byte[]{2}));
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(100);
        assertThat(store.readEnvelope(100, REQUIRED)).isPresent();
    }

    @Test
    void identityRoundTripsAcrossRestart() {
        var identity = new com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity(
                PREPROD, "ducklake", 1, REQUIRED);
        store.putIdentity(identity);
        restart();
        assertThat(store.identityFingerprint()).contains(identity.fingerprint());
    }

    // --------------------------------------------------------- torn records

    @Test
    void aSectionWithFewerChunksThanItsManifestDeclaresIsRejected() {
        commitIdentity(100, ProjectionBlockKind.SHELLEY_PLUS);
        // Write a manifest claiming two chunks but only store one.
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            var declared = section(ProjectionSectionType.TRANSACTION, 2, new byte[]{1}, new byte[]{2});
            batch.put(handles.get(2), ProjectionOutboxKeys.sectionManifestKey(100, ProjectionSectionType.TRANSACTION),
                    ProjectionSectionCodec.encodeManifest(declared.manifest()));
            batch.put(handles.get(2), ProjectionOutboxKeys.sectionChunkKey(100, ProjectionSectionType.TRANSACTION, 0),
                    new byte[]{1});
            db.write(options, batch);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThatThrownBy(() -> store.readEnvelope(100, REQUIRED))
                .isInstanceOf(ProjectionOutboxException.class)
                .hasMessageContaining("manifest declares");
    }

    // -------------------------------------------------------------- metrics

    @Test
    void statsReportBacklogAndCoordinates() {
        for (long block = 100; block <= 104; block++) commitCompleteBlock(block);
        var stats = store.stats(REQUIRED);
        assertThat(stats.pendingBlocks()).isEqualTo(5);
        assertThat(stats.oldestPendingBlock()).isEqualTo(100);
        assertThat(stats.completeThroughBlock()).isEqualTo(104);
        assertThat(stats.acknowledgedThroughBlock()).isEqualTo(-1);
        assertThat(stats.pendingRows()).isEqualTo(25);
        assertThat(stats.pendingBytes()).isEqualTo(25);
        assertThat(stats.isEmpty()).isFalse();
    }

    // ------------------------------------------------- stalled vs lagging

    @Test
    void aLaggingContributorIsNotReportedAsStalled() {
        var monitor = new ProjectionContributorHealth.Monitor(java.time.Duration.ofSeconds(30));
        java.time.Instant now = java.time.Instant.EPOCH;
        for (long block = 100; block <= 103; block++) commitIdentity(block, ProjectionBlockKind.SHELLEY_PLUS);
        commitSection(100, section(ProjectionSectionType.TRANSACTION, 1, new byte[]{1}));
        commitSection(100, section(ProjectionSectionType.UTXO_HISTORY, 1, new byte[]{2}));

        var health = monitor.evaluate(store, REQUIRED, now);
        assertThat(health.status()).isEqualTo(ProjectionContributorHealth.Status.LAGGING);

        // It advances a moment later, so the stall timer resets.
        commitSection(101, section(ProjectionSectionType.TRANSACTION, 1, new byte[]{1}));
        commitSection(101, section(ProjectionSectionType.UTXO_HISTORY, 1, new byte[]{2}));
        health = monitor.evaluate(store, REQUIRED, now.plusSeconds(20));
        assertThat(health.status()).isEqualTo(ProjectionContributorHealth.Status.LAGGING);
        assertThat(health.stalledFor()).isEqualTo(java.time.Duration.ZERO);
    }

    @Test
    void aContributorThatStopsAdvancingIsReportedAsStalledNotSilentlyIdle() {
        var monitor = new ProjectionContributorHealth.Monitor(java.time.Duration.ofSeconds(30));
        java.time.Instant now = java.time.Instant.EPOCH;
        for (long block = 100; block <= 110; block++) commitIdentity(block, ProjectionBlockKind.SHELLEY_PLUS);
        commitSection(100, section(ProjectionSectionType.TRANSACTION, 1, new byte[]{1}));
        // UTXO_HISTORY never contributes.

        assertThat(monitor.evaluate(store, REQUIRED, now).status())
                .isEqualTo(ProjectionContributorHealth.Status.LAGGING);

        var health = monitor.evaluate(store, REQUIRED, now.plusSeconds(31));
        assertThat(health.status()).isEqualTo(ProjectionContributorHealth.Status.STALLED);
        assertThat(health.isStalled()).isTrue();
        assertThat(health.slowestContributor()).contains(ProjectionSectionType.UTXO_HISTORY);
        assertThat(health.detail()).get().asString().contains("stalled contributor, not archive backlog");
        assertThat(health.identityCursor()).isEqualTo(110);
        assertThat(health.completeThroughBlock()).isEqualTo(-1);
    }

    @Test
    void aFullyCaughtUpProjectionIsHealthy() {
        var monitor = new ProjectionContributorHealth.Monitor(java.time.Duration.ofSeconds(30));
        commitCompleteBlock(100);
        var health = monitor.evaluate(store, REQUIRED, java.time.Instant.EPOCH.plusSeconds(600));
        assertThat(health.status()).isEqualTo(ProjectionContributorHealth.Status.HEALTHY);
        assertThat(health.contributorCursors()).containsEntry("transaction:v1", 100L);
    }

    // ------------------------------------------------- slot-based rollback

    @Test
    void rollbackBySlotRemovesExactlyTheEnvelopesNewerThanTheRollbackPoint() {
        // identity(n) puts block n at slot n*20.
        for (long block = 100; block <= 105; block++) commitCompleteBlock(block);
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(105);

        long removed = store.rollbackToSlot(102 * 20, REQUIRED);

        assertThat(removed).isEqualTo(3); // blocks 103, 104, 105
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(102);
        assertThat(store.readEnvelope(102, REQUIRED)).isPresent();
        assertThat(store.readEnvelope(103, REQUIRED)).isEmpty();
    }

    @Test
    void rollbackBySlotIsANoOpWhenNothingIsNewerThanTheRollbackPoint() {
        for (long block = 100; block <= 103; block++) commitCompleteBlock(block);
        assertThat(store.rollbackToSlot(103 * 20, REQUIRED)).isZero();
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(103);
        assertThat(store.readEnvelope(103, REQUIRED)).isPresent();
    }

    @Test
    void rollbackBySlotDoesNotDependOnReadingALiveChainTip() {
        // The whole point: the cutoff is derived from what the outbox itself recorded, so
        // a listener firing before or after chain-state rollback gets the same answer.
        for (long block = 100; block <= 110; block++) commitCompleteBlock(block);
        long first = store.rollbackToSlot(105 * 20, REQUIRED);
        long second = store.rollbackToSlot(105 * 20, REQUIRED);
        assertThat(first).isEqualTo(5);
        assertThat(second).isZero();
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(105);
    }

    @Test
    void exactRollbackToByronEbbRemovesSameSlotSuccessor() {
        byte[] ebbHash = new byte[32];
        java.util.Arrays.fill(ebbHash, (byte) 0x40);
        byte[] successorHash = new byte[32];
        java.util.Arrays.fill(successorHash, (byte) 0x41);
        commitCompleteBlock(100, 2_160, ProjectionBlockKind.BYRON_EBB, ebbHash);
        commitCompleteBlock(101, 2_160, ProjectionBlockKind.BYRON_MAIN, successorHash);

        long removed = store.rollbackToPoint(2_160, ebbHash, false, REQUIRED);

        assertThat(removed).isEqualTo(1);
        assertThat(store.readEnvelope(100, REQUIRED)).isPresent();
        assertThat(store.readEnvelope(101, REQUIRED)).isEmpty();
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(100);
    }

    @Test
    void exactRollbackToSameSlotMainRetainsMatchingEnvelope() {
        byte[] ebbHash = new byte[32];
        java.util.Arrays.fill(ebbHash, (byte) 0x50);
        byte[] successorHash = new byte[32];
        java.util.Arrays.fill(successorHash, (byte) 0x51);
        commitCompleteBlock(100, 2_160, ProjectionBlockKind.BYRON_EBB, ebbHash);
        commitCompleteBlock(101, 2_160, ProjectionBlockKind.BYRON_MAIN, successorHash);

        assertThat(store.rollbackToPoint(2_160, successorHash, false, REQUIRED)).isZero();
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(101);
    }

    @Test
    void exactOriginRollbackClearsPendingProjectionState() {
        commitCompleteBlock(100);
        commitCompleteBlock(101);

        assertThat(store.rollbackToPoint(0, null, true, REQUIRED)).isEqualTo(2);
        assertThat(store.identityCursor()).isEqualTo(99);
        assertThat(store.readEnvelope(100, REQUIRED)).isEmpty();
    }

    // ------------------------------------- lingering batches are not durable state

    @Test
    void aRestartWhileEnvelopesAreLingeringLeavesThemUnacknowledgedAndRebuildsTheSameBatch() {
        // Near tip the accumulator holds envelopes until 50 arrive or 15 minutes elapse. A batch
        // that is still lingering has no durable footprint, so a restart must lose nothing.
        var policy = new ProjectionBatchPolicy(10_000, 50, 20_000,
                java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(15),
                1L << 30, 1_000_000, 1L << 30, 600, 64L << 20, 4_000_000);

        for (long block = 100; block < 130; block++) commitCompleteBlock(block);

        var before = new ProjectionBatchAccumulator(policy);
        var beforeIds = new ArrayList<String>();
        for (ProjectionEnvelope envelope : store.readRange(100, store.completeThrough(REQUIRED), REQUIRED, 100, 1L << 30)) {
            before.offer(envelope, java.time.Instant.EPOCH);
            beforeIds.add(envelope.header().envelopeId());
        }
        assertThat(before.size()).isEqualTo(30);
        assertThat(before.decide(true, false, java.time.Instant.EPOCH).flush())
                .as("30 envelopes is short of the 50-envelope target and the 15-minute deadline")
                .isFalse();

        long acknowledgedBefore = store.acknowledgedThrough();
        restart();

        assertThat(store.acknowledgedThrough())
                .as("a lingering batch was never acknowledged")
                .isEqualTo(acknowledgedBefore);
        assertThat(store.completeThrough(REQUIRED)).isEqualTo(129);

        var after = new ProjectionBatchAccumulator(policy);
        var afterIds = new ArrayList<String>();
        for (ProjectionEnvelope envelope : store.readRange(100, store.completeThrough(REQUIRED), REQUIRED, 100, 1L << 30)) {
            after.offer(envelope, java.time.Instant.EPOCH);
            afterIds.add(envelope.header().envelopeId());
        }

        assertThat(afterIds).containsExactlyElementsOf(beforeIds);
        assertThat(after.size()).isEqualTo(before.size());
        assertThat(after.rows()).isEqualTo(before.rows());
        assertThat(after.encodedBytes()).isEqualTo(before.encodedBytes());
    }
}
