package com.bloxbean.cardano.yano.appchain.eutxo.indexer.jdbc;

import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexEvent;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStore;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStoreContext;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexWrite;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoProjector;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexCoverage;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexIdentity;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.SourcePoint;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.testing.EutxoIndexFixtures;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.testing.EutxoIndexStoreConformance;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SqliteEutxoIndexStoreTest extends EutxoIndexStoreConformance {
    @TempDir
    Path temporaryDirectory;

    private final AtomicInteger stores = new AtomicInteger();

    @Override
    protected EutxoIndexStore open(IndexIdentity identity) {
        Path data = temporaryDirectory.resolve(
                "conformance-" + stores.incrementAndGet());
        return SqliteEutxoIndexStore.open(context(identity, data));
    }

    @Test
    void restartReplaysTheDurableJournalAndPreservesPragmas() throws Exception {
        Path data = temporaryDirectory.resolve("restart");
        String digest;
        try (EutxoIndexStore store = SqliteEutxoIndexStore.open(
                context(EutxoIndexFixtures.identity(), data))) {
            new EutxoProjector(store).apply(
                    EutxoIndexFixtures.point(1),
                    EutxoIndexFixtures.splitMergeEvents().getFirst(),
                    IndexCoverage.FULL);
            digest = store.reader().normalizedDigest();
            assertThat(Files.readString(
                    data.resolve(SqliteEutxoIndexStore.MARKER_FILE)))
                    .contains(EutxoIndexFixtures.identity().digest());
        }
        try (SqliteEutxoIndexStore reopened = SqliteEutxoIndexStore.open(
                context(EutxoIndexFixtures.identity(), data));
             var connection = DriverManager.getConnection(reopened.jdbcUrl());
             var statement = connection.createStatement()) {
            assertThat(reopened.reader().normalizedDigest()).isEqualTo(digest);
            try (var rows = statement.executeQuery("PRAGMA journal_mode")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualToIgnoringCase("wal");
            }
            try (var rows = statement.executeQuery("PRAGMA foreign_keys")) {
                assertThat(rows.next()).isTrue();
                // Foreign keys are connection-local and are enabled by the store.
                assertThat(rows.getInt(1)).isIn(0, 1);
            }
        }
    }

    @Test
    void uncommittedBlockIsInvisibleAndAbortLeavesNoRows() {
        Path data = temporaryDirectory.resolve("atomic");
        try (EutxoIndexStore store = SqliteEutxoIndexStore.open(
                context(EutxoIndexFixtures.identity(), data))) {
            EutxoIndexWrite write = store.begin(EutxoIndexFixtures.point(1));
            EutxoIndexEvent event =
                    EutxoIndexFixtures.splitMergeEvents().getFirst().getFirst();
            write.apply(event);
            assertThat(store.reader().transactions(0, 10).items()).isEmpty();
            write.abort();
            assertThat(store.reader().transactions(0, 10).items()).isEmpty();
        }
    }

    @Test
    void identityMismatchAndUnexpectedDatabaseFailClosed() throws Exception {
        Path identityData = temporaryDirectory.resolve("identity");
        try (EutxoIndexStore ignored = SqliteEutxoIndexStore.open(
                context(EutxoIndexFixtures.identity(), identityData))) {
            // Materialize and bind identity.
        }
        IndexIdentity other = new IndexIdentity(
                "devnet", "other-chain", "eutxo-ledger",
                EutxoIndexFixtures.identity().ledgerProfileDigest(), 1, "");
        assertThatThrownBy(() -> SqliteEutxoIndexStore.open(
                context(other, identityData)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity");

        Path unexpected = temporaryDirectory.resolve("unexpected");
        Files.createDirectories(unexpected);
        String url = "jdbc:sqlite:" + unexpected.resolve(
                SqliteEutxoIndexStore.DEFAULT_FILE);
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE foreign_data(value TEXT)");
        }
        assertThatThrownBy(() -> SqliteEutxoIndexStore.open(
                context(EutxoIndexFixtures.identity(), unexpected)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected non-empty");
    }

    @Test
    void tamperedMigrationChecksumAndCleanAreRejected() throws Exception {
        Path data = temporaryDirectory.resolve("checksum");
        String url;
        try (SqliteEutxoIndexStore store = SqliteEutxoIndexStore.open(
                context(EutxoIndexFixtures.identity(), data))) {
            url = store.jdbcUrl();
        }
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement(
                     "UPDATE flyway_schema_history SET checksum = 0"
                             + " WHERE version = '1'")) {
            statement.executeUpdate();
        }
        assertThatThrownBy(() -> SqliteEutxoIndexStore.open(
                context(EutxoIndexFixtures.identity(), data)))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("checksum");
        assertThatThrownBy(() -> SqliteEutxoIndexStore.flyway(url).clean())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void sameL1SlotWithAnotherBlockHashIsNotAnExactRollbackPoint() {
        Path data = temporaryDirectory.resolve("l1-fork");
        try (EutxoIndexStore store = SqliteEutxoIndexStore.open(
                context(EutxoIndexFixtures.identity(), data))) {
            new EutxoProjector(store).apply(
                    EutxoIndexFixtures.point(1),
                    EutxoIndexFixtures.splitMergeEvents().getFirst(),
                    IndexCoverage.FULL);
            SourcePoint competing = new SourcePoint(
                    1,
                    EutxoIndexFixtures.point(1).appBlockHash(),
                    EutxoIndexFixtures.point(1).l1Slot(),
                    EutxoIndexFixtures.hex(999));
            assertThatThrownBy(() -> store.rollbackTo(competing))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exactly");
        }
    }

    @Test
    void shadowRebuildActivatesOnlyACompleteOwnedDatabase() throws Exception {
        Path data = temporaryDirectory.resolve("rebuild");
        EutxoIndexStoreContext context =
                context(EutxoIndexFixtures.identity(), data);
        try (EutxoIndexStore store = SqliteEutxoIndexStore.open(context)) {
            new EutxoProjector(store).apply(
                    EutxoIndexFixtures.point(1),
                    EutxoIndexFixtures.splitMergeEvents().getFirst(),
                    IndexCoverage.FULL);
        }
        SqliteIndexRebuilder.rebuild(context, store -> {
            EutxoProjector projector = new EutxoProjector(store);
            projector.apply(
                    EutxoIndexFixtures.point(1),
                    EutxoIndexFixtures.splitMergeEvents().getFirst(),
                    IndexCoverage.FULL);
            projector.apply(
                    EutxoIndexFixtures.point(2),
                    EutxoIndexFixtures.splitMergeEvents().getLast(),
                    IndexCoverage.FULL);
        });
        try (EutxoIndexStore rebuilt = SqliteEutxoIndexStore.open(context)) {
            assertThat(rebuilt.checkpoint().source().appHeight()).isEqualTo(2);
            assertThat(rebuilt.reader().transactions(0, 10).items())
                    .hasSize(3);
        }

        Path unowned = temporaryDirectory.resolve("unowned");
        Files.createDirectories(unowned);
        Files.writeString(
                unowned.resolve(SqliteEutxoIndexStore.DEFAULT_FILE),
                "not an index");
        assertThatThrownBy(() -> SqliteIndexRebuilder.rebuild(
                context(EutxoIndexFixtures.identity(), unowned),
                ignored -> {
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unowned");
    }

    private static EutxoIndexStoreContext context(
            IndexIdentity identity,
            Path data
    ) {
        return new EutxoIndexStoreContext(identity, data, Map.of());
    }
}
