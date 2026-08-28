package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import com.bloxbean.cardano.yano.api.account.RewardType;
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
