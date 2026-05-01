package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.account.RewardType;
import com.bloxbean.cardano.yano.api.era.EraProvider;
import com.bloxbean.cardano.yaci.core.model.Era;
import org.cardanofoundation.rewards.calculation.EpochCalculation;
import org.cardanofoundation.rewards.calculation.config.NetworkConfig;
import org.cardanofoundation.rewards.calculation.domain.*;
import org.cardanofoundation.rewards.calculation.enums.MirPot;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    private final RocksDB db;
    private final ColumnFamilyHandle cfState;
    private final ColumnFamilyHandle cfEpochSnapshot;
    private volatile boolean enabled;

    // Optional reference for querying retired pools and registered credentials
    private volatile com.bloxbean.cardano.yano.api.account.LedgerStateProvider ledgerStateProvider;

    // Optional reference to the account state store for slot/epoch helpers and event queries
    private volatile DefaultAccountStateStore accountStateStore;

    // Optional era metadata for reward-rule decisions. NetworkConfig hardfork epochs are fallback only.
    private volatile EraProvider eraProvider;
    private volatile Integer loggedEffectiveVasilHardforkEpoch;

    // CF NetworkConfig — built once from genesis at startup, or resolved per-magic (legacy)
    private volatile NetworkConfig cfNetworkConfig;

    // Per-invocation batch for delta-aware reward distribution.
    // Set by beginRewardBatch(), committed by commitRewardBatch().
    private org.rocksdb.WriteBatch rewardBatch;
    private List<DefaultAccountStateStore.DeltaOp> rewardDeltaOps;
    private DefaultAccountStateStore.BatchStateOverlay rewardStateOverlay;

    public EpochRewardCalculator(RocksDB db, ColumnFamilyHandle cfState,
                                 ColumnFamilyHandle cfEpochSnapshot, boolean enabled) {
        this.db = db;
        this.cfState = cfState;
        this.cfEpochSnapshot = cfEpochSnapshot;
        this.enabled = enabled;
    }

    /**
     * Set the CF NetworkConfig (built from genesis via NetworkConfigBuilder).
     * If set, this is used instead of resolveNetworkConfig(networkMagic).
     */
    public void setCfNetworkConfig(NetworkConfig config) {
        this.cfNetworkConfig = config;
    }

    public void setLedgerStateProvider(com.bloxbean.cardano.yano.api.account.LedgerStateProvider provider) {
        this.ledgerStateProvider = provider;
    }

    public void setAccountStateStore(DefaultAccountStateStore store) {
        this.accountStateStore = store;
    }

    public void setEraProvider(EraProvider eraProvider) {
        this.eraProvider = eraProvider;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Open a WriteBatch for delta-aware reward distribution.
     * Must be called before calculateAndDistribute() and paired with commitRewardBatch().
     */
    void beginRewardBatch() {
        this.rewardBatch = new org.rocksdb.WriteBatch();
        this.rewardDeltaOps = new ArrayList<>();
        this.rewardStateOverlay = new DefaultAccountStateStore.BatchStateOverlay();
    }

    /**
     * Get the active reward batch for adding additional writes (e.g., AdaPot) before commit.
     * Returns null if no batch is active.
     */
    org.rocksdb.WriteBatch getRewardBatch() { return rewardBatch; }

    /**
     * Get the active reward delta ops list for adding additional delta-aware writes before commit.
     */
    List<DefaultAccountStateStore.DeltaOp> getRewardDeltaOps() { return rewardDeltaOps; }

    /**
     * Commit the reward batch and persist its boundary delta journal entry.
     * All additional writes (e.g., AdaPot) must be added to the batch before calling this.
     *
     * @param boundarySlot the slot of the first block that triggered this epoch boundary
     * @param phase        the boundary delta phase constant (PHASE_REWARDS or PHASE_POOLREAP)
     */
    void commitRewardBatch(long boundarySlot, byte phase) throws RocksDBException {
        if (rewardBatch == null) return;
        try (var wo = new org.rocksdb.WriteOptions()) {
            accountStateStore.commitBoundaryDelta(boundarySlot, phase, rewardBatch, rewardDeltaOps);
            db.write(wo, rewardBatch);
        } finally {
            rewardBatch.close();
            rewardBatch = null;
            rewardDeltaOps = null;
            if (rewardStateOverlay != null) {
                rewardStateOverlay.clear();
            }
            rewardStateOverlay = null;
        }
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
        if (rewardRules.postVasilRewardRules()) {
            log.info("Epoch {} post-Vasil reward rules: rawBlocks={}, poolBlocks={}, paramEpoch={}",
                    epoch, totalBlocks, rewardRules.blockCount(), paramEpoch);
        }

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
            return Optional.empty();
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
            if (ledgerStateProvider.getPoolParams(entry.getKey(), stakeEpoch).isPresent()) {
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
                        && ledgerStateProvider.getPoolParams(entry.getKey(), stakeEpoch).isPresent()) {
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
        Map<String, List<Delegator>> poolDelegators = new HashMap<>();
        Map<String, BigInteger> poolActiveStake = new HashMap<>();

        for (var entry : snapshot.entrySet()) {
            var deleg = entry.getValue();
            String poolHash = deleg.poolHash();
            BigInteger amount = deleg.amount();

            String stakeAddr = entry.getKey(); // "credType:credHash"

            poolDelegators.computeIfAbsent(poolHash, _ -> new ArrayList<>())
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
            int blocks = blockCounts.getOrDefault(poolHash, 0L).intValue();

            // Resolve pool registration parameters
            String rewardAddress = poolHash;
            double margin = 0.0;
            BigInteger fixedCost = BigInteger.ZERO;
            BigInteger pledge = BigInteger.ZERO;
            Set<String> owners = new HashSet<>();

            if (ledgerStateProvider != null) {
                var poolParamsOpt = ledgerStateProvider.getPoolParams(poolHash, epoch);
                if (poolParamsOpt.isPresent()) {
                    var pp = poolParamsOpt.get();
                    rewardAddress = extractCredKeyFromRewardAddress(pp.rewardAccount(), poolHash);
                    margin = pp.margin();
                    fixedCost = pp.cost();
                    pledge = pp.pledge();
                    // Convert owner hashes to "credType:credHash" format to match delegator stakeAddress.
                    // cf-rewards checks poolOwnerStakeAddresses.contains(stakeAddress) to exclude
                    // pool owners from member rewards — formats must match.
                    if (pp.owners() != null) {
                        for (String ownerHash : pp.owners()) {
                            owners.add("0:" + ownerHash); // pool owners are always key hash (credType=0)
                        }
                    }
                } else if (blocks > 0) {
                    log.warn("Pool {} produced {} blocks but has no registration params", poolHash, blocks);
                }
            }

            // Calculate owner active stake: sum of delegator stakes whose credKey is in owners set.
            // Both owners and stakeAddress use "credType:credHash" format.
            BigInteger ownerActiveStake = BigInteger.ZERO;
            for (Delegator d : poolEntry.getValue()) {
                if (owners.contains(d.getStakeAddress())) {
                    ownerActiveStake = ownerActiveStake.add(d.getActiveStake());
                }
            }

            if (blocks > 0) {
                log.debug("buildPoolState: pool={} blocks={} margin={} cost={} pledge={} rewardAddr={} owners={} ownerStake={}",
                        poolHash, blocks, margin, fixedCost, pledge, rewardAddress, owners.size(), ownerActiveStake);
            }
            states.add(PoolState.builder()
                    .poolId(poolHash)
                    .blockCount(blocks)
                    .activeStake(poolActiveStake.getOrDefault(poolHash, BigInteger.ZERO))
                    .delegators(new HashSet<>(poolEntry.getValue()))
                    .epoch(epoch)
                    .rewardAddress(rewardAddress)
                    .owners(new HashSet<>(owners))
                    .ownerActiveStake(ownerActiveStake)
                    .poolFees(BigInteger.ZERO)
                    .margin(margin)
                    .fixedCost(fixedCost)
                    .pledge(pledge)
                    .build());
        }
        return states;
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
            String poolId = poolResult.getPoolId();

            // Leader reward
            BigInteger leaderReward = poolResult.getOperatorReward();
            if (leaderReward != null && leaderReward.signum() > 0) {
                String rewardAddr = poolResult.getRewardAddress();
                if (rewardAddr != null) {
                    try {
                        creditRewardByAddress(rewardAddr, leaderReward, earnedEpoch, RewardType.LEADER, poolId);
                        leaderCount++;
                    } catch (RocksDBException e) {
                        log.warn("Failed to credit leader reward for pool {}: {}", poolId, e.getMessage());
                    }
                }
            }

            // Member rewards
            var memberRewards = poolResult.getMemberRewards();
            if (memberRewards != null) {
                for (var reward : memberRewards) {
                    if (reward.getAmount() == null || reward.getAmount().signum() <= 0) continue;
                    try {
                        creditRewardByAddress(reward.getStakeAddress(), reward.getAmount(),
                                earnedEpoch, RewardType.MEMBER, poolId);
                        memberCount++;
                    } catch (RocksDBException e) {
                        log.warn("Failed to credit member reward: {}", e.getMessage());
                    }
                }
            }
        }

        log.info("Distributed rewards for epoch {}: {} leader, {} member", epoch, leaderCount, memberCount);
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
                deposit = BigInteger.valueOf(500_000_000); // default pool deposit
            }

            try {
                creditReward(credType, credHash, deposit, epoch,
                        com.bloxbean.cardano.yano.api.account.RewardType.LEADER, pool.poolHash());
                totalRefunded = totalRefunded.add(deposit);
                log.info("Pool {} deposit refund {} credited to {} at epoch {}",
                        pool.poolHash(), deposit, credKey, epoch);
            } catch (RocksDBException e) {
                log.warn("Failed to credit pool deposit refund for {}: {}", pool.poolHash(), e.getMessage());
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

        var registeredNow = ledgerStateProvider != null
                ? ledgerStateProvider.getAllRegisteredCredentials()
                : Set.<String>of();

        // Check if event-based queries are available
        boolean hasEventQueries = ledgerStateProvider != null && accountStateStore != null;

        if (!hasEventQueries) {
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

        if (postBabbage) {
            // Post-Babbage: all deregistrations from snapshot epoch to epoch boundary
            deregistered = new HashSet<>(
                    ledgerStateProvider.getDeregisteredAccountsInSlotRange(deregScanStartSlot, feeEpochEndSlot));
            lateDeregistered = new HashSet<>();
            deregisteredOnBoundary = new HashSet<>(deregistered);
        } else {
            // Pre-Babbage: split by stability window
            deregistered = new HashSet<>(
                    ledgerStateProvider.getDeregisteredAccountsInSlotRange(deregScanStartSlot, stabilityWindowSlot));
            deregisteredOnBoundary = new HashSet<>(
                    ledgerStateProvider.getDeregisteredAccountsInSlotRange(deregScanStartSlot, feeEpochEndSlot));
            lateDeregistered = new HashSet<>(deregisteredOnBoundary);
            lateDeregistered.removeAll(deregistered);
        }

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

        // Fallback: credentials in snapshot or pool reward addresses that are not currently
        // registered but were not caught by the event scan (e.g., deregistered before the
        // event retention window, or genesis accounts without events).
        // This matches yaci-store's behavior of checking all history up to the epoch boundary.
        for (String credKey : stakeSnapshot.keySet()) {
            if (!registeredNow.contains(credKey)
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
        byte[] epochPrefix = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch).array();

        try (var it = db.newIterator(cfEpochSnapshot)) {
            it.seek(epochPrefix);
            while (it.isValid()) {
                byte[] key = it.key();
                if (key.length < 5) break;
                int keyEpoch = ByteBuffer.wrap(key, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                if (keyEpoch != epoch) break;

                int credType = key[4] & 0xFF;
                String credHash = HexUtil.encodeHexString(Arrays.copyOfRange(key, 5, key.length));
                snapshot.put(credType + ":" + credHash,
                        AccountStateCborCodec.decodeEpochDelegSnapshot(it.value()));
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
    public Optional<com.bloxbean.cardano.yano.api.account.LedgerStateProvider.PoolParams> getPoolParams(String poolHash) {
        if (ledgerStateProvider == null) return Optional.empty();
        return ledgerStateProvider.getPoolParams(poolHash);
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
