package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.*;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.account.AccountHistoryProvider;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.api.rollback.RollbackCapableStore;
import org.rocksdb.*;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Function;

public final class AccountHistoryStore implements AccountHistoryProvider, RollbackCapableStore {
    static final byte TYPE_WITHDRAWAL = 0x01;
    static final byte TYPE_DELEGATION = 0x02;
    static final byte TYPE_REGISTRATION = 0x03;
    static final byte TYPE_MIR = 0x04;
    private static final int HISTORY_PREFIX_LEN = 1 + 1 + 28;
    private static final int HISTORY_KEY_LEN = HISTORY_PREFIX_LEN + 8 + 4 + 4;
    private static final byte[] META_LAST_APPLIED_SLOT = new byte[]{0x00, 'l', 'a', 's', 't', '_', 's', 'l', 'o', 't'};
    private static final byte[] META_LAST_APPLIED_BLOCK = new byte[]{0x00, 'l', 'a', 's', 't', '_', 'b', 'l', 'o', 'c', 'k'};

    private final RocksDB db;
    private final ColumnFamilyHandle cfHistory;
    private final ColumnFamilyHandle cfDelta;
    private final Logger log;
    private final boolean enabled;
    private final boolean txEventsEnabled;
    private final boolean rewardsHistoryEnabled;
    private final int retentionEpochs;
    private final int pruneBatchSize;
    private final long rollbackSafetySlots;
    private final EpochParamProvider epochParamProvider;
    private volatile boolean healthy = true;
    private volatile int lastKnownEpoch = -1;
    private volatile long lastKnownSlot = -1;

    public AccountHistoryStore(RocksDB db,
                               DefaultAccountStateStore.CfSupplier supplier,
                               Logger log,
                               Map<String, Object> config,
                               EpochParamProvider epochParamProvider) {
        this.db = db;
        this.cfHistory = supplier.handle(AccountHistoryCfNames.ACCOUNT_HISTORY);
        this.cfDelta = supplier.handle(AccountHistoryCfNames.ACCOUNT_HISTORY_DELTA);
        if (this.cfHistory == null || this.cfDelta == null) {
            throw new IllegalStateException("account history column families are not available");
        }
        this.log = log;
        this.enabled = getBool(config, "yaci.node.account-history.enabled", false);
        this.txEventsEnabled = getBool(config, "yaci.node.account-history.tx-events-enabled", true);
        this.rewardsHistoryEnabled = getBool(config, "yaci.node.account-history.rewards-enabled", false);
        this.retentionEpochs = getInt(config, "yaci.node.account-history.retention-epochs", 0);
        this.pruneBatchSize = getInt(config, "yaci.node.account-history.prune-batch-size", 50_000);
        this.rollbackSafetySlots = getLong(config, "yaci.node.account-history.rollback-safety-slots",
                getLong(config, "yaci.node.utxo.rollbackWindow", 0));
        this.epochParamProvider = epochParamProvider != null ? epochParamProvider : new EpochParamProvider() {
            @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.ZERO; }
            @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.ZERO; }
        };
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public boolean isTxEventsEnabled() {
        return txEventsEnabled;
    }

    public boolean isRewardsHistoryEnabled() {
        return rewardsHistoryEnabled;
    }

    public int getRetentionEpochs() {
        return retentionEpochs;
    }

    public synchronized void applyBlock(BlockAppliedEvent event) {
        if (!enabled || !txEventsEnabled || event == null || event.block() == null) return;

        Block block = event.block();
        long slot = event.slot();
        long blockNo = event.blockNumber();
        int currentEpoch = epochParamProvider.getEpochSlotCalc().slotToEpoch(slot);
        lastKnownEpoch = currentEpoch;
        lastKnownSlot = slot;

        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            List<byte[]> insertedKeys = new ArrayList<>();
            List<TransactionBody> txs = block.getTransactionBodies();
            Set<Integer> invalidIdx = block.getInvalidTransactions() != null
                    ? new HashSet<>(block.getInvalidTransactions())
                    : Collections.emptySet();

            if (txs != null && !txs.isEmpty()) {
                for (int txIdx = 0; txIdx < txs.size(); txIdx++) {
                    if (invalidIdx.contains(txIdx)) continue;
                    TransactionBody tx = txs.get(txIdx);
                    String txHash = tx.getTxHash();

                    Map<String, BigInteger> withdrawals = tx.getWithdrawals();
                    if (withdrawals != null && !withdrawals.isEmpty()) {
                        int withdrawalIdx = 0;
                        for (var entry : withdrawals.entrySet()) {
                            StakeCred cred = rewardAccountCred(entry.getKey());
                            if (cred != null && entry.getValue() != null && entry.getValue().signum() > 0) {
                                indexWithdrawal(cred, txHash, entry.getValue(), slot, blockNo,
                                        txIdx, withdrawalIdx, batch, insertedKeys);
                            }
                            withdrawalIdx++;
                        }
                    }

                    List<Certificate> certs = tx.getCertificates();
                    if (certs != null) {
                        for (int certIdx = 0; certIdx < certs.size(); certIdx++) {
                            indexCertificate(certs.get(certIdx), event.era(), currentEpoch, slot, blockNo,
                                    txIdx, certIdx, txHash, batch, insertedKeys);
                        }
                    }
                }
            }

            if (!insertedKeys.isEmpty()) {
                byte[] deltaKey = blockDeltaKey(blockNo);
                batch.put(cfDelta, deltaKey, AccountHistoryCborCodec.encodeDelta(slot, insertedKeys));
            }
            putLastApplied(batch, slot, blockNo);
            db.write(wo, batch);
        } catch (Exception e) {
            healthy = false;
            log.error("Account history apply failed for block {}: {}", blockNo, e.toString(), e);
        }
    }

    public void rollbackTo(RollbackEvent event) {
        if (!enabled || event == null || event.target() == null) return;
        rollbackToSlot(event.target().getSlot());
    }

    public synchronized void rollbackToSlot(long targetSlot) {
        if (!enabled) return;

        try {
            long latestBefore = getLatestAppliedSlot();
            int deltaDeletedRows = rollbackDeltasAfterSlot(targetSlot);
            int scanDeletedRows = deleteHistoryRowsAfterSlot(targetSlot);
            updateRollbackCursor(targetSlot, latestBefore);
            if (deltaDeletedRows > 0 || scanDeletedRows > 0) {
                log.info("Account history rollback to slot {} removed {} rows from deltas and {} orphan rows by scan",
                        targetSlot, deltaDeletedRows, scanDeletedRows);
            }
        } catch (Exception e) {
            healthy = false;
            log.error("Account history rollback failed to slot {}: {}", targetSlot, e.toString(), e);
            throw new RuntimeException("Account history rollback to slot " + targetSlot + " failed", e);
        }
    }

    @Override
    public String storeName() {
        return "accountHistoryStore";
    }

    @Override
    public long getLatestAppliedSlot() {
        if (!enabled) return -1;
        Long metaSlot = readLongMeta(META_LAST_APPLIED_SLOT);
        if (metaSlot != null) return metaSlot;

        long latest = -1;
        try (RocksIterator it = db.newIterator(cfDelta)) {
            it.seekToLast();
            while (it.isValid()) {
                if (it.key().length == 8) {
                    latest = Math.max(latest, AccountHistoryCborCodec.decodeDelta(it.value()).slot());
                    break;
                }
                it.prev();
            }
        } catch (Exception e) {
            log.warn("Failed to read account history latest delta slot: {}", e.getMessage());
        }
        try (RocksIterator it = db.newIterator(cfHistory)) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length == HISTORY_KEY_LEN) {
                    latest = Math.max(latest, slotFromHistoryKey(key));
                }
                it.next();
            }
        } catch (Exception e) {
            log.warn("Failed to scan account history latest row slot: {}", e.getMessage());
        }
        return latest;
    }

    public long getLastAppliedBlock() {
        if (!enabled) return -1;
        Long metaBlock = readLongMeta(META_LAST_APPLIED_BLOCK);
        if (metaBlock != null) return metaBlock;

        return latestDeltaBlock();
    }

    @Override
    public long getRollbackFloorSlot() {
        // Account history is append-only. Rollback can always truncate retained rows
        // by their slot, even if old delta records have already been pruned.
        return 0;
    }

    private int rollbackDeltasAfterSlot(long targetSlot) throws RocksDBException {
        int deletedRows = 0;
        int pendingOps = 0;
        int batchLimit = Math.max(1, pruneBatchSize);
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions();
             RocksIterator it = db.newIterator(cfDelta)) {
            it.seekToLast();
            while (it.isValid()) {
                byte[] deltaKey = Arrays.copyOf(it.key(), it.key().length);
                var delta = AccountHistoryCborCodec.decodeDelta(it.value());
                if (delta.slot() <= targetSlot) break;

                for (byte[] key : delta.keys()) {
                    batch.delete(cfHistory, key);
                    deletedRows++;
                    pendingOps++;
                }
                batch.delete(cfDelta, deltaKey);
                pendingOps++;

                it.prev();
                if (pendingOps >= batchLimit) {
                    db.write(wo, batch);
                    batch.clear();
                    pendingOps = 0;
                }
            }
            if (pendingOps > 0) db.write(wo, batch);
        }
        return deletedRows;
    }

    private int deleteHistoryRowsAfterSlot(long targetSlot) throws RocksDBException {
        int deletedRows = 0;
        int pendingOps = 0;
        int batchLimit = Math.max(1, pruneBatchSize);
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions();
             RocksIterator it = db.newIterator(cfHistory)) {
            it.seekToFirst();
            while (it.isValid()) {
                byte[] key = it.key();
                byte[] keyToDelete = null;
                if (key.length == HISTORY_KEY_LEN && slotFromHistoryKey(key) > targetSlot) {
                    keyToDelete = Arrays.copyOf(key, key.length);
                }
                it.next();

                if (keyToDelete != null) {
                    batch.delete(cfHistory, keyToDelete);
                    deletedRows++;
                    pendingOps++;
                    if (pendingOps >= batchLimit) {
                        db.write(wo, batch);
                        batch.clear();
                        pendingOps = 0;
                    }
                }
            }
            if (pendingOps > 0) db.write(wo, batch);
        }
        return deletedRows;
    }

    private void updateRollbackCursor(long targetSlot, long latestBefore) throws RocksDBException {
        long newLastSlot = latestBefore >= 0 ? Math.min(latestBefore, targetSlot) : targetSlot;
        lastKnownSlot = newLastSlot;
        try {
            lastKnownEpoch = epochParamProvider.getEpochSlotCalc().slotToEpoch(newLastSlot);
        } catch (Exception e) {
            lastKnownEpoch = -1;
        }
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            if (latestBefore >= 0) {
                batch.put(cfHistory, META_LAST_APPLIED_SLOT, longBytes(newLastSlot));
            } else {
                batch.delete(cfHistory, META_LAST_APPLIED_SLOT);
            }
            if (latestBefore > targetSlot) {
                long latestDeltaBlock = latestDeltaBlock();
                if (latestDeltaBlock > 0) {
                    batch.put(cfHistory, META_LAST_APPLIED_BLOCK, longBytes(latestDeltaBlock));
                } else {
                    batch.delete(cfHistory, META_LAST_APPLIED_BLOCK);
                }
            }
            db.write(wo, batch);
        }
    }

    public synchronized void handleEpochTransition(int previousEpoch, int newEpoch) {
        if (!enabled || retentionEpochs <= 0) return;
        lastKnownEpoch = newEpoch;
        pruneOnce();
    }

    public synchronized void pruneOnce() {
        if (!enabled || retentionEpochs <= 0 || lastKnownEpoch < 0) return;
        int cutoffEpoch = lastKnownEpoch - retentionEpochs;
        if (cutoffEpoch <= 0) return;
        long cutoffSlot = epochParamProvider.getEpochSlotCalc().epochToStartSlot(cutoffEpoch);
        long historyCutoffSlot = cutoffSlot;
        if (rollbackSafetySlots > 0) {
            historyCutoffSlot = Math.max(0, cutoffSlot - rollbackSafetySlots);
        }
        long deltaCutoffSlot = cutoffSlot;
        if (rollbackSafetySlots > 0 && lastKnownSlot > 0) {
            deltaCutoffSlot = Math.min(cutoffSlot, Math.max(0, lastKnownSlot - rollbackSafetySlots));
        }
        pruneBeforeSlot(historyCutoffSlot, deltaCutoffSlot);
    }

    public synchronized int pruneBeforeSlot(long cutoffSlot) {
        return pruneBeforeSlot(cutoffSlot, cutoffSlot);
    }

    private int pruneBeforeSlot(long cutoffSlot, long deltaCutoffSlot) {
        if (!enabled || (cutoffSlot <= 0 && deltaCutoffSlot <= 0)) return 0;

        int deleted = 0;
        int deltaDeleted = 0;
        try (WriteBatch batch = new WriteBatch(); WriteOptions wo = new WriteOptions();
             RocksIterator it = db.newIterator(cfHistory);
             RocksIterator deltaIt = db.newIterator(cfDelta)) {
            it.seekToFirst();
            while (it.isValid() && deleted < pruneBatchSize) {
                byte[] key = it.key();
                if (cutoffSlot > 0 && key.length == HISTORY_KEY_LEN && slotFromHistoryKey(key) < cutoffSlot) {
                    batch.delete(cfHistory, Arrays.copyOf(key, key.length));
                    deleted++;
                }
                it.next();
            }
            deltaIt.seekToFirst();
            while (deltaIt.isValid() && deleted + deltaDeleted < pruneBatchSize) {
                byte[] value = deltaIt.value();
                var delta = AccountHistoryCborCodec.decodeDelta(value);
                if (delta.slot() < deltaCutoffSlot) {
                    batch.delete(cfDelta, Arrays.copyOf(deltaIt.key(), deltaIt.key().length));
                    deltaDeleted++;
                }
                deltaIt.next();
            }
            if (deleted > 0 || deltaDeleted > 0) db.write(wo, batch);
            if (deleted > 0 || deltaDeleted > 0) {
                log.info("Account history pruned {} records and {} rollback deltas before slot {}",
                        deleted, deltaDeleted, cutoffSlot);
            }
            return deleted + deltaDeleted;
        } catch (Exception e) {
            healthy = false;
            log.error("Account history prune failed before slot {}: {}", cutoffSlot, e.toString(), e);
            return deleted + deltaDeleted;
        }
    }

    public synchronized void reconcile(ChainState chainState) {
        if (!enabled || !txEventsEnabled || chainState == null) return;

        ChainTip tip = chainState.getTip();
        if (tip == null) return;
        long tipBlock = tip.getBlockNumber();
        long lastAppliedBlock = getLastAppliedBlock();

        if (lastAppliedBlock == tipBlock) return;

        if (lastAppliedBlock > tipBlock) {
            rollbackToSlot(tip.getSlot());
            log.info("Account history reconciled: rolled back from block {} to tip block {}",
                    lastAppliedBlock, tipBlock);
            return;
        }

        if (lastAppliedBlock == 0 && getLatestAppliedSlot() < 0 && chainState.getBlockEra(tipBlock) == Era.Byron) {
            log.info("Account history reconcile skipped: tip block {} is Byron era, nothing to index", tipBlock);
            return;
        }

        log.info("Account history reconcile: replaying blocks {} to {}", lastAppliedBlock + 1, tipBlock);
        healthy = true;
        boolean failed = false;
        long replayed = 0;
        for (long bn = lastAppliedBlock + 1; bn <= tipBlock; bn++) {
            if ((bn - lastAppliedBlock) % 1000 == 0) {
                log.info("Account history reconcile progress: block {}/{}", bn, tipBlock);
            }
            byte[] blockBytes = chainState.getBlockByNumber(bn);
            if (blockBytes == null) {
                failed = true;
                log.warn("Account history reconcile: missing block body for block {}", bn);
                continue;
            }

            try {
                Block block = BlockSerializer.INSTANCE.deserialize(blockBytes);
                Long slotByNumber = chainState.getSlotByBlockNumber(bn);
                long slot = slotByNumber != null
                        ? slotByNumber
                        : block.getHeader().getHeaderBody().getSlot();
                String blockHash = block.getHeader() != null && block.getHeader().getHeaderBody() != null
                        ? block.getHeader().getHeaderBody().getBlockHash()
                        : null;
                Era era = block.getEra() != null ? block.getEra() : chainState.getBlockEra(bn);
                applyBlock(new BlockAppliedEvent(era, slot, bn, blockHash, block));
                if (!healthy) failed = true;
                replayed++;
            } catch (Throwable t) {
                healthy = false;
                failed = true;
                log.warn("Account history reconcile: skip block {} due to: {}", bn, t.toString());
            }
        }
        healthy = !failed;
        log.info("Account history reconciled: replayed {} blocks from {} to tip {}",
                replayed, lastAppliedBlock, tipBlock);
    }

    public List<AccountHistoryProvider.WithdrawalRecord> getWithdrawals(int credType, String credHash, int page, int count) {
        return listByCredential(TYPE_WITHDRAWAL, credType, credHash, page, count,
                AccountHistoryCborCodec::decodeWithdrawal);
    }

    @Override
    public List<AccountHistoryProvider.WithdrawalRecord> getWithdrawals(int credType, String credHash, int page, int count, String order) {
        return listByCredential(TYPE_WITHDRAWAL, credType, credHash, page, count, order,
                AccountHistoryCborCodec::decodeWithdrawal);
    }

    public List<AccountHistoryProvider.DelegationRecord> getDelegations(int credType, String credHash, int page, int count) {
        return listByCredential(TYPE_DELEGATION, credType, credHash, page, count,
                AccountHistoryCborCodec::decodeDelegation);
    }

    @Override
    public List<AccountHistoryProvider.DelegationRecord> getDelegations(int credType, String credHash, int page, int count, String order) {
        return listByCredential(TYPE_DELEGATION, credType, credHash, page, count, order,
                AccountHistoryCborCodec::decodeDelegation);
    }

    public List<AccountHistoryProvider.RegistrationRecord> getRegistrations(int credType, String credHash, int page, int count) {
        return listByCredential(TYPE_REGISTRATION, credType, credHash, page, count,
                AccountHistoryCborCodec::decodeRegistration);
    }

    @Override
    public List<AccountHistoryProvider.RegistrationRecord> getRegistrations(int credType, String credHash, int page, int count, String order) {
        return listByCredential(TYPE_REGISTRATION, credType, credHash, page, count, order,
                AccountHistoryCborCodec::decodeRegistration);
    }

    public List<AccountHistoryProvider.MirRecord> getMirs(int credType, String credHash, int page, int count) {
        return listByCredential(TYPE_MIR, credType, credHash, page, count,
                AccountHistoryCborCodec::decodeMir);
    }

    @Override
    public List<AccountHistoryProvider.MirRecord> getMirs(int credType, String credHash, int page, int count, String order) {
        return listByCredential(TYPE_MIR, credType, credHash, page, count, order,
                AccountHistoryCborCodec::decodeMir);
    }

    public long countByType(byte type) {
        long count = 0;
        try (RocksIterator it = db.newIterator(cfHistory)) {
            it.seek(new byte[]{type});
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length == 0 || key[0] != type) break;
                count++;
                it.next();
            }
        }
        return count;
    }

    public long countDeltas() {
        long count = 0;
        try (RocksIterator it = db.newIterator(cfDelta)) {
            it.seekToFirst();
            while (it.isValid()) {
                count++;
                it.next();
            }
        }
        return count;
    }

    private void indexCertificate(Certificate cert, Era era, int currentEpoch, long slot, long blockNo,
                                  int txIdx, int certIdx, String txHash,
                                  WriteBatch batch, List<byte[]> insertedKeys) throws RocksDBException {
        switch (cert) {
            case StakeRegistration sr -> indexRegistration(sr.getStakeCredential(), "registered",
                    epochParamProvider.getKeyDeposit(currentEpoch), slot, blockNo, txIdx, certIdx, txHash, batch, insertedKeys);
            case RegCert rc -> indexRegistration(rc.getStakeCredential(), "registered",
                    rc.getCoin(), slot, blockNo, txIdx, certIdx, txHash, batch, insertedKeys);
            case StakeDeregistration sd -> indexRegistration(sd.getStakeCredential(), "deregistered",
                    BigInteger.ZERO, slot, blockNo, txIdx, certIdx, txHash, batch, insertedKeys);
            case UnregCert uc -> indexRegistration(uc.getStakeCredential(), "deregistered",
                    BigInteger.ZERO, slot, blockNo, txIdx, certIdx, txHash, batch, insertedKeys);
            case StakeDelegation sd -> indexDelegation(sd.getStakeCredential(), sd.getStakePoolId().getPoolKeyHash(),
                    currentEpoch, slot, blockNo, txIdx, certIdx, txHash, batch, insertedKeys);
            case StakeVoteDelegCert svd -> indexDelegation(svd.getStakeCredential(), svd.getPoolKeyHash(),
                    currentEpoch, slot, blockNo, txIdx, certIdx, txHash, batch, insertedKeys);
            case StakeRegDelegCert srd -> {
                indexRegistration(srd.getStakeCredential(), "registered", srd.getCoin(),
                        slot, blockNo, txIdx, certIdx, txHash, batch, insertedKeys);
                indexDelegation(srd.getStakeCredential(), srd.getPoolKeyHash(), currentEpoch,
                        slot, blockNo, txIdx, certIdx, txHash, batch, insertedKeys);
            }
            case StakeVoteRegDelegCert svrd -> {
                indexRegistration(svrd.getStakeCredential(), "registered", svrd.getCoin(),
                        slot, blockNo, txIdx, certIdx, txHash, batch, insertedKeys);
                indexDelegation(svrd.getStakeCredential(), svrd.getPoolKeyHash(), currentEpoch,
                        slot, blockNo, txIdx, certIdx, txHash, batch, insertedKeys);
            }
            case MoveInstataneous mir -> indexMir(mir, era, currentEpoch, slot, blockNo,
                    txIdx, certIdx, txHash, batch, insertedKeys);
            default -> {
            }
        }
    }

    private void indexWithdrawal(StakeCred cred, String txHash, BigInteger amount, long slot, long blockNo,
                                 int txIdx, int eventIdx, WriteBatch batch, List<byte[]> insertedKeys) throws RocksDBException {
        byte[] key = historyKey(TYPE_WITHDRAWAL, cred.credType(), cred.credHash(), slot, txIdx, eventIdx);
        byte[] val = AccountHistoryCborCodec.encodeWithdrawal(txHash, amount, slot, blockNo, txIdx);
        batch.put(cfHistory, key, val);
        insertedKeys.add(key);
    }

    private void indexRegistration(StakeCredential cred, String action, BigInteger deposit, long slot, long blockNo,
                                   int txIdx, int certIdx, String txHash,
                                   WriteBatch batch, List<byte[]> insertedKeys) throws RocksDBException {
        if (cred == null || cred.getHash() == null || cred.getHash().isBlank()) return;
        int credType = credTypeInt(cred.getType());
        String credHash = cred.getHash();
        byte[] key = historyKey(TYPE_REGISTRATION, credType, credHash, slot, txIdx, certIdx);
        byte[] val = AccountHistoryCborCodec.encodeRegistration(txHash, action,
                deposit != null ? deposit : BigInteger.ZERO, slot, blockNo, txIdx, certIdx);
        batch.put(cfHistory, key, val);
        insertedKeys.add(key);
    }

    private void indexDelegation(StakeCredential cred, String poolHash, int currentEpoch, long slot, long blockNo,
                                 int txIdx, int certIdx, String txHash,
                                 WriteBatch batch, List<byte[]> insertedKeys) throws RocksDBException {
        if (cred == null || cred.getHash() == null || cred.getHash().isBlank()) return;
        if (poolHash == null || poolHash.isBlank()) return;
        int credType = credTypeInt(cred.getType());
        String credHash = cred.getHash();
        byte[] key = historyKey(TYPE_DELEGATION, credType, credHash, slot, txIdx, certIdx);
        byte[] val = AccountHistoryCborCodec.encodeDelegation(txHash, poolHash, slot, blockNo,
                txIdx, certIdx, currentEpoch + 2);
        batch.put(cfHistory, key, val);
        insertedKeys.add(key);
    }

    private void indexMir(MoveInstataneous mir, Era era, int currentEpoch, long slot, long blockNo,
                          int txIdx, int certIdx, String txHash,
                          WriteBatch batch, List<byte[]> insertedKeys) throws RocksDBException {
        Map<StakeCredential, BigInteger> credMap = mir.getStakeCredentialCoinMap();
        if (credMap == null || credMap.isEmpty()) return;

        String pot = mir.isTreasury() ? "treasury" : "reserves";
        int itemIdx = 0;
        for (var entry : credMap.entrySet()) {
            StakeCredential cred = entry.getKey();
            BigInteger amount = entry.getValue();
            if (cred == null || amount == null || amount.signum() <= 0) continue;
            int credType = credTypeInt(cred.getType());
            String credHash = cred.getHash();
            byte[] key = historyKey(TYPE_MIR, credType, credHash, slot, txIdx,
                    (certIdx << 16) | (itemIdx++ & 0xFFFF));
            byte[] val = AccountHistoryCborCodec.encodeMir(txHash, pot, amount, currentEpoch,
                    slot, blockNo, txIdx, certIdx);
            batch.put(cfHistory, key, val);
            insertedKeys.add(key);
        }
    }

    private static StakeCred rewardAccountCred(String rewardAddrHex) {
        try {
            byte[] addrBytes = HexUtil.decodeHexString(rewardAddrHex);
            if (addrBytes.length < 29) return null;
            int headerByte = addrBytes[0] & 0xFF;
            int credType = ((headerByte & 0x10) != 0) ? 1 : 0;
            return new StakeCred(credType, HexUtil.encodeHexString(Arrays.copyOfRange(addrBytes, 1, 29)));
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] historyKey(byte type, int credType, String credHash, long slot, int txIdx, int eventIdx) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        if (hash.length != 28) {
            throw new IllegalArgumentException("stake credential hash must be 28 bytes");
        }
        ByteBuffer bb = ByteBuffer.allocate(HISTORY_KEY_LEN).order(ByteOrder.BIG_ENDIAN);
        bb.put(type);
        bb.put((byte) credType);
        bb.put(hash);
        bb.putLong(slot);
        bb.putInt(txIdx);
        bb.putInt(eventIdx);
        return bb.array();
    }

    private static long slotFromHistoryKey(byte[] key) {
        return ByteBuffer.wrap(key, 30, 8).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    private <T> List<T> listByCredential(byte type, int credType, String credHash,
                                         int page, int count, Function<byte[], T> decoder) {
        return listByCredential(type, credType, credHash, page, count, "asc", decoder);
    }

    private <T> List<T> listByCredential(byte type, int credType, String credHash,
                                         int page, int count, String order, Function<byte[], T> decoder) {
        if (!enabled || credHash == null || credHash.isBlank()) return List.of();

        byte[] prefix;
        try {
            prefix = historyPrefix(type, credType, credHash);
        } catch (Exception e) {
            return List.of();
        }

        int safePage = Math.max(1, page);
        int safeCount = Math.max(1, count);
        long skip = (long) (safePage - 1) * safeCount;
        List<T> result = new ArrayList<>(safeCount);
        boolean descending = "desc".equalsIgnoreCase(order);

        try (RocksIterator it = db.newIterator(cfHistory)) {
            if (descending) {
                byte[] seekKey = Arrays.copyOf(prefix, HISTORY_KEY_LEN);
                Arrays.fill(seekKey, prefix.length, seekKey.length, (byte) 0xFF);
                it.seekForPrev(seekKey);
                while (it.isValid() && startsWith(it.key(), prefix)) {
                    if (skip > 0) {
                        skip--;
                    } else {
                        result.add(decoder.apply(it.value()));
                        if (result.size() >= safeCount) break;
                    }
                    it.prev();
                }
            } else {
                it.seek(prefix);
                while (it.isValid() && startsWith(it.key(), prefix)) {
                    if (skip > 0) {
                        skip--;
                    } else {
                        result.add(decoder.apply(it.value()));
                        if (result.size() >= safeCount) break;
                    }
                    it.next();
                }
            }
        }

        return result;
    }

    private static byte[] historyPrefix(byte type, int credType, String credHash) {
        byte[] hash = HexUtil.decodeHexString(credHash);
        if (hash.length != 28) {
            throw new IllegalArgumentException("stake credential hash must be 28 bytes");
        }
        ByteBuffer bb = ByteBuffer.allocate(HISTORY_PREFIX_LEN).order(ByteOrder.BIG_ENDIAN);
        bb.put(type);
        bb.put((byte) credType);
        bb.put(hash);
        return bb.array();
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key == null || prefix == null || key.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) return false;
        }
        return true;
    }

    private static byte[] blockDeltaKey(long blockNo) {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNo).array();
    }

    private void putLastApplied(WriteBatch batch, long slot, long blockNo) throws RocksDBException {
        batch.put(cfHistory, META_LAST_APPLIED_SLOT, longBytes(slot));
        batch.put(cfHistory, META_LAST_APPLIED_BLOCK, longBytes(blockNo));
    }

    private Long readLongMeta(byte[] key) {
        try {
            byte[] value = db.get(cfHistory, key);
            if (value != null && value.length == 8) {
                return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).getLong();
            }
        } catch (Exception e) {
            log.warn("Failed to read account history metadata: {}", e.getMessage());
        }
        return null;
    }

    private long latestDeltaBlock() {
        try (RocksIterator it = db.newIterator(cfDelta)) {
            it.seekToLast();
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length == 8) {
                    return ByteBuffer.wrap(key).order(ByteOrder.BIG_ENDIAN).getLong();
                }
                it.prev();
            }
        } catch (Exception e) {
            log.warn("Failed to read account history latest delta block: {}", e.getMessage());
        }
        return 0;
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array();
    }

    private static int credTypeInt(StakeCredType type) {
        return type == StakeCredType.ADDR_KEYHASH ? 0 : 1;
    }

    private static boolean getBool(Map<String, Object> config, String key, boolean def) {
        Object v = config != null ? config.get(key) : null;
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static int getInt(Map<String, Object> config, String key, int def) {
        Object v = config != null ? config.get(key) : null;
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static long getLong(Map<String, Object> config, String key, long def) {
        Object v = config != null ? config.get(key) : null;
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private record StakeCred(int credType, String credHash) {}

}
