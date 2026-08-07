package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.account.AccountHistoryProvider;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;



class AccountHistoryStoreAddressTxTest {
    private static final String RECEIVER =
            "addr_test1qzd4y0ezhvvrzdld2c4ytz73fdtxntgws2wmgehatsyvz69"
                    + "5czryt6cfgpcd59psqa4sagr8a8t746jm7cl6murshx6shucw4c";
    private static final String SENDER =
            "addr_test1qrnantjwnwydg3zttu8dlhxxm95ag97g2z4d75fp2vwysl"
                    + "fv4nclv229apqtm39sy0lplknrc6703m4n0kpae5al83qqj0x6rx";
    private static final String TX_HASH = "aa".repeat(32);
    private static final String CONSUMED_TX_HASH = "bb".repeat(32);

    @TempDir
    Path tempDir;

    @Test
    void indexesOutputAndInputAddressesAcrossScopesAndDeduplicatesPerTx() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks);
            store.setUtxoState(utxoStateResolving(SENDER));

            // Two outputs to the same receiver must produce ONE row per scope.
            store.applyBlock(event(100, 1, tx(TX_HASH,
                    List.of(input(CONSUMED_TX_HASH, 0)),
                    List.of(output(RECEIVER, 5_000_000), output(RECEIVER, 1_000_000)))));

            var receiverTxs = store.getAddressTransactionsForAddress(RECEIVER, false, 1, 10, "asc");
            assertThat(receiverTxs).hasSize(1);
            assertThat(receiverTxs.getFirst().txHash()).isEqualTo(TX_HASH);
            assertThat(receiverTxs.getFirst().blockNo()).isEqualTo(1);

            // Payment-credential scope answers the same question.
            assertThat(store.getAddressTransactionsForAddress(RECEIVER, true, 1, 10, "asc")).hasSize(1);

            // The sender is indexed via input resolution through the UTXO store.
            assertThat(store.getAddressTransactionsForAddress(SENDER, false, 1, 10, "asc")).hasSize(1);

            // Stake-credential scope backs /accounts/{stake}/transactions.
            String stakeCredHex = HexUtil.encodeHexString(
                    AddressProvider.getDelegationCredentialHash(new Address(RECEIVER)).orElseThrow());
            var accountTxs = store.getAddressTransactions(
                    AccountHistoryProvider.ADDR_SCOPE_STAKE_CRED, stakeCredHex, 1, 10, "desc");
            assertThat(accountTxs).hasSize(1);

            assertThat(store.isAddressUsed(AccountHistoryProvider.ADDR_SCOPE_STAKE_CRED, stakeCredHex)).isTrue();
        }
    }

    @Test
    void spentOutAddressStaysUsedUnlikeUtxoIndex() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks);
            store.setUtxoState(utxoStateResolving(RECEIVER));

            // Block 1 funds the receiver; block 2 spends it all away.
            store.applyBlock(event(100, 1, tx("01".repeat(32),
                    List.of(), List.of(output(RECEIVER, 5_000_000)))));
            store.applyBlock(event(110, 2, tx("02".repeat(32),
                    List.of(input("01".repeat(32), 0)), List.of(output(SENDER, 4_800_000)))));

            var txs = store.getAddressTransactionsForAddress(RECEIVER, false, 1, 10, "asc");
            assertThat(txs).extracting(AccountHistoryProvider.AddressTxRecord::txHash)
                    .containsExactly("01".repeat(32), "02".repeat(32));
        }
    }

    @Test
    void rollbackRemovesAddressTxRowsViaDeltas() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks);

            store.applyBlock(event(100, 1, tx("01".repeat(32),
                    List.of(), List.of(output(RECEIVER, 5_000_000)))));
            store.applyBlock(event(200, 2, tx("02".repeat(32),
                    List.of(), List.of(output(RECEIVER, 7_000_000)))));
            assertThat(store.getAddressTransactionsForAddress(RECEIVER, false, 1, 10, "asc")).hasSize(2);

            store.rollbackToSlot(150);

            var txs = store.getAddressTransactionsForAddress(RECEIVER, false, 1, 10, "asc");
            assertThat(txs).hasSize(1);
            assertThat(txs.getFirst().txHash()).isEqualTo("01".repeat(32));
        }
    }

    @Test
    void rewardRowsCommitWithBatchReadBackAndRollBackBySlot() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            Map<String, Object> config = new HashMap<>();
            config.put("yano.account-history.enabled", true);
            config.put("yano.account-history.rewards-enabled", true);
            AccountHistoryStore store = new AccountHistoryStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(AccountHistoryStoreAddressTxTest.class), config, epochProvider());

            String credHash = "295b987135610616f3c74e11c94d77b6ced5ccc93a7d719cfb135062";
            long boundarySlot = 2400;
            try (var batch = new org.rocksdb.WriteBatch(); var wo = new org.rocksdb.WriteOptions()) {
                store.appendRewardRows(batch, boundarySlot, (byte) 1, List.of(
                        new AccountHistoryStore.RewardHistoryEntry(0, credHash,
                                BigInteger.valueOf(1_234_567), 2, "MEMBER", "aa".repeat(28)),
                        new AccountHistoryStore.RewardHistoryEntry(0, credHash,
                                BigInteger.valueOf(9_999), 2, "LEADER", "aa".repeat(28))));
                rocks.db().write(wo, batch);
            }

            var rewards = store.getRewards(0, credHash, 1, 10, "asc");
            assertThat(rewards).hasSize(2);
            assertThat(rewards.getFirst().amount()).isEqualTo(BigInteger.valueOf(1_234_567));
            assertThat(rewards.getFirst().earnedEpoch()).isEqualTo(2);
            assertThat(rewards.getFirst().type()).isEqualTo("MEMBER");
            assertThat(rewards.getFirst().poolHash()).isEqualTo("aa".repeat(28));
            assertThat(rewards.get(1).type()).isEqualTo("LEADER");

            // A rollback past the boundary slot removes the rows via the slot scan.
            store.rollbackToSlot(2000);
            assertThat(store.getRewards(0, credHash, 1, 10, "asc")).isEmpty();
        }
    }

    @Test
    void laterEnabledFamilyReconcilesFromZeroNotFromSharedCursor() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            // Phase 1: only address-tx enabled — shared cursor advances to block 3.
            Map<String, Object> addrOnly = new HashMap<>();
            addrOnly.put("yano.account-history.enabled", true);
            addrOnly.put("yano.account-history.tx-events-enabled", false);
            addrOnly.put("yano.account-history.address-tx-enabled", true);
            AccountHistoryStore phase1 = new AccountHistoryStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(AccountHistoryStoreAddressTxTest.class), addrOnly, epochProvider());
            phase1.applyBlock(event(100, 3, tx("01".repeat(32), List.of(),
                    List.of(output(RECEIVER, 5_000_000)))));
            assertThat(phase1.getLastAppliedBlock()).isEqualTo(3);

            // Phase 2: tx-events additionally enabled — reconcile must NOT be
            // short-circuited by the shared cursor; it replays from 0 so cert
            // history backfills.
            AccountHistoryStore phase2 = store(rocks);
            assertThat(phase2.reconcileFloorBlock()).isZero();
        }
    }

    @Test
    void legacyStoreWithoutFamilyCursorsBackfillsOnlyAddressTx() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            // Simulate a store written before per-family cursors: legacy metadata
            // only (advanced by tx-events, the only family that existed).
            var cfHistory = rocks.cf(AccountHistoryCfNames.ACCOUNT_HISTORY);
            rocks.db().put(cfHistory, new byte[]{0x00, 'l', 'a', 's', 't', '_', 'b', 'l', 'o', 'c', 'k'},
                    java.nio.ByteBuffer.allocate(8).putLong(5).array());

            AccountHistoryStore store = store(rocks); // both families enabled
            // tx-events falls back to the legacy cursor (5); address-tx never ran → 0.
            assertThat(store.reconcileFloorBlock()).isZero();

            Map<String, Object> txEventsOnly = new HashMap<>();
            txEventsOnly.put("yano.account-history.enabled", true);
            txEventsOnly.put("yano.account-history.tx-events-enabled", true);
            AccountHistoryStore txeStore = new AccountHistoryStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(AccountHistoryStoreAddressTxTest.class), txEventsOnly, epochProvider());
            assertThat(txeStore.reconcileFloorBlock()).isEqualTo(5);
        }
    }

    @Test
    void retentionPruneNeverDeletesAddressTxOrRewardRows() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            Map<String, Object> config = new HashMap<>();
            config.put("yano.account-history.enabled", true);
            config.put("yano.account-history.tx-events-enabled", true);
            config.put("yano.account-history.address-tx-enabled", true);
            config.put("yano.account-history.rewards-enabled", true);
            AccountHistoryStore store = new AccountHistoryStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(AccountHistoryStoreAddressTxTest.class), config, epochProvider());

            store.applyBlock(event(100, 1, tx("01".repeat(32), List.of(),
                    List.of(output(RECEIVER, 5_000_000)))));
            try (var batch = new org.rocksdb.WriteBatch(); var wo = new org.rocksdb.WriteOptions()) {
                store.appendRewardRows(batch, 100, (byte) 1, List.of(
                        new AccountHistoryStore.RewardHistoryEntry(0, "bb".repeat(28),
                                BigInteger.TEN, 2, "MEMBER", null)));
                rocks.db().write(wo, batch);
            }
            long addressRows = store.countByType(AccountHistoryStore.TYPE_ADDRESS_TX);
            assertThat(addressRows).isPositive();

            store.pruneBeforeSlot(10_000); // cutoff far beyond every row's slot

            assertThat(store.countByType(AccountHistoryStore.TYPE_ADDRESS_TX)).isEqualTo(addressRows);
            assertThat(store.countByType(AccountHistoryStore.TYPE_REWARD)).isEqualTo(1);
        }
    }

    @Test
    void rewardRowsFromDifferentPhasesAtSameBoundaryDoNotCollide() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            Map<String, Object> config = new HashMap<>();
            config.put("yano.account-history.enabled", true);
            config.put("yano.account-history.rewards-enabled", true);
            AccountHistoryStore store = new AccountHistoryStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(AccountHistoryStoreAddressTxTest.class), config, epochProvider());

            String credHash = "295b987135610616f3c74e11c94d77b6ced5ccc93a7d719cfb135062";
            try (var batch = new org.rocksdb.WriteBatch(); var wo = new org.rocksdb.WriteOptions()) {
                store.appendRewardRows(batch, 2400, (byte) 1, List.of( // PHASE_REWARDS
                        new AccountHistoryStore.RewardHistoryEntry(0, credHash,
                                BigInteger.valueOf(777), 2, "LEADER", "aa".repeat(28))));
                store.appendRewardRows(batch, 2400, (byte) 6, List.of( // PHASE_POOLREAP
                        new AccountHistoryStore.RewardHistoryEntry(0, credHash,
                                BigInteger.valueOf(500_000_000), 2, "REFUND", "aa".repeat(28))));
                rocks.db().write(wo, batch);
            }

            var rewards = store.getRewards(0, credHash, 1, 10, "asc");
            assertThat(rewards).hasSize(2);
            assertThat(rewards).extracting(AccountHistoryProvider.RewardRecord::type)
                    .containsExactlyInAnyOrder("LEADER", "REFUND");
        }
    }

    @Test
    void rewardRowsNotWrittenWhenRewardsHistoryDisabled() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            AccountHistoryStore store = store(rocks); // rewards-enabled not set

            try (var batch = new org.rocksdb.WriteBatch(); var wo = new org.rocksdb.WriteOptions()) {
                store.appendRewardRows(batch, 2400, (byte) 1, List.of(
                        new AccountHistoryStore.RewardHistoryEntry(0, "bb".repeat(28),
                                BigInteger.TEN, 2, "MEMBER", null)));
                rocks.db().write(wo, batch);
            }

            assertThat(store.getRewards(0, "bb".repeat(28), 1, 10, "asc")).isEmpty();
            assertThat(store.countByType(AccountHistoryStore.TYPE_REWARD)).isZero();
        }
    }

    @Test
    void addressTxDisabledWritesNothingAndReadsEmpty() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            Map<String, Object> config = new HashMap<>();
            config.put("yano.account-history.enabled", true);
            config.put("yano.account-history.tx-events-enabled", true);
            AccountHistoryStore store = new AccountHistoryStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(AccountHistoryStoreAddressTxTest.class), config, epochProvider());

            store.applyBlock(event(100, 1, tx(TX_HASH, List.of(), List.of(output(RECEIVER, 5_000_000)))));

            assertThat(store.countByType(AccountHistoryStore.TYPE_ADDRESS_TX)).isZero();
            assertThat(store.getAddressTransactionsForAddress(RECEIVER, false, 1, 10, "asc")).isEmpty();
            assertThat(store.isAddressTxEnabled()).isFalse();
        }
    }

    private AccountHistoryStore store(TestRocksDBHelper rocks) {
        Map<String, Object> config = new HashMap<>();
        config.put("yano.account-history.enabled", true);
        config.put("yano.account-history.tx-events-enabled", true);
        config.put("yano.account-history.address-tx-enabled", true);
        return new AccountHistoryStore(rocks.db(), rocks.cfSupplier(),
                LoggerFactory.getLogger(AccountHistoryStoreAddressTxTest.class), config, epochProvider());
    }

    /** Minimal UtxoState stub that resolves every consumed outpoint to one address. */
    private UtxoState utxoStateResolving(String consumedAddress) {
        return new UtxoState() {
            @Override
            public List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize) {
                return List.of();
            }

            @Override
            public List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getUtxo(Outpoint outpoint) {
                return Optional.empty();
            }

            @Override
            public Optional<Utxo> getUtxoSpentOrUnspent(Outpoint outpoint) {
                return Optional.of(new Utxo(outpoint, consumedAddress, BigInteger.valueOf(5_000_000),
                        List.of(), null, null, null, null, false, 90, 0, "00".repeat(32)));
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
    }

    private static BlockAppliedEvent event(long slot, long blockNo, TransactionBody tx) {
        Block block = Block.builder()
                .transactionBodies(List.of(tx))
                .build();
        return new BlockAppliedEvent(Era.Conway, slot, blockNo, "cc".repeat(32), block);
    }

    private static TransactionBody tx(String txHash, List<TransactionInput> inputs, List<TransactionOutput> outputs) {
        return TransactionBody.builder()
                .txHash(txHash)
                .inputs(new java.util.HashSet<>(inputs))
                .outputs(outputs)
                .build();
    }

    private static TransactionInput input(String txHash, int index) {
        return TransactionInput.builder().transactionId(txHash).index(index).build();
    }

    private static TransactionOutput output(String address, long lovelace) {
        return TransactionOutput.builder()
                .address(address)
                .amounts(List.of(Amount.builder()
                        .unit("lovelace")
                        .quantity(BigInteger.valueOf(lovelace))
                        .build()))
                .build();
    }

    private static EpochParamProvider epochProvider() {
        return new EpochParamProvider() {
            @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.valueOf(2_000_000); }
            @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.valueOf(500_000_000); }
        };
    }
}
