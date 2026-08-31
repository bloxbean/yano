package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.account.RewardType;
import com.bloxbean.cardano.yano.api.era.EraProvider;
import com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink;
import com.bloxbean.cardano.yaci.core.model.Era;
import org.cardanofoundation.rewards.calculation.EpochCalculation;
import org.cardanofoundation.rewards.calculation.config.NetworkConfig;
import org.cardanofoundation.rewards.calculation.domain.*;
import org.cardanofoundation.rewards.calculation.enums.MirPot;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.ReadOptions;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Orchestrates reward calculation at epoch boundaries using the
 * cf-java-rewards-calculation library.
 * <p>
 * At epoch N boundary, calculates rewards for epoch N-2 data:
 * <ul>
 *   <li>Stake snapshot from epoch N-2 (delegation + UTXO balance + rewards)</li>
 *   <li>Protocol params from epoch N-2 (ρ, τ, k, a₀, d)</li>
 *   <li>Block production counts from epoch N-2</li>
 *   <li>Fees collected in epoch N-1</li>
 * </ul>
 * Rewards are credited to stake credential reward balances and become
 * spendable (withdrawable) at epoch N.
 */
public class EpochRewardCalculator {
    private static final Logger log = LoggerFactory.getLogger(EpochRewardCalculator.class);

    private RocksDB db;
    private ColumnFamilyHandle cfState;
    private ColumnFamilyHandle cfEpochSnapshot;
    private volatile boolean enabled;

    // Optional reference for querying retired pools and registered credentials
    private volatile LedgerStateProvider ledgerStateProvider;
    // Reward calculation is serialized on the boundary thread, so this invocation-local map
    // deliberately needs no synchronization and must never survive a boundary or restore.
    private Map<PoolParamsAtEpochKey, Optional<LedgerStateProvider.PoolParams>> boundaryPoolParamsMemo;

    // Optional reference to the account state store for slot/epoch helpers and event queries
    private volatile DefaultAccountStateStore accountStateStore;

    // Optional era metadata for reward-rule decisions. NetworkConfig hardfork epochs are fallback only.
    private volatile EraProvider eraProvider;
    private volatile Integer loggedEffectiveVasilHardforkEpoch;
    private volatile String rewardMode = "legacy";

    // CF NetworkConfig — built once from genesis at startup, or resolved per-magic (legacy)
    private volatile NetworkConfig cfNetworkConfig;

    // Per-invocation batch for delta-aware reward distribution.
    // Set by beginRewardBatch(), committed by commitRewardBatch().
    private WriteBatch rewardBatch;
    private List<DefaultAccountStateStore.DeltaOp> rewardDeltaOps;
    private DefaultAccountStateStore.BatchStateOverlay rewardStateOverlay;
    private volatile com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink archiveStaging =
            com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.NOOP;
    private volatile com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Boundary archiveBoundary;
    private com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.FactWriter<
            com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.RewardFact> rewardArchiveWriter;
    private static final byte[] REWARD_PROGRESS_KEY =
            "meta.reward.progress.v1".getBytes(StandardCharsets.UTF_8);
    private static final byte REWARD_FLAGS_PREFIX = (byte) 0xFE;
    static final int REWARD_FLAG_DEREGISTERED_AT_STABILITY = 1;
    static final int REWARD_FLAG_DEREGISTERED_AT_BOUNDARY = 1 << 1;
    static final int REWARD_FLAG_REGISTERED_SINCE = 1 << 2;
    static final int REWARD_FLAG_REGISTERED_UNTIL = 1 << 3;
    static final int REWARD_FLAG_REGISTERED_NOW = 1 << 4;
    private int rewardArchiveEpoch;
    private int rewardChunkSequence;
    private String rewardResumeAfterPool;
    private BigInteger rewardResumeDistributed = BigInteger.ZERO;
    private BigInteger rewardResumeUnspendable = BigInteger.ZERO;
    private int maxBatchOperations = 10_000;
    private int maxBatchBytes = 4 * 1024 * 1024;

    public EpochRewardCalculator(RocksDB db, ColumnFamilyHandle cfState,
                                 ColumnFamilyHandle cfEpochSnapshot, boolean enabled) {
        this.db = db;
        this.cfState = cfState;
        this.cfEpochSnapshot = cfEpochSnapshot;
        this.enabled = enabled;
    }

    /**
     * Refresh RocksDB handles after snapshot restore. Any open reward batch is
     * discarded because restore is only allowed while block production is paused.
     */
    public void reinitialize(RocksDB db, ColumnFamilyHandle cfState, ColumnFamilyHandle cfEpochSnapshot) {
        this.db = db;
        this.cfState = cfState;
        this.cfEpochSnapshot = cfEpochSnapshot;
        if (rewardBatch != null) {
            rewardBatch.close();
            rewardBatch = null;
        }
        rewardDeltaOps = null;
        if (rewardStateOverlay != null) {
            rewardStateOverlay.clear();
            rewardStateOverlay = null;
        }
        clearBoundaryPoolParamsMemo();
        log.info("EpochRewardCalculator reinitialized after snapshot restore");
    }

    /**
     * Set the CF NetworkConfig (built from genesis via NetworkConfigBuilder).
     * If set, this is used instead of resolveNetworkConfig(networkMagic).
     */
    public void setCfNetworkConfig(NetworkConfig config) {
        this.cfNetworkConfig = config;
    }

    public void setLedgerStateProvider(LedgerStateProvider provider) {
        this.ledgerStateProvider = provider;
    }

    public void setAccountStateStore(DefaultAccountStateStore store) {
        this.accountStateStore = store;
    }

    public void setEpochArchiveStagingSink(
            com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink sink) {
        this.archiveStaging = sink != null ? sink
                : com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.NOOP;
    }

    public void setArchiveBoundary(
            com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Boundary boundary) {
        this.archiveBoundary = boundary;
    }

    public void setEraProvider(EraProvider eraProvider) {
        this.eraProvider = eraProvider;
    }

    public void setRewardMode(String rewardMode) {
        String normalized = rewardMode == null ? "legacy"
                : rewardMode.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("legacy") && !normalized.equals("streaming")) {
            throw new IllegalArgumentException("Unsupported epoch reward mode: " + rewardMode);
        }
        this.rewardMode = normalized;
    }

    String rewardMode() {
        return rewardMode;
    }

    String executionModeForEpoch(int epoch) {
        return "streaming".equals(rewardMode) && hasCompletePoolMajorSnapshot(epoch - 4)
                ? "streaming" : "legacy";
    }

    public void setBatchLimits(int maxOperations, int maxBytes) {
        if (maxOperations <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("Reward batch limits must be positive");
        }
        this.maxBatchOperations = maxOperations;
        this.maxBatchBytes = maxBytes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Open a WriteBatch for delta-aware reward distribution.
     * Must be called before calculateAndDistribute() and paired with commitRewardBatch().
     */
    void beginRewardBatch(int archiveEpoch, String part) {
        beginRewardBatch(archiveEpoch, part, true);
    }

    void beginPoolReapBatch(int archiveEpoch, String part) {
        try {
            if (db.get(cfState, REWARD_PROGRESS_KEY) != null) {
                throw new IllegalStateException("Cannot start POOLREAP while bounded reward progress exists");
            }
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to inspect bounded reward progress", e);
        }
        beginRewardBatch(archiveEpoch, part, false);
    }

    private void beginRewardBatch(int archiveEpoch, String part, boolean loadProgress) {
        this.rewardArchiveEpoch = archiveEpoch;
        this.rewardBatch = new WriteBatch();
        this.rewardDeltaOps = new ArrayList<>();
        this.rewardStateOverlay = new DefaultAccountStateStore.BatchStateOverlay();
        this.rewardArchiveWriter = archiveStaging.enabled(
                com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset.REWARD)
                ? archiveStaging.openRewards(archiveEpoch, part) : null;
        if (loadProgress) {
            loadRewardProgress(archiveEpoch);
        } else {
            rewardChunkSequence = 0;
            rewardResumeAfterPool = null;
            rewardResumeDistributed = BigInteger.ZERO;
            rewardResumeUnspendable = BigInteger.ZERO;
        }
    }

    /**
     * Get the active reward batch for adding additional writes (e.g., AdaPot) before commit.
     * Returns null if no batch is active.
     */
    WriteBatch getRewardBatch() { return rewardBatch; }

    /**
     * Get the active reward delta ops list for adding additional delta-aware writes before commit.
     */
    List<DefaultAccountStateStore.DeltaOp> getRewardDeltaOps() { return rewardDeltaOps; }

    DefaultAccountStateStore.BatchStateOverlay getRewardStateOverlay() {
        return rewardStateOverlay;
    }

    /**
     * Commit the reward batch and persist its boundary delta journal entry.
     * All additional writes (e.g., AdaPot) must be added to the batch before calling this.
     *
     * @param boundarySlot the slot of the first block that triggered this epoch boundary
     * @param phase        the boundary delta phase constant (PHASE_REWARDS or PHASE_POOLREAP)
     */
    void commitRewardBatch(long boundarySlot, byte phase) throws RocksDBException {
        commitRewardBatch(boundarySlot, phase, rewardChunkSequence);
    }

    void commitRewardBatch(long boundarySlot, byte phase, int sequence) throws RocksDBException {
        if (rewardBatch == null) return;
        var archiveWriter = rewardArchiveWriter;
        try (var wo = new WriteOptions()) {
            accountStateStore.deleteStateWithDelta(REWARD_PROGRESS_KEY, rewardBatch,
                    rewardDeltaOps, rewardStateOverlay);
            accountStateStore.commitBoundaryDelta(boundarySlot, phase, sequence,
                    rewardBatch, rewardDeltaOps);
            if (archiveWriter != null) archiveWriter.commit();
            db.write(wo, rewardBatch);
        } finally {
            if (archiveWriter != null) archiveWriter.close();
            rewardBatch.close();
            rewardBatch = null;
            rewardDeltaOps = null;
            rewardArchiveWriter = null;
            if (rewardStateOverlay != null) {
                rewardStateOverlay.clear();
            }
            rewardStateOverlay = null;
            rewardResumeAfterPool = null;
            rewardResumeDistributed = BigInteger.ZERO;
            rewardResumeUnspendable = BigInteger.ZERO;
        }
    }

    void abortRewardBatch() {
        try {
            if (rewardArchiveWriter != null) rewardArchiveWriter.close();
        } finally {
            if (rewardBatch != null) rewardBatch.close();
            rewardBatch = null;
            rewardDeltaOps = null;
            rewardArchiveWriter = null;
            if (rewardStateOverlay != null) rewardStateOverlay.clear();
            rewardStateOverlay = null;
            rewardResumeAfterPool = null;
            rewardResumeDistributed = BigInteger.ZERO;
            rewardResumeUnspendable = BigInteger.ZERO;
        }
    }

    private void maybeFlushRewardChunk(String poolId,
                                       StreamingEpochRewardOrchestrator.RunningTotals totals) {
        if (rewardDeltaOps.size() < maxBatchOperations
                && estimatedRewardDeltaBytes() < maxBatchBytes) {
            return;
        }
        flushRewardChunk(poolId, totals);
    }

    private int estimatedRewardDeltaBytes() {
        int total = 0;
        for (DefaultAccountStateStore.DeltaOp operation : rewardDeltaOps) {
            total += operation.key().length + 16;
            if (operation.prevValue() != null) total += operation.prevValue().length;
        }
        return total;
    }

    private void flushRewardChunk(String poolId,
                                  StreamingEpochRewardOrchestrator.RunningTotals totals) {
        try {
            byte[] progress = encodeRewardProgress(rewardArchiveEpoch, rewardChunkSequence,
                    poolId, totals.distributed(), totals.unspendable());
            accountStateStore.putStateWithDelta(REWARD_PROGRESS_KEY, progress, rewardBatch,
                    rewardDeltaOps, rewardStateOverlay);
            long boundarySlot = accountStateStore.slotForEpochStart(rewardArchiveEpoch);
            accountStateStore.commitBoundaryDelta(boundarySlot,
                    DefaultAccountStateStore.PHASE_REWARDS, rewardChunkSequence,
                    rewardBatch, rewardDeltaOps);
            try (var options = new WriteOptions()) {
                db.write(options, rewardBatch);
            }
            rewardBatch.close();
            rewardBatch = new WriteBatch();
            rewardDeltaOps = new ArrayList<>();
            rewardStateOverlay.clear();
            rewardChunkSequence++;
            rewardResumeAfterPool = poolId;
            rewardResumeDistributed = totals.distributed();
            rewardResumeUnspendable = totals.unspendable();
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to commit bounded reward chunk for pool " + poolId, e);
        }
    }

    private void loadRewardProgress(int epoch) {
        rewardChunkSequence = 0;
        rewardResumeAfterPool = null;
        rewardResumeDistributed = BigInteger.ZERO;
        rewardResumeUnspendable = BigInteger.ZERO;
        if (db == null || cfState == null) return;
        try {
            byte[] value = db.get(cfState, REWARD_PROGRESS_KEY);
            if (value == null) return;
            RewardProgress progress = decodeRewardProgress(value);
            if (progress.epoch() != epoch) {
                throw new IllegalStateException("Reward progress belongs to epoch "
                        + progress.epoch() + " while starting epoch " + epoch);
            }
            rewardChunkSequence = progress.sequence() + 1;
            rewardResumeAfterPool = progress.poolId();
            rewardResumeDistributed = progress.distributed();
            rewardResumeUnspendable = progress.unspendable();
            log.info("Resuming bounded rewards for epoch {} after pool {} at chunk {}",
                    epoch, rewardResumeAfterPool, rewardChunkSequence);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to read reward progress", e);
        }
    }

    private static byte[] encodeRewardProgress(int epoch, int sequence, String poolId,
                                               BigInteger distributed,
                                               BigInteger unspendable) {
        byte[] pool = HexUtil.decodeHexString(poolId);
        byte[] distributedBytes = distributed.toByteArray();
        byte[] unspendableBytes = unspendable.toByteArray();
        if (pool.length != 28 || distributedBytes.length > 0xFFFF
                || unspendableBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("Invalid reward progress value");
        }
        return ByteBuffer.allocate(4 + 4 + 28 + 2 + distributedBytes.length
                        + 2 + unspendableBytes.length).order(ByteOrder.BIG_ENDIAN)
                .putInt(epoch).putInt(sequence).put(pool)
                .putShort((short) distributedBytes.length).put(distributedBytes)
                .putShort((short) unspendableBytes.length).put(unspendableBytes).array();
    }

    private static RewardProgress decodeRewardProgress(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN);
        int epoch = buffer.getInt();
        int sequence = buffer.getInt();
        byte[] pool = new byte[28];
        buffer.get(pool);
        byte[] distributed = new byte[buffer.getShort() & 0xFFFF];
        buffer.get(distributed);
        byte[] unspendable = new byte[buffer.getShort() & 0xFFFF];
        buffer.get(unspendable);
        if (buffer.hasRemaining()) throw new IllegalStateException("Malformed reward progress");
        return new RewardProgress(epoch, sequence, HexUtil.encodeHexString(pool),
                new BigInteger(distributed), new BigInteger(unspendable));
    }

    private record RewardProgress(int epoch, int sequence, String poolId,
                                  BigInteger distributed, BigInteger unspendable) {
    }

    /**
     * Calculate and distribute rewards for epoch N.
     *
     * @param epoch          the current epoch N (rewards calculated for N-2)
     * @param prevTreasury   treasury balance at end of epoch N-1
     * @param prevReserves   reserves balance at end of epoch N-1
     * @param paramProvider  protocol parameters
     * @param networkMagic   network magic number (764824073=mainnet, 1=preprod, 2=preview)
     * @return the calculation result, or empty if disabled or insufficient data
     */
    public Optional<EpochCalculationResult> calculateAndDistribute(
            int epoch,
            BigInteger prevTreasury,
            BigInteger prevReserves,
            EpochParamProvider paramProvider,
            long networkMagic) {

        if (!enabled) return Optional.empty();

        beginBoundaryPoolParamsMemo();
        try {
            return calculateAndDistributeInternal(
                    epoch, prevTreasury, prevReserves, paramProvider, networkMagic);
        } finally {
            clearBoundaryPoolParamsMemo();
        }
    }

    private Optional<EpochCalculationResult> calculateAndDistributeInternal(
            int epoch,
            BigInteger prevTreasury,
            BigInteger prevReserves,
            EpochParamProvider paramProvider,
            long networkMagic) {

        int stakeEpoch = epoch - 2; // snapshot epoch (N-2)
        int feeEpoch = epoch - 1;   // fee collection epoch (N-1), used for deregistration slot ranges
        // Snapshot key E captures delegation state at the END of epoch E (matching yaci-store epoch_stake convention).
        // Cardano ledger uses the mark snapshot from END of epoch N-4 for reward epoch N.
        // Verified against Haskell node (DBSync): store uses epoch_stake WHERE epoch = N-4.
        // With stakeEpoch = N-2, snapshotKey = stakeEpoch - 2 = N-4.
        int snapshotKey = stakeEpoch - 2;

        log.info("Starting reward calculation for epoch {} (stakeEpoch={}, snapshotKey={})", epoch, stakeEpoch, snapshotKey);
        long start = System.currentTimeMillis();

        // 1. Build protocol parameters for cf-rewards-calculation (from stake epoch N-2)
        // Special case for first Shelley epoch: stakeEpoch may be a Byron epoch with no Shelley params.
        // Matching Yaci Store's logic: if (epoch == nonByronEpoch + 1) use params from nonByronEpoch.
        var networkConfig = resolveEffectiveRewardNetworkConfig(getNetworkConfig(networkMagic));
        if (epoch <= networkConfig.getShelleyStartEpoch()) return Optional.empty();
        int shelleyStartEpoch = networkConfig.getShelleyStartEpoch();
        int paramEpoch = (stakeEpoch < shelleyStartEpoch) ? shelleyStartEpoch : stakeEpoch;
        var protocolParams = buildProtocolParameters(paramProvider, paramEpoch);
        if (paramEpoch != stakeEpoch) {
            log.info("Using protocol params from epoch {} (shelley start) instead of {} (Byron)",
                    paramEpoch, stakeEpoch);
        }

        // 2. Gather block counts, fees, and snapshot for stake epoch N-2
        var blockCounts = getPoolBlockCounts(stakeEpoch);
        long totalBlocks = blockCounts.values().stream().mapToLong(Long::longValue).sum();
        // Fees collected during epoch N-2 (stored in epoch fees for stakeEpoch)
        var fees = getEpochFees(stakeEpoch);

        boolean streamingSnapshotAvailable = hasCompletePoolMajorSnapshot(snapshotKey);
        validateRewardResumePath(epoch, streamingSnapshotAvailable);
        if ("streaming".equals(rewardMode) && streamingSnapshotAvailable) {
            return Optional.of(calculateAndDistributeStreaming(
                    epoch, stakeEpoch, feeEpoch, snapshotKey, prevTreasury, prevReserves,
                    paramProvider, networkMagic, networkConfig, protocolParams,
                    paramEpoch, blockCounts, totalBlocks, fees, start));
        }
        if ("streaming".equals(rewardMode)) {
            log.info("Pool-major snapshot {} is unavailable; using legacy reward path", snapshotKey);
        }

        // Stake snapshot: key=snapshotKey captures delegation/stake state at end of that epoch
        var stakeSnapshot = getStakeSnapshot(snapshotKey);
        if (stakeSnapshot.isEmpty()) {
            log.info("No stake snapshot at key {} for epoch {} — proceeding with empty snapshot " +
                    "(early epochs or post-rollback)", snapshotKey, epoch);
        }
        var totalActiveStake = stakeSnapshot.values().stream()
                .map(AccountStateCborCodec.EpochDelegSnapshot::amount)
                .reduce(BigInteger.ZERO, BigInteger::add);

        // 3. Build pool states from snapshot (before epochInfo, needed for nonOBFTBlockCount)
        var poolStates = buildPoolStates(stakeSnapshot, blockCounts, stakeEpoch);
        // cf-rewards param 9 is "poolsThatProducedBlocksInEpoch" — must only contain
        // pools that actually produced blocks, not ALL pools in the snapshot.
        var poolIds = poolStates.stream()
                .filter(ps -> ps.getBlockCount() > 0)
                .map(PoolState::getPoolId).toList();

        var rewardRules = resolveRewardRuleContext(epoch, stakeEpoch, protocolParams,
                blockCounts, totalBlocks, networkConfig);
        protocolParams = rewardRules.protocolParameters();
        logPostVasilRewardRules(epoch, totalBlocks, rewardRules, paramEpoch);

        var epochInfo = Epoch.builder()
                .number(stakeEpoch)
                .fees(fees)
                .blockCount((int) rewardRules.blockCount())
                .activeStake(totalActiveStake)
                .nonOBFTBlockCount((int) rewardRules.nonOBFTBlockCount())
                .build();

        // 4. Retired pools — pass real set to cf-rewards library.
        // The library adds unclaimed deposits (unregistered reward address) to treasury.
        // Individual account credits for registered reward addresses are handled separately
        // by processPoolDepositRefunds() in EpochBoundaryProcessor.
        Set<RetiredPool> retiredPools = buildRetiredPools(epoch);

        // 5. Deregistered and registered account sets — event-based tracking
        var accountSets = buildAccountSets(epoch, stakeEpoch, feeEpoch, paramProvider,
                networkMagic, stakeSnapshot, poolStates, retiredPools);
        var deregistered = accountSets.deregistered;
        var lateDeregistered = accountSets.lateDeregistered;
        var deregisteredOnBoundary = accountSets.deregisteredOnBoundary;
        var registeredSinceLast = accountSets.registeredSinceLast;
        var registeredUntilNow = accountSets.registeredUntilNow;

        log.info("Epoch {} reward inputs: snapshot={} entries, pools={}, blocks={}, fees={}, " +
                        "deregistered={}, lateDeregistered={}, registeredSinceLast={}, registeredUntilNow={}, " +
                        "retiredPools={}, d={}, nOpt={}, activeStake={}, rho={}, tau={}, a0={}, " +
                        "prevTreasury={}, prevReserves={}",
                epoch, stakeSnapshot.size(), poolStates.size(), rewardRules.blockCount(), fees,
                deregistered.size(), lateDeregistered.size(), registeredSinceLast.size(),
                registeredUntilNow.size(), retiredPools.size(),
                protocolParams.getDecentralisation(), protocolParams.getOptimalPoolCount(),
                totalActiveStake, protocolParams.getMonetaryExpandRate(),
                protocolParams.getTreasuryGrowRate(), protocolParams.getPoolOwnerInfluence(),
                prevTreasury, prevReserves);

        // 6. Shared pool reward addresses (mainnet pre-Allegra only)
        var sharedPoolRewardAddresses = SharedPoolRewardAddresses
                .getSharedAddressesWithoutReward(epoch, networkMagic);

        // 7. MIR certificates — aggregate per-epoch per-pot totals from stored MIR data
        List<MirCertificate> mirCertificates = buildMirCertificates(feeEpoch);
        if (!mirCertificates.isEmpty()) {
            for (var mir : mirCertificates) {
                log.info("Epoch {} MIR certificate: pot={}, totalRewards={}", epoch, mir.getPot(), mir.getTotalRewards());
            }
        }

        // 8. Network config (already resolved above for param epoch selection)

        // 9. Calculate
        EpochCalculationResult result;
        try {
            result = EpochCalculation.calculateEpochRewardPots(
                    epoch,
                    prevReserves,
                    prevTreasury,
                    protocolParams,
                    epochInfo,
                    retiredPools,
                    deregistered,
                    mirCertificates,
                    poolIds,
                    poolStates,
                    lateDeregistered,
                    registeredSinceLast,
                    registeredUntilNow,
                    sharedPoolRewardAddresses,
                    deregisteredOnBoundary,
                    networkConfig);
        } catch (Exception e) {
            log.error("Reward calculation failed for epoch {}: {}", epoch, e.getMessage(), e);
            throw new RuntimeException("Reward calculation failed for epoch " + epoch, e);
        }

        long elapsed = System.currentTimeMillis() - start;

        var poolResults = result.getPoolRewardCalculationResults();
        if (poolResults != null) {
            int leaderCount = 0, memberCount = 0, deniedCount = 0;
            for (var pr : poolResults) {
                if (pr.getOperatorReward() != null && pr.getOperatorReward().signum() > 0) {
                    leaderCount++;
                } else if (pr.getPoolReward() != null && pr.getPoolReward().signum() > 0) {
                    deniedCount++;
                    log.info("Epoch {} pool {} operator DENIED: poolReward={}, rewardAddr={}, " +
                                    "inRegisteredPast={}, inDeregistered={}, inLateDeregistered={}",
                            epoch, pr.getPoolId(), pr.getPoolReward(), pr.getRewardAddress(),
                            registeredSinceLast.contains(pr.getRewardAddress()),
                            deregistered.contains(pr.getRewardAddress()),
                            lateDeregistered.contains(pr.getRewardAddress()));
                }
                if (pr.getMemberRewards() != null) {
                    memberCount += (int) pr.getMemberRewards().stream()
                            .filter(r -> r.getAmount() != null && r.getAmount().signum() > 0).count();
                }
            }
            log.info("Epoch {} reward summary: {} leader, {} member, {} denied operators (pool had reward but operator got 0)",
                    epoch, leaderCount, memberCount, deniedCount);
        }

        log.info("Reward calculation for epoch {} complete in {}ms: distributed={}, undistributed={}, " +
                        "rewardsPot={}, poolRewardsPot={}, treasury={}, reserves={}",
                epoch, elapsed, result.getTotalDistributedRewards(),
                result.getTotalUndistributedRewards(),
                result.getTotalRewardsPot(), result.getTotalPoolRewardsPot(),
                result.getTreasury(), result.getReserves());

        // 10. Distribute rewards — credit to stake credential balances
        distributeRewards(epoch, result);

        return Optional.of(result);
    }

    private boolean hasCompletePoolMajorSnapshot(int snapshotEpoch) {
        return accountStateStore != null
                && accountStateStore.isPoolMajorSnapshotComplete(snapshotEpoch);
    }

    void validateRewardResumePath(int epoch, boolean streamingSnapshotAvailable) {
        if (rewardResumeAfterPool != null
                && (!"streaming".equals(rewardMode) || !streamingSnapshotAvailable)) {
            throw new IllegalStateException("A bounded streaming reward calculation is in progress for epoch "
                    + epoch + "; reward-mode must remain streaming and its pool-major snapshot must remain available");
        }
    }

    private EpochCalculationResult calculateAndDistributeStreaming(
            int epoch, int stakeEpoch, int feeEpoch, int snapshotKey,
            BigInteger previousTreasury, BigInteger previousReserves,
            EpochParamProvider paramProvider, long networkMagic,
            NetworkConfig networkConfig, ProtocolParameters protocolParameters,
            int paramEpoch, Map<String, Long> blockCounts, long totalBlocks, BigInteger fees,
            long startMillis) {
        BigInteger totalActiveStake = totalPoolMajorStake(snapshotKey);
        var rewardRules = resolveRewardRuleContext(epoch, stakeEpoch, protocolParameters,
                blockCounts, totalBlocks, networkConfig);
        protocolParameters = rewardRules.protocolParameters();
        logPostVasilRewardRules(epoch, totalBlocks, rewardRules, paramEpoch);
        var epochInfo = Epoch.builder()
                .number(stakeEpoch)
                .fees(fees)
                .blockCount((int) rewardRules.blockCount())
                .activeStake(totalActiveStake)
                .nonOBFTBlockCount((int) rewardRules.nonOBFTBlockCount())
                .build();
        Set<RetiredPool> retiredPools = buildRetiredPools(epoch);
        StreamingAccountContext accounts = buildStreamingAccountContext(
                epoch, stakeEpoch, feeEpoch, paramProvider, networkMagic,
                snapshotKey, retiredPools);
        HashSet<String> sharedPoolRewardAddresses = new HashSet<>(
                SharedPoolRewardAddresses.getSharedAddressesWithoutReward(epoch, networkMagic));
        List<MirCertificate> mirCertificates = buildMirCertificates(feeEpoch);

        try (PreparedRewardFlags rewardFlags = prepareRewardCredentialFlags(snapshotKey, accounts);
             PoolMajorCursor pools = new PoolMajorCursor(snapshotKey, stakeEpoch, blockCounts,
                     accounts, rewardFlags)) {
            EpochCalculationResult result = StreamingEpochRewardOrchestrator.calculate(
                    epoch, previousReserves, previousTreasury, protocolParameters, epochInfo,
                    retiredPools, accounts.deregistered(), mirCertificates, pools,
                    accounts.lateDeregistered(), accounts.registeredSinceLast(),
                    accounts.registeredUntilNow(), sharedPoolRewardAddresses,
                    accounts.deregisteredOnBoundary(), networkConfig,
                    rewardResumeAfterPool, rewardResumeDistributed,
                    rewardResumeUnspendable,
                    (poolInput, poolResult, totals, replayed) -> {
                        if (replayed) {
                            stagePoolRewardFacts(epoch, poolResult);
                        } else {
                            distributePrefetchedPoolReward(
                                    epoch, poolInput, poolResult);
                            maybeFlushRewardChunk(poolResult.getPoolId(), totals);
                        }
                    });
            log.info("Streaming reward calculation for epoch {} complete in {}ms: distributed={}, "
                            + "undistributed={}, rewardsPot={}, poolRewardsPot={}, treasury={}, reserves={}",
                    epoch, System.currentTimeMillis() - startMillis,
                    result.getTotalDistributedRewards(), result.getTotalUndistributedRewards(),
                    result.getTotalRewardsPot(), result.getTotalPoolRewardsPot(),
                    result.getTreasury(), result.getReserves());
            return result;
        }
    }

    private BigInteger totalPoolMajorStake(int epoch) {
        BigInteger total = BigInteger.ZERO;
        byte[] prefix = poolMajorPrefix(epoch);
        try (ReadOptions options = new ReadOptions().setFillCache(false);
             RocksIterator iterator = db.newIterator(cfEpochSnapshot, options)) {
            for (iterator.seek(prefix); iterator.isValid() && startsWith(iterator.key(), prefix);
                 iterator.next()) {
                total = total.add(AccountStateCborCodec.decodePoolMajorStake(iterator.value()));
            }
        }
        return total;
    }

    private void logPostVasilRewardRules(int epoch, long totalBlocks,
                                         RewardRuleContext rewardRules, int paramEpoch) {
        if (rewardRules.postVasilRewardRules()) {
            log.info("Epoch {} post-Vasil reward rules: rawBlocks={}, poolBlocks={}, paramEpoch={}",
                    epoch, totalBlocks, rewardRules.blockCount(), paramEpoch);
        }
    }

    private StreamingAccountContext buildStreamingAccountContext(
            int epoch, int stakeEpoch, int feeEpoch, EpochParamProvider paramProvider,
            long networkMagic, int snapshotKey, Set<RetiredPool> retiredPools) {
        long feeEpochStart = accountStateStore.slotForEpochStart(feeEpoch);
        long feeEpochEnd = accountStateStore.slotForEpochStart(feeEpoch + 1);
        boolean postBabbage = isPostBabbage(stakeEpoch, getNetworkConfig(networkMagic));
        long stabilityCutoff = postBabbage ? feeEpochEnd
                : feeEpochStart + paramProvider.getRandomnessStabilisationWindow();
        long registeredSinceCutoff = feeEpochStart + (postBabbage
                ? paramProvider.getEpochLength()
                : paramProvider.getRandomnessStabilisationWindow());
        long registeredUntilCutoff = accountStateStore.slotForEpochStart(epoch);

        Set<String> rewardAddresses = poolRewardAddresses(snapshotKey, stakeEpoch);
        for (RetiredPool retiredPool : retiredPools) {
            if (retiredPool.getRewardAddress() != null) {
                rewardAddresses.add(retiredPool.getRewardAddress());
            }
        }
        HashSet<String> deregistered = new HashSet<>();
        HashSet<String> deregisteredOnBoundary = new HashSet<>();
        HashSet<String> lateDeregistered = new HashSet<>();
        HashSet<String> registeredSinceLast = new HashSet<>();
        HashSet<String> registeredUntilNow = new HashSet<>();
        for (String address : rewardAddresses) {
            var summary = accountStateStore.getCredentialEventSummary(address,
                    stabilityCutoff, feeEpochEnd, registeredSinceCutoff,
                    registeredUntilCutoff);
            if (summary.deregisteredAtStability()) deregistered.add(address);
            if (summary.deregisteredAtBoundary()) deregisteredOnBoundary.add(address);
            if (!postBabbage && summary.deregisteredAtBoundary()
                    && !summary.deregisteredAtStability()) lateDeregistered.add(address);
            if (summary.registeredSince()) registeredSinceLast.add(address);
            if (summary.registeredUntil()) registeredUntilNow.add(address);
        }
        if (postBabbage) {
            deregistered.addAll(deregisteredOnBoundary);
            lateDeregistered.clear();
        }
        return new StreamingAccountContext(
                deregistered, lateDeregistered, deregisteredOnBoundary,
                registeredSinceLast, registeredUntilNow, postBabbage,
                stabilityCutoff, feeEpochEnd, registeredSinceCutoff,
                registeredUntilCutoff);
    }

    private Set<String> poolRewardAddresses(int snapshotEpoch, int poolParamsEpoch) {
        HashSet<String> result = new HashSet<>();
        byte[] prefix = poolMajorPrefix(snapshotEpoch);
        byte[] previousPool = null;
        try (ReadOptions options = new ReadOptions().setFillCache(false);
             RocksIterator iterator = db.newIterator(cfEpochSnapshot, options)) {
            for (iterator.seek(prefix); iterator.isValid() && startsWith(iterator.key(), prefix);
                 iterator.next()) {
                byte[] key = iterator.key();
                byte[] pool = Arrays.copyOfRange(key, 5, 33);
                if (previousPool != null && Arrays.equals(previousPool, pool)) continue;
                previousPool = pool;
                String poolHash = HexUtil.encodeHexString(pool);
                String rewardAddress = ledgerStateProvider != null
                        ? getPoolParamsAtEpoch(poolHash, poolParamsEpoch)
                        .map(params -> extractCredKeyFromRewardAddress(
                                params.rewardAccount(), poolHash))
                        .orElse(poolHash)
                        : poolHash;
                result.add(rewardAddress);
            }
        }
        return result;
    }

    private static byte[] poolMajorPrefix(int epoch) {
        return ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
                .putInt(epoch).put((byte) 0xFF).array();
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) return false;
        }
        return true;
    }

    /**
     * Build cf-rewards ProtocolParameters from our EpochParamProvider.
     */
    ProtocolParameters buildProtocolParameters(EpochParamProvider pp, int epoch) {
        BigDecimal decentralization = pp.getDecentralization(epoch);
        if (decentralization == null) {
            decentralization = BigDecimal.ZERO;
        }

        return ProtocolParameters.builder()
                .decentralisation(decentralization)
                .treasuryGrowRate(pp.getTau(epoch))
                .monetaryExpandRate(pp.getRho(epoch))
                .optimalPoolCount(pp.getNOpt(epoch))
                .poolOwnerInfluence(pp.getA0(epoch))
                .build();
    }

    record RewardRuleContext(ProtocolParameters protocolParameters,
                             long blockCount,
                             long nonOBFTBlockCount,
                             boolean postVasilRewardRules) {}

    NetworkConfig resolveEffectiveRewardNetworkConfig(NetworkConfig networkConfig) {
        // CF rewards uses vasilHardforkEpoch internally for Babbage-era unspendable rewards,
        // so prefer the persisted era boundary when it is known.
        Integer firstBabbageEpoch = eraProvider != null
                ? eraProvider.resolveKnownFirstEpochOrNull(Era.Babbage.getValue())
                : null;
        if (firstBabbageEpoch == null && isCustomNetwork(networkConfig.getNetworkMagic()) && eraProvider != null) {
            Integer inferredBabbageEpoch = eraProvider.resolveFirstEpochOrNull(Era.Babbage.getValue());
            if (inferredBabbageEpoch != null && inferredBabbageEpoch == 0) {
                firstBabbageEpoch = 0;
            }
        }
        if (firstBabbageEpoch == null
                || firstBabbageEpoch == networkConfig.getVasilHardforkEpoch()) {
            return networkConfig;
        }

        if (loggedEffectiveVasilHardforkEpoch == null
                || !loggedEffectiveVasilHardforkEpoch.equals(firstBabbageEpoch)) {
            log.info("Using persisted Babbage start epoch {} as reward Vasil hardfork epoch (configured={})",
                    firstBabbageEpoch, networkConfig.getVasilHardforkEpoch());
            loggedEffectiveVasilHardforkEpoch = firstBabbageEpoch;
        }
        return copyWithVasilHardforkEpoch(networkConfig, firstBabbageEpoch);
    }

    private NetworkConfig copyWithVasilHardforkEpoch(NetworkConfig original, int vasilHardforkEpoch) {
        return NetworkConfig.builder()
                .networkMagic(original.getNetworkMagic())
                .totalLovelace(original.getTotalLovelace())
                .poolDepositInLovelace(original.getPoolDepositInLovelace())
                .expectedSlotsPerEpoch(original.getExpectedSlotsPerEpoch())
                .shelleyInitialReserves(original.getShelleyInitialReserves())
                .shelleyInitialTreasury(original.getShelleyInitialTreasury())
                .shelleyInitialUtxo(original.getShelleyInitialUtxo())
                .genesisConfigSecurityParameter(original.getGenesisConfigSecurityParameter())
                .shelleyStartEpoch(original.getShelleyStartEpoch())
                .allegraHardforkEpoch(original.getAllegraHardforkEpoch())
                .vasilHardforkEpoch(vasilHardforkEpoch)
                .bootstrapAddressAmount(original.getBootstrapAddressAmount())
                .activeSlotCoefficient(original.getActiveSlotCoefficient())
                .randomnessStabilisationWindow(original.getRandomnessStabilisationWindow())
                .shelleyStartDecentralisation(original.getShelleyStartDecentralisation())
                .shelleyStartTreasuryGrowRate(original.getShelleyStartTreasuryGrowRate())
                .shelleyStartMonetaryExpandRate(original.getShelleyStartMonetaryExpandRate())
                .shelleyStartOptimalPoolCount(original.getShelleyStartOptimalPoolCount())
                .shelleyStartPoolOwnerInfluence(original.getShelleyStartPoolOwnerInfluence())
                .build();
    }

    private boolean isCustomNetwork(int networkMagic) {
        return networkMagic != 764824073
                && networkMagic != 1
                && networkMagic != 2;
    }

    RewardRuleContext resolveRewardRuleContext(int rewardEpoch,
                                               int stakeEpoch,
                                               ProtocolParameters protocolParameters,
                                               Map<String, Long> blockCounts,
                                               long totalBlocks,
                                               NetworkConfig networkConfig) {
        if (!usesPostVasilRewardRules(rewardEpoch, networkConfig)) {
            return new RewardRuleContext(
                    protocolParameters,
                    totalBlocks,
                    computeNonOBFTBlockCount(protocolParameters, blockCounts, totalBlocks, stakeEpoch),
                    false);
        }

        long poolBlocks = countBlocksProducedByRegisteredPools(blockCounts, totalBlocks, stakeEpoch);
        return new RewardRuleContext(
                withDecentralization(protocolParameters, BigDecimal.ZERO),
                poolBlocks,
                poolBlocks,
                true);
    }

    boolean usesPostVasilRewardRules(int rewardEpoch, NetworkConfig networkConfig) {
        Integer firstBabbageEpoch = eraProvider != null
                ? eraProvider.resolveKnownFirstEpochOrNull(Era.Babbage.getValue())
                : null;
        if (firstBabbageEpoch != null) {
            // The transition epoch itself still uses the old reward update. The next reward
            // calculation observes Babbage/Vasil reward rules.
            return rewardEpoch > firstBabbageEpoch;
        }

        return rewardEpoch > networkConfig.getVasilHardforkEpoch();
    }

    private ProtocolParameters withDecentralization(ProtocolParameters original, BigDecimal decentralization) {
        return ProtocolParameters.builder()
                .decentralisation(decentralization)
                .treasuryGrowRate(original.getTreasuryGrowRate())
                .monetaryExpandRate(original.getMonetaryExpandRate())
                .optimalPoolCount(original.getOptimalPoolCount())
                .poolOwnerInfluence(original.getPoolOwnerInfluence())
                .build();
    }

    private long countBlocksProducedByRegisteredPools(Map<String, Long> blockCounts,
                                                      long totalBlocks,
                                                      int stakeEpoch) {
        if (ledgerStateProvider == null) {
            log.warn("LedgerStateProvider unavailable for post-Vasil pool block count; using total block count");
            return totalBlocks;
        }

        long count = 0;
        for (var entry : blockCounts.entrySet()) {
            if (getPoolParamsAtEpoch(entry.getKey(), stakeEpoch).isPresent()) {
                count += entry.getValue();
            }
        }
        return count;
    }

    /**
     * Compute non-OBFT block count matching Yaci Store's EpochInfoService logic:
     *   d == null or 0: nonOBFT = totalBlocks (no OBFT blocks)
     *   d == 1: nonOBFT = 0 (all blocks are OBFT)
     *   0 < d < 1: count blocks whose issuerVkey matches a registered pool
     */
    private long computeNonOBFTBlockCount(ProtocolParameters protocolParams,
                                           Map<String, Long> blockCounts,
                                           long totalBlocks,
                                           int stakeEpoch) {
        BigDecimal d = protocolParams.getDecentralisation();
        if (d == null || d.compareTo(BigDecimal.ZERO) == 0) {
            return totalBlocks;
        } else if (d.compareTo(BigDecimal.ONE) == 0) {
            return 0;
        } else {
            // Count blocks by registered pools only (excludes genesis delegate/OBFT blocks).
            // blockCounts keys are issuerVkey (= pool cold vkey hash = pool ID).
            long count = 0;
            for (var entry : blockCounts.entrySet()) {
                if (ledgerStateProvider != null
                        && getPoolParamsAtEpoch(entry.getKey(), stakeEpoch).isPresent()) {
                    count += entry.getValue();
                }
            }
            return count;
        }
    }

    /**
     * Build cf-rewards PoolState list from stake snapshot and block counts.
     */
    private List<PoolState> buildPoolStates(
            Map<String, AccountStateCborCodec.EpochDelegSnapshot> snapshot,
            Map<String, Long> blockCounts,
            int epoch) {

        // Group delegators by pool
        Map<String, HashSet<Delegator>> poolDelegators = new HashMap<>();
        Map<String, BigInteger> poolActiveStake = new HashMap<>();

        for (var entry : snapshot.entrySet()) {
            var deleg = entry.getValue();
            String poolHash = deleg.poolHash();
            BigInteger amount = deleg.amount();

            String stakeAddr = entry.getKey(); // "credType:credHash"

            poolDelegators.computeIfAbsent(poolHash, _ -> new HashSet<>())
                    .add(Delegator.builder()
                            .stakeAddress(stakeAddr)
                            .activeStake(amount)
                            .build());

            poolActiveStake.merge(poolHash, amount, BigInteger::add);
        }

        // Build PoolState for each pool
        List<PoolState> states = new ArrayList<>();
        for (var poolEntry : poolDelegators.entrySet()) {
            String poolHash = poolEntry.getKey();
            states.add(buildPoolState(poolHash, poolEntry.getValue(),
                    poolActiveStake.getOrDefault(poolHash, BigInteger.ZERO),
                    blockCounts, epoch));
        }
        return states;
    }

    private PoolState buildPoolState(String poolHash, HashSet<Delegator> delegators,
                                     BigInteger activeStake, Map<String, Long> blockCounts,
                                     int epoch) {
        int blocks = blockCounts.getOrDefault(poolHash, 0L).intValue();
        String rewardAddress = poolHash;
        double margin = 0.0;
        BigInteger fixedCost = BigInteger.ZERO;
        BigInteger pledge = BigInteger.ZERO;
        HashSet<String> owners = new HashSet<>();

        if (ledgerStateProvider != null) {
            var poolParamsOpt = getPoolParamsAtEpoch(poolHash, epoch);
            if (poolParamsOpt.isPresent()) {
                var params = poolParamsOpt.get();
                rewardAddress = extractCredKeyFromRewardAddress(params.rewardAccount(), poolHash);
                margin = params.margin();
                fixedCost = params.cost();
                pledge = params.pledge();
                if (params.owners() != null) {
                    for (String ownerHash : params.owners()) owners.add("0:" + ownerHash);
                }
            } else if (blocks > 0) {
                log.warn("Pool {} produced {} blocks but has no registration params", poolHash, blocks);
            }
        }

        BigInteger ownerActiveStake = BigInteger.ZERO;
        for (Delegator delegator : delegators) {
            if (owners.contains(delegator.getStakeAddress())) {
                ownerActiveStake = ownerActiveStake.add(delegator.getActiveStake());
            }
        }
        return PoolState.builder()
                .poolId(poolHash)
                .blockCount(blocks)
                .activeStake(activeStake)
                .delegators(delegators)
                .epoch(epoch)
                .rewardAddress(rewardAddress)
                .owners(owners)
                .ownerActiveStake(ownerActiveStake)
                .poolFees(BigInteger.ZERO)
                .margin(BigDecimal.valueOf(margin))
                .fixedCost(fixedCost)
                .pledge(pledge)
                .build();
    }

    private final class PoolMajorCursor implements Iterator<StreamingEpochRewardOrchestrator.PoolRewardInput>,
            AutoCloseable {
        private final int poolParamsEpoch;
        private final Map<String, Long> blockCounts;
        private final StreamingAccountContext accounts;
        private final byte[] prefix;
        private final ReadOptions options = new ReadOptions().setFillCache(false);
        private final RocksIterator iterator;
        private final RocksIterator flagsIterator;
        private final byte[] flagsPrefix;
        private StreamingEpochRewardOrchestrator.PoolRewardInput next;

        private PoolMajorCursor(int snapshotEpoch, int poolParamsEpoch,
                                Map<String, Long> blockCounts,
                                StreamingAccountContext accounts,
                                PreparedRewardFlags rewardFlags) {
            this.poolParamsEpoch = poolParamsEpoch;
            this.blockCounts = blockCounts;
            this.accounts = accounts;
            this.prefix = poolMajorPrefix(snapshotEpoch);
            this.flagsPrefix = rewardFlags.prefix();
            this.iterator = db.newIterator(cfEpochSnapshot, options);
            this.flagsIterator = db.newIterator(cfEpochSnapshot, options);
            iterator.seek(prefix);
            flagsIterator.seek(flagsPrefix);
            try {
                advance();
            } catch (RuntimeException failure) {
                flagsIterator.close();
                iterator.close();
                options.close();
                throw failure;
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public StreamingEpochRewardOrchestrator.PoolRewardInput next() {
            if (next == null) throw new NoSuchElementException();
            var current = next;
            advance();
            return current;
        }

        private void advance() {
            if (!iterator.isValid() || !startsWith(iterator.key(), prefix)) {
                if (flagsIterator.isValid() && startsWith(flagsIterator.key(), flagsPrefix)) {
                    throw new IllegalStateException(
                            "Reward credential flags contain rows without pool-major stake rows");
                }
                next = null;
                return;
            }
            byte[] poolBytes = Arrays.copyOfRange(iterator.key(), 5, 33);
            String poolHash = HexUtil.encodeHexString(poolBytes);
            HashSet<Delegator> delegators = new HashSet<>();
            HashSet<String> deregistered = new HashSet<>();
            HashSet<String> lateDeregistered = new HashSet<>();
            Map<String, BoundaryCredentialKey> credentialKeys = new HashMap<>();
            BigInteger activeStake = BigInteger.ZERO;

            while (iterator.isValid() && startsWith(iterator.key(), prefix)
                    && Arrays.equals(iterator.key(), 5, 33,
                    poolBytes, 0, BoundaryCredentialKey.HASH_LENGTH)) {
                byte[] key = iterator.key();
                BoundaryCredentialKey credentialKey =
                        BoundaryCredentialKey.fromKey(key, 33);
                String credential = credentialKey.address();
                credentialKeys.put(credential, credentialKey);
                BigInteger amount = AccountStateCborCodec.decodePoolMajorStake(iterator.value());
                delegators.add(Delegator.builder()
                        .stakeAddress(credential).activeStake(amount).build());
                activeStake = activeStake.add(amount);

                int flags = rewardFlagsFor(key);
                boolean atStability = (flags & REWARD_FLAG_DEREGISTERED_AT_STABILITY) != 0;
                boolean atBoundary = (flags & REWARD_FLAG_DEREGISTERED_AT_BOUNDARY) != 0;
                if (atStability || (accounts.postBabbage() && atBoundary)) {
                    deregistered.add(credential);
                }
                if (!accounts.postBabbage() && atBoundary && !atStability) {
                    lateDeregistered.add(credential);
                }
                iterator.next();
            }

            PoolState pool = buildPoolState(poolHash, delegators, activeStake,
                    blockCounts, poolParamsEpoch);
            credentialKeys.computeIfAbsent(pool.getRewardAddress(),
                    BoundaryCredentialKey::fromAddress);
            if (accounts.deregistered().contains(pool.getRewardAddress())) {
                deregistered.add(pool.getRewardAddress());
            }
            if (accounts.lateDeregistered().contains(pool.getRewardAddress())) {
                lateDeregistered.add(pool.getRewardAddress());
            }
            next = new StreamingEpochRewardOrchestrator.PoolRewardInput(
                    pool, deregistered, lateDeregistered, credentialKeys);
        }

        private int rewardFlagsFor(byte[] poolMajorKey) {
            if (!flagsIterator.isValid() || !startsWith(flagsIterator.key(), flagsPrefix)) {
                throw new IllegalStateException("Missing reward credential flags for pool-major snapshot row");
            }
            byte[] flagsKey = flagsIterator.key();
            if (poolMajorKey.length != 62 || flagsKey.length != 62
                    || !Arrays.equals(poolMajorKey, 0, 4, flagsKey, 0, 4)
                    || !Arrays.equals(poolMajorKey, 5, 62, flagsKey, 5, 62)) {
                throw new IllegalStateException("Reward credential flags are not aligned with pool-major snapshot");
            }
            byte[] value = flagsIterator.value();
            if (value.length != 1) {
                throw new IllegalStateException("Malformed reward credential flags value");
            }
            flagsIterator.next();
            return value[0] & 0xFF;
        }

        @Override
        public void close() {
            flagsIterator.close();
            iterator.close();
            options.close();
        }
    }

    private PreparedRewardFlags prepareRewardCredentialFlags(
            int snapshotEpoch, StreamingAccountContext accounts) {
        return prepareRewardCredentialFlags(snapshotEpoch,
                accounts.stabilityCutoff(), accounts.boundaryCutoff(),
                accounts.registeredSinceCutoff(), accounts.registeredUntilCutoff());
    }

    PreparedRewardFlags prepareRewardCredentialFlags(
            int snapshotEpoch, long stabilityCutoff, long boundaryCutoff,
            long registeredSinceCutoff, long registeredUntilCutoff) {
        byte[] flagsPrefix = rewardFlagsPrefix(snapshotEpoch);
        deleteRewardCredentialFlags(flagsPrefix);
        long started = System.currentTimeMillis();
        long maximumCutoff = Math.max(Math.max(stabilityCutoff, boundaryCutoff),
                Math.max(registeredSinceCutoff, registeredUntilCutoff));
        int rows = 0;
        int events = 0;
        int chunks = 0;
        Snapshot snapshot = db.getSnapshot();
        try (ReadOptions snapshotReadOptions = new ReadOptions()
                     .setFillCache(false).setSnapshot(snapshot);
             ReadOptions accountReadOptions = new ReadOptions()
                     .setFillCache(true).setSnapshot(snapshot);
             RocksIterator snapshotRows = db.newIterator(cfEpochSnapshot, snapshotReadOptions);
             RocksIterator credentialEvents = db.newIterator(cfState, accountReadOptions);
             RocksIterator accounts = db.newIterator(cfState, accountReadOptions);
             WriteOptions writeOptions = new WriteOptions();
             WriteBatch batch = new WriteBatch()) {
            byte[] snapshotPrefix = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                    .putInt(snapshotEpoch).array();
            snapshotRows.seek(snapshotPrefix);
            credentialEvents.seek(new byte[]{DefaultAccountStateStore.PREFIX_STAKE_EVENT_BY_CREDENTIAL});
            accounts.seek(new byte[]{DefaultAccountStateStore.PREFIX_ACCT});
            int batchBytes = 0;
            while (isCredentialMajorSnapshotRow(snapshotRows, snapshotPrefix)) {
                byte[] snapshotKey = snapshotRows.key();
                byte[] credentialSuffix = Arrays.copyOfRange(snapshotKey, 4, 33);
                EventFlags eventFlags = consumeCredentialEvents(
                        credentialEvents, credentialSuffix, stabilityCutoff, boundaryCutoff,
                        registeredSinceCutoff, registeredUntilCutoff, maximumCutoff);
                events += eventFlags.events();
                boolean registeredNow = consumeRegisteredAccount(accounts, credentialSuffix);
                int flags = eventFlags.flags();
                if (registeredNow) flags |= REWARD_FLAG_REGISTERED_NOW;
                if (!registeredNow
                        && (flags & (REWARD_FLAG_DEREGISTERED_AT_STABILITY
                        | REWARD_FLAG_DEREGISTERED_AT_BOUNDARY)) == 0) {
                    flags |= REWARD_FLAG_DEREGISTERED_AT_STABILITY
                            | REWARD_FLAG_DEREGISTERED_AT_BOUNDARY;
                }

                byte[] poolHash = AccountStateCborCodec.decodeEpochDelegSnapshotPoolHash(
                        snapshotRows.value());
                if (poolHash.length != 28) {
                    throw new IllegalStateException(
                            "Invalid pool hash length in reward snapshot: " + poolHash.length);
                }
                byte[] flagsKey = rewardFlagsKey(
                        snapshotEpoch, poolHash, credentialSuffix);
                batch.put(cfEpochSnapshot, flagsKey, new byte[]{(byte) flags});
                rows++;
                batchBytes += flagsKey.length + 1;
                if (batch.count() >= maxBatchOperations || batchBytes >= maxBatchBytes) {
                    db.write(writeOptions, batch);
                    batch.clear();
                    batchBytes = 0;
                    chunks++;
                }
                snapshotRows.next();
            }
            if (batch.count() > 0) {
                db.write(writeOptions, batch);
                chunks++;
            }
            log.info("Prepared reward credential flags for snapshot {}: rows={}, events={}, "
                            + "chunks={}, elapsedMs={}",
                    snapshotEpoch, rows, events, chunks,
                    System.currentTimeMillis() - started);
            return new PreparedRewardFlags(flagsPrefix, rows);
        } catch (Exception failure) {
            try {
                deleteRewardCredentialFlags(flagsPrefix);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException(
                    "Failed to prepare reward credential flags for snapshot " + snapshotEpoch,
                    failure);
        } finally {
            db.releaseSnapshot(snapshot);
        }
    }

    private boolean isCredentialMajorSnapshotRow(
            RocksIterator iterator, byte[] snapshotPrefix) {
        if (!iterator.isValid()) return false;
        byte[] key = iterator.key();
        return key.length == 33 && startsWith(key, snapshotPrefix)
                && key[4] != REWARD_FLAGS_PREFIX && key[4] != (byte) 0xFF;
    }

    private EventFlags consumeCredentialEvents(
            RocksIterator iterator, byte[] credentialSuffix,
            long stabilityCutoff, long boundaryCutoff,
            long registeredSinceCutoff, long registeredUntilCutoff,
            long maximumCutoff) {
        while (isCredentialEvent(iterator)
                && compareUnsigned(iterator.key(), 1, credentialSuffix, 0, 29) < 0) {
            iterator.next();
        }
        int lastAtStability = -1;
        int lastAtBoundary = -1;
        boolean registeredSince = false;
        boolean registeredUntil = false;
        int events = 0;
        while (isCredentialEvent(iterator)
                && compareUnsigned(iterator.key(), 1, credentialSuffix, 0, 29) == 0) {
            byte[] key = iterator.key();
            long slot = ByteBuffer.wrap(key, 30, 8).order(ByteOrder.BIG_ENDIAN).getLong();
            if (slot < maximumCutoff) {
                int event = AccountStateCborCodec.decodeStakeEvent(iterator.value());
                if (slot < stabilityCutoff) lastAtStability = event;
                if (slot < boundaryCutoff) lastAtBoundary = event;
                if (event == AccountStateCborCodec.EVENT_REGISTRATION) {
                    if (slot < registeredSinceCutoff) registeredSince = true;
                    if (slot < registeredUntilCutoff) registeredUntil = true;
                }
                events++;
            }
            iterator.next();
        }
        int flags = 0;
        if (lastAtStability == AccountStateCborCodec.EVENT_DEREGISTRATION) {
            flags |= REWARD_FLAG_DEREGISTERED_AT_STABILITY;
        }
        if (lastAtBoundary == AccountStateCborCodec.EVENT_DEREGISTRATION) {
            flags |= REWARD_FLAG_DEREGISTERED_AT_BOUNDARY;
        }
        if (registeredSince) flags |= REWARD_FLAG_REGISTERED_SINCE;
        if (registeredUntil) flags |= REWARD_FLAG_REGISTERED_UNTIL;
        return new EventFlags(flags, events);
    }

    private boolean consumeRegisteredAccount(
            RocksIterator iterator, byte[] credentialSuffix) {
        while (isAccount(iterator)
                && compareUnsigned(iterator.key(), 1, credentialSuffix, 0, 29) < 0) {
            iterator.next();
        }
        return isAccount(iterator)
                && compareUnsigned(iterator.key(), 1, credentialSuffix, 0, 29) == 0;
    }

    private static boolean isCredentialEvent(RocksIterator iterator) {
        return iterator.isValid() && iterator.key().length == 42
                && iterator.key()[0] == DefaultAccountStateStore.PREFIX_STAKE_EVENT_BY_CREDENTIAL;
    }

    private static boolean isAccount(RocksIterator iterator) {
        return iterator.isValid() && iterator.key().length == 30
                && iterator.key()[0] == DefaultAccountStateStore.PREFIX_ACCT;
    }

    private void deleteRewardCredentialFlags(byte[] flagsPrefix) {
        byte[] end = flagsPrefix.clone();
        end[4] = (byte) 0xFF;
        try (WriteOptions options = new WriteOptions();
             WriteBatch batch = new WriteBatch()) {
            batch.deleteRange(cfEpochSnapshot, flagsPrefix, end);
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to clear temporary reward credential flags", e);
        }
    }

    static byte[] rewardFlagsPrefix(int snapshotEpoch) {
        return ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
                .putInt(snapshotEpoch).put(REWARD_FLAGS_PREFIX).array();
    }

    static byte[] rewardFlagsKey(
            int snapshotEpoch, byte[] poolHash, byte[] credentialSuffix) {
        if (poolHash.length != 28 || credentialSuffix.length != 29) {
            throw new IllegalArgumentException("Invalid reward credential flags key component");
        }
        return ByteBuffer.allocate(62).order(ByteOrder.BIG_ENDIAN)
                .putInt(snapshotEpoch).put(REWARD_FLAGS_PREFIX).put(poolHash)
                .put(credentialSuffix).array();
    }

    private static int compareUnsigned(
            byte[] left, int leftOffset, byte[] right, int rightOffset, int length) {
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(
                    left[leftOffset + index] & 0xFF,
                    right[rightOffset + index] & 0xFF);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private record EventFlags(int flags, int events) {
    }

    final class PreparedRewardFlags implements AutoCloseable {
        private final byte[] prefix;
        private final int rows;
        private boolean closed;

        private PreparedRewardFlags(byte[] prefix, int rows) {
            this.prefix = prefix;
            this.rows = rows;
        }

        byte[] prefix() {
            return prefix;
        }

        int rows() {
            return rows;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                deleteRewardCredentialFlags(prefix);
            } catch (RuntimeException cleanupFailure) {
                log.warn("Could not remove temporary reward credential flags: {}",
                        cleanupFailure.toString());
            }
        }
    }

    private record StreamingAccountContext(
            HashSet<String> deregistered,
            HashSet<String> lateDeregistered,
            HashSet<String> deregisteredOnBoundary,
            HashSet<String> registeredSinceLast,
            HashSet<String> registeredUntilNow,
            boolean postBabbage,
            long stabilityCutoff,
            long boundaryCutoff,
            long registeredSinceCutoff,
            long registeredUntilCutoff) {
    }

    /**
     * Extract credential key ("credType:credHash") from a hex-encoded reward address.
     * Reward address format: header(1) + credential(28).
     * Header byte: e0 = key hash, f0 = script hash.
     *
     * @param rewardAccountHex hex-encoded reward address
     * @param fallback         fallback value if parsing fails
     * @return "credType:credHash" string
     */
    static String extractCredKeyFromRewardAddress(String rewardAccountHex, String fallback) {
        if (rewardAccountHex == null || rewardAccountHex.isEmpty()) return fallback;
        try {
            byte[] addrBytes = HexUtil.decodeHexString(rewardAccountHex);
            if (addrBytes.length < 29) return fallback;
            int headerByte = addrBytes[0] & 0xFF;
            int credType = ((headerByte & 0x10) != 0) ? 1 : 0;
            String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(addrBytes, 1, 29));
            return credType + ":" + credHash;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Extract credential hash from a "credType:credHash" stake address string.
     */
    private static String extractCredHash(String stakeAddress) {
        if (stakeAddress == null) return null;
        int colonIdx = stakeAddress.indexOf(':');
        return colonIdx >= 0 ? stakeAddress.substring(colonIdx + 1) : stakeAddress;
    }

    /**
     * Distribute calculated rewards: credit member and leader rewards to credential balances.
     */
    private void distributeRewards(int epoch, EpochCalculationResult result) {
        int earnedEpoch = epoch - 2;
        var poolResults = result.getPoolRewardCalculationResults();
        if (poolResults == null) return;

        int memberCount = 0;
        int leaderCount = 0;

        for (var poolResult : poolResults) {
            int[] counts = distributePoolReward(epoch, poolResult);
            leaderCount += counts[0];
            memberCount += counts[1];
        }

        log.info("Distributed rewards for epoch {}: {} leader, {} member", epoch, leaderCount, memberCount);
    }

    private int[] distributePoolReward(int epoch,
                                       org.cardanofoundation.rewards.calculation.domain.PoolRewardCalculationResult poolResult) {
        return distributePoolReward(epoch, poolResult, null);
    }

    private int[] distributePoolReward(
            int epoch,
            org.cardanofoundation.rewards.calculation.domain.PoolRewardCalculationResult poolResult,
            PrefetchedPoolRewardState prefetched) {
        int earnedEpoch = epoch - 2;
        String poolId = poolResult.getPoolId();
        int leaderCount = 0;
        int memberCount = 0;
        BigInteger leaderReward = poolResult.getOperatorReward();
        if (leaderReward != null && leaderReward.signum() > 0
                && poolResult.getRewardAddress() != null) {
            try {
                if (prefetched != null) {
                    creditPrefetchedReward(poolResult.getRewardAddress(), leaderReward,
                            earnedEpoch, RewardType.LEADER, poolId, prefetched);
                } else {
                    creditRewardByAddress(poolResult.getRewardAddress(), leaderReward,
                            earnedEpoch, RewardType.LEADER, poolId);
                }
                leaderCount++;
            } catch (RocksDBException e) {
                throw new RuntimeException("Failed to credit leader reward for pool " + poolId, e);
            }
        }
        if (poolResult.getMemberRewards() != null) {
            for (var reward : poolResult.getMemberRewards()) {
                if (reward.getAmount() == null || reward.getAmount().signum() <= 0) continue;
                try {
                    if (prefetched != null) {
                        creditPrefetchedReward(reward.getStakeAddress(), reward.getAmount(),
                                earnedEpoch, RewardType.MEMBER, poolId, prefetched);
                    } else {
                        creditRewardByAddress(reward.getStakeAddress(), reward.getAmount(),
                                earnedEpoch, RewardType.MEMBER, poolId);
                    }
                    memberCount++;
                } catch (RocksDBException e) {
                    throw new RuntimeException("Failed to credit member reward for pool " + poolId, e);
                }
            }
        }
        return new int[]{leaderCount, memberCount};
    }

    int[] distributePrefetchedPoolReward(
            int epoch,
            StreamingEpochRewardOrchestrator.PoolRewardInput poolInput,
            PoolRewardCalculationResult poolResult) {
        PrefetchedPoolRewardState prefetched =
                prefetchPoolRewardState(poolInput, poolResult);
        return distributePoolReward(epoch, poolResult, prefetched);
    }

    private PrefetchedPoolRewardState prefetchPoolRewardState(
            StreamingEpochRewardOrchestrator.PoolRewardInput poolInput,
            PoolRewardCalculationResult poolResult) {
        if (rewardStateOverlay == null) {
            throw new IllegalStateException("Reward state prefetch requires an active reward batch");
        }

        byte[] poolHash = HexUtil.decodeHexString(poolResult.getPoolId());
        if (poolHash.length != BoundaryCredentialKey.HASH_LENGTH) {
            throw new IllegalStateException(
                    "Invalid reward pool hash length: " + poolHash.length);
        }
        Map<BoundaryCredentialKey, AccountStateCborCodec.StakeAccount> accounts =
                new HashMap<>();
        Map<String, BoundaryCredentialKey> credentialKeys =
                poolInput.credentialKeys();
        List<RewardPrefetchKey> keys = new ArrayList<>();
        HashSet<BoundaryCredentialKey> credentials = new HashSet<>();
        BigInteger leaderReward = poolResult.getOperatorReward();
        if (leaderReward != null && leaderReward.signum() > 0
                && poolResult.getRewardAddress() != null) {
            addRewardStatePrefetchKeys(
                    poolResult.getRewardAddress(), credentialKeys,
                    credentials, keys, accounts);
        }
        if (poolResult.getMemberRewards() != null) {
            for (var reward : poolResult.getMemberRewards()) {
                if (reward.getAmount() != null && reward.getAmount().signum() > 0) {
                    addRewardStatePrefetchKeys(
                            reward.getStakeAddress(), credentialKeys,
                            credentials, keys, accounts);
                }
            }
        }
        PrefetchedPoolRewardState prefetched = new PrefetchedPoolRewardState(
                credentialKeys, accounts, poolHash);
        if (keys.isEmpty()) return prefetched;

        List<ColumnFamilyHandle> columnFamilies = new ArrayList<>(keys.size());
        List<byte[]> storageKeys = new ArrayList<>(keys.size());
        for (int index = 0; index < keys.size(); index++) {
            columnFamilies.add(cfState);
            storageKeys.add(keys.get(index).storageKey());
        }
        try {
            List<byte[]> values = db.multiGetAsList(columnFamilies, storageKeys);
            if (values.size() != keys.size()) {
                throw new IllegalStateException(
                        "Reward state MultiGet returned an unexpected value count");
            }
            for (int index = 0; index < keys.size(); index++) {
                RewardPrefetchKey key = keys.get(index);
                byte[] value = values.get(index);
                rewardStateOverlay.put(key.storageKey(), value);
                if (key.account() && value != null) {
                    accounts.put(key.credential(),
                            AccountStateCborCodec.decodeStakeAccount(value));
                }
            }
        } catch (RocksDBException failure) {
            throw new IllegalStateException(
                    "Failed to prefetch reward account state for pool "
                            + poolResult.getPoolId(), failure);
        }
        return prefetched;
    }

    private void addRewardStatePrefetchKeys(
            String address,
            Map<String, BoundaryCredentialKey> poolCredentialKeys,
            Set<BoundaryCredentialKey> credentials,
            List<RewardPrefetchKey> keys,
            Map<BoundaryCredentialKey, AccountStateCborCodec.StakeAccount> accounts) {
        BoundaryCredentialKey credential = poolCredentialKeys.get(address);
        if (credential == null) {
            throw new IllegalStateException(
                    "Missing byte credential for reward address " + address);
        }
        if (!credentials.add(credential)) return;

        byte[] accountKey = DefaultAccountStateStore.accountKey(credential);
        if (rewardStateOverlay.contains(accountKey)) {
            byte[] value = rewardStateOverlay.get(accountKey);
            if (value != null) {
                accounts.put(credential, AccountStateCborCodec.decodeStakeAccount(value));
            }
        } else {
            keys.add(new RewardPrefetchKey(accountKey, credential, true));
        }
        byte[] rewardKey = DefaultAccountStateStore.accumulatedRewardKey(credential);
        if (!rewardStateOverlay.contains(rewardKey)) {
            keys.add(new RewardPrefetchKey(rewardKey, credential, false));
        }
    }

    private record RewardPrefetchKey(
            byte[] storageKey, BoundaryCredentialKey credential, boolean account) {
    }

    private record PrefetchedPoolRewardState(
            Map<String, BoundaryCredentialKey> credentialKeys,
            Map<BoundaryCredentialKey, AccountStateCborCodec.StakeAccount> accounts,
            byte[] poolHash) {
    }

    private void stagePoolRewardFacts(int epoch,
                                      org.cardanofoundation.rewards.calculation.domain.PoolRewardCalculationResult result) {
        if (rewardArchiveWriter == null) return;
        int earnedEpoch = epoch - 2;
        BigInteger leaderReward = result.getOperatorReward();
        if (leaderReward != null && leaderReward.signum() > 0
                && result.getRewardAddress() != null) {
            appendRewardFact(result.getRewardAddress(), leaderReward, earnedEpoch,
                    RewardType.LEADER, result.getPoolId());
        }
        if (result.getMemberRewards() != null) {
            for (var reward : result.getMemberRewards()) {
                if (reward.getAmount() != null && reward.getAmount().signum() > 0) {
                    appendRewardFact(reward.getStakeAddress(), reward.getAmount(), earnedEpoch,
                            RewardType.MEMBER, result.getPoolId());
                }
            }
        }
    }

    private void appendRewardFact(String address, BigInteger amount, int earnedEpoch,
                                  RewardType rewardType, String poolHash) {
        int credentialType = 0;
        String credentialHash = address;
        int separator = address.indexOf(':');
        if (separator >= 0) {
            credentialType = Integer.parseInt(address.substring(0, separator));
            credentialHash = address.substring(separator + 1);
        }
        int spendableEpoch = archiveBoundary != null
                ? archiveBoundary.newEpoch() : earnedEpoch + 2;
        String sourceId = poolHash != null && !poolHash.isBlank() ? poolHash : "ledger";
        rewardArchiveWriter.append(
                new EpochArchiveStagingSink.RewardFact(
                        credentialType, credentialHash, poolHash, rewardType.name(), earnedEpoch,
                        spendableEpoch, amount, sourceId));
    }

    /**
     * Credit a reward using an address string (which may be a credKey "type:hash").
     */
    private void creditRewardByAddress(String address, BigInteger amount,
                                       int earnedEpoch, RewardType type, String poolId) throws RocksDBException {
        if (amount == null || amount.signum() <= 0) return;

        // Parse credKey format "credType:credHash"
        int credType = 0;
        String credHash = address;
        if (address.contains(":")) {
            String[] parts = address.split(":", 2);
            credType = Integer.parseInt(parts[0]);
            credHash = parts[1];
        }

        creditReward(credType, credHash, amount, earnedEpoch, type, poolId);
    }

    private void creditPrefetchedReward(
            String address, BigInteger amount, int earnedEpoch,
            RewardType rewardType, String poolHash,
            PrefetchedPoolRewardState prefetched) throws RocksDBException {
        if (amount == null || amount.signum() <= 0) return;
        if (rewardBatch == null) {
            throw new IllegalStateException(
                    "creditReward called without an active reward batch — call beginRewardBatch() first");
        }

        BoundaryCredentialKey credential = prefetched.credentialKeys().get(address);
        if (credential == null) {
            throw new IllegalStateException(
                    "Missing byte credential for reward address " + address);
        }
        byte[] accountKey = DefaultAccountStateStore.accountKey(credential);
        AccountStateCborCodec.StakeAccount account =
                prefetched.accounts().get(credential);
        if (account != null) {
            BigInteger newReward = account.reward().add(amount);
            byte[] newValue = AccountStateCborCodec.encodeStakeAccount(
                    newReward, account.deposit());
            accountStateStore.putStateWithDelta(accountKey, newValue,
                    rewardBatch, rewardDeltaOps, rewardStateOverlay);
            prefetched.accounts().put(credential,
                    new AccountStateCborCodec.StakeAccount(newReward, account.deposit()));
        }

        byte[] rewardKey = DefaultAccountStateStore.accumulatedRewardKey(credential);
        byte[] rewardValue = AccountStateCborCodec.encodeAccumulatedReward(
                earnedEpoch, rewardType.ordinal(), amount, prefetched.poolHash());
        accountStateStore.putStateWithDelta(rewardKey, rewardValue,
                rewardBatch, rewardDeltaOps, rewardStateOverlay);

        if (rewardArchiveWriter != null) {
            int separator = address.indexOf(':');
            String credentialHash = separator >= 0
                    ? address.substring(separator + 1) : address;
            int spendableEpoch = archiveBoundary != null
                    ? archiveBoundary.newEpoch() : earnedEpoch + 2;
            String sourceId = poolHash != null && !poolHash.isBlank()
                    ? poolHash : "ledger";
            rewardArchiveWriter.append(new EpochArchiveStagingSink.RewardFact(
                    credential.credentialType(), credentialHash, poolHash,
                    rewardType.name(), earnedEpoch, spendableEpoch, amount, sourceId));
        }
    }

    /**
     * Credit a reward to a stake credential's reward balance.
     * Uses delta-aware writes so the credit can be undone on rollback.
     */
    public void creditReward(int credType, String credHash, BigInteger amount,
                             int earnedEpoch, RewardType rewardType, String poolHash) throws RocksDBException {
        if (amount == null || amount.signum() <= 0) return;
        if (rewardBatch == null) {
            throw new IllegalStateException("creditReward called without an active reward batch — call beginRewardBatch() first");
        }

        byte[] acctKey = DefaultAccountStateStore.accountKey(credType, credHash);
        byte[] acctVal = accountStateStore.getStateWithOverlay(acctKey, rewardStateOverlay);
        if (acctVal != null) {
            var acct = AccountStateCborCodec.decodeStakeAccount(acctVal);
            BigInteger newReward = acct.reward().add(amount);
            byte[] newVal = AccountStateCborCodec.encodeStakeAccount(newReward, acct.deposit());
            accountStateStore.putStateWithDelta(acctKey, newVal, rewardBatch, rewardDeltaOps, rewardStateOverlay);
        }

        byte[] rewardKey = DefaultAccountStateStore.accumulatedRewardKey(credType, credHash);
        var reward = new AccountStateCborCodec.AccumulatedReward(
                earnedEpoch, rewardType.ordinal(), amount, poolHash);
        accountStateStore.putStateWithDelta(rewardKey,
                AccountStateCborCodec.encodeAccumulatedReward(reward), rewardBatch, rewardDeltaOps, rewardStateOverlay);

        if (rewardArchiveWriter != null) {
            int spendableEpoch = archiveBoundary != null ? archiveBoundary.newEpoch() : earnedEpoch + 2;
            String sourceId = poolHash != null && !poolHash.isBlank() ? poolHash : "ledger";
            rewardArchiveWriter.append(new com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.RewardFact(
                    credType, credHash, poolHash, rewardType.name(), earnedEpoch, spendableEpoch,
                    amount, sourceId));
        }
    }

    /**
     * Build the set of pools retiring at this epoch from on-chain pool retirement certs.
     * Scans PREFIX_POOL_RETIRE entries where retireEpoch == epoch.
     */
    private Set<RetiredPool> buildRetiredPools(int epoch) {
        if (ledgerStateProvider == null) return Set.of();

        var retiring = ledgerStateProvider.getPoolsRetiringAtEpoch(epoch);
        var result = new HashSet<RetiredPool>();
        for (var pool : retiring) {
            String rewardAddress = pool.poolHash();
            var poolParamsOpt = ledgerStateProvider.getPoolParams(pool.poolHash());
            if (poolParamsOpt.isPresent()) {
                rewardAddress = extractCredKeyFromRewardAddress(
                        poolParamsOpt.get().rewardAccount(), pool.poolHash());
            }
            result.add(RetiredPool.builder()
                    .poolId(pool.poolHash())
                    .rewardAddress(rewardAddress)
                    .depositAmount(pool.deposit())
                    .build());
        }
        if (!result.isEmpty()) {
            log.info("Found {} pools retiring at epoch {}", result.size(), epoch);
        }
        return result;
    }

    /**
     * Process pool deposit refunds for pools retiring at this epoch.
     * The cf-rewards library handles the treasury side (unclaimed deposits from unregistered
     * reward addresses go to treasury). This method handles the other side: crediting deposits
     * to registered reward addresses. Per the Cardano ledger spec (POOLREAP), if the pool's
     * reward address is a registered stake credential, the deposit is refunded to that address.
     *
     * @param epoch the epoch at which pools retire
     * @return total amount refunded to individual accounts
     */
    public BigInteger processPoolDepositRefunds(int epoch) {
        if (ledgerStateProvider == null) return BigInteger.ZERO;

        var retiring = ledgerStateProvider.getPoolsRetiringAtEpoch(epoch);
        BigInteger totalRefunded = BigInteger.ZERO;

        for (var pool : retiring) {
            var poolParamsOpt = ledgerStateProvider.getPoolParams(pool.poolHash());
            if (poolParamsOpt.isEmpty()) continue;

            String rewardAccountHex = poolParamsOpt.get().rewardAccount();
            String credKey = extractCredKeyFromRewardAddress(rewardAccountHex, null);
            if (credKey == null) continue;

            // Check if the reward address is registered
            int credType = 0;
            String credHash = credKey;
            int colonIdx = credKey.indexOf(':');
            if (colonIdx >= 0) {
                credType = Integer.parseInt(credKey.substring(0, colonIdx));
                credHash = credKey.substring(colonIdx + 1);
            }

            if (!ledgerStateProvider.isStakeCredentialRegistered(credType, credHash)) {
                log.debug("Pool {} reward address {} not registered, deposit stays in treasury",
                        pool.poolHash(), credKey);
                continue;
            }

            BigInteger deposit = pool.deposit();
            if (deposit == null || deposit.signum() <= 0) {
                throw new IllegalStateException("Pool " + pool.poolHash()
                        + " has no valid stored lifecycle deposit");
            }

            try {
                // A deposit refund is not leader income — type it as REFUND so
                // reward history (and the accumulated-reward record) label it correctly.
                creditReward(credType, credHash, deposit, epoch,
                        RewardType.REFUND, pool.poolHash());
                totalRefunded = totalRefunded.add(deposit);
                log.info("Pool {} deposit refund {} credited to {} at epoch {}",
                        pool.poolHash(), deposit, credKey, epoch);
            } catch (RocksDBException e) {
                log.warn("Failed to credit pool deposit refund for {}: {}", pool.poolHash(), e.getMessage());
                throw new RuntimeException("Failed to credit pool deposit refund for " + pool.poolHash(), e);
            }
        }

        return totalRefunded;
    }

    // --- Account set construction ---

    private record AccountSets(
            HashSet<String> deregistered,
            HashSet<String> lateDeregistered,
            HashSet<String> deregisteredOnBoundary,
            HashSet<String> registeredSinceLast,
            HashSet<String> registeredUntilNow
    ) {}

    /** Read-only union view; avoids copying every snapshot credential into another set. */
    private static final class RelevantCredentialsView extends AbstractSet<String> {
        private final Set<String> snapshotCredentials;
        private final Set<String> poolRewardAddresses;

        private RelevantCredentialsView(Set<String> snapshotCredentials,
                                        Set<String> poolRewardAddresses) {
            this.snapshotCredentials = snapshotCredentials;
            this.poolRewardAddresses = poolRewardAddresses;
        }

        @Override
        public boolean contains(Object value) {
            return snapshotCredentials.contains(value) || poolRewardAddresses.contains(value);
        }

        @Override
        public Iterator<String> iterator() {
            return java.util.stream.Stream.concat(
                            snapshotCredentials.stream(),
                            poolRewardAddresses.stream()
                                    .filter(value -> !snapshotCredentials.contains(value)))
                    .iterator();
        }

        @Override
        public int size() {
            int uniquePoolAddresses = 0;
            for (String value : poolRewardAddresses) {
                if (!snapshotCredentials.contains(value)) uniquePoolAddresses++;
            }
            return snapshotCredentials.size() + uniquePoolAddresses;
        }
    }

    /**
     * Build MIR certificates for the cf-rewards library by aggregating per-epoch per-pot totals.
     * Uses feeEpoch (epoch - 1) matching Yaci Store's convention:
     * "Block producing epoch is epoch - 1 (Fee + MIR + DeRegistration)"
     *
     * @param mirEpoch the epoch from which to aggregate MIR data (feeEpoch = epoch - 1)
     * @return list of MirCertificate objects (0-2 entries: one per pot type with non-zero total)
     */
    private List<MirCertificate> buildMirCertificates(int mirEpoch) {
        if (accountStateStore == null) return List.of();

        List<MirCertificate> result = new ArrayList<>();

        BigInteger totalFromReserves = accountStateStore.getMirEpochTotal(
                mirEpoch, DefaultAccountStateStore.REWARD_REST_MIR_RESERVES);
        if (totalFromReserves.signum() > 0) {
            result.add(MirCertificate.builder()
                    .pot(MirPot.RESERVES)
                    .totalRewards(totalFromReserves)
                    .build());
        }

        BigInteger totalFromTreasury = accountStateStore.getMirEpochTotal(
                mirEpoch, DefaultAccountStateStore.REWARD_REST_MIR_TREASURY);
        if (totalFromTreasury.signum() > 0) {
            result.add(MirCertificate.builder()
                    .pot(MirPot.TREASURY)
                    .totalRewards(totalFromTreasury)
                    .build());
        }

        return result;
    }

    /**
     * Build the five account sets needed by cf-rewards-calculation using event-based tracking.
     * Falls back to snapshot diff when event queries are not available.
     */
    private AccountSets buildAccountSets(int epoch, int stakeEpoch, int feeEpoch,
                                          EpochParamProvider paramProvider, long networkMagic,
                                          Map<String, AccountStateCborCodec.EpochDelegSnapshot> stakeSnapshot,
                                          List<PoolState> poolStates,
                                          Set<RetiredPool> retiredPools) {

        // Check if event-based queries are available
        boolean hasEventQueries = ledgerStateProvider != null && accountStateStore != null;

        if (!hasEventQueries) {
            var registeredNow = ledgerStateProvider != null
                    ? ledgerStateProvider.getAllRegisteredCredentials()
                    : Set.<String>of();
            // Fallback: snapshot diff (no temporal precision)
            var deregistered = new HashSet<String>();
            for (String credKey : stakeSnapshot.keySet()) {
                if (!registeredNow.contains(credKey)) {
                    deregistered.add(credKey);
                }
            }
            return new AccountSets(
                    deregistered,
                    new HashSet<>(),
                    new HashSet<>(deregistered),
                    new HashSet<>(),
                    new HashSet<>(registeredNow)
            );
        }

        // Event-based: compute epoch boundary slots
        long feeEpochStartSlot = accountStateStore.slotForEpochStart(feeEpoch);
        long feeEpochEndSlot = accountStateStore.slotForEpochStart(feeEpoch + 1);
        long stabilityWindowSlot = feeEpochStartSlot + paramProvider.getRandomnessStabilisationWindow();

        // Determine era: post-Babbage = all dereg treated uniformly
        var networkConfig = getNetworkConfig(networkMagic);
        boolean postBabbage = isPostBabbage(stakeEpoch, networkConfig);

        // Scan deregistration events from the beginning to cover ALL history.
        // yaci-store checks ALL epochs (epoch <= epoch-1). With SNAPSHOT_RETENTION_EPOCHS=50,
        // all stake events since genesis are retained and available for scanning.
        long deregScanStartSlot = 0;

        HashSet<String> deregistered;
        HashSet<String> lateDeregistered;
        HashSet<String> deregisteredOnBoundary;

        // Pool reward addresses for registered since last / until now
        // Build early so we can include them in the deregistered fallback check
        Set<String> poolRewardAddresses = new HashSet<>();
        for (PoolState ps : poolStates) {
            if (ps.getRewardAddress() != null) {
                poolRewardAddresses.add(ps.getRewardAddress());
            }
        }
        for (RetiredPool rp : retiredPools) {
            if (rp.getRewardAddress() != null) {
                poolRewardAddresses.add(rp.getRewardAddress());
            }
        }

        Set<String> relevantCredentials = new RelevantCredentialsView(
                stakeSnapshot.keySet(), poolRewardAddresses);

        if (postBabbage) {
            deregistered = new HashSet<>(
                    ledgerStateProvider.getDeregisteredAccountsInSlotRange(
                            deregScanStartSlot, feeEpochEndSlot, relevantCredentials));
            lateDeregistered = new HashSet<>();
            // These sets are semantically identical post-Babbage and read-only in
            // cf-rewards; sharing avoids a network-sized duplicate HashSet.
            deregisteredOnBoundary = deregistered;
        } else {
            deregistered = new HashSet<>(
                    ledgerStateProvider.getDeregisteredAccountsInSlotRange(
                            deregScanStartSlot, stabilityWindowSlot, relevantCredentials));
            deregisteredOnBoundary = new HashSet<>(
                    ledgerStateProvider.getDeregisteredAccountsInSlotRange(
                            deregScanStartSlot, feeEpochEndSlot, relevantCredentials));
            lateDeregistered = new HashSet<>(deregisteredOnBoundary);
            lateDeregistered.removeAll(deregistered);
        }

        // Fallback: credentials in snapshot or pool reward addresses that are not currently
        // registered but were not caught by the event scan (e.g., deregistered before the
        // event retention window, or genesis accounts without events).
        // This matches yaci-store's behavior of checking all history up to the epoch boundary.
        for (String credKey : stakeSnapshot.keySet()) {
            if (!ledgerStateProvider.isStakeCredentialRegistered(credKey)
                    && !deregistered.contains(credKey)
                    && !deregisteredOnBoundary.contains(credKey)) {
                deregisteredOnBoundary.add(credKey);
                deregistered.add(credKey);
            }
        }
        // Note: pool reward addresses are NOT added to the deregistered fallback.
        // Never-registered credentials (like genesis pool reward addresses) should not be
        // in the deregistered set. They are already handled by the registeredSinceLast check
        // in cf-rewards ("has never been registered" → denied before the deregistered check).
        // Reward addresses that WERE registered and then deregistered are caught by the
        // event-based scan above.

        // registeredSinceLast: pool reward addresses registered up to the fee epoch stability window.
        // Matches Yaci Store: cutoff = startOfFeeEpoch + stabilityWindow (not epoch end).
        // Pre-Babbage: stabilityWindow = randomnessStabilisationWindow (172800 slots).
        // Post-Babbage: stabilityWindow = epochLength (432000 slots) = same as epoch end.
        long registeredSinceLastCutoff = feeEpochStartSlot + (postBabbage
                ? paramProvider.getEpochLength()
                : paramProvider.getRandomnessStabilisationWindow());
        var registeredSinceLast = new HashSet<>(
                ledgerStateProvider.getRegisteredPoolRewardAddressesBeforeSlot(registeredSinceLastCutoff, poolRewardAddresses));

        // registeredUntilNow: pool reward addresses registered up to the start of current epoch N
        long currentEpochStartSlot = accountStateStore.slotForEpochStart(epoch);
        var registeredUntilNow = new HashSet<>(
                ledgerStateProvider.getRegisteredPoolRewardAddressesBeforeSlot(currentEpochStartSlot, poolRewardAddresses));

        log.debug("AccountSets: deregistered={}, lateDeregistered={}, registeredSinceLast={}, registeredUntilNow={}, poolRewardAddresses={}",
                deregistered.size(), lateDeregistered.size(), registeredSinceLast.size(),
                registeredUntilNow.size(), poolRewardAddresses.size());
        return new AccountSets(deregistered, lateDeregistered, deregisteredOnBoundary,
                registeredSinceLast, registeredUntilNow);
    }

    /**
     * Determine if the given epoch is post-Babbage (Vasil hardfork).
     */
    private static boolean isPostBabbage(int epoch, NetworkConfig networkConfig) {
        int vasilEpoch = networkConfig.getVasilHardforkEpoch();
        return epoch >= vasilEpoch;
    }

    // --- Data access helpers ---

    public Map<String, Long> getPoolBlockCounts(int epoch) {
        Map<String, Long> counts = new HashMap<>();
        byte[] seekKey = new byte[1 + 4];
        seekKey[0] = DefaultAccountStateStore.PREFIX_BLOCK_ISSUER;
        ByteBuffer.wrap(seekKey, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);

        try (var it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 5 || key[0] != DefaultAccountStateStore.PREFIX_BLOCK_ISSUER) break;
                int keyEpoch = ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (keyEpoch != epoch) break;

                String poolHash = HexUtil.encodeHexString(it.value());
                counts.merge(poolHash, 1L, Long::sum);
                it.next();
            }
        }
        return counts;
    }

    public BigInteger getEpochFees(int epoch) {
        BigInteger total = BigInteger.ZERO;
        byte[] seekKey = new byte[1 + 4];
        seekKey[0] = DefaultAccountStateStore.PREFIX_BLOCK_FEE;
        ByteBuffer.wrap(seekKey, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(epoch);

        try (var it = db.newIterator(cfState)) {
            it.seek(seekKey);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 5 || key[0] != DefaultAccountStateStore.PREFIX_BLOCK_FEE) break;
                int keyEpoch = ByteBuffer.wrap(key, 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (keyEpoch != epoch) break;

                total = total.add(AccountStateCborCodec.decodeEpochFees(it.value()));
                it.next();
            }
        }
        return total;
    }

    public Map<String, AccountStateCborCodec.EpochDelegSnapshot> getStakeSnapshot(int epoch) {
        Map<String, AccountStateCborCodec.EpochDelegSnapshot> snapshot = new HashMap<>();
        Map<String, String> canonicalPoolHashes = new HashMap<>();
        byte[] epochPrefix = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();

        try (var it = db.newIterator(cfEpochSnapshot)) {
            it.seek(epochPrefix);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 5) break;
                int keyEpoch = ByteBuffer.wrap(key, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (keyEpoch != epoch) break;
                if (key.length != 33) {
                    it.next();
                    continue;
                }

                int credType = key[4] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 5, key.length));
                var decoded = AccountStateCborCodec.decodeEpochDelegSnapshot(it.value());
                String poolHash = canonicalPoolHashes.computeIfAbsent(
                        decoded.poolHash(), keyValue -> keyValue);
                snapshot.put(credType + ":" + credHash,
                        new AccountStateCborCodec.EpochDelegSnapshot(
                                poolHash, decoded.amount()));
                it.next();
            }
        }
        return snapshot;
    }

    public long getTotalBlocksInEpoch(int epoch) {
        return getPoolBlockCounts(epoch).values().stream().mapToLong(Long::longValue).sum();
    }

    // --- Public input assembly for debug / comparison ---

    /**
     * All reward calculation inputs assembled for a given epoch, suitable for JSON export.
     */
    public record RewardInputs(
            int epoch,
            int stakeEpoch,
            int feeEpoch,
            BigInteger prevTreasury,
            BigInteger prevReserves,
            ProtocolParameters protocolParameters,
            Epoch epochInfo,
            Map<String, AccountStateCborCodec.EpochDelegSnapshot> stakeSnapshot,
            Map<String, Long> poolBlockCounts,
            BigInteger epochFees,
            List<PoolState> poolStates,
            Set<RetiredPool> retiredPools,
            HashSet<String> deregistered,
            HashSet<String> lateDeregistered,
            HashSet<String> deregisteredOnBoundary,
            HashSet<String> registeredSinceLast,
            HashSet<String> registeredUntilNow,
            Set<String> sharedPoolRewardAddresses,
            List<MirCertificate> mirCertificates
    ) {}

    /**
     * Assemble all reward calculation inputs for the given epoch without running the calculation.
     * Useful for debugging and comparison with yaci-store.
     *
     * @param epoch         the current epoch (rewards calculated for epoch-2)
     * @param prevTreasury  treasury at end of epoch-1
     * @param prevReserves  reserves at end of epoch-1
     * @param paramProvider protocol parameters
     * @param networkMagic  network magic number
     * @return assembled inputs, or empty if disabled
     */
    public Optional<RewardInputs> assembleRewardInputs(
            int epoch,
            BigInteger prevTreasury,
            BigInteger prevReserves,
            EpochParamProvider paramProvider,
            long networkMagic) {

        if (!enabled) return Optional.empty();

        int stakeEpoch = epoch - 2;
        int feeEpoch = epoch - 1;
        int snapshotKey = stakeEpoch - 2; // N-4: mark snapshot from end of epoch N-4

        // Protocol params: use shelley start epoch if stakeEpoch is Byron
        var networkConfig = resolveEffectiveRewardNetworkConfig(getNetworkConfig(networkMagic));
        if (epoch <= networkConfig.getShelleyStartEpoch()) return Optional.empty();
        int shelleyStartEpoch = networkConfig.getShelleyStartEpoch();
        int paramEpoch = (stakeEpoch < shelleyStartEpoch) ? shelleyStartEpoch : stakeEpoch;
        var protocolParams = buildProtocolParameters(paramProvider, paramEpoch);

        var blockCounts = getPoolBlockCounts(stakeEpoch);
        long totalBlocks = blockCounts.values().stream().mapToLong(Long::longValue).sum();
        var fees = getEpochFees(stakeEpoch); // fees collected during epoch N-2

        var stakeSnapshot = getStakeSnapshot(snapshotKey);
        var totalActiveStake = stakeSnapshot.values().stream()
                .map(AccountStateCborCodec.EpochDelegSnapshot::amount)
                .reduce(BigInteger.ZERO, BigInteger::add);

        var poolStates = buildPoolStates(stakeSnapshot, blockCounts, stakeEpoch);
        var rewardRules = resolveRewardRuleContext(epoch, stakeEpoch, protocolParams,
                blockCounts, totalBlocks, networkConfig);
        protocolParams = rewardRules.protocolParameters();

        var epochInfo = Epoch.builder()
                .number(stakeEpoch)
                .fees(fees)
                .blockCount((int) rewardRules.blockCount())
                .activeStake(totalActiveStake)
                .nonOBFTBlockCount((int) rewardRules.nonOBFTBlockCount())
                .build();

        Set<RetiredPool> retiredPools = buildRetiredPools(epoch);

        var accountSets = buildAccountSets(epoch, stakeEpoch, feeEpoch, paramProvider,
                networkMagic, stakeSnapshot, poolStates, retiredPools);

        var sharedPoolRewardAddresses = SharedPoolRewardAddresses
                .getSharedAddressesWithoutReward(epoch, networkMagic);

        List<MirCertificate> mirCertificates = buildMirCertificates(feeEpoch);

        return Optional.of(new RewardInputs(
                epoch, stakeEpoch, feeEpoch,
                prevTreasury, prevReserves,
                protocolParams, epochInfo,
                stakeSnapshot, blockCounts, fees,
                poolStates, retiredPools,
                accountSets.deregistered, accountSets.lateDeregistered,
                accountSets.deregisteredOnBoundary,
                accountSets.registeredSinceLast, accountSets.registeredUntilNow,
                sharedPoolRewardAddresses, mirCertificates
        ));
    }

    /**
     * Get pool registration parameters for a specific pool.
     * Delegates to the LedgerStateProvider.
     */
    public Optional<LedgerStateProvider.PoolParams> getPoolParams(String poolHash) {
        if (ledgerStateProvider == null) return Optional.empty();
        return ledgerStateProvider.getPoolParams(poolHash);
    }

    void beginBoundaryPoolParamsMemo() {
        if (boundaryPoolParamsMemo != null) {
            throw new IllegalStateException("Boundary pool-parameter memo is already active");
        }
        boundaryPoolParamsMemo = new HashMap<>();
    }

    void clearBoundaryPoolParamsMemo() {
        if (boundaryPoolParamsMemo != null) {
            boundaryPoolParamsMemo.clear();
            boundaryPoolParamsMemo = null;
        }
    }

    Optional<LedgerStateProvider.PoolParams> getPoolParamsAtEpoch(String poolHash, int epoch) {
        var provider = ledgerStateProvider;
        if (provider == null) return Optional.empty();
        if (boundaryPoolParamsMemo == null) {
            return provider.getPoolParams(poolHash, epoch);
        }
        var key = new PoolParamsAtEpochKey(poolHash, epoch);
        return boundaryPoolParamsMemo.computeIfAbsent(key,
                ignored -> Objects.requireNonNull(provider.getPoolParams(poolHash, epoch),
                        "LedgerStateProvider.getPoolParams must return Optional.empty(), not null"));
    }

    private record PoolParamsAtEpochKey(String poolHash, int epoch) {
    }

    /**
     * Get the CF NetworkConfig. Must be set via {@link #setCfNetworkConfig(NetworkConfig)}
     * before reward calculation runs. Throws if not available.
     */
    NetworkConfig getNetworkConfig(long networkMagic) {
        if (cfNetworkConfig != null) return cfNetworkConfig;
        throw new IllegalStateException(
                "CF NetworkConfig not set. Build from genesis via NetworkConfigBuilder or "
                + "set via setCfNetworkConfig() before reward calculation.");
    }

    /**
     * Resolve NetworkConfig from network magic using the CF library's built-in configs.
     * Only for known public networks. Throws for unknown magic.
     */
    public static NetworkConfig resolveNetworkConfig(long networkMagic) {
        return switch ((int) networkMagic) {
            case 764824073 -> NetworkConfig.getMainnetConfig();
            case 1 -> NetworkConfig.getPreprodConfig();
            case 2 -> NetworkConfig.getPreviewConfig();
            default -> throw new IllegalStateException(
                    "Unknown network magic " + networkMagic + " — provide genesis config to build CF NetworkConfig");
        };
    }
}
