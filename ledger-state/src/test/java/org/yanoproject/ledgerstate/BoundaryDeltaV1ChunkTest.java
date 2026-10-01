package org.yanoproject.ledgerstate;

import org.yanoproject.ledgerstate.test.TestRocksDBHelper;
import org.yanoproject.api.account.RewardType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundaryDeltaV1ChunkTest {
    @TempDir
    Path tempDir;

    @Test
    void descriptorTableRegistersEveryPersistedPhaseExactlyOnce() {
        Set<Byte> ids = DefaultAccountStateStore.boundaryPhaseDescriptors().stream()
                .map(DefaultAccountStateStore.BoundaryPhaseDescriptor::id)
                .collect(Collectors.toSet());
        assertThat(ids).containsExactlyInAnyOrder(
                DefaultAccountStateStore.PHASE_REWARDS,
                DefaultAccountStateStore.PHASE_MIR,
                DefaultAccountStateStore.PHASE_SPENDABLE_REST,
                DefaultAccountStateStore.PHASE_GOV_ENACT,
                DefaultAccountStateStore.PHASE_GOV_RATIFY,
                DefaultAccountStateStore.PHASE_POOLREAP);
        assertThat(ids).hasSameSizeAs(DefaultAccountStateStore.boundaryPhaseDescriptors());
    }

    @Test
    void rollbackReplaysSamePhaseChunksInDescendingSequence() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            byte[] key = DefaultAccountStateStore.accountKey(0, "31".repeat(28));
            byte[] first = {1};
            byte[] second = {2};
            byte[] third = {3};
            rocks.db().put(rocks.cfState(), key, first);

            writeChunk(rocks, store, key, second, first, 0);
            writeChunk(rocks, store, key, third, second, 1);
            assertThat(rocks.db().get(rocks.cfState(), key)).isEqualTo(third);

            store.rollbackToSlot(50);

            assertThat(rocks.db().get(rocks.cfState(), key)).isEqualTo(first);
            try (var iterator = rocks.db().newIterator(rocks.cfSupplier()
                    .handle(AccountStateCfNames.ACCT_BOUNDARY_DELTA))) {
                iterator.seekToFirst();
                assertThat(iterator.isValid()).isFalse();
            }
        }
    }

    @Test
    void startupResumesAfterCrashImmediatelyFollowingChunkCommit() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            byte[] key = DefaultAccountStateStore.accountKey(0, "41".repeat(28));
            byte[] first = {1};
            byte[] second = {2};
            byte[] third = {3};
            rocks.db().put(rocks.cfState(), key, first);
            byte[] futureEpochParams = ByteBuffer.allocate(4)
                    .order(ByteOrder.BIG_ENDIAN).putInt(5).array();
            rocks.db().put(rocks.cf(AccountStateCfNames.EPOCH_PARAMS),
                    futureEpochParams, new byte[]{9});
            writeChunk(rocks, store, key, second, first, 0);
            writeChunk(rocks, store, key, third, second, 1);
            store.setRollbackChunkCommitHook(() -> {
                throw new SimulatedCrash();
            });

            assertThatThrownBy(() -> store.rollbackToSlot(50))
                    .isInstanceOf(RuntimeException.class);
            assertThat(rocks.db().get(rocks.cfState(), key)).isEqualTo(second);

            new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            assertThat(rocks.db().get(rocks.cfState(), key)).isEqualTo(first);
            assertThat(rocks.db().get(rocks.cf(AccountStateCfNames.EPOCH_PARAMS),
                    futureEpochParams)).isNull();
        }
    }

    @Test
    void rewardCreditsForSameCredentialAccumulateAcrossCommittedChunks() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            String credentialHash = "51".repeat(28);
            byte[] accountKey = DefaultAccountStateStore.accountKey(0, credentialHash);
            rocks.db().put(rocks.cfState(), accountKey,
                    AccountStateCborCodec.encodeStakeAccount(
                            BigInteger.valueOf(7), BigInteger.valueOf(2_000_000)));

            var calculator = new EpochRewardCalculator(
                    rocks.db(), rocks.cfState(), rocks.cfSnapshot(), true);
            calculator.setAccountStateStore(store);
            calculator.beginRewardBatch(10, "rewards");
            calculator.creditReward(0, credentialHash, BigInteger.valueOf(11),
                    8, RewardType.MEMBER, "61".repeat(28));

            Method flush = EpochRewardCalculator.class.getDeclaredMethod(
                    "flushRewardChunk", String.class,
                    StreamingEpochRewardOrchestrator.RunningTotals.class);
            flush.setAccessible(true);
            flush.invoke(calculator, "61".repeat(28),
                    new StreamingEpochRewardOrchestrator.RunningTotals(
                            BigInteger.valueOf(11), BigInteger.ZERO));

            calculator.creditReward(0, credentialHash, BigInteger.valueOf(13),
                    8, RewardType.MEMBER, "62".repeat(28));
            calculator.commitRewardBatch(store.slotForEpochStart(10),
                    DefaultAccountStateStore.PHASE_REWARDS);

            var account = AccountStateCborCodec.decodeStakeAccount(
                    rocks.db().get(rocks.cfState(), accountKey));
            assertThat(account.reward()).isEqualTo(BigInteger.valueOf(31));
        }
    }

    @Test
    void aPhaseCommittedAgainAtTheSameBoundaryAppendsToItsJournal() throws Exception {
        // A crash between governance Phase 1 and Phase 2 re-runs Phase 1 at the same boundary slot.
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            byte[] key = DefaultAccountStateStore.accountKey(0, "71".repeat(28));
            byte[] first = {1};
            byte[] second = {2};
            byte[] third = {3};
            rocks.db().put(rocks.cfState(), key, first);

            writePhase(rocks, store, List.<byte[][]>of(new byte[][]{key, second, first}));
            writePhase(rocks, store, List.<byte[][]>of(new byte[][]{key, third, second}));
            // The first run keeps sequence 0; the re-run is appended at sequence 1.
            assertThat(boundaryDeltaKeys(rocks)).containsExactly(
                    DefaultAccountStateStore.boundaryDeltaKey(100, DefaultAccountStateStore.PHASE_GOV_ENACT, 0),
                    DefaultAccountStateStore.boundaryDeltaKey(100, DefaultAccountStateStore.PHASE_GOV_ENACT, 1));

            store.rollbackToSlot(50);

            assertThat(rocks.db().get(rocks.cfState(), key)).isEqualTo(first);
            assertThat(boundaryDeltaValues(rocks)).isEmpty();
        }
    }

    @Test
    void aJournalLargerThanTheLimitIsSplitAndRollsBackExactly() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            // Each op encodes to 5 + 30 (key) + 65_501 (previous) = 65_536 bytes, so 64 ops are exactly
            // MAX_BOUNDARY_DELTA_BYTES: with the 12-byte header they no longer fit in one value.
            List<byte[][]> writes = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                byte[] key = DefaultAccountStateStore.accountKey(0, String.format("%056x", i));
                byte[] previous = new byte[65_501];
                Arrays.fill(previous, (byte) i);
                rocks.db().put(rocks.cfState(), key, previous);
                writes.add(new byte[][]{key, new byte[]{1}, previous});
            }

            writePhase(rocks, store, writes);

            List<byte[]> values = boundaryDeltaValues(rocks);
            assertThat(values).hasSizeGreaterThan(1);
            assertThat(values).allSatisfy(value ->
                    assertThat(value.length).isLessThanOrEqualTo(DefaultAccountStateStore.MAX_BOUNDARY_DELTA_BYTES));

            store.rollbackToSlot(50);

            for (byte[][] write : writes) {
                assertThat(rocks.db().get(rocks.cfState(), write[0])).isEqualTo(write[2]);
            }
            assertThat(boundaryDeltaValues(rocks)).isEmpty();
        }
    }

    /** One boundary phase commit at slot 100: puts {key, value} over {previous}, journalled. */
    private static void writePhase(TestRocksDBHelper rocks, DefaultAccountStateStore store,
                                   List<byte[][]> writes) throws Exception {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            List<DefaultAccountStateStore.DeltaOp> ops = new ArrayList<>();
            for (byte[][] write : writes) {
                batch.put(rocks.cfState(), write[0], write[1]);
                ops.add(new DefaultAccountStateStore.DeltaOp(DefaultAccountStateStore.OP_PUT, write[0], write[2]));
            }
            store.commitBoundaryDelta(100, DefaultAccountStateStore.PHASE_GOV_ENACT, batch, ops);
            rocks.db().write(options, batch);
        }
    }

    private static List<byte[]> boundaryDeltaKeys(TestRocksDBHelper rocks) {
        List<byte[]> keys = new ArrayList<>();
        try (var iterator = rocks.db().newIterator(rocks.cfSupplier()
                .handle(AccountStateCfNames.ACCT_BOUNDARY_DELTA))) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                keys.add(iterator.key());
            }
        }
        return keys;
    }

    private static List<byte[]> boundaryDeltaValues(TestRocksDBHelper rocks) {
        List<byte[]> values = new ArrayList<>();
        try (var iterator = rocks.db().newIterator(rocks.cfSupplier()
                .handle(AccountStateCfNames.ACCT_BOUNDARY_DELTA))) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                values.add(iterator.value());
            }
        }
        return values;
    }

    private static final class SimulatedCrash extends RuntimeException {
    }

    private static void writeChunk(TestRocksDBHelper rocks,
                                   DefaultAccountStateStore store,
                                   byte[] key, byte[] value, byte[] previous,
                                   int sequence) throws Exception {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            batch.put(rocks.cfState(), key, value);
            store.commitBoundaryDelta(100, DefaultAccountStateStore.PHASE_REWARDS,
                    sequence, batch,
                    List.of(new DefaultAccountStateStore.DeltaOp(
                            DefaultAccountStateStore.OP_PUT, key, previous)));
            rocks.db().write(options, batch);
        }
    }
}
