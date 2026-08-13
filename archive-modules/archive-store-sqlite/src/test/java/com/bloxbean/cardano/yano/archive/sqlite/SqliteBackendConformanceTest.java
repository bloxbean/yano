package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveMaintenanceBudget;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveRetentionCutoff;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.api.SourceKind;
import com.bloxbean.cardano.yano.archive.api.test.AbstractArchiveBackendConformanceTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteBackendConformanceTest extends AbstractArchiveBackendConformanceTest {
    @TempDir Path temp;

    @Override
    protected ArchiveBackend createBackend() {
        return open(identity(UUID.randomUUID()), config());
    }

    @Test
    void flywayOwnsSchemaAndWalSnapshotStaysPinnedAcrossCommit() throws Exception {
        ArchiveJob first = job(0, 9, (byte) 1);
        commit(backend(), first, row(first, (byte) 1, 9));
        try (var pinned = (SqliteReadSession) backend().openReadSession()) {
            assertThat(count(pinned, "chain_transaction")).isEqualTo(1);
            ArchiveJob second = job(10, 19, (byte) 2);
            commit(backend(), second, row(second, (byte) 2, 19));
            assertThat(count(pinned, "chain_transaction")).isEqualTo(1);
        }
        try (var current = (SqliteReadSession) backend().openReadSession()) {
            assertThat(count(current, "transactions")).isEqualTo(2);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + config().databasePath());
             var query = connection.createStatement();
             var result = query.executeQuery("SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("1");
            assertThat(result.getBoolean(2)).isTrue();
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("2");
            assertThat(result.getBoolean(2)).isTrue();
        }
    }

    @Test
    void retryWithDifferentRowsFailsAndInvalidationRemovesRowsAndCoverage() throws Exception {
        ArchiveJob job = job(0, 9, (byte) 3);
        commit(backend(), job, row(job, (byte) 3, 9));
        assertThatThrownBy(() -> commit(backend(), job, row(job, (byte) 9, 9)))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("different rows");

        backend().invalidate(ArchiveDatasetId.TRANSACTION, new BlockRange(0, 9));
        assertThat(backend().findReceipt(job.jobId())).isEmpty();
        assertThat(backend().coverage(ArchiveDatasetId.TRANSACTION).covers(5)).isFalse();
        try (var read = (SqliteReadSession) backend().openReadSession()) {
            assertThat(count(read, "chain_transaction")).isZero();
        }
    }

    @Test
    void retentionDeletesOnlyWholeJobsAndOldReaderRetainsSnapshot() throws Exception {
        ArchiveJob first = job(0, 9, (byte) 4);
        ArchiveJob second = job(10, 19, (byte) 5);
        commit(backend(), first, row(first, (byte) 4, 9));
        commit(backend(), second, row(second, (byte) 5, 19));
        try (var old = (SqliteReadSession) backend().openReadSession()) {
            backend().applyRetention(ArchiveDatasetId.TRANSACTION,
                    new ArchiveRetentionCutoff(SourceKind.BLOCK, 10));
            assertThat(count(old, "chain_transaction")).isEqualTo(2);
        }
        assertThat(backend().coverage(ArchiveDatasetId.TRANSACTION).completeRanges())
                .containsExactly(new BlockRange(10, 19));
    }

    @Test
    void identityAndSingleWriterAreFailClosedAndRestartIsIdempotent() {
        ArchiveIdentity identity = backend().identity();
        ArchiveJob job = job(0, 9, (byte) 6);
        ArchiveRow row = row(job, (byte) 6, 9);
        commit(backend(), job, row);
        assertThatThrownBy(() -> open(identity, config()))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("already has a writer");
        backend().close();

        try (var reopened = open(identity, config())) {
            commit(reopened, job, row);
            assertThat(reopened.findReceipt(job.jobId())).isPresent();
        }
        assertThatThrownBy(() -> open(new ArchiveIdentity(UUID.randomUUID(), "sqlite", 1, 1, "other"), config()))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("identity mismatch");
    }

    @Test
    void readerPoolIsBoundedAndCrossExecutorAbortReleasesWriter() throws Exception {
        SqliteArchiveConfig bounded = new SqliteArchiveConfig(temp.resolve("bounded.sqlite"),
                Duration.ofMillis(100), Duration.ofSeconds(2), 1, SqliteArchiveConfig.Durability.FULL);
        backend().close();
        try (var store = open(identity(UUID.randomUUID()), bounded);
             var firstReader = store.openReadSession()) {
            assertThatThrownBy(store::openReadSession)
                    .isInstanceOf(ArchiveStoreException.class)
                    .hasMessageContaining("reader");

            ArchiveJob job = job(0, 9, (byte) 7);
            var abandoned = store.begin(job);
            abandoned.append(row(job, (byte) 7, 9));
            Thread closer = Thread.ofVirtual().start(abandoned::close);
            closer.join();
            commit(store, job, row(job, (byte) 7, 9));
        }
    }

    @Test
    void backendCloseDefersProcessLockReleaseUntilOpenSessionCloses() {
        ArchiveIdentity identity = backend().identity();
        var reader = backend().openReadSession();
        backend().close();
        assertThatThrownBy(() -> open(identity, config()))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("already has a writer");
        reader.close();
        try (var reopened = open(identity, config())) {
            assertThat(reopened.identity()).isEqualTo(identity);
        }
    }

    @Test
    void integrityMaintenanceAndOnlineBackupProduceUsableDatabase() throws Exception {
        ArchiveJob job = job(0, 9, (byte) 8);
        commit(backend(), job, row(job, (byte) 8, 9));
        var sqlite = (SqliteHistoryArchiveBackend) backend();
        sqlite.verifyIntegrity(Duration.ofSeconds(5));
        assertThat(sqlite.storageStats().databaseBytes()).isPositive();
        sqlite.maintain(new ArchiveMaintenanceBudget(Duration.ofSeconds(5), 0));
        Path backup = sqlite.backup(temp.resolve("backup/history.sqlite"));
        assertThat(backup).isRegularFile();
        assertThat(Files.size(backup)).isPositive();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + backup);
             var query = connection.createStatement();
             var result = query.executeQuery("SELECT count(*) FROM chain_transaction")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(1);
        }
    }

    @Test
    void duplicateLogicalKeyAndWrongJobProvenanceFailBeforePublication() {
        ArchiveJob job = job(0, 9, (byte) 9);
        assertThatThrownBy(() -> {
            try (var write = backend().begin(job)) {
                ArchiveRow row = row(job, (byte) 9, 9);
                write.append(row);
                write.append(row);
            }
        }).isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("duplicate logical primary key");

        ArchiveRow wrongJob = new ArchiveRow("chain_transaction", List.of(new byte[32], new byte[32],
                9L, 90L, 0L, 0L, 0, true, 10L, UUID.randomUUID()));
        assertThatThrownBy(() -> {
            try (var write = backend().begin(job)) {
                write.append(wrongJob);
            }
        }).isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("archive_job_id");
    }

    @Test
    void unknownNonEmptyDatabaseIsNotSilentlyBaselined() throws Exception {
        backend().close();
        Path unknown = temp.resolve("unknown.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + unknown);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unrelated(value TEXT)");
        }
        SqliteArchiveConfig unknownConfig = new SqliteArchiveConfig(unknown, Duration.ofSeconds(1),
                Duration.ofSeconds(2), 1, SqliteArchiveConfig.Durability.FULL);
        assertThatThrownBy(() -> open(identity(UUID.randomUUID()), unknownConfig))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("initialization failed");
    }

    @Test
    void serviceProviderAndIndependentReadOnlyClientSeeCommittedRows() throws Exception {
        assertThat(ServiceLoader.load(com.bloxbean.cardano.yano.archive.api.ArchiveBackendProvider.class)
                .stream().map(provider -> provider.get().engine())).contains("sqlite");
        ArchiveJob job = job(0, 9, (byte) 12);
        commit(backend(), job, row(job, (byte) 12, 9));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + config().databasePath());
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            try (var result = statement.executeQuery("SELECT count(*) FROM transactions")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void unsignedAssetQuantityAboveSignedLongRemainsExactText() throws Exception {
        ArchiveJob job = utxoJob(0, 0, (byte) 10);
        byte[] hash = job.anchorBlockHash();
        byte[] addressKey = new byte[32];
        Arrays.fill(addressKey, (byte) 11);
        BigInteger quantity = new BigInteger("18446744073709551615");
        try (var write = backend().begin(job)) {
            write.append(new ArchiveRow("addresses", Arrays.asList(addressKey, new byte[] {1}, "addr_test", 0,
                    "base", "key", new byte[28], "credential", "key", new byte[28],
                    null, null, null, 0L, 0L, 0L)));
            write.append(new ArchiveRow("transaction_outputs", Arrays.asList(hash, 0, 0, "regular", addressKey,
                    null, null, 1L, "none", null, null, false, hash, 0L, 0L, 0L, 0L, job.jobId())));
            write.append(new ArchiveRow("transaction_output_assets", List.of(hash, 0, new byte[28],
                    new byte[] {2}, quantity, 0L, 0L, 0L, job.jobId())));
            write.commit();
        }
        try (var read = (SqliteReadSession) backend().openReadSession();
             var query = read.connection().createStatement();
             var result = query.executeQuery("SELECT quantity, typeof(quantity) FROM transaction_output_assets")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo(quantity.toString());
            assertThat(result.getString(2)).isEqualTo("text");
        }
    }

    private SqliteHistoryArchiveBackend open(ArchiveIdentity identity, SqliteArchiveConfig config) {
        return new SqliteHistoryArchiveBackend(identity, config);
    }

    private SqliteArchiveConfig config() {
        return new SqliteArchiveConfig(temp.resolve("history.sqlite"), Duration.ofSeconds(2),
                Duration.ofSeconds(5), 2, SqliteArchiveConfig.Durability.FULL);
    }

    private ArchiveIdentity identity(UUID id) {
        return new ArchiveIdentity(id, "sqlite", 1, 1, "fixture-genesis");
    }

    private ArchiveJob job(long from, long to, byte marker) {
        return archiveJob(ArchiveDatasetId.TRANSACTION, from, to, marker);
    }

    private ArchiveJob utxoJob(long from, long to, byte marker) {
        return archiveJob(ArchiveDatasetId.UTXO_HISTORY, from, to, marker);
    }

    private ArchiveJob archiveJob(ArchiveDatasetId dataset, long from, long to, byte marker) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, marker);
        return ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "fixture-genesis"), dataset, 1,
                new BlockRange(from, to), new ArchiveRangeAnchor(from * 10, hash, to * 10, hash), "fixture-v1");
    }

    private ArchiveRow row(ArchiveJob job, byte marker, long block) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, marker);
        return new ArchiveRow("chain_transaction", List.of(hash, hash, block, block * 10,
                0L, 0L, 0, true, 10L, job.jobId()));
    }

    private void commit(ArchiveBackend backend, ArchiveJob job, ArchiveRow row) {
        try (var write = backend.begin(job)) {
            write.append(row);
            write.commit();
        }
    }

    private long count(SqliteReadSession session, String table) throws Exception {
        try (var query = session.connection().createStatement();
             var result = query.executeQuery("SELECT count(*) FROM " + SqliteArchiveSql.name(table))) {
            result.next();
            return result.getLong(1);
        }
    }
}
