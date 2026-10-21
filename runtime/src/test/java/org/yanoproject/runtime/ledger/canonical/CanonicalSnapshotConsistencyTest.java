package org.yanoproject.runtime.ledger.canonical;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.ledgerstate.LedgerStateTestRecords;
import org.yanoproject.runtime.utxo.UtxoTestRecords;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-056 snapshot gate over real RocksDB stores sharing one database.
 *
 * <p>The synthetic invariant: every canonical write section writes the same value {@code v} to a
 * UTxO (its lovelace) and to a stake account (its reward balance), in two separate RocksDB commits,
 * UTxO first. A snapshot is consistent exactly when both reads return the same {@code v}.</p>
 */
class CanonicalSnapshotConsistencyTest {

    private static final String MARKER_TX = "ab".repeat(32);
    private static final Outpoint MARKER = new Outpoint(MARKER_TX, 0);
    private static final CredentialKey ACCOUNT = CredentialKey.key("11".repeat(28));

    @TempDir
    Path tempDir;

    private CanonicalTestStores stores;

    @BeforeEach
    void setUp() throws Exception {
        stores = new CanonicalTestStores(tempDir, false, epoch -> Optional.empty());
        writeBlock(1, () -> { });
    }

    @AfterEach
    void tearDown() {
        stores.close();
    }

    @Test
    @Timeout(30)
    void captureWaitsForAWriterPausedBetweenTheUtxoAndAccountUpdates() throws Exception {
        long before = stores.gate.generation();
        CountDownLatch utxoWritten = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(() -> {
                writeBlock(2, () -> {
                    utxoWritten.countDown();
                    await(resume);
                });
                return null;
            });
            assertThat(utxoWritten.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Observed> reader = executor.submit(this::observe);
            // The UTxO of block 2 is committed, the account is not: the capture must wait.
            assertThatThrownBy(() -> reader.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            resume.countDown();
            writer.get(10, TimeUnit.SECONDS);
            Observed observed = reader.get(10, TimeUnit.SECONDS);

            assertThat(observed.generation()).isEqualTo(before + 1);
            assertThat(observed.utxoValue()).isEqualTo(2);
            assertThat(observed.accountValue()).isEqualTo(2);
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
        assertThat(stores.gate.liveSnapshotCount()).isZero();
    }

    @Test
    void aWriteBetweenTwoReadBatchesDoesNotReachTheSnapshot() throws Exception {
        CanonicalSnapshot snapshot = acquire();
        try (CanonicalLedgerView view = CanonicalLedgerView.over(snapshot)) {
            snapshot.release();  // the view now holds the only reference
            long generation = view.generation();

            assertThat(values(view)).containsExactly(1L, 1L);

            writeBlock(2, () -> { });
            assertThat(stores.gate.generation()).isEqualTo(generation + 1);

            // Second batch of the same snapshot: still generation `generation`, still value 1.
            assertThat(view.generation()).isEqualTo(generation);
            assertThat(values(view)).containsExactly(1L, 1L);

            try (CanonicalLedgerView fresh = view(acquire())) {
                assertThat(fresh.generation()).isEqualTo(generation + 1);
                assertThat(values(fresh)).containsExactly(2L, 2L);
            }
        }
        assertThat(stores.gate.liveSnapshotCount()).isZero();
    }

    @Test
    @Timeout(120)
    void soakForwardBlocksAndRollbacksNeverMixTips() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong snapshotsChecked = new AtomicLong();
        Map<Long, Long> valueByGeneration = new ConcurrentHashMap<>();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        int readers = 4;
        ExecutorService executor = Executors.newFixedThreadPool(readers + 1);
        try {
            Future<?> writer = executor.submit(() -> {
                long value = 1;
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < 400; i++) {
                    // Mostly forward blocks, with rollbacks of up to 3 blocks.
                    value = random.nextInt(5) == 0 ? Math.max(1, value - 1 - random.nextInt(3)) : value + 1;
                    writeBlock(value, Thread::yield);
                }
                stop.set(true);
                return null;
            });
            List<Future<?>> readerFutures = new ArrayList<>();
            for (int r = 0; r < readers; r++) {
                readerFutures.add(executor.submit(() -> {
                    while (!stop.get()) {
                        Observed observed = observe();
                        if (observed.utxoValue() != observed.accountValue()) {
                            failures.add("mixed tips at generation " + observed.generation() + ": utxo="
                                    + observed.utxoValue() + " account=" + observed.accountValue());
                        }
                        Long previous = valueByGeneration.putIfAbsent(observed.generation(), observed.utxoValue());
                        if (previous != null && previous != observed.utxoValue()) {
                            failures.add("generation " + observed.generation() + " seen with values " + previous
                                    + " and " + observed.utxoValue());
                        }
                        snapshotsChecked.incrementAndGet();
                    }
                    return null;
                }));
            }
            writer.get(100, TimeUnit.SECONDS);
            for (Future<?> future : readerFutures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            stop.set(true);
            executor.shutdownNow();
        }
        assertThat(failures).isEmpty();
        assertThat(snapshotsChecked.get()).isPositive();
        assertThat(stores.gate.generation()).isEqualTo(401);
        assertThat(stores.gate.liveSnapshotCount()).isZero();
    }

    @Test
    void closingTheDatabaseInvalidatesLiveSnapshots() {
        CanonicalLedgerView view = view(acquire());
        stores.chain.close();

        assertThat(view.account(ACCOUNT)).isInstanceOfSatisfying(Lookup.Unavailable.class,
                u -> assertThat(u.reason()).contains("closed"));
        view.close();
        assertThat(stores.gate.liveSnapshotCount()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private record Observed(long generation, long utxoValue, long accountValue) {
    }

    /** One canonical write section: UTxO commit, pause hook, account commit. */
    private void writeBlock(long value, Runnable betweenUtxoAndAccount) {
        stores.gate.runWrite(() -> {
            try {
                UtxoTestRecords.putLovelace(stores.chain, MARKER_TX, 0, BigInteger.valueOf(value));
                betweenUtxoAndAccount.run();
                LedgerStateTestRecords.putStakeAccount(stores.db(), stores.cfState(), 0, ACCOUNT.hashHex(),
                        BigInteger.valueOf(value), BigInteger.TWO);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private Observed observe() {
        try (CanonicalLedgerView view = view(acquire())) {
            List<Long> values = values(view);
            return new Observed(view.generation(), values.get(0), values.get(1));
        }
    }

    private static List<Long> values(CanonicalLedgerView view) {
        UtxoEntry utxo = view.utxo(MARKER).require("marker utxo");
        AccountState account = view.account(ACCOUNT).require("marker account");
        return List.of(utxo.output().getValue().getCoin().longValueExact(),
                account.rewardBalance().longValueExact());
    }

    private CanonicalSnapshot acquire() {
        Lookup<CanonicalSnapshot> acquired = stores.gate.acquireSnapshot(SnapshotPurpose.ADMISSION);
        assertThat(acquired).isInstanceOf(Lookup.Present.class);
        return ((Lookup.Present<CanonicalSnapshot>) acquired).value();
    }

    /** A view that takes over the caller's acquisition reference. */
    private static CanonicalLedgerView view(CanonicalSnapshot snapshot) {
        CanonicalLedgerView view = CanonicalLedgerView.over(snapshot);
        snapshot.release();
        return view;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
