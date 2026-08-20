package com.bloxbean.cardano.yano.archive.ducklake;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-038: locator generation mechanics.
 *
 * <p>Mainnet measurement showed every archive commit spending ~340 s in
 * {@code advance} — 99.9% of write-session time — because it demanded the locator
 * sit at exactly {@code generation - 1} and otherwise rebuilt the whole
 * {@code chain_transaction} table. These tests pin the corrected behaviour: reads
 * never mutate the locator, and forward generation gaps are absorbed in
 * O(entries) while backward moves still trigger fail-safe recovery.
 */
class DuckLakeTransactionLocatorTest {
    @TempDir Path temp;

    private Connection duckLake;
    private DuckLakeTransactionLocator locator;
    private Path catalog;

    @BeforeEach
    void setUp() throws Exception {
        Path root = temp.resolve("lake");
        Files.createDirectories(root);
        catalog = root.resolve("catalog.sqlite");
        var config = new DuckLakeArchiveConfig(catalog, root.resolve("data"),
                Duration.ofSeconds(30), 10, 10, 16L * 1024 * 1024, 100_000,
                Duration.ofHours(168), Duration.ofHours(24));
        duckLake = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement sql = duckLake.createStatement()) {
            sql.execute("SET autoinstall_known_extensions = false");
            sql.execute("SET autoload_known_extensions = false");
        }
        new PackagedDuckDbExtensionLoader(temp.resolve("extensions")).load(duckLake);
        DuckLakeSql.attach(duckLake, config, null, false);
        try (Statement sql = duckLake.createStatement()) {
            sql.execute("CREATE TABLE " + DuckLakeSql.ALIAS
                    + ".chain_transaction (tx_hash BLOB, block_number BIGINT, archive_job_id UUID)");
        }
        locator = new DuckLakeTransactionLocator(catalog);
    }

    private static byte[] hash(int id) {
        byte[] value = new byte[32];
        value[0] = (byte) id; value[1] = (byte) (id >>> 8);
        return value;
    }

    /** Inserts into the authoritative table, as a TRANSACTION commit would. */
    private void insertChainTransaction(int id, long block, UUID job) throws Exception {
        try (PreparedStatement insert = duckLake.prepareStatement(
                "INSERT INTO " + DuckLakeSql.ALIAS + ".chain_transaction VALUES (?,?,?)")) {
            insert.setBytes(1, hash(id));
            insert.setLong(2, block);
            insert.setObject(3, job);
            insert.executeUpdate();
        }
    }

    /**
     * Moves the locator off its initial {@code -1} state. The first advance on a
     * fresh locator deliberately takes the uninitialised fail-safe rebuild branch,
     * so tests that assert incremental behaviour must prime past it first.
     */
    private void prime(long generation) {
        locator.advance(duckLake, generation, List.of());
        assertThat(locator.fullRebuilds()).isEqualTo(1);
    }

    private static List<DuckLakeTransactionLocator.Entry> entries(UUID job, int from, int to, long block) {
        List<DuckLakeTransactionLocator.Entry> list = new ArrayList<>();
        for (int i = from; i <= to; i++) list.add(new DuckLakeTransactionLocator.Entry(hash(i), block, job));
        return list;
    }

    @Test
    void pinnedHistoricalLookupNeverChangesLocatorGeneration() throws Exception {
        UUID job = UUID.randomUUID();
        insertChainTransaction(1, 100, job);
        locator.advance(duckLake, 10, entries(job, 1, 1, 100));
        long before = locator.currentGeneration();
        long rebuildsBefore = locator.fullRebuilds();

        // Later commits move the locator forward.
        locator.advance(duckLake, 11, List.of());
        locator.advance(duckLake, 12, List.of());
        assertThat(locator.currentGeneration()).isEqualTo(12);

        // A lookup through an OLD pinned generation must not rewind anything.
        OptionalLong hint = locator.block(duckLake, 10, hash(1));

        assertThat(hint).hasValue(100);
        assertThat(locator.currentGeneration())
                .as("pinned historical lookup must not rewind the active locator").isEqualTo(12);
        assertThat(locator.fullRebuilds())
                .as("read path must never rebuild").isEqualTo(rebuildsBefore);
        assertThat(before).isEqualTo(10);
    }

    @Test
    void currentGenerationLookupUsesTheLocatorNormally() throws Exception {
        prime(4);
        UUID job = UUID.randomUUID();
        insertChainTransaction(7, 700, job);
        locator.advance(duckLake, 5, entries(job, 7, 7, 700));

        assertThat(locator.block(duckLake, 5, hash(7))).hasValue(700);
        assertThat(locator.fullRebuilds()).as("no rebuild after priming").isEqualTo(1);
    }

    @Test
    void staleNegativeHintReturnsEmptySoCallerQueriesAuthoritatively() throws Exception {
        prime(2);
        UUID job = UUID.randomUUID();
        // Row exists authoritatively but was never added to the locator.
        insertChainTransaction(42, 4200, job);
        locator.advance(duckLake, 3, List.of());

        assertThat(locator.block(duckLake, 3, hash(42)))
                .as("absent hint -> caller performs full-range authoritative query").isEmpty();
        assertThat(locator.fullRebuilds()).isEqualTo(1);
    }

    @Test
    void stalePositiveHintIsReturnedAndCallerFallsBack() throws Exception {
        prime(3);
        UUID job = UUID.randomUUID();
        locator.advance(duckLake, 4, entries(job, 9, 9, 999));

        // The authoritative table has no such row; the hint is a stale positive.
        assertThat(locator.block(duckLake, 4, hash(9))).hasValue(999);
        assertThat(locator.fullRebuilds()).isEqualTo(1);
    }

    @Test
    void nonTransactionCommitWithEmptyEntriesAdvancesWithoutRebuild() throws Exception {
        UUID job = UUID.randomUUID();
        insertChainTransaction(1, 100, job);
        locator.advance(duckLake, 20, entries(job, 1, 1, 100));
        long rebuilds = locator.fullRebuilds();

        // Generation jumps by more than one: maintenance snapshots plus other
        // datasets' commits happened in between.
        locator.advance(duckLake, 25, List.of());

        assertThat(locator.currentGeneration()).isEqualTo(25);
        assertThat(locator.fullRebuilds())
                .as("forward gap must not rebuild chain_transaction").isEqualTo(rebuilds);
        assertThat(locator.block(hash(1))).as("existing hints survive").hasValue(100);
    }

    @Test
    void manySequentialNonTransactionCommitsRemainBounded() throws Exception {
        UUID job = UUID.randomUUID();
        for (int i = 0; i < 2_000; i++) insertChainTransaction(i, i, job);
        locator.advance(duckLake, 1, entries(job, 0, 1_999, 0));
        long rebuilds = locator.fullRebuilds();

        long started = System.nanoTime();
        long generation = 1;
        for (int i = 0; i < 50; i++) {
            generation += 3; // gap each time, as maintenance produces
            locator.advance(duckLake, generation, List.of());
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        assertThat(locator.fullRebuilds()).as("no rebuilds across 50 gapped commits").isEqualTo(rebuilds);
        assertThat(locator.currentGeneration()).isEqualTo(generation);
        assertThat(seconds).as("50 empty-entry advances must be bounded").isLessThan(30.0);
    }

    @Test
    void concurrentPinnedReadsCannotOscillateGeneration() throws Exception {
        UUID job = UUID.randomUUID();
        insertChainTransaction(1, 100, job);
        locator.advance(duckLake, 30, entries(job, 1, 1, 100));
        long rebuilds = locator.fullRebuilds();

        int readers = 6;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < readers; i++) {
            long pinned = 10 + i; // assorted older pinned generations
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 40; n++) locator.block(duckLake, pinned, hash(1));
                } catch (Throwable t) { failure.set(t); } finally { done.countDown(); }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();

        assertThat(locator.currentGeneration())
                .as("concurrent pinned reads must not move the generation").isEqualTo(30);
        assertThat(locator.fullRebuilds()).isEqualTo(rebuilds);
    }

    @Test
    void transactionCommitsIncrementallyAddEntries() throws Exception {
        prime(39);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertChainTransaction(1, 10, first);
        insertChainTransaction(2, 20, second);

        locator.advance(duckLake, 40, entries(first, 1, 1, 10));
        locator.advance(duckLake, 41, entries(second, 2, 2, 20));

        assertThat(locator.block(hash(1))).hasValue(10);
        assertThat(locator.block(hash(2))).hasValue(20);
        assertThat(locator.fullRebuilds())
                .as("both commits applied incrementally after priming").isEqualTo(1);
    }

    @Test
    void removeJobsDropsEntriesAndKeepsGeneration() throws Exception {
        UUID keep = UUID.randomUUID();
        UUID drop = UUID.randomUUID();
        insertChainTransaction(1, 10, keep);
        insertChainTransaction(2, 20, drop);
        locator.advance(duckLake, 50, entries(keep, 1, 1, 10));
        locator.advance(duckLake, 51, entries(drop, 2, 2, 20));

        locator.removeJobs(52, List.of(drop));

        assertThat(locator.block(hash(1))).hasValue(10);
        assertThat(locator.block(hash(2))).as("invalidated job entry removed").isEmpty();
        assertThat(locator.currentGeneration()).isEqualTo(52);
    }

    @Test
    void backwardGenerationMoveTriggersFailSafeRebuild() throws Exception {
        UUID job = UUID.randomUUID();
        insertChainTransaction(1, 100, job);
        insertChainTransaction(2, 200, job);
        locator.advance(duckLake, 60, entries(job, 1, 1, 100));
        long rebuilds = locator.fullRebuilds();

        // An unexplained rewind must not be absorbed silently.
        locator.advance(duckLake, 55, List.of());

        assertThat(locator.fullRebuilds())
                .as("backward move must invoke fail-safe recovery").isEqualTo(rebuilds + 1);
        assertThat(locator.currentGeneration()).isEqualTo(55);
        // The rebuild reconstructs from the authoritative table, so both rows appear.
        assertThat(locator.block(hash(2))).as("rebuild recovers rows never advanced").hasValue(200);
    }

    @Test
    void uninitialisedLocatorRebuildsOnFirstAdvance() throws Exception {
        UUID job = UUID.randomUUID();
        insertChainTransaction(5, 500, job);

        locator.advance(duckLake, 70, List.of());

        assertThat(locator.fullRebuilds()).isEqualTo(1);
        assertThat(locator.block(hash(5))).hasValue(500);
        assertThat(locator.currentGeneration()).isEqualTo(70);
    }
}
