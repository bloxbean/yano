package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.api.projection.ArchiveArtifactReader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionMaintenance;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionReceipt;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionReceiptMismatchException;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionRowBatch;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-039 Phase 4: DuckLake as the primary projection sink.
 *
 * <p>Covers the properties the outbox depends on — all required tables plus the receipt
 * commit together, a matching retry is idempotent, a reshaped retry is rejected, and a
 * failed batch leaves nothing behind.
 */
class DuckLakeProjectionSinkTest {
    @TempDir Path temp;

    private static final ArchiveNetworkIdentity NETWORK = new ArchiveNetworkIdentity(1, "fixture-genesis");
    private static final ProjectionIdentity IDENTITY = new ProjectionIdentity(NETWORK, "ducklake", 1,
            Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY));

    private DuckLakeHistoryArchiveBackend backend;
    private DuckLakeArchiveConfig config;

    @AfterEach
    void closeBackend() {
        if (backend != null) backend.close();
    }

    private DuckLakeProjectionSink open(String name) throws Exception {
        Path root = temp.resolve(name);
        Files.createDirectories(root);
        config = new DuckLakeArchiveConfig(root.resolve("catalog.sqlite"), root.resolve("data"),
                Duration.ofSeconds(30), 10, 10, 16L * 1024 * 1024, 100_000,
                Duration.ofHours(168), Duration.ofHours(24));
        // Opening the backend creates the archive tables the sink writes into.
        backend = DuckLakeHistoryArchiveBackend.openReadOnly(
                new ArchiveIdentity(UUID.randomUUID(), "ducklake", 1, 1, "fixture-genesis"),
                config, DuckDbManagerConfig.defaults(root.resolve("tmp")),
                new PackagedDuckDbExtensionLoader(temp.resolve("extensions")));
        // Each sink owns its own manager and closes it, exactly as the provider arranges in
        // production. Sharing one across sinks would only work because of a leak.
        var sink = newSink();
        sink.initialize(IDENTITY);
        var selected = com.bloxbean.cardano.yano.archive.api.projection
                .ProjectionArtifactContracts.shipped();
        sink.initializeArtifacts(selected,
                com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactEnrollments.of(
                        selected.contracts().keySet().stream().map(dataset ->
                                new com.bloxbean.cardano.yano.archive.api.projection
                                        .ProjectionArtifactEnrollment(dataset,
                                        java.util.OptionalInt.of(0),
                                        com.bloxbean.cardano.yano.archive.api.projection
                                                .ProjectionArtifactEnrollmentOrigin.FRESH)).toList()));
        return sink;
    }

    /** A fresh sink with its own manager, mirroring what the provider constructs. */
    private DuckLakeProjectionSink newSink() {
        return new DuckLakeProjectionSink(
                new DuckDbManager(DuckDbManagerConfig.defaults(
                        config.catalogPath().getParent().resolve("tmp-" + java.util.UUID.randomUUID())),
                        new PackagedDuckDbExtensionLoader(temp.resolve("extensions"))),
                config);
    }

    private static ArchiveRow txRow(long block, int index) {
        byte[] txHash = new byte[32];
        txHash[0] = (byte) block;
        txHash[1] = (byte) index;
        byte[] blockHash = new byte[32];
        blockHash[0] = (byte) block;
        return new ArchiveRow("chain_transaction", List.of(txHash, blockHash, block, block * 20,
                block / 100, 1_600_000_000L + block, index, true, 170_000L, UUID.randomUUID()));
    }

    private static ArchiveRow inputRow(long block, int index) {
        byte[] spending = new byte[32];
        spending[0] = (byte) block;
        spending[1] = (byte) index;
        byte[] referenced = new byte[32];
        referenced[0] = (byte) (block + 1);
        byte[] blockHash = new byte[32];
        blockHash[0] = (byte) block;
        return new ArchiveRow("transaction_inputs", List.of(spending, index, 0, "input",
                referenced, 0, true, blockHash, block, block * 20, block / 100,
                1_600_000_000L + block, UUID.randomUUID()));
    }

    private static ProjectionRowBatch batch(long firstBlock, long lastBlock, List<ArchiveRow> rows) {
        java.util.Map<Long, byte[]> hashes = new java.util.LinkedHashMap<>();
        for (long block = firstBlock; block <= lastBlock; block++) {
            hashes.put(block, new byte[]{(byte) block, 9});
        }
        return new ProjectionRowBatch(IDENTITY, firstBlock, lastBlock, lastBlock - firstBlock + 1,
                "aa".repeat(32), "bb".repeat(32), "cc".repeat(32), rows, List.of(), hashes);
    }

    private static ProjectionRowBatch simpleBatch(long firstBlock, long lastBlock) {
        List<ArchiveRow> rows = new ArrayList<>();
        for (long block = firstBlock; block <= lastBlock; block++) {
            rows.add(txRow(block, 0));
            rows.add(inputRow(block, 0));
        }
        return batch(firstBlock, lastBlock, rows);
    }

    private long count(String table) throws Exception {
        try (var read = (DuckLakeReadSession) backend.openReadSession();
             Statement sql = read.connection().createStatement();
             ResultSet rs = sql.executeQuery("SELECT count(*) FROM history_lake." + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<String> partitionColumns(String table) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + config.catalogPath());
             var sql = connection.prepareStatement(
                     "SELECT c.column_name FROM ducklake_partition_info i"
                             + " JOIN ducklake_table t ON t.table_id=i.table_id"
                             + " JOIN ducklake_partition_column p ON p.partition_id=i.partition_id"
                             + " AND p.table_id=i.table_id"
                             + " JOIN ducklake_column c ON c.column_id=p.column_id"
                             + " AND c.table_id=i.table_id"
                             + " WHERE t.table_name=? AND t.end_snapshot IS NULL"
                             + " AND i.end_snapshot IS NULL AND c.end_snapshot IS NULL"
                             + " ORDER BY p.partition_key_index")) {
            sql.setString(1, table);
            try (var rows = sql.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (rows.next()) columns.add(rows.getString(1));
                return List.copyOf(columns);
            }
        }
    }

    private boolean projectionTableExists(DuckLakeArchiveConfig selectedConfig, String table)
            throws Exception {
        try (var probe = new DuckDbManager(DuckDbManagerConfig.defaults(
                selectedConfig.catalogPath().getParent().resolve("probe-tmp")),
                new PackagedDuckDbExtensionLoader(temp.resolve("extensions")));
             var lease = probe.acquire(DuckDbWorkload.STEADY, Duration.ofSeconds(30))) {
            DuckLakeSql.attach(lease.connection(), selectedConfig, null, true);
            try (var statement = lease.connection().prepareStatement(
                    "SELECT 1 FROM information_schema.tables WHERE table_name=? LIMIT 1")) {
                statement.setString(1, table);
                try (var rows = statement.executeQuery()) {
                    return rows.next();
                }
            } finally {
                DuckLakeSql.detach(lease.connection());
            }
        }
    }

    private static final ArchiveArtifactReader NO_ARTIFACTS = new ArchiveArtifactReader() {
        @Override public ArtifactLease acquire(ProjectionArtifactRef ref, Instant expiresAt) {
            throw new UnsupportedOperationException();
        }
        @Override public ArtifactPage read(ProjectionArtifactRef ref, ArtifactLease lease,
                                           Optional<String> cursor, int limit) {
            throw new UnsupportedOperationException();
        }
        @Override public void acknowledge(ProjectionArtifactRef ref) { }
    };

    @Test
    void freshProjectionArchiveDoesNotCreateReplayWorkerMetadata() throws Exception {
        try (var ignored = open("fresh-schema")) {
            assertThat(projectionTableExists(config, "archive_coverage")).isFalse();
            assertThat(projectionTableExists(config, "archive_commits")).isFalse();
            assertThat(projectionTableExists(config, "archive_commit_counts")).isFalse();
            assertThat(projectionTableExists(config, "archive_invalidations")).isFalse();
            assertThat(projectionTableExists(config, "archive_schema")).isFalse();
            assertThat(projectionTableExists(config, "projection_receipts")).isTrue();
        }
    }

    @Test
    void rewardsArePartitionedByTheirCanonicalEarnedEpoch() throws Exception {
        try (var ignored = open("reward-partition")) {
            assertThat(partitionColumns("rewards")).containsExactly("epoch");
        }
    }

    @Test
    void existingReplayMetadataIsLeftInertAndUnchanged() throws Exception {
        ArchiveIdentity archiveIdentity;
        try (var ignored = open("legacy-metadata")) {
            archiveIdentity = backend.identity();
        }
        backend.close();
        backend = null;

        try (var manager = new DuckDbManager(DuckDbManagerConfig.defaults(
                config.catalogPath().getParent().resolve("legacy-table-tmp")),
                new PackagedDuckDbExtensionLoader(temp.resolve("extensions")));
             var lease = manager.acquire(DuckDbWorkload.BULK_CATCH_UP, Duration.ofSeconds(30))) {
            DuckLakeSql.attach(lease.connection(), config, null, false);
            try (Statement sql = lease.connection().createStatement()) {
                sql.execute("CREATE TABLE history_lake.archive_coverage(marker BIGINT)");
                sql.execute("INSERT INTO history_lake.archive_coverage VALUES (7)");
            } finally {
                DuckLakeSql.detach(lease.connection());
            }
        }

        backend = DuckLakeHistoryArchiveBackend.openReadOnly(
                archiveIdentity, config,
                DuckDbManagerConfig.defaults(config.catalogPath().getParent().resolve("reopen-tmp")),
                new PackagedDuckDbExtensionLoader(temp.resolve("extensions")));
        assertThat(count("archive_coverage")).isEqualTo(1);
    }


    // --------------------------------------------------------- epoch artifacts

    @Test
    void noneCreatesNoEpochTablesAndProspectiveJoinCreatesOnlyItsSchema() throws Exception {
        Path root = temp.resolve("selected-schema");
        Files.createDirectories(root);
        var selectedConfig = new DuckLakeArchiveConfig(root.resolve("catalog.sqlite"),
                root.resolve("data"), Duration.ofSeconds(30), 10, 10,
                16L * 1024 * 1024, 100_000, Duration.ofHours(168), Duration.ofHours(24));
        var provider = new DuckLakeProjectionSinkProvider();
        var archiveIdentity = new ArchiveIdentity(UUID.randomUUID(), "ducklake", 1, 1,
                "fixture-genesis");
        try (var projection = provider.openProjectionSink(archiveIdentity, root,
                java.util.Map.of("catalog.path", selectedConfig.catalogPath().toString(),
                        "data.path", selectedConfig.dataPath().toString(),
                        "temp.path", root.resolve("tmp").toString(),
                        "extensions.path", temp.resolve("extensions").toString()))) {
            projection.initialize(IDENTITY);
            projection.initializeArtifacts(
                    com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactIdentity.NONE,
                    com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactEnrollments.NONE);

            assertThat(projectionTableExists(selectedConfig, "chain_transaction")).isTrue();
            assertThat(projectionTableExists(selectedConfig, "rewards")).isFalse();
            assertThat(projectionTableExists(selectedConfig, "epoch_stakes")).isFalse();

            var rewards = com.bloxbean.cardano.yano.archive.api.projection
                    .ProjectionArtifactIdentity.of(List.of(
                            com.bloxbean.cardano.yano.archive.api.projection
                                    .ProjectionArtifactContracts.reward()));
            var enrollment = com.bloxbean.cardano.yano.archive.api.projection
                    .ProjectionArtifactEnrollments.of(List.of(
                            new com.bloxbean.cardano.yano.archive.api.projection
                                    .ProjectionArtifactEnrollment(
                                    com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.REWARD,
                                    java.util.OptionalInt.of(500),
                                    com.bloxbean.cardano.yano.archive.api.projection
                                            .ProjectionArtifactEnrollmentOrigin.PROSPECTIVE_JOIN)));
            projection.initializeArtifacts(rewards, enrollment);

            assertThat(projectionTableExists(selectedConfig, "rewards")).isTrue();
            assertThat(projectionTableExists(selectedConfig, "epoch_stakes")).isFalse();
            assertThat(projectionTableExists(selectedConfig, "ada_pots")).isFalse();
        }
    }

    @Test
    void epochGapIsIdempotentConflictsFailAndRollbackUsesTheFullPoint() throws Exception {
        try (var sink = open("epoch-gap")) {
            var gap = new com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap(
                    com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.REWARD,
                    450, 9_000, 90_000, new byte[] {7, 7}, "io", "disk write failed",
                    Instant.parse("2026-08-27T00:00:00Z"));
            sink.recordEpochArtifactGap(gap);
            sink.recordEpochArtifactGap(gap);

            assertThat(sink.epochArtifactGaps()).singleElement()
                    .satisfies(stored -> assertThat(stored.sameOutcome(gap)).isTrue());
            assertThatThrownBy(() -> sink.recordEpochArtifactGap(
                    new com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap(
                            gap.dataset(), gap.semanticEpoch(), 9_001, 90_001, new byte[] {8},
                            "capture", "different", Instant.now())))
                    .isInstanceOf(ProjectionSinkException.class)
                    .hasMessageContaining("conflicting");

            sink.rollbackEpochArtifactCoverage(90_000, new byte[] {7, 7}, false);
            assertThat(sink.epochArtifactGaps()).hasSize(1);
            sink.rollbackEpochArtifactCoverage(90_000, new byte[] {8}, false);
            assertThat(sink.epochArtifactGaps()).isEmpty();

            var interval = new com.bloxbean.cardano.yano.archive.api.projection
                    .EpochArtifactGapInterval(gap.dataset(), 451, 452, 91_000,
                    new byte[] {8}, 92_000, new byte[] {9}, true, 450, "io");
            sink.recordEpochArtifactGapInterval(interval);
            sink.recordEpochArtifactGapInterval(interval);
            assertThat(sink.epochArtifactGapIntervals()).singleElement()
                    .satisfies(stored -> assertThat(stored.throughEpoch()).isEqualTo(452));
            sink.rollbackEpochArtifactCoverage(91_000, new byte[] {8}, false);
            assertThat(sink.epochArtifactGapIntervals()).isEmpty();
        }
    }

    @Test
    void repairedRowsAndPausedIntervalSplitCommitAtomically() throws Exception {
        try (var sink = open("epoch-interval-repair")) {
            var dataset = com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.REWARD;
            var original = new com.bloxbean.cardano.yano.archive.api.projection
                    .EpochArtifactGapInterval(dataset, 451, 453, 91_000, new byte[] {8},
                    93_000, new byte[] {10}, true, 450, "io");
            sink.replaceEpochArtifactGapIntervals(List.of(original));
            var left = new com.bloxbean.cardano.yano.archive.api.projection
                    .EpochArtifactGapInterval(dataset, 451, 451, 91_000, new byte[] {8},
                    91_000, new byte[] {8}, false, 450, "io");
            var right = new com.bloxbean.cardano.yano.archive.api.projection
                    .EpochArtifactGapInterval(dataset, 453, 453, 93_000, new byte[] {10},
                    93_000, new byte[] {10}, true, 450, "io");
            var artifact = new ProjectionArtifactRef(dataset, 452, 4, 4,
                    com.bloxbean.cardano.yano.archive.api.projection
                            .ProjectionArtifactRepresentation.STAGED_FILE,
                    "repair-452", 1, "ledger-boundary-v1/rewards",
                    java.util.OptionalLong.of(0), "digest", -1);
            var base = batchWith(0, 4, simpleBatch(0, 4).rows(), List.of(artifact));
            var repaired = new ProjectionRowBatch(base.identity(), base.firstBlock(), base.lastBlock(),
                    base.blockCount(), base.firstEnvelopeId(), base.lastEnvelopeId(),
                    base.orderedDigest(), base.rows(), base.artifacts(), base.canonicalBlockHashes(),
                    base.lastSlot(),
                    List.of(new com.bloxbean.cardano.yano.archive.api.projection
                            .EpochArtifactIntervalRepair(dataset, 450, List.of(left, right))));

            sink.append(repaired, new FakeArtifacts(List.of()));

            assertThat(sink.epochArtifactCoverage().get(dataset))
                    .containsExactly(new com.bloxbean.cardano.yano.archive.api.EpochRange(452, 452));
            assertThat(sink.epochArtifactGapIntervals())
                    .extracting(com.bloxbean.cardano.yano.archive.api.projection
                                    .EpochArtifactGapInterval::fromEpoch,
                            com.bloxbean.cardano.yano.archive.api.projection
                                    .EpochArtifactGapInterval::throughEpoch)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(451, 451),
                            org.assertj.core.groups.Tuple.tuple(453, 453));
        }
    }

    private static ProjectionArtifactRef stakeArtifact(int epoch, long block, long expectedRows) {
        return new ProjectionArtifactRef(
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE, epoch, block, block,
                com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRepresentation
                        .IMMUTABLE_GENERATION,
                "epoch-deleg-snapshot:" + epoch, 1, "ledger-boundary-v1/snapshot",
                expectedRows < 0 ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(expectedRows),
                "", block);
    }

    private static ProjectionRowBatch batchWith(long firstBlock, long lastBlock, Iterable<ArchiveRow> rows,
                                                List<ProjectionArtifactRef> artifacts) {
        java.util.Map<Long, byte[]> hashes = new java.util.LinkedHashMap<>();
        for (long block = firstBlock; block <= lastBlock; block++) {
            hashes.put(block, new byte[]{(byte) block, 9});
        }
        return new ProjectionRowBatch(IDENTITY, firstBlock, lastBlock, lastBlock - firstBlock + 1,
                "aa".repeat(32), "bb".repeat(32), "cc".repeat(32), rows, artifacts, hashes);
    }

    /** One already-materialised epoch_stakes row, exactly as the real reader emits it. */
    private static byte[] stakeRow(int epoch, long block, int index) {
        return com.bloxbean.cardano.yano.archive.api.ArchiveRowCodec.encode(new ArchiveRow("epoch_stakes",
                java.util.Arrays.asList(epoch, "key", new byte[]{(byte) index},
                        "stake_test_fixture_" + index, new byte[]{1}, 1_000L + index,
                        new byte[]{9, 9}, block, block, 1_600_000_000L, "ledger-boundary-v1/snapshot",
                        UUID.nameUUIDFromBytes(("fixture" + epoch).getBytes()))));
    }

    /** Serves a fixed set of rows per artifact, and records lease discipline. */
    private static final class FakeArtifacts implements ArchiveArtifactReader {
        private final List<byte[]> rows;
        int leasesOpened;
        int leasesClosed;
        int acknowledged;

        FakeArtifacts(List<byte[]> rows) { this.rows = rows; }

        @Override public ArtifactLease acquire(ProjectionArtifactRef ref, Instant expiresAt) {
            leasesOpened++;
            return new ArtifactLease() {
                private boolean open = true;
                @Override public UUID leaseId() { return UUID.randomUUID(); }
                @Override public String ownerFence() { return "test"; }
                @Override public Instant expiresAt() { return expiresAt; }
                @Override public ArtifactLease renew(Instant newExpiry) { return this; }
                @Override public boolean isOpen() { return open; }
                @Override public void close() { if (open) { open = false; leasesClosed++; } }
            };
        }

        @Override public ArtifactPage read(ProjectionArtifactRef ref, ArtifactLease lease,
                                           Optional<String> cursor, int limit) {
            if (cursor.isPresent()) return new ArtifactPage(List.of(), Optional.empty());
            return new ArtifactPage(rows, Optional.empty());
        }

        @Override public void acknowledge(ProjectionArtifactRef ref) { acknowledged++; }
    }

    @Test
    void anArtifactCommitsItsRowsInTheSameTransactionAsTheBlocks() throws Exception {
        try (var sink = open("artifact-commit")) {
            var artifacts = new FakeArtifacts(List.of(stakeRow(250, 4, 0), stakeRow(250, 4, 1)));
            var batch = batchWith(0, 4, simpleBatch(0, 4).rows(), List.of(stakeArtifact(250, 4, 2)));

            ProjectionReceipt receipt = sink.append(batch, artifacts);

            assertThat(count("epoch_stakes")).as("artifact rows land in the archive").isEqualTo(2);
            assertThat(receipt.rowCounts()).containsEntry("epoch_stakes", 2L);
            assertThat(sink.epochArtifactCoverage()
                    .get(com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE))
                    .containsExactly(new com.bloxbean.cardano.yano.archive.api.EpochRange(250, 250));
            assertThat(artifacts.leasesOpened).isEqualTo(1);
            assertThat(artifacts.leasesClosed).as("the lease is released even on success").isEqualTo(1);
        }
    }

    @Test
    void aZeroRowArtifactRecordsCompleteCoverageRatherThanAbsence() throws Exception {
        try (var sink = open("artifact-zero-row")) {
            var artifacts = new FakeArtifacts(List.of());
            var batch = batchWith(0, 4, simpleBatch(0, 4).rows(),
                    List.of(stakeArtifact(250, 4, 0)));

            ProjectionReceipt receipt = sink.append(batch, artifacts);

            assertThat(count("epoch_stakes")).isZero();
            assertThat(receipt.rowCounts()).doesNotContainKey("epoch_stakes");
            assertThat(sink.epochArtifactCoverage()
                    .get(com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE))
                    .containsExactly(new com.bloxbean.cardano.yano.archive.api.EpochRange(250, 250));
            assertThat(sink.hasCompleteEpochArtifact(
                    com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE,
                    250, 4, new byte[] {4, 9})).isTrue();
            assertThat(sink.hasCompleteEpochArtifact(
                    com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE,
                    250, 4, new byte[] {9, 9})).isFalse();
            assertThat(artifacts.leasesClosed).isEqualTo(1);
        }
    }

    @Test
    void anArtifactShortOfItsDeclaredRowsAbortsTheWholeCommit() throws Exception {
        try (var sink = open("artifact-short")) {
            // Declares two rows, yields one: a truncated read would otherwise leave the epoch
            // permanently short with a receipt claiming it was complete.
            var artifacts = new FakeArtifacts(List.of(stakeRow(250, 4, 0)));
            var batch = batchWith(0, 4, simpleBatch(0, 4).rows(), List.of(stakeArtifact(250, 4, 2)));

            assertThatThrownBy(() -> sink.append(batch, artifacts))
                    .isInstanceOf(ProjectionSinkException.class)
                    .hasMessageContaining("yielded 1 rows but declares 2");

            assertThat(count("epoch_stakes")).isZero();
            assertThat(count("transactions")).as("the block rows roll back with the artifact").isZero();
            assertThat(sink.coordinate()).isEqualTo(ProjectionCoordinate.NONE);
            assertThat(artifacts.leasesClosed).as("the lease is released on failure too").isEqualTo(1);
        }
    }

    @Test
    void anArtifactWithNoDeclaredRowCountIsRefused() throws Exception {
        try (var sink = open("artifact-uncounted")) {
            var batch = batchWith(0, 4, simpleBatch(0, 4).rows(), List.of(stakeArtifact(250, 4, -1)));

            assertThatThrownBy(() -> sink.append(batch, new FakeArtifacts(List.of())))
                    .isInstanceOf(ProjectionSinkException.class)
                    .hasMessageContaining("declares no expected row count");

            assertThat(count("transactions")).isZero();
        }
    }

    @Test
    void anArtifactRowForAnUnrelatedTableIsRefused() throws Exception {
        try (var sink = open("artifact-misrouted")) {
            // A reader that emitted rows for another dataset's table would corrupt it silently.
            var stray = com.bloxbean.cardano.yano.archive.api.ArchiveRowCodec.encode(
                    new ArchiveRow("transactions", simpleBatch(0, 0).rows().iterator().next().values()));
            var batch = batchWith(0, 4, simpleBatch(0, 4).rows(), List.of(stakeArtifact(250, 4, 1)));

            assertThatThrownBy(() -> sink.append(batch, new FakeArtifacts(List.of(stray))))
                    .isInstanceOf(ProjectionSinkException.class)
                    .hasMessageContaining("unrelated table transactions");

            assertThat(count("transactions")).isZero();
        }
    }

    @Test
    void anInlineEvidenceArtifactCommitsItsSingleRow() throws Exception {
        // The ada pot carries its values on the reference rather than pointing at a source, so
        // this proves the sink treats a dataset with no protected generation the same way.
        try (var sink = open("artifact-inline")) {
            var potRow = com.bloxbean.cardano.yano.archive.api.ArchiveRowCodec.encode(
                    new ArchiveRow("ada_pots", java.util.Arrays.asList(250L, 1L, 2L, 3L, 4L, 5L, 6L,
                            7L, 8L, new byte[]{9}, 4L, 4L, 1_600_000_000L, "ledger-boundary-v1/final",
                            UUID.nameUUIDFromBytes("pot".getBytes()))));
            var artifact = new ProjectionArtifactRef(
                    com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.ADA_POT, 250, 4, 4,
                    com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRepresentation
                            .ATOMIC_EVIDENCE, "ada-pot:250", 1, "ledger-boundary-v1/final",
                    java.util.OptionalLong.of(1), "", -1L, new byte[8 * Long.BYTES]);
            var artifacts = new FakeArtifacts(List.of(potRow));

            sink.append(batchWith(0, 4, simpleBatch(0, 4).rows(), List.of(artifact)), artifacts);

            assertThat(count("ada_pots")).isEqualTo(1);
            assertThat(artifacts.leasesClosed).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------- cases

    @Test
    void everyRequiredTableAndTheReceiptCommitTogether() throws Exception {
        try (var sink = open("commit")) {
            ProjectionReceipt receipt = sink.append(simpleBatch(0, 4), NO_ARTIFACTS);

            assertThat(receipt.firstBlock()).isZero();
            assertThat(receipt.lastBlock()).isEqualTo(4);
            assertThat(receipt.blockCount()).isEqualTo(5);
            assertThat(receipt.rowCounts())
                    .containsEntry("chain_transaction", 5L)
                    .containsEntry("transaction_inputs", 5L);
            assertThat(count("chain_transaction")).isEqualTo(5);
            assertThat(count("transaction_inputs")).isEqualTo(5);
            assertThat(count("projection_receipts")).isEqualTo(1);
            try (var read = backend.openReadSession()) {
                assertThat(backend.coverage(read, ArchiveDatasetId.TRANSACTION)
                        .completeRanges()).containsExactly(new BlockRange(0, 4));
                assertThat(backend.findTransaction(read, new byte[32])).isPresent();
            }
        }
    }

    @Test
    void aMatchingRetryIsIdempotentAndWritesNoDuplicateRows() throws Exception {
        try (var sink = open("idempotent")) {
            var first = sink.append(simpleBatch(0, 2), NO_ARTIFACTS);
            var replay = sink.append(simpleBatch(0, 2), NO_ARTIFACTS);

            assertThat(replay.orderedDigest()).isEqualTo(first.orderedDigest());
            assertThat(count("chain_transaction")).isEqualTo(3);
            assertThat(count("projection_receipts")).isEqualTo(1);
        }
    }

    @Test
    void aReshapedRetryForTheSameRangeIsRejected() throws Exception {
        try (var sink = open("mismatch")) {
            sink.append(simpleBatch(0, 2), NO_ARTIFACTS);

            var reshaped = new ProjectionRowBatch(IDENTITY, 0, 2, 3,
                    "aa".repeat(32), "bb".repeat(32), "dd".repeat(32),
                    List.of(txRow(0, 0)), List.of());
            assertThatThrownBy(() -> sink.append(reshaped, NO_ARTIFACTS))
                    .isInstanceOf(ProjectionReceiptMismatchException.class)
                    .hasMessageContaining("different job");
            assertThat(count("chain_transaction")).isEqualTo(3);
        }
    }

    @Test
    void theCoordinateAdvancesOnlyOverContiguousCommittedRanges() throws Exception {
        try (var sink = open("coordinate")) {
            assertThat(sink.coordinate()).isEqualTo(ProjectionCoordinate.NONE);

            sink.append(simpleBatch(0, 4), NO_ARTIFACTS);
            assertThat(sink.coordinate()).satisfies(coordinate -> {
                assertThat(coordinate.blockNumber()).isEqualTo(4);
                assertThat(coordinate.slot()).isEqualTo(4);
                assertThat(coordinate.blockHash()).containsExactly((byte) 4, (byte) 9);
                assertThat(coordinate.envelopeId()).isEqualTo("bb".repeat(32));
            });

            // A range beyond a gap must not advance the contiguous coordinate.
            sink.append(simpleBatch(10, 12), NO_ARTIFACTS);
            assertThat(sink.coordinate().blockNumber()).isEqualTo(4);

            sink.append(simpleBatch(5, 9), NO_ARTIFACTS);
            assertThat(sink.coordinate().blockNumber()).isEqualTo(12);
        }
    }

    @Test
    void aBatchWithAnUnknownTableCommitsNothing() throws Exception {
        try (var sink = open("unknown-table")) {
            var poisoned = batch(0, 0, List.of(txRow(0, 0),
                    new ArchiveRow("not_a_real_table", List.of(1L))));
            assertThatThrownBy(() -> sink.append(poisoned, NO_ARTIFACTS))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count("chain_transaction")).isZero();
            assertThat(count("projection_receipts")).isZero();
        }
    }

    @Test
    void aForeignProjectionIdentityIsRejected() throws Exception {
        try (var sink = open("identity")) {
            var foreign = new ProjectionIdentity(new ArchiveNetworkIdentity(764824073, "mainnet-genesis"),
                    "ducklake", 1, Set.of(ProjectionSectionType.TRANSACTION));
            assertThatThrownBy(() -> sink.append(
                    new ProjectionRowBatch(foreign, 0, 0, 1, "aa".repeat(32), "bb".repeat(32),
                            "cc".repeat(32), List.of(txRow(0, 0)), List.of()), NO_ARTIFACTS))
                    .isInstanceOf(ProjectionSinkException.class)
                    .hasMessageContaining("does not match the sink identity");
        }
    }

    @Test
    void reopeningWithADifferentIdentityFailsClosed() throws Exception {
        try (var sink = open("reopen")) {
            sink.append(simpleBatch(0, 1), NO_ARTIFACTS);
        }
        var other = new ProjectionIdentity(NETWORK, "ducklake", 2,
                Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY));
        var reopened = newSink();
        assertThatThrownBy(() -> reopened.initialize(other))
                .isInstanceOf(ProjectionSinkException.class)
                .hasMessageContaining("written by projection identity");
    }

    @Test
    void receiptsAreReadableAcrossSinkInstances() throws Exception {
        try (var sink = open("receipts")) {
            sink.append(simpleBatch(0, 3), NO_ARTIFACTS);
        }
        var reopened = newSink();
        reopened.initialize(IDENTITY);
        assertThat(reopened.receiptFor(0)).isPresent();
        assertThat(reopened.receiptFor(0).orElseThrow().lastBlock()).isEqualTo(3);
        assertThat(reopened.receiptFor(99)).isEmpty();
    }

    @Test
    void repeatedOpenAndCloseDoesNotLeakConnections() throws Exception {
        // close() now closes the DuckDbManager it owns. Without that, each cycle leaked a
        // connection pool, which a restart loop would accumulate.
        try (var first = open("leak")) {
            first.append(simpleBatch(0, 1), NO_ARTIFACTS);
        }
        for (int i = 0; i < 5; i++) {
            var sink = newSink();
            sink.initialize(IDENTITY);
            assertThat(sink.coordinate().blockNumber()).isEqualTo(1);
            sink.close();
        }
        var finalSink = newSink();
        finalSink.initialize(IDENTITY);
        assertThat(finalSink.receiptFor(0)).isPresent();
        finalSink.close();
    }

    // ------------------------------------------------------------- maintenance

    @Test
    void compactionMergesTheSinksOwnReceiptTableNotOnlyTheDatasetTables() throws Exception {
        try (var sink = open("compact-receipts")) {
            // One commit writes one file per touched table, including a receipt file. Left out
            // of compaction the receipt table becomes the dominant term in file count: measured
            // at 10,515 files on a preprod archive whose dataset tables had compacted to ~1,900.
            for (int i = 0; i < 12; i++) sink.append(simpleBatch(i * 5, i * 5 + 4), NO_ARTIFACTS);
            long receiptFilesBefore = dataFiles("projection_receipts");
            assertThat(receiptFilesBefore).isGreaterThan(1);

            var result = sink.maintain(ProjectionMaintenance.Budget.full(
                    Duration.ofSeconds(30), Duration.ofSeconds(30), 1L << 30));
            long receiptFilesAfter = dataFiles("projection_receipts");
            long outputFilesAfter = dataFiles("transaction_outputs");
            System.out.printf("ADR-039 compaction: receipts %d -> %d, outputs -> %d, outcome %s%n",
                    receiptFilesBefore, receiptFilesAfter, outputFilesAfter, result.outcome());

            assertThat(result.outcome()).isEqualTo(ProjectionMaintenance.Outcome.COMPLETED);
            assertThat(receiptFilesAfter)
                    .as("the receipt table must be compacted like any other")
                    .isLessThan(receiptFilesBefore);
            // Compaction must not disturb what the receipts say.
            assertThat(count("projection_receipts")).isEqualTo(12);
            assertThat(sink.receiptFor(0)).isPresent();

            // A second pass over an already-compacted lake must not rewrite the same
            // neighbourhood again: every rewrite orphans its inputs, and orphans survive the
            // cleanup grace, so a repeatedly-firing compaction would grow disk without bound.
            var second = sink.maintain(ProjectionMaintenance.Budget.full(
                    Duration.ofSeconds(30), Duration.ofSeconds(30), 1L << 30));
            long receiptFilesSecond = dataFiles("projection_receipts");
            System.out.printf("ADR-039 compaction second pass: receipts %d, outputs %d, outcome %s%n",
                    receiptFilesSecond, dataFiles("transaction_outputs"), second.outcome());
            assertThat(receiptFilesSecond)
                    .as("a second pass must be a no-op, not another rewrite")
                    .isEqualTo(receiptFilesAfter);
            assertThat(dataFiles("transaction_outputs")).isEqualTo(outputFilesAfter);
        }
    }

    /** Active data files for one table, read from the DuckLake catalog. */
    private long dataFiles(String table) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + config.catalogPath());
             var sql = connection.createStatement();
             var rows = sql.executeQuery(
                     "SELECT COUNT(*) FROM ducklake_data_file f JOIN ducklake_table t"
                             + " ON t.table_id = f.table_id WHERE f.end_snapshot IS NULL"
                             + " AND t.table_name = '" + table + "'")) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    @Test
    void compactionIsSkippedWhenNoFileIsSmallEnoughToBeWorthMerging() throws Exception {
        try (var sink = open("compact-threshold")) {
            for (int i = 0; i < 12; i++) sink.append(simpleBatch(i * 5, i * 5 + 4), NO_ARTIFACTS);

            // minSmallFileBytes = 1, so no file qualifies as small. This discriminates a real
            // size-distribution read from the fall-back file count: the fall-back would see
            // well over eight files and compact anyway.
            var result = sink.maintain(new ProjectionMaintenance.Budget(
                    Duration.ofSeconds(30), Duration.ofSeconds(30), 1L << 30,
                    512L << 20, 8, 1, true));

            assertThat(result.outcome())
                    .as("nothing is small enough to merge, so the pass must decline")
                    .isEqualTo(ProjectionMaintenance.Outcome.UNNECESSARY);
        }
    }

    // --------------------------------------------- maintenance contract gaps

    @Test
    void theByteBudgetIsDividedAcrossTablesRatherThanMultipliedByThem() {
        // DuckLake applies max_compacted_files per table. Passing the aggregate allowance
        // straight through would let a 10-table catalog rewrite 10x the advertised budget.
        int perTable = DuckLakeProjectionSink.compactionOutputsPerTable(
                1L << 30, 16L * 1024 * 1024, 10);
        assertThat(perTable).isEqualTo(6);           // 1 GiB / 16 MiB = 64 outputs, / 10 tables
        assertThat((long) perTable * 10 * (16L * 1024 * 1024))
                .as("aggregate worst case stays within the advertised budget")
                .isLessThanOrEqualTo(1L << 30);

        // Never zero: a budget too small for even one output still makes progress on one file.
        assertThat(DuckLakeProjectionSink.compactionOutputsPerTable(1, 16L * 1024 * 1024, 10))
                .isEqualTo(1);
        // Capped, so a huge budget cannot turn one call into an unbounded rewrite.
        assertThat(DuckLakeProjectionSink.compactionOutputsPerTable(1L << 50, 1024, 1))
                .isEqualTo(100);
        assertThatThrownBy(() -> DuckLakeProjectionSink.compactionOutputsPerTable(0, 1024, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCompactionPassReportsTheBytesItActuallyRewrote() throws Exception {
        try (var sink = open("rewritten-bytes")) {
            for (int i = 0; i < 12; i++) sink.append(simpleBatch(i * 5, i * 5 + 4), NO_ARTIFACTS);

            var result = sink.maintain(ProjectionMaintenance.Budget.full(
                    Duration.ofSeconds(30), Duration.ofSeconds(30), 1L << 30));

            System.out.printf("ADR-039 rewritten bytes: %d (files %d -> %d, outcome %s)%n",
                    result.bytesRewritten().orElse(-1), result.filesBefore().orElse(-1),
                    result.filesAfter().orElse(-1), result.outcome());
            assertThat(result.measurementsAvailable())
                    .as("a completed pass must have measured what it did")
                    .isTrue();
            assertThat(result.filesAfter().getAsLong()).isLessThan(result.filesBefore().getAsLong());
            assertThat(result.bytesRewritten().getAsLong())
                    .as("a pass that merged files must report the bytes it wrote, not zero")
                    .isPositive();

            // A second pass rewrites nothing, and must say so rather than repeating the figure.
            var second = sink.maintain(ProjectionMaintenance.Budget.full(
                    Duration.ofSeconds(30), Duration.ofSeconds(30), 1L << 30));
            assertThat(second.bytesRewritten().orElse(-1)).isZero();
        }
    }

    @Test
    void housekeepingFailureDowngradesTheOutcomeAndNamesTheStep() throws Exception {
        try (var sink = open("housekeeping-failure")) {
            sink.append(simpleBatch(0, 4), NO_ARTIFACTS);

            // A negative retention makes DuckLake reject the interval, failing every
            // housekeeping step. Reporting COMPLETED here is the bug being pinned: an archive
            // that has silently stopped reclaiming space must not look healthy.
            var broken = new DuckLakeArchiveConfig(config.catalogPath(), config.dataPath(),
                    config.acquireTimeout(), config.maxRetries(), config.retryWaitMillis(),
                    config.targetFileSizeBytes(), config.rowGroupSize(),
                    // A retention DuckDB cannot express as an INTERVAL, so every housekeeping
                    // step fails. The config itself stays valid, so this exercises the failure
                    // path rather than the constructor's validation.
                    Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE));
            try (var brokenSink = new DuckLakeProjectionSink(
                    new DuckDbManager(DuckDbManagerConfig.defaults(
                            config.catalogPath().getParent().resolve("tmp-broken")),
                            new PackagedDuckDbExtensionLoader(temp.resolve("extensions"))),
                    broken)) {
                brokenSink.initialize(IDENTITY);
                var result = brokenSink.maintain(ProjectionMaintenance.Budget.housekeepingOnly(
                        Duration.ofSeconds(30)));

                System.out.printf("ADR-039 housekeeping failure: %s %s%n",
                        result.outcome(), result.detail().orElse("<none>"));
                assertThat(result.outcome())
                        .as("a pass whose mandatory housekeeping failed is never COMPLETED")
                        .isIn(ProjectionMaintenance.Outcome.FAILED, ProjectionMaintenance.Outcome.PARTIAL);
                assertThat(result.detail()).isPresent();
                assertThat(result.detail().orElseThrow())
                        .as("the diagnostic must name the step that failed")
                        .contains("expire_snapshots");
            }
        }
    }

    @Test
    void housekeepingCutOffByItsDeadlineIsReportedRatherThanSilentlySkipped() throws Exception {
        try (var sink = open("housekeeping-deadline")) {
            sink.append(simpleBatch(0, 4), NO_ARTIFACTS);

            // A one-nanosecond budget: the first step may run, the rest are cut off.
            var result = sink.maintain(ProjectionMaintenance.Budget.housekeepingOnly(Duration.ofNanos(1)));

            assertThat(result.outcome())
                    .as("steps skipped for lack of budget are not a completed pass")
                    .isIn(ProjectionMaintenance.Outcome.FAILED, ProjectionMaintenance.Outcome.PARTIAL);
            assertThat(result.detail().orElse("")).contains("budget exhausted");
        }
    }

    @Test
    void aPinnedReaderSurvivesSnapshotExpirationAndFileCleanup() throws Exception {
        // Retention is only safe to shorten if a reader holding an older snapshot cannot have
        // its files deleted underneath it. This is the gate that must pass before the 168h/24h
        // windows are reduced.
        try (var sink = open("pinned-reader")) {
            for (int i = 0; i < 8; i++) sink.append(simpleBatch(i * 5, i * 5 + 4), NO_ARTIFACTS);
            long rowsBefore = count("chain_transaction");
            assertThat(rowsBefore).isEqualTo(40);

            var readerManager = new DuckDbManager(DuckDbManagerConfig.defaults(
                    config.catalogPath().getParent().resolve("tmp-reader")),
                    new PackagedDuckDbExtensionLoader(temp.resolve("extensions")));
            try (var lease = readerManager.acquire(DuckDbWorkload.BULK_CATCH_UP, config.acquireTimeout())) {
                var reader = lease.connection();
                DuckLakeSql.attach(reader, config, null, false);
                reader.setAutoCommit(false);
                try (var st = reader.createStatement();
                     var rs = st.executeQuery("SELECT count(*) FROM history_lake.chain_transaction")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isEqualTo(rowsBefore);
                }

                // Aggressive retention while that read transaction is open, plus compaction,
                // which supersedes the very files the pinned snapshot still references.
                var aggressive = new DuckLakeArchiveConfig(config.catalogPath(), config.dataPath(),
                        config.acquireTimeout(), config.maxRetries(), config.retryWaitMillis(),
                        config.targetFileSizeBytes(), config.rowGroupSize(),
                        Duration.ofSeconds(1), Duration.ofSeconds(1));
                try (var maintainer = new DuckLakeProjectionSink(
                        new DuckDbManager(DuckDbManagerConfig.defaults(
                                config.catalogPath().getParent().resolve("tmp-maint")),
                                new PackagedDuckDbExtensionLoader(temp.resolve("extensions"))),
                        aggressive)) {
                    maintainer.initialize(IDENTITY);
                    var result = maintainer.maintain(ProjectionMaintenance.Budget.full(
                            Duration.ofSeconds(30), Duration.ofSeconds(30), 1L << 30));
                    System.out.printf("ADR-039 pinned reader: maintenance %s, snapshots expired %d,"
                                    + " orphans deleted %d, files %d -> %d, detail=%s%n",
                            result.outcome(), result.snapshotsExpired(), result.orphanedFilesDeleted(),
                            result.filesBefore().orElse(-1), result.filesAfter().orElse(-1),
                            result.detail().orElse("<none>"));

                    // Assert the invariant this test exists to protect, not SQLite's current
                    // locking behaviour. Requiring maintenance to *fail*, or requiring global
                    // expired/deleted counts to be zero, would encode today's mechanism: a future
                    // DuckLake could safely expire unrelated snapshots while preserving the
                    // pinned reader, and this test should still pass then.
                    //
                    // What must hold in every version: no false success, and no measurement
                    // reported as zero when it was never taken.
                    if (result.outcome() == ProjectionMaintenance.Outcome.COMPLETED) {
                        assertThat(result.measurementsAvailable())
                                .as("a pass claiming success must have actually measured what it did")
                                .isTrue();
                    } else {
                        assertThat(result.detail())
                                .as("a pass that did not complete must say why")
                                .isPresent();
                    }
                    assertThat(result.filesBefore().isPresent() || result.detail().isPresent())
                            .as("an unmeasured file count must never be reported as a measured zero")
                            .isTrue();
                }

                // The invariant proper: the pinned reader's data is still there and still
                // correct, whatever maintenance did or failed to do.
                try (var st = reader.createStatement();
                     var rs = st.executeQuery("SELECT count(*) FROM history_lake.chain_transaction")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1))
                            .as("a reader pinned across expiration and cleanup must not lose its data")
                            .isEqualTo(rowsBefore);
                }
                reader.rollback();
                reader.setAutoCommit(true);
            } finally {
                readerManager.close();
            }

            // And a fresh reader afterwards still sees everything.
            assertThat(count("chain_transaction")).isEqualTo(rowsBefore);

            // The block is transient, not permanent: once the reader's transaction is gone,
            // the next pass succeeds. This is what makes a shorter retention window safe to
            // consider - maintenance defers to readers rather than being defeated by them.
            var aggressiveAgain = new DuckLakeArchiveConfig(config.catalogPath(), config.dataPath(),
                    config.acquireTimeout(), config.maxRetries(), config.retryWaitMillis(),
                    config.targetFileSizeBytes(), config.rowGroupSize(),
                    Duration.ofSeconds(1), Duration.ofSeconds(1));
            try (var after = new DuckLakeProjectionSink(
                    new DuckDbManager(DuckDbManagerConfig.defaults(
                            config.catalogPath().getParent().resolve("tmp-after")),
                            new PackagedDuckDbExtensionLoader(temp.resolve("extensions"))),
                    aggressiveAgain)) {
                after.initialize(IDENTITY);
                var recovered = after.maintain(ProjectionMaintenance.Budget.full(
                        Duration.ofSeconds(30), Duration.ofSeconds(30), 1L << 30));
                System.out.printf("ADR-039 pinned reader released: %s, snapshots expired %d,"
                                + " orphans deleted %d, detail=%s%n",
                        recovered.outcome(), recovered.snapshotsExpired(),
                        recovered.orphanedFilesDeleted(), recovered.detail().orElse("<none>"));
                assertThat(recovered.outcome())
                        .as("with no reader pinned, maintenance must be able to run")
                        .isIn(ProjectionMaintenance.Outcome.COMPLETED,
                                ProjectionMaintenance.Outcome.PARTIAL,
                                ProjectionMaintenance.Outcome.UNNECESSARY);
            }
            // Data is still intact after real expiration and cleanup have run.
            assertThat(count("chain_transaction")).isEqualTo(rowsBefore);
        }
    }

    @Test
    void aPoisonedConnectionAbandonsThePassRatherThanCascadingThroughIt() throws Exception {
        // Deterministic reproduction of the captured pinned-reader failure's *second* defect.
        // A failed CALL leaves the connection unable to answer anything else; the old code kept
        // using it, so compaction and every measurement failed too. The diagnostics became a
        // cascade of driver errors and the file counts read "35 -> 0" — an unmeasured value
        // printed as a measured zero, which reads as a catastrophic deletion.
        try (var sink = open("poisoned-connection")) {
            sink.append(simpleBatch(0, 4), NO_ARTIFACTS);

            // A retention DuckDB cannot express as an INTERVAL makes every housekeeping step
            // fail and leaves the connection in the same unusable state.
            var broken = new DuckLakeArchiveConfig(config.catalogPath(), config.dataPath(),
                    config.acquireTimeout(), config.maxRetries(), config.retryWaitMillis(),
                    config.targetFileSizeBytes(), config.rowGroupSize(),
                    Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE));
            try (var brokenSink = new DuckLakeProjectionSink(
                    new DuckDbManager(DuckDbManagerConfig.defaults(
                            config.catalogPath().getParent().resolve("tmp-poisoned")),
                            new PackagedDuckDbExtensionLoader(temp.resolve("extensions"))),
                    broken)) {
                brokenSink.initialize(IDENTITY);

                // Compaction is allowed by the budget; the pass must decline to attempt it.
                var result = brokenSink.maintain(ProjectionMaintenance.Budget.full(
                        Duration.ofSeconds(30), Duration.ofSeconds(30), 1L << 30));

                System.out.printf("ADR-039 poisoned connection: %s, files %s -> %s, bytes %s%n",
                        result.outcome(), result.filesBefore(), result.filesAfter(),
                        result.bytesRewritten());

                assertThat(result.outcome())
                        .as("a pass whose mandatory housekeeping failed must not claim success")
                        .isEqualTo(ProjectionMaintenance.Outcome.FAILED);

                // The contract is not "everything is empty" — it is that every reported figure
                // was genuinely measured, and anything that could not be measured is absent
                // rather than fabricated as zero. `filesBefore` is taken before the first CALL,
                // so it is real and must be reported. `bytesRewritten` is never reached, so it
                // must be absent — the old code returned 0 there, which is what made
                // "files 35 -> 0" read as a deletion.
                assertThat(result.bytesRewritten())
                        .as("a figure the pass never reached must be absent, not zero")
                        .isEmpty();
                assertThat(result.measurementsAvailable())
                        .as("the result must not claim to be fully measured")
                        .isFalse();
                assertThat(result.detail().orElseThrow())
                        .as("the diagnostic must name the step that failed")
                        .contains("expire_snapshots");

                // And compaction must not have been attempted on the back of a failed pass.
                assertThat(result.detail().orElseThrow()).doesNotContain("merge_adjacent_files");
            }

            // The next pass, on a fresh connection with a working configuration, succeeds.
            var recovered = sink.maintain(ProjectionMaintenance.Budget.full(
                    Duration.ofSeconds(30), Duration.ofSeconds(30), 1L << 30));
            assertThat(recovered.outcome())
                    .as("a poisoned connection must not poison the sink")
                    .isIn(ProjectionMaintenance.Outcome.COMPLETED,
                            ProjectionMaintenance.Outcome.PARTIAL,
                            ProjectionMaintenance.Outcome.UNNECESSARY);
            assertThat(count("chain_transaction"))
                    .as("no data was harmed by the abandoned pass")
                    .isEqualTo(5);
        }
    }
}
