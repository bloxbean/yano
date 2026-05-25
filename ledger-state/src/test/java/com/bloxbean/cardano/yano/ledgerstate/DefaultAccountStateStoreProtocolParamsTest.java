package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.era.EraProvider;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.GenesisBlockEvent;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.PoolVotingThresholds;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultAccountStateStoreProtocolParamsTest {
    private static final byte[] META_LAST_APPLIED_BLOCK = "meta.last_block".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] META_LAST_APPLIED_SLOT = "meta.last_applied_slot".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] META_LAST_SNAPSHOT_EPOCH = "meta.last_snapshot_epoch".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] META_BOUNDARY_STEP = "meta.boundary_step".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Test
    void applyBlockRethrowsStorageFailure() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(
                    null, rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreProtocolParamsTest.class),
                    true, provider());

            Block block = Block.builder()
                    .era(Era.Babbage)
                    .transactionBodies(Collections.emptyList())
                    .invalidTransactions(Collections.emptyList())
                    .build();

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> store.applyBlock(new BlockAppliedEvent(Era.Babbage, 10, 1, "bb".repeat(32), block)));

            assertThat(ex).hasMessageContaining("Account state apply failed for block 1");
        }
    }

    @Test
    void malformedMetadataFailsClosed() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreProtocolParamsTest.class),
                    true, provider());

            rocks.db().put(rocks.cfState(), META_LAST_APPLIED_SLOT, new byte[]{1, 2, 3});
            RuntimeException slotEx = assertThrows(RuntimeException.class, store::getLatestAppliedSlot);
            assertThat(slotEx).hasMessageContaining("Failed to read account state latest applied slot");

            rocks.db().delete(rocks.cfState(), META_LAST_APPLIED_SLOT);
            rocks.db().put(rocks.cfState(), META_BOUNDARY_STEP, new byte[]{1, 2, 3});
            RuntimeException stepEx = assertThrows(RuntimeException.class, () -> store.getBoundaryStep(1));
            assertThat(stepEx).hasMessageContaining("Failed to read boundary step");
            RuntimeException stateEx = assertThrows(RuntimeException.class, store::getLastBoundaryState);
            assertThat(stateEx).hasMessageContaining("Failed to read boundary state");

            rocks.db().delete(rocks.cfState(), META_BOUNDARY_STEP);
            rocks.db().put(rocks.cfState(), META_LAST_APPLIED_BLOCK, new byte[]{1, 2, 3});
            RuntimeException blockEx = assertThrows(RuntimeException.class, () -> store.reconcile(chainStateWithTip()));
            assertThat(blockEx).hasMessageContaining("Failed to read account state last applied block");

            rocks.db().delete(rocks.cfState(), META_LAST_APPLIED_BLOCK);
            rocks.db().put(rocks.cfState(), META_LAST_SNAPSHOT_EPOCH, new byte[]{1, 2, 3});
            RuntimeException snapshotEx = assertThrows(RuntimeException.class, () -> store.rollbackToSlot(50));
            assertThat(snapshotEx).hasMessageContaining("Account state rollback to slot 50 failed");
            assertThat(snapshotEx.getCause()).hasMessageContaining("Failed to read last snapshot epoch metadata");
        }
    }

    @Test
    void rollbackUpdatesLastAppliedBlockMetadataToRetainedBlock() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreProtocolParamsTest.class),
                    true, provider());

            Block block = Block.builder()
                    .era(Era.Babbage)
                    .transactionBodies(Collections.emptyList())
                    .invalidTransactions(Collections.emptyList())
                    .build();
            store.applyBlock(new BlockAppliedEvent(Era.Babbage, 100, 1, "b1".repeat(32), block));
            store.applyBlock(new BlockAppliedEvent(Era.Babbage, 200, 2, "b2".repeat(32), block));

            store.rollbackToSlot(100);

            assertThat(readLongMeta(rocks, META_LAST_APPLIED_SLOT)).isEqualTo(100L);
            assertThat(readLongMeta(rocks, META_LAST_APPLIED_BLOCK)).isEqualTo(1L);

            store.rollbackToSlot(50);

            assertThat(rocks.db().get(rocks.cfState(), META_LAST_APPLIED_SLOT)).isNull();
            assertThat(rocks.db().get(rocks.cfState(), META_LAST_APPLIED_BLOCK)).isNull();
        }
    }

    @Test
    void protocolParametersReturnEmptyWhenTrackerHasNoFinalizedSnapshot() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            EpochParamProvider provider = provider();
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreProtocolParamsTest.class),
                    true, provider);
            store.setParamTracker(new EpochParamTracker(provider, true, rocks.db(),
                    rocks.cf(AccountStateCfNames.EPOCH_PARAMS)));

            assertThat(store.getProtocolParameters(42)).isEmpty();
        }
    }

    @Test
    void protocolParametersReturnFinalizedTrackerSnapshotWhenAvailable() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            EpochParamProvider provider = provider();
            var tracker = new EpochParamTracker(provider, true, rocks.db(),
                    rocks.cf(AccountStateCfNames.EPOCH_PARAMS));
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreProtocolParamsTest.class),
                    true, provider);
            store.setParamTracker(tracker);

            tracker.finalizeEpoch(42);

            assertThat(store.getProtocolParameters(42))
                    .isPresent()
                    .get()
                    .extracting(snapshot -> snapshot.protocolMajorVer())
                    .isEqualTo(5);
        }
    }

    @Test
    void genesisBlockBootstrapsEpoch0ProtocolParameters() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            EpochParamProvider provider = provider();
            var tracker = new EpochParamTracker(provider, true, rocks.db(),
                    rocks.cf(AccountStateCfNames.EPOCH_PARAMS));
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreProtocolParamsTest.class),
                    true, provider);
            store.setParamTracker(tracker);

            assertThat(store.getProtocolParameters(0)).isEmpty();

            store.handleGenesisBlock(new GenesisBlockEvent(Era.Conway, 0, 0, 0, "00"));

            assertThat(store.getProtocolParameters(0))
                    .isPresent()
                    .get()
                    .extracting(snapshot -> snapshot.protocolMajorVer())
                    .isEqualTo(5);
        }
    }

    @Test
    void protocolParametersDoNotFallbackToFutureEraGenesisFields() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            EpochParamProvider provider = providerWithFutureEraFields();
            var tracker = new EpochParamTracker(provider, true, rocks.db(),
                    rocks.cf(AccountStateCfNames.EPOCH_PARAMS));
            tracker.setEraProvider(preAlonzoEraProvider());
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreProtocolParamsTest.class),
                    true, provider);
            store.setParamTracker(tracker);

            tracker.finalizeEpoch(42);

            var params = store.getProtocolParameters(42).orElseThrow();
            assertThat(params.costModels()).isNull();
            assertThat(params.costModelsRaw()).isNull();
            assertThat(params.priceMem()).isNull();
            assertThat(params.priceStep()).isNull();
            assertThat(params.maxTxExMem()).isNull();
            assertThat(params.maxTxExSteps()).isNull();
            assertThat(params.maxBlockExMem()).isNull();
            assertThat(params.maxBlockExSteps()).isNull();
            assertThat(params.maxValSize()).isNull();
            assertThat(params.collateralPercent()).isNull();
            assertThat(params.maxCollateralInputs()).isNull();
            assertThat(params.coinsPerUtxoWord()).isNull();
            assertThat(params.pvtMotionNoConfidence()).isNull();
            assertThat(params.dvtMotionNoConfidence()).isNull();
            assertThat(params.committeeMinSize()).isNull();
            assertThat(params.committeeMaxTermLength()).isNull();
            assertThat(params.govActionLifetime()).isNull();
            assertThat(params.govActionDeposit()).isNull();
            assertThat(params.drepDeposit()).isNull();
            assertThat(params.drepActivity()).isNull();
            assertThat(params.minFeeRefScriptCostPerByte()).isNull();
        }
    }

    @Test
    void protocolParametersUseProviderFallbackWhenTrackerIsDisabled() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            EpochParamProvider provider = provider();
            var store = new DefaultAccountStateStore(
                    rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(DefaultAccountStateStoreProtocolParamsTest.class),
                    true, provider);

            assertThat(store.getProtocolParameters(42))
                    .isPresent()
                    .get()
                    .extracting(snapshot -> snapshot.protocolMajorVer())
                    .isEqualTo(5);
        }
    }

    private static EpochParamProvider provider() {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.valueOf(2_000_000);
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.valueOf(500_000_000);
            }

            @Override
            public int getProtocolMajor(long epoch) {
                return 5;
            }
        };
    }

    private static EpochParamProvider providerWithFutureEraFields() {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.valueOf(2_000_000);
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.valueOf(500_000_000);
            }

            @Override
            public int getProtocolMajor(long epoch) {
                return 3;
            }

            @Override
            public Map<String, Object> getAlonzoCostModels(long epoch) {
                return Map.of("PlutusV1", java.util.List.of(1L, 2L));
            }

            @Override
            public BigDecimal getPriceMem(long epoch) {
                return new BigDecimal("0.0577");
            }

            @Override
            public NonNegativeInterval getPriceMemInterval(long epoch) {
                return new NonNegativeInterval(BigInteger.valueOf(577), BigInteger.valueOf(10_000));
            }

            @Override
            public BigDecimal getPriceStep(long epoch) {
                return new BigDecimal("0.0000721");
            }

            @Override
            public NonNegativeInterval getPriceStepInterval(long epoch) {
                return new NonNegativeInterval(BigInteger.valueOf(721), BigInteger.valueOf(10_000_000));
            }

            @Override public BigInteger getMaxTxExMem(long epoch) { return BigInteger.valueOf(10_000_000); }
            @Override public BigInteger getMaxTxExSteps(long epoch) { return new BigInteger("10000000000"); }
            @Override public BigInteger getMaxBlockExMem(long epoch) { return BigInteger.valueOf(50_000_000); }
            @Override public BigInteger getMaxBlockExSteps(long epoch) { return new BigInteger("40000000000"); }
            @Override public BigInteger getMaxValSize(long epoch) { return BigInteger.valueOf(5000); }
            @Override public Integer getCollateralPercent(long epoch) { return 150; }
            @Override public Integer getMaxCollateralInputs(long epoch) { return 3; }
            @Override public BigInteger getCoinsPerUtxoWord(long epoch) { return BigInteger.valueOf(34_482); }

            @Override
            public PoolVotingThresholds getPoolVotingThresholds(long epoch) {
                return PoolVotingThresholds.builder()
                        .pvtMotionNoConfidence(new UnitInterval(BigInteger.ONE, BigInteger.valueOf(2)))
                        .build();
            }

            @Override
            public DrepVoteThresholds getDrepVotingThresholds(long epoch) {
                return DrepVoteThresholds.builder()
                        .dvtMotionNoConfidence(new UnitInterval(BigInteger.ONE, BigInteger.valueOf(2)))
                        .build();
            }

            @Override public int getCommitteeMinSize(long epoch) { return 7; }
            @Override public int getCommitteeMaxTermLength(long epoch) { return 146; }
            @Override public int getGovActionLifetime(long epoch) { return 6; }
            @Override public BigInteger getGovActionDeposit(long epoch) { return new BigInteger("100000000000"); }
            @Override public BigInteger getDRepDeposit(long epoch) { return BigInteger.valueOf(500_000_000); }
            @Override public int getDRepActivity(long epoch) { return 20; }

            @Override
            public BigDecimal getMinFeeRefScriptCostPerByte(long epoch) {
                return BigDecimal.valueOf(15);
            }

            @Override
            public NonNegativeInterval getMinFeeRefScriptCostPerByteInterval(long epoch) {
                return new NonNegativeInterval(BigInteger.valueOf(15), BigInteger.ONE);
            }
        };
    }

    private static EraProvider preAlonzoEraProvider() {
        return new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return switch (eraValue) {
                    case 5 -> 100;
                    case 6 -> 200;
                    case 7 -> 300;
                    default -> 0;
                };
            }
        };
    }

    private static long readLongMeta(TestRocksDBHelper rocks, byte[] key) throws Exception {
        byte[] value = rocks.db().get(rocks.cfState(), key);
        return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    private static ChainState chainStateWithTip() {
        return new ChainState() {
            private final ChainTip tip = new ChainTip(100, new byte[32], 1);

            @Override public void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) {}
            @Override public byte[] getBlock(byte[] blockHash) { return null; }
            @Override public boolean hasBlock(byte[] blockHash) { return false; }
            @Override public void storeBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {}
            @Override public byte[] getBlockHeader(byte[] blockHash) { return null; }
            @Override public byte[] getBlockByNumber(Long number) { return null; }
            @Override public byte[] getBlockHeaderByNumber(Long blockNumber) { return null; }
            @Override public Point findNextBlock(Point currentPoint) { return null; }
            @Override public Point findNextBlockHeader(Point currentPoint) { return null; }
            @Override public List<Point> findBlocksInRange(Point from, Point to) { return List.of(); }
            @Override public Point findLastPointAfterNBlocks(Point from, long batchSize) { return null; }
            @Override public boolean hasPoint(Point point) { return false; }
            @Override public Point getFirstBlock() { return null; }
            @Override public Long getBlockNumberBySlot(Long slot) { return null; }
            @Override public Long getSlotByBlockNumber(Long blockNumber) { return null; }
            @Override public void rollbackTo(Long slot) {}
            @Override public ChainTip getTip() { return tip; }
            @Override public ChainTip getHeaderTip() { return tip; }
        };
    }
}
