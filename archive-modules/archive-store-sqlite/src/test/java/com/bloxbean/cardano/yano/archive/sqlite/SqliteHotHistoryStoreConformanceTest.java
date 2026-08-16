package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.core.hot.*;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.core.source.ArchiveSourceLease;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveProgress;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SqliteHotHistoryStoreConformanceTest extends AbstractHotHistoryStoreConformanceTest {
    @Override protected HotHistoryStore open(Path path) {
        return new SqliteHotHistoryStore(path.resolve("hot-history.sqlite"));
    }

    @Test void providerIsDiscoverableWithoutApplicationCompileDependency() {
        assertThat(ServiceLoader.load(HotHistoryStoreProvider.class).stream()
                .map(ServiceLoader.Provider::get).map(HotHistoryStoreProvider::engine))
                .contains("sqlite");
    }

    @Test void resolverSeedUsesOneClusteredPrimaryKeyAndDoesNotEnterTheBlockIndex() throws Exception {
        Path database = temp.resolve("resolver-layout").resolve("hot-history.sqlite");
        try (HotHistoryStore ignored = new SqliteHotHistoryStore(database)) {
            // Schema installation is the behavior under test.
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (var statement = connection.prepareStatement(
                    "SELECT sql FROM sqlite_schema WHERE name='resolver_outputs'");
                 var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1))
                        .containsIgnoringCase("WITHOUT ROWID")
                        .doesNotContainIgnoringCase("namespace");
            }
            try (var statement = connection.prepareStatement(
                    "SELECT sql FROM sqlite_schema WHERE name='resolver_outputs_created'");
                 var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).containsIgnoringCase("WHERE source_kind = 'BLOCK'");
            }
            try (var statement = connection.prepareStatement(
                    "SELECT count(*) FROM sqlite_schema WHERE type='table' AND name='hot_track'");
                 var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    @Test void sourceLeaseAndProjectionWritesShareOneSerializedWriterTransaction() throws Exception {
        Path database = temp.resolve("concurrent-writer").resolve("hot-history.sqlite");
        try (var store = new SqliteHotHistoryStore(database);
             var executor = Executors.newFixedThreadPool(4)) {
            int iterations = 250;
            List<ArchiveSourceLease> leases = new ArrayList<>(iterations);
            for (int i = 0; i < iterations; i++) {
                leases.add(store.acquireBlockBodyLease(i + 1L, i + 1L,
                        Instant.now().plusSeconds(60)));
            }

            CountDownLatch start = new CountDownLatch(1);
            Future<?> releases = executor.submit(() -> {
                await(start);
                leases.forEach(ArchiveSourceLease::close);
            });
            Future<?> progress = executor.submit(() -> {
                await(start);
                for (int i = 1; i <= iterations; i++) {
                    store.save(new ArchiveProgress(ArchiveDatasetId.ACCOUNT_EVENT,
                            ArchiveTrack.BACKFILL, i, i, new byte[]{(byte) i}, 0), null);
                }
            });

            start.countDown();
            releases.get();
            progress.get();
            assertThat(store.load(ArchiveDatasetId.ACCOUNT_EVENT, ArchiveTrack.BACKFILL))
                    .get().extracting(ArchiveProgress::coordinate).isEqualTo((long) iterations);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
