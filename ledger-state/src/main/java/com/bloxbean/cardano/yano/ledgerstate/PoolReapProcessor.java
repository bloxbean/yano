package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.account.RewardType;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded, resumable application of the Shelley POOLREAP live-state transition. */
final class PoolReapProcessor {
    static final byte[] META_POOL_REAP_PROGRESS =
            "meta.pool.reap.progress.v1".getBytes(StandardCharsets.UTF_8);

    private static final byte PROGRESS_VERSION = 1;
    static final byte STAGE_DELEGATIONS = 1;
    static final byte STAGE_POOLS = 2;

    private final RocksDB db;
    private final ColumnFamilyHandle cfState;
    private final DefaultAccountStateStore store;
    private final EpochRewardCalculator rewardCalculator;
    private final Logger log;
    private final int maxBatchOperations;
    private final int maxBatchBytes;
    private final int maxScanRows;
    private final CommitHook commitHook;

    PoolReapProcessor(RocksDB db, ColumnFamilyHandle cfState,
                      DefaultAccountStateStore store,
                      EpochRewardCalculator rewardCalculator,
                      Logger log, int maxBatchOperations, int maxBatchBytes) {
        this(db, cfState, store, rewardCalculator, log,
                maxBatchOperations, maxBatchBytes, checkpoint -> { });
    }

    PoolReapProcessor(RocksDB db, ColumnFamilyHandle cfState,
                      DefaultAccountStateStore store,
                      EpochRewardCalculator rewardCalculator,
                      Logger log, int maxBatchOperations, int maxBatchBytes,
                      CommitHook commitHook) {
        this.db = db;
        this.cfState = cfState;
        this.store = store;
        this.rewardCalculator = rewardCalculator;
        this.log = log;
        this.maxBatchOperations = maxBatchOperations;
        this.maxBatchBytes = maxBatchBytes;
        this.maxScanRows = Math.max(maxBatchOperations,
                (int) Math.min(100_000L, (long) maxBatchOperations * 10));
        this.commitHook = commitHook != null ? commitHook : checkpoint -> { };
    }

    Result process(int epoch, long boundarySlot) {
        long started = System.currentTimeMillis();
        Progress progress = readProgress();
        boolean resuming = progress != null;
        if (progress != null) {
            validateProgress(progress, epoch, boundarySlot);
        }

        PoolReapPlan plan = buildPlan(epoch);
        if (progress == null) {
            if (plan.entries().isEmpty()) {
                return new Result(0, 0, 0, 0, 0, 0,
                        BigInteger.ZERO, BigInteger.ZERO,
                        System.currentTimeMillis() - started);
            }
            if (rewardCalculator == null || !rewardCalculator.isEnabled()) {
                throw new IllegalStateException("POOLREAP for epoch " + epoch
                        + " requires the monetary reward/refund processor");
            }
            progress = new Progress(epoch, boundarySlot, STAGE_DELEGATIONS,
                    null, null, 1);
            commitProgress(progress, 0);
        } else if (plan.entries().isEmpty()) {
            throw new IllegalStateException("POOLREAP progress exists for epoch " + epoch
                    + " but no live retirement entries remain");
        }

        MutableMetrics metrics = new MutableMetrics(
                plan.entries().size(), progress.nextSequence());
        log.info("POOLREAP epoch {} {}: retiringPools={}, stage={}, nextSequence={}",
                epoch, resuming ? "resuming" : "started", plan.entries().size(),
                progress.stage() == STAGE_DELEGATIONS ? "delegations" : "pools",
                progress.nextSequence());
        if (progress.stage() == STAGE_DELEGATIONS) {
            progress = reapDelegations(plan, progress, metrics);
        }
        reapPools(epoch, progress, metrics);

        Result result = metrics.result(System.currentTimeMillis() - started);
        log.info("POOLREAP epoch {} complete: retiringPools={}, delegationRowsExamined={}, "
                        + "delegationsRemoved={}, poolRowsRemoved={}, registeredRefunds={}, "
                        + "registeredRefundAmount={}, unclaimedDepositAmount={}, chunks={}, elapsedMs={}",
                epoch, result.retiringPools(), result.delegationRowsExamined(),
                result.delegationsRemoved(), result.poolRowsRemoved(),
                result.registeredRefunds(), result.registeredRefundAmount(),
                result.unclaimedDepositAmount(), result.chunks(), result.elapsedMillis());
        return result;
    }

    private Progress reapDelegations(PoolReapPlan plan, Progress initial,
                                     MutableMetrics metrics) {
        Progress progress = initial;
        while (progress.stage() == STAGE_DELEGATIONS) {
            DelegationChunk chunk = readDelegationChunk(
                    plan.retiringPoolHashes(), progress.lastDelegationKey());
            int sequence = progress.nextSequence();
            Progress next = new Progress(progress.epoch(), progress.boundarySlot(),
                    chunk.exhausted() ? STAGE_POOLS : STAGE_DELEGATIONS,
                    chunk.exhausted() ? null : chunk.lastExaminedKey(), null,
                    sequence + 1);

            try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
                List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
                for (byte[] key : chunk.keysToDelete()) {
                    store.deleteStateWithDelta(key, batch, deltaOps);
                }
                store.putStateWithDelta(META_POOL_REAP_PROGRESS, encodeProgress(next),
                        batch, deltaOps);
                store.commitBoundaryDelta(progress.boundarySlot(),
                        DefaultAccountStateStore.PHASE_POOLREAP, sequence, batch, deltaOps);
                checkpoint(sequence, next.stage(), false, CommitMoment.BEFORE);
                db.write(options, batch);
                checkpoint(sequence, next.stage(), false, CommitMoment.AFTER);
            } catch (RocksDBException e) {
                throw new IllegalStateException("Failed to commit POOLREAP delegation chunk "
                        + sequence + " for epoch " + progress.epoch(), e);
            }

            metrics.delegationRowsExamined += chunk.rowsExamined();
            metrics.delegationsRemoved += chunk.keysToDelete().size();
            metrics.chunks++;
            progress = next;
        }
        return progress;
    }

    private DelegationChunk readDelegationChunk(Set<String> retiringPoolHashes,
                                                 byte[] lastExaminedKey) {
        List<byte[]> keysToDelete = new ArrayList<>();
        byte[] lastKey = null;
        int rowsExamined = 0;
        long estimatedBytes = 0;
        boolean exhausted;

        try (ReadOptions options = new ReadOptions().setFillCache(false);
             RocksIterator iterator = db.newIterator(cfState, options)) {
            if (lastExaminedKey == null) {
                iterator.seek(new byte[]{DefaultAccountStateStore.PREFIX_POOL_DELEG});
            } else {
                iterator.seek(lastExaminedKey);
                if (iterator.isValid() && Arrays.equals(iterator.key(), lastExaminedKey)) {
                    iterator.next();
                }
            }

            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length == 0
                        || key[0] != DefaultAccountStateStore.PREFIX_POOL_DELEG) {
                    break;
                }
                byte[] value = iterator.value();
                AccountStateCborCodec.PoolDelegation delegation;
                try {
                    delegation = AccountStateCborCodec.decodePoolDelegation(value);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Malformed live pool delegation at key "
                            + HexUtil.encodeHexString(key), e);
                }

                lastKey = key.clone();
                rowsExamined++;
                if (retiringPoolHashes.contains(delegation.poolHash())) {
                    keysToDelete.add(lastKey);
                    estimatedBytes += key.length + value.length + 16;
                }
                iterator.next();

                if (rowsExamined >= maxScanRows
                        || keysToDelete.size() >= maxBatchOperations
                        || estimatedBytes >= maxBatchBytes) {
                    break;
                }
            }
            exhausted = !iterator.isValid()
                    || iterator.key().length == 0
                    || iterator.key()[0] != DefaultAccountStateStore.PREFIX_POOL_DELEG;
        }
        return new DelegationChunk(keysToDelete, lastKey, rowsExamined, exhausted);
    }

    private void reapPools(int epoch, Progress initial, MutableMetrics metrics) {
        Progress progress = initial;
        while (true) {
            PoolReapPlan remaining = buildPlan(epoch);
            String durablePoolCursor = progress.lastPoolHash();
            List<PoolReapEntry> entries = remaining.entries().stream()
                    .filter(entry -> durablePoolCursor == null
                            || entry.poolHash().compareTo(durablePoolCursor) > 0)
                    .toList();
            if (entries.isEmpty()) {
                throw new IllegalStateException("POOLREAP pool stage for epoch " + epoch
                        + " has no pool after durable cursor " + progress.lastPoolHash());
            }
            for (PoolReapEntry entry : remaining.entries()) {
                if (progress.lastPoolHash() != null
                        && entry.poolHash().compareTo(progress.lastPoolHash()) <= 0) {
                    throw new IllegalStateException("POOLREAP found already-applied pool "
                            + entry.poolHash() + " at or before durable cursor "
                            + progress.lastPoolHash());
                }
            }

            int sequence = progress.nextSequence();
            rewardCalculator.beginPoolReapBatch(epoch,
                    "pool-reap-" + String.format("%06d", sequence));
            boolean batchCommitted = false;
            try {
                WriteBatch batch = rewardCalculator.getRewardBatch();
                List<DefaultAccountStateStore.DeltaOp> deltaOps =
                        rewardCalculator.getRewardDeltaOps();
                DefaultAccountStateStore.BatchStateOverlay overlay =
                        rewardCalculator.getRewardStateOverlay();

                int processed = 0;
                String lastPoolHash = null;
                int refunded = 0;
                for (PoolReapEntry entry : entries) {
                    validateEntry(entry);
                    if (entry.registeredRewardCredential()) {
                        try {
                            rewardCalculator.creditReward(entry.rewardCredentialType(),
                                    entry.rewardCredentialHash(), entry.deposit(), epoch,
                                    RewardType.REFUND, entry.poolHash());
                        } catch (RocksDBException e) {
                            throw new IllegalStateException("Failed to stage pool deposit refund for "
                                    + entry.poolHash(), e);
                        }
                        refunded++;
                        metrics.registeredRefundAmount =
                                metrics.registeredRefundAmount.add(entry.deposit());
                    } else {
                        metrics.unclaimedDepositAmount =
                                metrics.unclaimedDepositAmount.add(entry.deposit());
                    }

                    try {
                        store.deleteStateWithDelta(
                                DefaultAccountStateStore.poolDepositKey(entry.poolHash()),
                                batch, deltaOps, overlay);
                        store.deleteStateWithDelta(
                                DefaultAccountStateStore.poolRetireKey(entry.poolHash()),
                                batch, deltaOps, overlay);
                        store.deleteStateWithDelta(
                                DefaultAccountStateStore.poolRegSlotKey(entry.poolHash()),
                                batch, deltaOps, overlay);
                    } catch (RocksDBException e) {
                        throw new IllegalStateException("Failed to stage live pool cleanup for "
                                + entry.poolHash(), e);
                    }
                    processed++;
                    lastPoolHash = entry.poolHash();
                    if (deltaOps.size() >= maxBatchOperations
                            || estimatedDeltaBytes(deltaOps) >= maxBatchBytes) {
                        break;
                    }
                }

                boolean finalChunk = processed == entries.size();
                try {
                    if (finalChunk) {
                        store.deleteStateWithDelta(META_POOL_REAP_PROGRESS,
                                batch, deltaOps, overlay);
                    } else {
                        Progress next = new Progress(epoch, progress.boundarySlot(), STAGE_POOLS,
                                null, lastPoolHash, sequence + 1);
                        store.putStateWithDelta(META_POOL_REAP_PROGRESS, encodeProgress(next),
                                batch, deltaOps, overlay);
                    }
                    checkpoint(sequence, STAGE_POOLS, finalChunk, CommitMoment.BEFORE);
                    rewardCalculator.commitRewardBatch(progress.boundarySlot(),
                            DefaultAccountStateStore.PHASE_POOLREAP, sequence);
                    batchCommitted = true;
                    checkpoint(sequence, STAGE_POOLS, finalChunk, CommitMoment.AFTER);
                } catch (RocksDBException e) {
                    throw new IllegalStateException("Failed to commit POOLREAP pool chunk "
                            + sequence + " for epoch " + epoch, e);
                }

                metrics.poolRowsRemoved += processed * 3;
                metrics.registeredRefunds += refunded;
                metrics.chunks++;
                if (finalChunk) return;
                progress = new Progress(epoch, progress.boundarySlot(), STAGE_POOLS,
                        null, lastPoolHash, sequence + 1);
            } finally {
                if (!batchCommitted) rewardCalculator.abortRewardBatch();
            }
        }
    }

    private PoolReapPlan buildPlan(int epoch) {
        List<PoolReapEntry> entries = new ArrayList<>();
        try (ReadOptions options = new ReadOptions().setFillCache(false);
             RocksIterator iterator = db.newIterator(cfState, options)) {
            iterator.seek(new byte[]{DefaultAccountStateStore.PREFIX_POOL_RETIRE});
            while (iterator.isValid()) {
                byte[] retirementKey = iterator.key();
                if (retirementKey.length == 0
                        || retirementKey[0] != DefaultAccountStateStore.PREFIX_POOL_RETIRE) {
                    break;
                }
                byte[] retirementValue = iterator.value().clone();
                long retirementEpoch;
                try {
                    retirementEpoch = AccountStateCborCodec.decodePoolRetirement(retirementValue);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Malformed live pool retirement at key "
                            + HexUtil.encodeHexString(retirementKey), e);
                }
                if (retirementEpoch == epoch) {
                    if (retirementKey.length != 29) {
                        throw new IllegalStateException("Malformed live pool retirement key "
                                + HexUtil.encodeHexString(retirementKey));
                    }
                    String poolHash = HexUtil.encodeHexString(
                            Arrays.copyOfRange(retirementKey, 1, retirementKey.length));
                    entries.add(buildEntry(poolHash, retirementValue));
                }
                iterator.next();
            }
        }
        entries.sort((left, right) -> left.poolHash().compareTo(right.poolHash()));
        Set<String> hashes = new HashSet<>();
        for (PoolReapEntry entry : entries) hashes.add(entry.poolHash());
        return new PoolReapPlan(List.copyOf(entries), Set.copyOf(hashes));
    }

    private PoolReapEntry buildEntry(String poolHash, byte[] retirementValue) {
        try {
            byte[] poolValue = db.get(cfState,
                    DefaultAccountStateStore.poolDepositKey(poolHash));
            if (poolValue == null) {
                throw new IllegalStateException("POOLREAP retirement " + poolHash
                        + " has no live pool registration");
            }
            AccountStateCborCodec.PoolRegistrationData pool =
                    AccountStateCborCodec.decodePoolRegistration(poolValue);
            if (pool.deposit() == null || pool.deposit().signum() <= 0) {
                throw new IllegalStateException("POOLREAP pool " + poolHash
                        + " has an invalid lifecycle deposit");
            }
            byte[] registrationSlotValue = db.get(cfState,
                    DefaultAccountStateStore.poolRegSlotKey(poolHash));
            if (registrationSlotValue == null || registrationSlotValue.length != Long.BYTES) {
                throw new IllegalStateException("POOLREAP pool " + poolHash
                        + " has no valid lifecycle registration slot");
            }

            String credential = EpochRewardCalculator.extractCredKeyFromRewardAddress(
                    pool.rewardAccount(), null);
            Integer credentialType = null;
            String credentialHash = null;
            boolean registered = false;
            if (credential != null) {
                int separator = credential.indexOf(':');
                credentialType = Integer.parseInt(credential.substring(0, separator));
                credentialHash = credential.substring(separator + 1);
                registered = store.isStakeCredentialRegistered(
                        credentialType, credentialHash);
            }
            return new PoolReapEntry(poolHash, pool.deposit(), credentialType,
                    credentialHash, registered, poolValue.clone(), retirementValue,
                    registrationSlotValue.clone());
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to construct POOLREAP plan for "
                    + poolHash, e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) throw e;
            throw new IllegalStateException("Malformed live pool registration for "
                    + poolHash, e);
        }
    }

    private void validateEntry(PoolReapEntry entry) {
        try {
            requireSame(entry.poolHash(), "registration", entry.poolValue(),
                    db.get(cfState, DefaultAccountStateStore.poolDepositKey(entry.poolHash())));
            requireSame(entry.poolHash(), "retirement", entry.retirementValue(),
                    db.get(cfState, DefaultAccountStateStore.poolRetireKey(entry.poolHash())));
            requireSame(entry.poolHash(), "registration slot", entry.registrationSlotValue(),
                    db.get(cfState, DefaultAccountStateStore.poolRegSlotKey(entry.poolHash())));
            if (entry.rewardCredentialType() != null
                    && store.isStakeCredentialRegistered(entry.rewardCredentialType(),
                    entry.rewardCredentialHash()) != entry.registeredRewardCredential()) {
                throw new IllegalStateException("POOLREAP reward credential registration changed for pool "
                        + entry.poolHash());
            }
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to validate POOLREAP plan for "
                    + entry.poolHash(), e);
        }
    }

    private static void requireSame(String poolHash, String field,
                                    byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException("POOLREAP " + field
                    + " changed while applying pool " + poolHash);
        }
    }

    private void commitProgress(Progress progress, int sequence) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions()) {
            List<DefaultAccountStateStore.DeltaOp> deltaOps = new ArrayList<>();
            store.putStateWithDelta(META_POOL_REAP_PROGRESS, encodeProgress(progress),
                    batch, deltaOps);
            store.commitBoundaryDelta(progress.boundarySlot(),
                    DefaultAccountStateStore.PHASE_POOLREAP, sequence, batch, deltaOps);
            checkpoint(sequence, progress.stage(), false, CommitMoment.BEFORE);
            db.write(options, batch);
            checkpoint(sequence, progress.stage(), false, CommitMoment.AFTER);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to start POOLREAP for epoch "
                    + progress.epoch(), e);
        }
    }

    private Progress readProgress() {
        try {
            byte[] value = db.get(cfState, META_POOL_REAP_PROGRESS);
            return value == null ? null : decodeProgress(value);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to read POOLREAP progress", e);
        }
    }

    static Progress readProgress(RocksDB db, ColumnFamilyHandle cfState) {
        try {
            byte[] value = db.get(cfState, META_POOL_REAP_PROGRESS);
            return value == null ? null : decodeProgress(value);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to inspect POOLREAP progress", e);
        }
    }

    private static void validateProgress(Progress progress, int epoch,
                                         long boundarySlot) {
        if (progress.epoch() != epoch || progress.boundarySlot() != boundarySlot) {
            throw new IllegalStateException("POOLREAP progress belongs to epoch "
                    + progress.epoch() + " at slot " + progress.boundarySlot()
                    + " while processing epoch " + epoch + " at slot " + boundarySlot);
        }
    }

    static byte[] encodeProgress(Progress progress) {
        byte[] delegationKey = progress.lastDelegationKey() == null
                ? new byte[0] : progress.lastDelegationKey();
        byte[] poolHash = progress.lastPoolHash() == null
                ? new byte[0] : HexUtil.decodeHexString(progress.lastPoolHash());
        return ByteBuffer.allocate(1 + 4 + 8 + 1 + 4 + 2
                        + delegationKey.length + 1 + poolHash.length)
                .order(ByteOrder.BIG_ENDIAN)
                .put(PROGRESS_VERSION)
                .putInt(progress.epoch())
                .putLong(progress.boundarySlot())
                .put(progress.stage())
                .putInt(progress.nextSequence())
                .putShort((short) delegationKey.length)
                .put(delegationKey)
                .put((byte) poolHash.length)
                .put(poolHash)
                .array();
    }

    private static Progress decodeProgress(byte[] value) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN);
            byte version = buffer.get();
            if (version != PROGRESS_VERSION) {
                throw new IllegalStateException("Unsupported POOLREAP progress version "
                        + version);
            }
            int epoch = buffer.getInt();
            long boundarySlot = buffer.getLong();
            byte stage = buffer.get();
            int nextSequence = buffer.getInt();
            int delegationLength = buffer.getShort() & 0xFFFF;
            byte[] delegationKey = new byte[delegationLength];
            buffer.get(delegationKey);
            int poolLength = buffer.get() & 0xFF;
            byte[] poolHash = new byte[poolLength];
            buffer.get(poolHash);
            if (buffer.hasRemaining() || nextSequence <= 0
                    || (stage != STAGE_DELEGATIONS && stage != STAGE_POOLS)
                    || (delegationLength != 0 && (delegationLength != 30
                    || delegationKey[0] != DefaultAccountStateStore.PREFIX_POOL_DELEG))
                    || (poolLength != 0 && poolLength != 28)
                    || (stage == STAGE_DELEGATIONS && poolLength != 0)
                    || (stage == STAGE_POOLS && delegationLength != 0)) {
                throw new IllegalStateException("Malformed POOLREAP progress marker");
            }
            return new Progress(epoch, boundarySlot, stage,
                    delegationLength == 0 ? null : delegationKey,
                    poolLength == 0 ? null : HexUtil.encodeHexString(poolHash),
                    nextSequence);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) throw e;
            throw new IllegalStateException("Malformed POOLREAP progress marker", e);
        }
    }

    private static long estimatedDeltaBytes(
            List<DefaultAccountStateStore.DeltaOp> deltaOps) {
        long bytes = 0;
        for (DefaultAccountStateStore.DeltaOp operation : deltaOps) {
            bytes += operation.key().length + 16;
            if (operation.prevValue() != null) bytes += operation.prevValue().length;
        }
        return bytes;
    }

    private void checkpoint(int sequence, byte stage, boolean finalChunk,
                            CommitMoment moment) {
        commitHook.checkpoint(new CommitCheckpoint(sequence,
                stage == STAGE_DELEGATIONS ? "delegations" : "pools",
                finalChunk, moment));
    }

    @FunctionalInterface
    interface CommitHook {
        void checkpoint(CommitCheckpoint checkpoint);
    }

    enum CommitMoment { BEFORE, AFTER }

    record CommitCheckpoint(int sequence, String stage, boolean finalChunk,
                            CommitMoment moment) {
    }

    record Progress(int epoch, long boundarySlot, byte stage,
                    byte[] lastDelegationKey, String lastPoolHash,
                    int nextSequence) {
    }

    record PoolReapEntry(String poolHash, BigInteger deposit,
                         Integer rewardCredentialType, String rewardCredentialHash,
                         boolean registeredRewardCredential,
                         byte[] poolValue, byte[] retirementValue,
                         byte[] registrationSlotValue) {
    }

    private record PoolReapPlan(List<PoolReapEntry> entries,
                                Set<String> retiringPoolHashes) {
    }

    private record DelegationChunk(List<byte[]> keysToDelete,
                                   byte[] lastExaminedKey,
                                   int rowsExamined, boolean exhausted) {
    }

    record Result(int retiringPools, long delegationRowsExamined,
                  int delegationsRemoved, int poolRowsRemoved,
                  int registeredRefunds, int chunks,
                  BigInteger registeredRefundAmount,
                  BigInteger unclaimedDepositAmount,
                  long elapsedMillis) {
    }

    private static final class MutableMetrics {
        private final int retiringPools;
        private long delegationRowsExamined;
        private int delegationsRemoved;
        private int poolRowsRemoved;
        private int registeredRefunds;
        private int chunks;
        private BigInteger registeredRefundAmount = BigInteger.ZERO;
        private BigInteger unclaimedDepositAmount = BigInteger.ZERO;

        private MutableMetrics(int retiringPools, int previouslyCommittedChunks) {
            this.retiringPools = retiringPools;
            this.chunks = previouslyCommittedChunks;
        }

        private Result result(long elapsedMillis) {
            return new Result(retiringPools, delegationRowsExamined,
                    delegationsRemoved, poolRowsRemoved, registeredRefunds,
                    chunks, registeredRefundAmount, unclaimedDepositAmount,
                    elapsedMillis);
        }
    }
}
