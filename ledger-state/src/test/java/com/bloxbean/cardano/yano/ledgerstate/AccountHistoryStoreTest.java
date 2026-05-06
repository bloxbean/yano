package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.certs.MoveInstataneous;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDelegation;
import com.bloxbean.cardano.yaci.core.model.certs.StakePoolId;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.account.AccountHistoryProvider;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AccountHistoryStoreTest {
    private static final String CRED_HASH = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef01";
    private static final String REWARD_ACCOUNT = "e1" + CRED_HASH;
    private static final String POOL_HASH = "deadbeef00000000000000000000000000000000000000000000cafe";
    private static final String TX_HASH = "aa".repeat(32);

    @TempDir
    Path tempDir;

    @Test
    void disabledFlagDoesNotWriteHistory() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks, Map.of(
                    "yano.account-history.enabled", false));

            store.applyBlock(blockEvent(100, 1, TX_HASH,
                    Map.of(REWARD_ACCOUNT, BigInteger.valueOf(10)),
                    StakeRegistration.builder().stakeCredential(credential()).build()));

            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isZero();
            assertThat(store.countDeltas()).isZero();
        }
    }

    @Test
    void txEventsDisabledDoesNotWriteHistoryOrMetadata() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks, enabledConfig(0, Map.of(
                    "yano.account-history.tx-events-enabled", false)));

            store.applyBlock(blockEvent(100, 1, TX_HASH,
                    Map.of(REWARD_ACCOUNT, BigInteger.ONE),
                    StakeRegistration.builder().stakeCredential(credential()).build()));

            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isZero();
            assertThat(store.countDeltas()).isZero();
            assertThat(store.getLatestAppliedSlot()).isEqualTo(-1);
            assertThat(store.getLastAppliedBlock()).isZero();
        }
    }

    @Test
    void indexesTxCertHistoryAndRollbackDeletesInsertedRecords() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks, enabledConfig(0));

            store.applyBlock(blockEvent(250, 7, TX_HASH,
                    Map.of(REWARD_ACCOUNT, BigInteger.valueOf(12_345)),
                    StakeRegistration.builder().stakeCredential(credential()).build(),
                    StakeDelegation.builder()
                            .stakeCredential(credential())
                            .stakePoolId(StakePoolId.builder().poolKeyHash(POOL_HASH).build())
                            .build(),
                    MoveInstataneous.builder()
                            .treasury(true)
                            .stakeCredentialCoinMap(Map.of(credential(), BigInteger.valueOf(7_000)))
                            .build(),
                    MoveInstataneous.builder()
                            .reserves(true)
                            .stakeCredentialCoinMap(Map.of(credential(), BigInteger.valueOf(8_000)))
                            .build()));

            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isEqualTo(1);
            assertThat(store.countByType(AccountHistoryStore.TYPE_REGISTRATION)).isEqualTo(1);
            assertThat(store.countByType(AccountHistoryStore.TYPE_DELEGATION)).isEqualTo(1);
            assertThat(store.countByType(AccountHistoryStore.TYPE_MIR)).isEqualTo(2);
            assertThat(store.countDeltas()).isEqualTo(1);

            var withdrawal = store.getWithdrawals(0, CRED_HASH, 1, 10);
            assertThat(withdrawal).hasSize(1);
            assertThat(withdrawal.get(0).amount()).isEqualTo(BigInteger.valueOf(12_345));
            assertThat(withdrawal.get(0).txHash()).isEqualTo(TX_HASH);

            var delegation = store.getDelegations(0, CRED_HASH, 1, 10);
            assertThat(delegation).hasSize(1);
            assertThat(delegation.get(0).poolHash()).isEqualTo(POOL_HASH);
            assertThat(delegation.get(0).activeEpoch()).isEqualTo(4);

            var registration = store.getRegistrations(0, CRED_HASH, 1, 10);
            assertThat(registration).hasSize(1);
            assertThat(registration.get(0).action()).isEqualTo("registered");
            assertThat(registration.get(0).deposit()).isEqualTo(BigInteger.valueOf(2_000_000));

            var mir = store.getMirs(0, CRED_HASH, 1, 10);
            assertThat(mir).hasSize(2);
            assertThat(mir.get(0).pot()).isEqualTo("treasury");
            assertThat(mir.get(0).amount()).isEqualTo(BigInteger.valueOf(7_000));
            assertThat(mir.get(1).pot()).isEqualTo("reserves");
            assertThat(mir.get(1).amount()).isEqualTo(BigInteger.valueOf(8_000));

            store.rollbackTo(new RollbackEvent(new Point(249, "bb".repeat(32)), true));

            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isZero();
            assertThat(store.countByType(AccountHistoryStore.TYPE_REGISTRATION)).isZero();
            assertThat(store.countByType(AccountHistoryStore.TYPE_DELEGATION)).isZero();
            assertThat(store.countByType(AccountHistoryStore.TYPE_MIR)).isZero();
            assertThat(store.countDeltas()).isZero();
        }
    }

    @Test
    void listsHistoryInAscendingOrDescendingKeyOrder() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks, enabledConfig(0));

            store.applyBlock(new BlockAppliedEvent(Era.Conway, 100, 1, "01".repeat(32),
                    block(100, 1, "01".repeat(32),
                            tx("01".repeat(32), Map.of(REWARD_ACCOUNT, BigInteger.ONE)),
                            tx("02".repeat(32), Map.of(REWARD_ACCOUNT, BigInteger.TWO)))));
            store.applyBlock(blockEvent(250, 2, "03".repeat(32),
                    Map.of(REWARD_ACCOUNT, BigInteger.valueOf(3))));

            var asc = store.getWithdrawals(0, CRED_HASH, 1, 10, "asc");
            assertThat(asc).extracting(AccountHistoryProvider.WithdrawalRecord::amount)
                    .containsExactly(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3));

            var descPage1 = store.getWithdrawals(0, CRED_HASH, 1, 2, "desc");
            assertThat(descPage1).extracting(AccountHistoryProvider.WithdrawalRecord::amount)
                    .containsExactly(BigInteger.valueOf(3), BigInteger.TWO);

            var descPage2 = store.getWithdrawals(0, CRED_HASH, 2, 2, "desc");
            assertThat(descPage2).extracting(AccountHistoryProvider.WithdrawalRecord::amount)
                    .containsExactly(BigInteger.ONE);
        }
    }

    @Test
    void registrationHistoryDescendingOrderUsesCertIndexForTies() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks, enabledConfig(0));

            store.applyBlock(blockEvent(100, 1, TX_HASH, Map.of(),
                    StakeRegistration.builder().stakeCredential(credential()).build(),
                    StakeRegistration.builder().stakeCredential(credential()).build()));

            var desc = store.getRegistrations(0, CRED_HASH, 1, 10, "desc");
            assertThat(desc).extracting(AccountHistoryProvider.RegistrationRecord::certIdx)
                    .containsExactly(1, 0);
        }
    }

    @Test
    void invalidTransactionsAreNotIndexed() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks, enabledConfig(0));
            TransactionBody tx = TransactionBody.builder()
                    .txHash(TX_HASH)
                    .withdrawals(Map.of(REWARD_ACCOUNT, BigInteger.ONE))
                    .certificates(List.of(
                            StakeRegistration.builder().stakeCredential(credential()).build(),
                            StakeDelegation.builder()
                                    .stakeCredential(credential())
                                    .stakePoolId(StakePoolId.builder().poolKeyHash(POOL_HASH).build())
                                    .build(),
                            MoveInstataneous.builder()
                                    .treasury(true)
                                    .stakeCredentialCoinMap(Map.of(credential(), BigInteger.TWO))
                                    .build()))
                    .build();
            Block block = Block.builder()
                    .transactionBodies(List.of(tx))
                    .invalidTransactions(List.of(0))
                    .build();

            store.applyBlock(new BlockAppliedEvent(Era.Conway, 100, 1, "cc".repeat(32), block));

            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isZero();
            assertThat(store.countByType(AccountHistoryStore.TYPE_REGISTRATION)).isZero();
            assertThat(store.countByType(AccountHistoryStore.TYPE_DELEGATION)).isZero();
            assertThat(store.countByType(AccountHistoryStore.TYPE_MIR)).isZero();
            assertThat(store.countDeltas()).isZero();
            assertThat(store.getLatestAppliedSlot()).isEqualTo(100);
            assertThat(store.getLastAppliedBlock()).isEqualTo(1);
        }
    }

    @Test
    void pruningRemovesOldHistoryAndOldRollbackDeltas() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks, enabledConfig(2));

            store.applyBlock(blockEvent(100, 1, "01".repeat(32),
                    Map.of(REWARD_ACCOUNT, BigInteger.ONE)));
            store.applyBlock(blockEvent(350, 2, "02".repeat(32),
                    Map.of(REWARD_ACCOUNT, BigInteger.TWO)));

            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isEqualTo(2);
            assertThat(store.countDeltas()).isEqualTo(2);

            store.handleEpochTransition(4, 5);

            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isEqualTo(1);
            assertThat(store.countDeltas()).isEqualTo(1);
            assertThat(store.getWithdrawals(0, CRED_HASH, 1, 10).get(0).amount())
                    .isEqualTo(BigInteger.TWO);
        }
    }

    @Test
    void pruningUsesUtxoRollbackWindowFallbackForHistoryAndDeltas() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks, enabledConfig(2, Map.of(
                    "yano.utxo.rollbackWindow", 100)));

            store.applyBlock(blockEvent(260, 1, "01".repeat(32),
                    Map.of(REWARD_ACCOUNT, BigInteger.ONE)));
            store.applyBlock(blockEvent(350, 2, "02".repeat(32),
                    Map.of(REWARD_ACCOUNT, BigInteger.TWO)));

            store.handleEpochTransition(4, 5);

            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isEqualTo(2);
            assertThat(store.countDeltas()).isEqualTo(2);
        }
    }

    @Test
    void pruningUsesExplicitAccountHistoryRollbackSafetySlots() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks, enabledConfig(2, Map.of(
                    "yano.utxo.rollbackWindow", 0,
                    "yano.account-history.rollback-safety-slots", 100)));

            store.applyBlock(blockEvent(260, 1, "01".repeat(32),
                    Map.of(REWARD_ACCOUNT, BigInteger.ONE)));
            store.applyBlock(blockEvent(350, 2, "02".repeat(32),
                    Map.of(REWARD_ACCOUNT, BigInteger.TWO)));

            store.handleEpochTransition(4, 5);

            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isEqualTo(2);
            assertThat(store.countDeltas()).isEqualTo(2);
        }
    }

    @Test
    void rollbackToSlotRemovesRowsBeyondTargetEvenWhenDeltaIsMissing() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks, enabledConfig(0));

            store.applyBlock(blockEvent(100, 1, "01".repeat(32),
                    Map.of(REWARD_ACCOUNT, BigInteger.ONE)));
            store.applyBlock(blockEvent(250, 2, "02".repeat(32),
                    Map.of(REWARD_ACCOUNT, BigInteger.TWO)));
            rocks.db().delete(rocks.cf(AccountHistoryCfNames.ACCOUNT_HISTORY_DELTA), blockDeltaKey(2));

            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isEqualTo(2);
            assertThat(store.countDeltas()).isEqualTo(1);

            store.rollbackToSlot(150);

            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isEqualTo(1);
            assertThat(store.getWithdrawals(0, CRED_HASH, 1, 10).get(0).amount())
                    .isEqualTo(BigInteger.ONE);
            assertThat(store.countDeltas()).isEqualTo(1);
            assertThat(store.getLatestAppliedSlot()).isEqualTo(150);
        }
    }

    @Test
    void rollbackToSlotIsIdempotentAndDoesNotAdvanceMetadata() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks, enabledConfig(0));

            store.applyBlock(blockEvent(100, 1, "01".repeat(32),
                    Map.of(REWARD_ACCOUNT, BigInteger.ONE)));
            store.applyBlock(emptyBlockEvent(200, 2));

            assertThat(store.getLatestAppliedSlot()).isEqualTo(200);
            assertThat(store.getLastAppliedBlock()).isEqualTo(2);

            store.rollbackToSlot(300);
            assertThat(store.getLatestAppliedSlot()).isEqualTo(200);
            assertThat(store.getLastAppliedBlock()).isEqualTo(2);

            store.rollbackToSlot(150);
            store.rollbackToSlot(150);

            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isEqualTo(1);
            assertThat(store.getLatestAppliedSlot()).isEqualTo(150);
            assertThat(store.getLastAppliedBlock()).isEqualTo(1);
        }
    }

    @Test
    void rollbackToSlotResetsPruneCursorToRolledBackEpoch() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks, enabledConfig(2));

            store.applyBlock(blockEvent(150, 1, "01".repeat(32),
                    Map.of(REWARD_ACCOUNT, BigInteger.ONE)));
            store.applyBlock(blockEvent(550, 2, "02".repeat(32),
                    Map.of(REWARD_ACCOUNT, BigInteger.TWO)));

            store.rollbackToSlot(150);
            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isEqualTo(1);

            store.pruneOnce();

            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isEqualTo(1);
            assertThat(store.getWithdrawals(0, CRED_HASH, 1, 10).get(0).amount())
                    .isEqualTo(BigInteger.ONE);
        }
    }

    @Test
    void reconcileRollsBackWhenHistoryIsAheadOfChainTip() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks, enabledConfig(0));
            TestChainState chain = new TestChainState();
            Block block1 = block(100, 1, "01".repeat(32),
                    tx("01".repeat(32), Map.of(REWARD_ACCOUNT, BigInteger.ONE)));
            Block block2 = block(250, 2, "02".repeat(32),
                    tx("02".repeat(32), Map.of(REWARD_ACCOUNT, BigInteger.TWO)));
            chain.setTip(1, 100, "01".repeat(32));

            store.applyBlock(new BlockAppliedEvent(Era.Conway, 100, 1, "01".repeat(32), block1));
            store.applyBlock(new BlockAppliedEvent(Era.Conway, 250, 2, "02".repeat(32), block2));
            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isEqualTo(2);

            store.reconcile(chain);

            assertThat(store.countByType(AccountHistoryStore.TYPE_WITHDRAWAL)).isEqualTo(1);
            assertThat(store.getLatestAppliedSlot()).isEqualTo(100);
            assertThat(store.getLastAppliedBlock()).isEqualTo(1);
        }
    }

    private static AccountHistoryStore store(TestRocksDBHelper rocks, Map<String, Object> config) {
        return new AccountHistoryStore(
                rocks.db(),
                rocks.cfSupplier(),
                LoggerFactory.getLogger(AccountHistoryStoreTest.class),
                config,
                epochProvider());
    }

    private static Map<String, Object> enabledConfig(int retentionEpochs) {
        return enabledConfig(retentionEpochs, Map.of());
    }

    private static Map<String, Object> enabledConfig(int retentionEpochs, Map<String, Object> overrides) {
        Map<String, Object> config = new HashMap<>();
        config.put("yano.account-history.enabled", true);
        config.put("yano.account-history.tx-events-enabled", true);
        config.put("yano.account-history.retention-epochs", retentionEpochs);
        config.put("yano.account-history.prune-batch-size", 100);
        config.putAll(overrides);
        return config;
    }

    private static StakeCredential credential() {
        return StakeCredential.builder()
                .type(StakeCredType.ADDR_KEYHASH)
                .hash(CRED_HASH)
                .build();
    }

    private static BlockAppliedEvent blockEvent(long slot, long blockNo, String txHash,
                                                Map<String, BigInteger> withdrawals,
                                                Certificate... certs) {
        return new BlockAppliedEvent(Era.Conway, slot, blockNo, "dd".repeat(32),
                block(slot, blockNo, "dd".repeat(32), tx(txHash, withdrawals, certs)));
    }

    private static BlockAppliedEvent emptyBlockEvent(long slot, long blockNo) {
        Block block = Block.builder()
                .era(Era.Conway)
                .header(header(slot, blockNo, "ee".repeat(32)))
                .transactionBodies(List.of())
                .transactionWitness(List.of())
                .auxiliaryDataMap(Map.of())
                .invalidTransactions(List.of())
                .build();
        return new BlockAppliedEvent(Era.Conway, slot, blockNo, "ee".repeat(32), block);
    }

    private static TransactionBody tx(String txHash, Map<String, BigInteger> withdrawals, Certificate... certs) {
        TransactionBody tx = TransactionBody.builder()
                .txHash(txHash)
                .withdrawals(withdrawals)
                .certificates(new ArrayList<>(List.of(certs)))
                .build();
        return tx;
    }

    private static Block block(long slot, long blockNo, String blockHash, TransactionBody... txs) {
        return Block.builder()
                .era(Era.Conway)
                .header(header(slot, blockNo, blockHash))
                .transactionBodies(List.of(txs))
                .transactionWitness(emptyWitnesses(txs.length))
                .auxiliaryDataMap(Map.of())
                .invalidTransactions(List.of())
                .build();
    }

    private static BlockHeader header(long slot, long blockNo, String blockHash) {
        return BlockHeader.builder()
                .headerBody(HeaderBody.builder()
                        .slot(slot)
                        .blockNumber(blockNo)
                        .blockHash(blockHash)
                        .build())
                .build();
    }

    private static List<Witnesses> emptyWitnesses(int count) {
        List<Witnesses> witnesses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            witnesses.add(Witnesses.builder()
                    .vkeyWitnesses(List.of())
                    .nativeScripts(List.of())
                    .bootstrapWitnesses(List.of())
                    .plutusV1Scripts(List.of())
                    .datums(List.of())
                    .redeemers(List.of())
                    .plutusV2Scripts(List.of())
                    .plutusV3Scripts(List.of())
                    .build());
        }
        return witnesses;
    }

    private static byte[] blockDeltaKey(long blockNo) {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNo).array();
    }

    private static EpochParamProvider epochProvider() {
        return new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.valueOf(2_000_000);
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.ZERO;
            }

            @Override
            public long getEpochLength() {
                return 100;
            }

            @Override
            public long getByronSlotsPerEpoch() {
                return 100;
            }
        };
    }

    private static final class TestChainState implements ChainState {
        private final Map<Long, Long> slots = new HashMap<>();
        private ChainTip tip;

        void setTip(long blockNo, long slot, String blockHash) {
            slots.put(blockNo, slot);
            tip = new ChainTip(slot, HexUtil.decodeHexString(blockHash), blockNo);
        }

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
        @Override public Long getSlotByBlockNumber(Long blockNumber) { return slots.get(blockNumber); }
        @Override public void rollbackTo(Long slot) {}
        @Override public ChainTip getTip() { return tip; }
        @Override public ChainTip getHeaderTip() { return tip; }
    }
}
