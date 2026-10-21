package org.yanoproject.ledgerstate;

import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.account.LedgerStateProvider.DepositObligations;
import org.yanoproject.ledgerstate.UtxoBalanceAggregator;
import org.yanoproject.api.archive.EpochArchiveStagingSink;
import org.yanoproject.ledgerstate.governance.epoch.GovernanceEpochProcessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.rewards.calculation.domain.EpochCalculationResult;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Orchestrates epoch boundary processing across the three-phase epoch transition sequence
 * that mirrors the Cardano ledger spec's EPOCH rule (shelley-ledger.pdf §17.4):
 *
 * <ol>
 *   <li>{@link #processEpochBoundary} — Reward calculation, AdaPot update, param finalization
 *       (called from {@code PreEpochTransitionEvent})</li>
 *   <li><em>SNAP (delegation snapshot)</em> — handled directly by {@code DefaultAccountStateStore}
 *       (called from {@code EpochTransitionEvent})</li>
 *   <li>{@link #processPostEpochBoundary} — <b>POOLREAP</b>: pool deposit refunds
 *       (called from {@code PostEpochTransitionEvent})</li>
 * </ol>
 *
 * <p>Each subsystem is independently enabled/disabled via configuration.</p>
 */
public class EpochBoundaryProcessor {
    private static final Logger log = LoggerFactory.getLogger(EpochBoundaryProcessor.class);

    private final AdaPotTracker adaPotTracker;
    private final EpochRewardCalculator rewardCalculator;
    private final EpochParamTracker paramTracker;
    private final EpochParamProvider paramProvider;
    private final long networkMagic;

    // Optional governance epoch processor (null = disabled, set after construction)
    private volatile GovernanceEpochProcessor governanceEpochProcessor;

    // Snapshot creator — creates the delegation snapshot between rewards and governance
    private volatile DefaultAccountStateStore snapshotCreator;

    // Expected AdaPot values for verification (loaded lazily from classpath JSON)
    private volatile Map<Integer, ExpectedAdaPot> expectedAdaPots;

    private EpochArchiveStagingSink archiveStaging = EpochArchiveStagingSink.NOOP;
    private volatile EpochArchiveStagingSink.Boundary archiveBoundary;

    // Auto-checkpoint: creates a RocksDB checkpoint at epoch boundaries for fast rollback.
    // The callback receives the epoch number and creates the checkpoint externally.
    private volatile java.util.function.IntConsumer autoCheckpointCallback;
    private int autoCheckpointInterval = 0; // 0 = disabled, >0 = every N epochs

    // If true, System.exit(1) on AdaPot verification failure (development mode).
    // If false, log error and record for REST query (production mode).
    private boolean exitOnEpochCalcError = false;

    // Last verification error (null = OK). Queryable via REST endpoint.
    private volatile VerificationError lastVerificationError;

    // Last completed or failed boundary attribution report (ADR-048 Phase 0).
    private volatile EpochBoundaryTelemetry.BoundarySummary lastBoundaryTelemetry;

    // Allegra bootstrap UTXO removal is now self-contained in DefaultUtxoStore.applyBlock().
    // No callback needed — removal happens automatically on the first Allegra-era block.

    // Executor for parallel UTXO balance scan during epoch boundary processing
    private final ExecutorService utxoScanExecutor = Executors.newSingleThreadExecutor(
            r -> { Thread t = new Thread(r, "utxo-balance-scan"); t.setDaemon(true); return t; });

    // Boundary step constants for crash recovery tracking
    public static final int STEP_STARTED = 0;
    public static final int STEP_REWARDS = 1;
    public static final int STEP_SNAPSHOT = 2;
    public static final int STEP_POOLREAP = 3;
    public static final int STEP_GOVERNANCE = 4;
    public static final int STEP_COMPLETE = 5;

    public record VerificationError(int epoch, java.math.BigInteger expectedTreasury,
                                     java.math.BigInteger actualTreasury, java.math.BigInteger treasuryDiff,
                                     java.math.BigInteger expectedReserves, java.math.BigInteger actualReserves,
                                     java.math.BigInteger reservesDiff) {}

    // CF NetworkConfig — injected at construction or lazily after boundary capture for unknown+Byron networks
    private volatile org.cardanofoundation.rewards.calculation.config.NetworkConfig cfNetworkConfig;

    public EpochBoundaryProcessor(AdaPotTracker adaPotTracker,
                                  EpochRewardCalculator rewardCalculator,
                                  EpochParamTracker paramTracker,
                                  EpochParamProvider paramProvider,
                                  long networkMagic,
                                  org.cardanofoundation.rewards.calculation.config.NetworkConfig cfNetworkConfig) {
        this.adaPotTracker = adaPotTracker;
        this.rewardCalculator = rewardCalculator;
        this.paramTracker = paramTracker;
        this.paramProvider = paramProvider;
        this.networkMagic = networkMagic;
        this.cfNetworkConfig = cfNetworkConfig;
    }

    /**
     * Update the CF NetworkConfig after lazy construction (for unknown+Byron fresh sync).
     */
    public void setCfNetworkConfig(org.cardanofoundation.rewards.calculation.config.NetworkConfig config) {
        this.cfNetworkConfig = config;
    }


    /** @return the governance epoch processor, or {@code null} when governance is not processed */
    public GovernanceEpochProcessor getGovernanceEpochProcessor() {
        return governanceEpochProcessor;
    }

    /**
     * @return true when the reward/refund processor POOLREAP needs is available; without it a
     *         boundary that retires a pool fails
     */
    boolean isPoolReapRefundProcessorEnabled() {
        return rewardCalculator != null && rewardCalculator.isEnabled();
    }

    /** @return true once the rewards network configuration is known (boundaries are deferred or fail before) */
    boolean isNetworkConfigAvailable() {
        return cfNetworkConfig != null;
    }

    /**
     * Set the governance epoch processor for Conway-era governance state tracking.
     */
    public void setGovernanceEpochProcessor(GovernanceEpochProcessor processor) {
        this.governanceEpochProcessor = processor;
        if (processor != null) {
            processor.setEpochArchiveStagingSink(archiveStaging);
            if (archiveBoundary != null) processor.setBoundaryCoordinates(archiveBoundary);
        }
        // The PV 10 DRep delegation rebuild runs inside governance Phase 1, after the hard fork is enacted
        if (processor != null && snapshotCreator != null) {
            processor.setHardForkDRepDelegationRebuilder(snapshotCreator::rebuildDRepDelegReverseIndexIfNeeded);
        }
        // Wire AdaPot batch adjuster so governance treasury adjustment is atomic with Phase 2
        if (processor != null && adaPotTracker != null && adaPotTracker.isEnabled() && snapshotCreator != null) {
            processor.setAdaPotBatchAdjuster((epoch, treasuryDelta, batch, deltaOps) -> {
                var currentPot = adaPotTracker.getAdaPot(epoch);
                if (currentPot.isPresent()) {
                    var pot = currentPot.get();
                    BigInteger adjustedTreasury = pot.treasury().add(treasuryDelta);
                    var adjustedPot = new AccountStateCborCodec.AdaPot(adjustedTreasury, pot.reserves(),
                            pot.deposits(), pot.fees(), pot.distributed(),
                            pot.undistributed(), pot.rewardsPot(), pot.poolRewardsPot(),
                            pot.depositObligations());
                    adaPotTracker.storeAdaPotBatch(epoch, adjustedPot, batch, deltaOps, snapshotCreator);
                }
            });
        }
    }

    /**
     * Set the snapshot creator for creating delegation snapshots between rewards and governance.
     */
    public void setSnapshotCreator(DefaultAccountStateStore store) {
        this.snapshotCreator = store;
    }

    /**
     * Enable automatic RocksDB checkpoint creation at epoch boundaries.
     * Checkpoints are fast (hard-linked) and enable quick rollback for debugging.
     *
     * @param interval create checkpoint every N epochs (0 = disabled)
     * @param callback receives the epoch number; creates the actual checkpoint externally
     */
    public void setAutoCheckpoint(int interval, java.util.function.IntConsumer callback) {
        this.autoCheckpointInterval = interval;
        this.autoCheckpointCallback = callback;
    }

    /**
     * If true, System.exit(1) on AdaPot verification failure (useful during development).
     * If false (default), log the error and continue syncing.
     */
    public void setExitOnEpochCalcError(boolean flag) {
        this.exitOnEpochCalcError = flag;
    }

    /**
     * Returns the last AdaPot verification error, or null if all verifications passed.
     */
    public VerificationError getLastVerificationError() {
        return lastVerificationError;
    }

    public EpochBoundaryTelemetry.BoundarySummary getLastBoundaryTelemetry() {
        return lastBoundaryTelemetry;
    }

    public void setEpochArchiveStagingSink(EpochArchiveStagingSink sink) {
        this.archiveStaging = sink != null ? sink : EpochArchiveStagingSink.NOOP;
        if (rewardCalculator != null) {
            rewardCalculator.setEpochArchiveStagingSink(this.archiveStaging);
        }
        if (governanceEpochProcessor != null) {
            governanceEpochProcessor.setEpochArchiveStagingSink(this.archiveStaging);
        }
    }

    public void setBoundaryCoordinates(EpochArchiveStagingSink.Boundary boundary) {
        this.archiveBoundary = boundary;
        if (governanceEpochProcessor != null) governanceEpochProcessor.setBoundaryCoordinates(boundary);
        if (rewardCalculator != null) rewardCalculator.setArchiveBoundary(boundary);
    }

    /**
     * Refresh nested RocksDB-backed processors after snapshot restore.
     */
    public void reinitializeAfterSnapshotRestore(RocksDB db, ColumnFamilyHandle cfState,
                                                 ColumnFamilyHandle cfDelta,
                                                 ColumnFamilyHandle cfEpochSnapshot) {
        if (governanceEpochProcessor != null) {
            governanceEpochProcessor.reinitialize(db, cfState, cfDelta, cfEpochSnapshot);
        }
        log.info("EpochBoundaryProcessor reinitialized after snapshot restore");
    }

    /**
     * Check for and recover an interrupted epoch boundary from a previous run.
     * Called at startup before syncing to ensure no incomplete boundaries are left behind.
     * Also repairs missed PostEpochTransition: auto-checkpoints are taken after
     * processEpochBoundary (STEP_COMPLETE) but before PostEpochTransition credits
     * reward_rest to accounts. On restart from such a checkpoint, the reward_rest
     * entries remain uncredited. This method detects and credits them.
     */
    public void recoverInterruptedBoundary() {
        if (snapshotCreator == null) return;
        int[] lastState = snapshotCreator.getLastBoundaryState();
        PoolReapProcessor.Progress poolReapProgress =
                snapshotCreator.getPoolReapProgress();
        if (lastState == null) {
            if (poolReapProgress != null) {
                throw new IllegalStateException("Unfinished POOLREAP for epoch "
                        + poolReapProgress.epoch()
                        + " has no boundary-step state; startup cannot continue");
            }
            return;
        }
        int epoch = lastState[0];
        int step = lastState[1];
        if (poolReapProgress != null) {
            long expectedBoundarySlot = snapshotCreator.slotForEpochStart(epoch);
            if (poolReapProgress.epoch() != epoch
                    || poolReapProgress.boundarySlot() != expectedBoundarySlot
                    || step != STEP_SNAPSHOT) {
                throw new IllegalStateException("Unfinished POOLREAP does not match durable "
                        + "boundary state for epoch " + epoch + " at step " + step);
            }
            snapshotCreator.isPoolReapInProgress(expectedBoundarySlot);
        }
        if (step >= STEP_STARTED && step < STEP_COMPLETE) {
            log.info("Recovering interrupted epoch boundary for epoch {} (stopped at step {})", epoch, step);
            restorePersistedBoundaryCoordinates(epoch);
            processEpochBoundary(epoch - 1, epoch);
        }
        if (snapshotCreator.getPoolReapProgress() != null) {
            throw new IllegalStateException("POOLREAP recovery for epoch " + epoch
                    + " did not clear its progress marker");
        }

        // Repair missed PostEpochTransition: credit any uncredited reward_rest entries
        // from a completed boundary whose PostEpochTransition was not replayed (e.g.,
        // restart from an auto-checkpoint taken between STEP_COMPLETE and PostEpochTransition).
        snapshotCreator.creditPendingRewardRest();
    }

    private void restorePersistedBoundaryCoordinates(int epoch) {
        EpochArchiveStagingSink.Boundary boundary = snapshotCreator.getBoundaryCoordinates(epoch)
                .orElseThrow(() -> new IllegalStateException(
                        "Interrupted boundary " + epoch
                                + " has no persisted coordinates; resync is required"));
        if (boundary.previousEpoch() != epoch - 1) {
            throw new IllegalStateException(
                    "Persisted boundary coordinates do not match epoch " + epoch);
        }
        snapshotCreator.restoreBoundaryCoordinates(boundary);
        setBoundaryCoordinates(boundary);
    }

    public void processEpochBoundary(int previousEpoch, int newEpoch) {
        // Guard: defer ALL boundary work until cfNetworkConfig is available.
        // For unknown+Byron fresh sync, config is built lazily after boundary UTXO capture.
        // Byron epochs don't have Shelley rewards/AdaPot, so deferring is safe.
        if (cfNetworkConfig == null) {
            int shelleyStartEpoch = shelleyStartEpochFromParams();
            if (newEpoch <= shelleyStartEpoch) {
                log.info("cfNetworkConfig not yet available — deferring pre-Shelley epoch boundary for {} → {}",
                        previousEpoch, newEpoch);
                return;
            }
            throw new IllegalStateException("cfNetworkConfig not available at Shelley+ epoch boundary "
                    + previousEpoch + " -> " + newEpoch);
        }

        var telemetry = EpochBoundaryTelemetry.start(log, previousEpoch, newEpoch, snapshotCreator);
        boolean completed = false;
        try {
            long start = System.currentTimeMillis();

        // Check that the previous epoch boundary completed. If not, re-process it first.
        if (snapshotCreator != null && newEpoch >= 3) {
            int[] lastState = snapshotCreator.getLastBoundaryState();
            if (lastState != null && lastState[0] == newEpoch - 1 && lastState[1] < STEP_COMPLETE) {
                log.warn("Previous boundary for epoch {} was incomplete (step {}), re-processing first",
                        lastState[0], lastState[1]);
                EpochArchiveStagingSink.Boundary currentBoundary = archiveBoundary;
                restorePersistedBoundaryCoordinates(newEpoch - 1);
                processEpochBoundary(newEpoch - 2, newEpoch - 1);
                if (currentBoundary != null) {
                    snapshotCreator.restoreBoundaryCoordinates(currentBoundary);
                    setBoundaryCoordinates(currentBoundary);
                }
            }
        }

        // Check for interrupted boundary for THIS epoch and resume if needed
        int resumeFromStep = STEP_STARTED;
        if (snapshotCreator != null) {
            int lastStep = snapshotCreator.getBoundaryStep(newEpoch);
            if (lastStep >= STEP_STARTED && lastStep < STEP_COMPLETE) {
                resumeFromStep = lastStep + 1;
                log.info("Resuming epoch boundary {} → {} from step {} (previous run interrupted after step {})",
                        previousEpoch, newEpoch, resumeFromStep, lastStep);
            }

            // Consult boundary delta evidence for committed but unmarkered phases.
            // If a crash happened between phase commit and step marker write, the boundary
            // delta journal proves the phase was committed — skip it to avoid double-apply.
            if (resumeFromStep <= STEP_GOVERNANCE) {
                long boundarySlot = snapshotCreator.slotForEpochStart(newEpoch);
                var committedPhases = snapshotCreator.getCommittedBoundaryPhases(boundarySlot);
                if (!committedPhases.isEmpty()) {
                    int deltaResumeFrom = resumeFromStep;
                    if (committedPhases.contains(DefaultAccountStateStore.PHASE_GOV_RATIFY)) {
                        deltaResumeFrom = Math.max(deltaResumeFrom, STEP_GOVERNANCE + 1);
                        log.info("Boundary delta evidence: governance committed for epoch {}", newEpoch);
                    } else if (committedPhases.contains(DefaultAccountStateStore.PHASE_POOLREAP)) {
                        deltaResumeFrom = Math.max(deltaResumeFrom, STEP_POOLREAP + 1);
                        log.info("Boundary delta evidence: pool refund committed for epoch {}", newEpoch);
                    } else if (committedPhases.contains(DefaultAccountStateStore.PHASE_REWARDS)) {
                        deltaResumeFrom = Math.max(deltaResumeFrom, STEP_REWARDS + 1);
                        log.info("Boundary delta evidence: rewards committed for epoch {}", newEpoch);
                    }
                    // Repair step marker forward to match actual durable state
                    if (deltaResumeFrom > resumeFromStep) {
                        int resolvedStep = deltaResumeFrom - 1;
                        snapshotCreator.setBoundaryStep(newEpoch, resolvedStep);
                        resumeFromStep = deltaResumeFrom;
                    }
                }
            }
        }

        if (resumeFromStep <= STEP_STARTED) {
            log.info("Processing epoch boundary: {} → {}", previousEpoch, newEpoch);
        }

        // Mark boundary as started
        if (snapshotCreator != null && resumeFromStep <= STEP_STARTED) {
            EpochArchiveStagingSink.Boundary boundary = archiveBoundary;
            if (boundary == null || boundary.previousEpoch() != previousEpoch
                    || boundary.newEpoch() != newEpoch) {
                throw new IllegalStateException("Exact boundary coordinates are unavailable for "
                        + previousEpoch + " -> " + newEpoch);
            }
            snapshotCreator.setBoundaryStarted(boundary);
        }

        boolean orderedStakeIndex = snapshotCreator != null
                && resumeFromStep <= STEP_SNAPSHOT
                && snapshotCreator.probeOrderedStakeBalanceIndex(previousEpoch);

        // 1. Finalize protocol parameters for the new epoch
        try (var ignored = telemetry.phase("params")) {
            if (paramTracker != null && paramTracker.isEnabled()) {
                paramTracker.finalizeEpoch(newEpoch);
            }

            // Log effective params for verification against yaci-store epoch_param
            EpochParamProvider effectiveParams = (paramTracker != null && paramTracker.isEnabled())
                    ? paramTracker : paramProvider;
            log.info("Epoch {} params: protoVer={}.{}, d={}, nOpt={}, rho={}, tau={}, a0={}, minPoolCost={}",
                    newEpoch, effectiveParams.getProtocolMajor(newEpoch), effectiveParams.getProtocolMinor(newEpoch),
                    effectiveParams.getDecentralization(newEpoch), effectiveParams.getNOpt(newEpoch),
                    effectiveParams.getRho(newEpoch), effectiveParams.getTau(newEpoch),
                    effectiveParams.getA0(newEpoch), effectiveParams.getMinPoolCost(newEpoch));
        }

        // 2. Bootstrap AdaPot at the Shelley start epoch (before any reward calculation)
        try (var ignored = telemetry.phase("adapot-bootstrap")) {
            bootstrapAdaPotIfNeeded(newEpoch);
        }

        // 2a. Allegra bootstrap UTXO removal is now self-contained in
        // DefaultUtxoStore.applyBlock() — triggered automatically when era >= Allegra.

        // 2b. Credit spendable MIR reward_rest to account balances BEFORE reward calculation.
        try (var ignored = telemetry.phase("mir-credit")) {
            if (snapshotCreator != null && resumeFromStep <= STEP_STARTED) {
                snapshotCreator.creditMirRewardRest(newEpoch);
            }
        }

        // Start UTXO balance scan in parallel (read-only, independent of reward calc).
        boolean pointerOverlayRequired = orderedStakeIndex
                && snapshotCreator != null
                && snapshotCreator.requiresPointerStakeOverlay(previousEpoch);
        Future<DefaultAccountStateStore.BoundaryStakeInput> utxoBalancesFuture = null;
        try (var ignored = telemetry.phase("snapshot-input-start",
                orderedStakeIndex
                        ? (pointerOverlayRequired ? "stake-index+pointer-auto" : "stake-index")
                        : "utxo-scan")) {
            if (snapshotCreator != null && resumeFromStep <= STEP_SNAPSHOT
                    && (!orderedStakeIndex || pointerOverlayRequired)) {
                final int snapshotEpoch = previousEpoch;
                utxoBalancesFuture = utxoScanExecutor.submit(() -> orderedStakeIndex
                        ? snapshotCreator.aggregatePointerUtxoBalances(snapshotEpoch)
                        : new DefaultAccountStateStore.BoundaryStakeInput(
                                snapshotCreator.aggregateUtxoBalances(snapshotEpoch),
                                null,
                                "utxo-scan"));
            }
        }

        Map<UtxoBalanceAggregator.CredentialKey, BigInteger> utxoBalances = null;
        DefaultAccountStateStore.BoundaryStakeInput boundaryStakeInput = null;
        try {
            // 3. Calculate rewards (skip if already committed from a previous interrupted run)
            try (var ignored = telemetry.phase("rewards",
                    shouldCalculateRewards(newEpoch)
                            ? rewardCalculator.executionModeForEpoch(newEpoch) + "-reward" : "disabled")) {
                if (resumeFromStep <= STEP_REWARDS) {
                    if (shouldCalculateRewards(newEpoch)) {
                        calculateAndStoreRewards(previousEpoch, newEpoch, null);
                    }
                    // Step marker AFTER all outputs (reward credits + AdaPot) are durable
                    if (snapshotCreator != null) {
                        snapshotCreator.setBoundaryStep(newEpoch, STEP_REWARDS);
                    }
                } else {
                    log.info("Skipping reward calc for epoch {} (already committed in previous run)", newEpoch);
                }
            }

            // 4. SNAP: Wait for parallel UTXO input, then create delegation snapshot.
            Map<UtxoBalanceAggregator.CredentialKey, BigInteger> precomputedBalances = null;
            if (utxoBalancesFuture != null) {
                try (var ignored = telemetry.phase("snapshot-input-wait", "parallel")) {
                    try {
                        boundaryStakeInput = utxoBalancesFuture.get();
                        precomputedBalances = boundaryStakeInput.balances();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "Interrupted while awaiting the epoch UTXO input", e);
                    } catch (ExecutionException e) {
                        throw new IllegalStateException(
                                "Epoch UTXO input failed", e.getCause());
                    }
                }
            }
            String snapshotWritePath = orderedStakeIndex
                    ? (pointerOverlayRequired
                            ? "stake-index+" + (boundaryStakeInput != null
                                    ? boundaryStakeInput.path()
                                    : snapshotCreator.lastPointerInputPath())
                            : "stake-index")
                    : "utxo-scan";
            try (var ignored = telemetry.phase("snapshot-write", snapshotWritePath)) {
                if (resumeFromStep <= STEP_SNAPSHOT) {
                    if (snapshotCreator != null) {
                        utxoBalances = snapshotCreator.createAndCommitDelegationSnapshot(
                                previousEpoch, precomputedBalances, orderedStakeIndex,
                                boundaryStakeInput != null
                                        ? boundaryStakeInput.stakeBalanceView()
                                        : null);
                    }
                    if (snapshotCreator != null) {
                        snapshotCreator.setBoundaryStep(newEpoch, STEP_SNAPSHOT);
                    }
                } else {
                    log.info("Skipping snapshot for epoch {} (already committed in previous run)", previousEpoch);
                }
            }
        } finally {
            closeBoundaryStakeInput(utxoBalancesFuture, boundaryStakeInput);
        }

        // 4b. POOLREAP: exact pool refunds plus bounded live-state cleanup
        //     (after snapshot, before governance). The store-owned seam uses one
        //     deterministic plan for the monetary and lifecycle mutations.
        try (var ignored = telemetry.phase("pool-reap")) {
            if (resumeFromStep <= STEP_POOLREAP) {
                if (snapshotCreator != null) {
                    long boundarySlot = snapshotCreator.slotForEpochStart(newEpoch);
                    snapshotCreator.processPoolReap(newEpoch, boundarySlot,
                            rewardCalculator);
                }
                // Step marker AFTER all refund and live cleanup chunks are durable.
                if (snapshotCreator != null) {
                    snapshotCreator.setBoundaryStep(newEpoch, STEP_POOLREAP);
                }
            } else {
                log.info("Skipping POOLREAP for epoch {} (already committed in previous run)", newEpoch);
            }
        }

        // 5. Conway governance epoch processing (ratify, enact, expire, refund)
        // reward_rest from previous boundaries is already credited to PREFIX_ACCT.reward
        // in PostEpochTransition, so DRep distribution picks it up from account balances.
        try (var ignored = telemetry.phase("governance")) {
            GovernanceEpochProcessor.GovernanceEpochResult govResult = null;
            if (resumeFromStep <= STEP_GOVERNANCE) {
                if (governanceEpochProcessor != null) {
                    try {
                        govResult = governanceEpochProcessor.processEpochBoundaryAndCommit(
                                previousEpoch, newEpoch,
                                orderedStakeIndex ? null : utxoBalances, null);
                    } catch (Exception e) {
                        log.error("Governance epoch processing failed for {} → {}: {}",
                                previousEpoch, newEpoch, e.getMessage(), e);
                        throw new RuntimeException("Governance epoch processing failed for "
                                + previousEpoch + " -> " + newEpoch, e);
                    }
                }
            } else {
                log.info("Skipping governance for epoch {} (already committed in previous run)", newEpoch);
            }

            // 6. Governance treasury delta to AdaPot is now applied atomically inside
            //    GovernanceEpochProcessor Phase 2 batch (via AdaPotBatchAdjuster).

            // Step marker AFTER governance commits (including AdaPot adjustment) are all durable
            if (resumeFromStep <= STEP_GOVERNANCE && snapshotCreator != null) {
                snapshotCreator.setBoundaryStep(newEpoch, STEP_GOVERNANCE);
            }
        }

        // 7. Finalise the deposit pot, then verify the final AdaPot (after both reward calculation and
        //    governance adjustment)
        try (var ignored = telemetry.phase("artifact-finalize")) {
            Optional<AccountStateCborCodec.AdaPot> finalPot = Optional.empty();
            if (adaPotTracker != null && adaPotTracker.isEnabled()) {
                finalPot = adaPotTracker.getAdaPot(newEpoch).map(pot -> finalizeDeposits(newEpoch, pot));
            }
            if (finalPot.isPresent() && newEpoch >= 2) {
                var p = finalPot.get();
                verifyAdaPot(newEpoch, p.treasury(), p.reserves());

                // ADR-039: record the artifact against the same final value the legacy staging
                // path below writes, so both pipelines describe the identical pot.
                if (snapshotCreator != null) {
                    snapshotCreator.contributeAdaPotArtifact(newEpoch, p);
                }

                if (archiveStaging.enabled(EpochArchiveStagingSink.Dataset.ADA_POT)) {
                    try (var writer = archiveStaging.openAdaPot(newEpoch)) {
                        writer.append(new EpochArchiveStagingSink.AdaPotFact(
                                p.treasury(), p.reserves(), p.deposits(), p.fees(),
                                p.distributed(), p.undistributed(), p.rewardsPot(), p.poolRewardsPot()));
                        writer.commit();
                    }
                }
            }
        }

        // Mark boundary as fully complete
        try (var ignored = telemetry.phase("complete")) {
            if (snapshotCreator != null) {
                snapshotCreator.setBoundaryStep(newEpoch, STEP_COMPLETE);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Epoch boundary processing complete ({} → {}) in {}ms", previousEpoch, newEpoch, elapsed);

        // 8. Auto-checkpoint: create RocksDB checkpoint at epoch boundary for fast rollback
        if (autoCheckpointInterval > 0 && autoCheckpointCallback != null
                && newEpoch % autoCheckpointInterval == 0) {
            try {
                autoCheckpointCallback.accept(newEpoch);
            } catch (Exception e) {
                log.warn("Auto-checkpoint failed for epoch {}: {}", newEpoch, e.getMessage());
            }
        }
            completed = true;
        } finally {
            lastBoundaryTelemetry = telemetry.finish(completed);
        }
    }

    /**
     * Process post-epoch boundary.
     * POOLREAP is now done in processEpochBoundary (after snapshot, before governance)
     * matching the Haskell/Amaru order: Snapshot → POOLREAP → Governance.
     */
    public void processPostEpochBoundary(int newEpoch) {
        // POOLREAP moved to processEpochBoundary step 4b
    }

    private static void closeBoundaryStakeInput(
            Future<DefaultAccountStateStore.BoundaryStakeInput> future,
            DefaultAccountStateStore.BoundaryStakeInput acquired) {
        if (acquired != null) {
            acquired.close();
            return;
        }
        if (future == null) return;

        // A reward failure can occur while the read task is still producing a
        // coordinate-bound RocksDB view. Await it so that view cannot leak.
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    DefaultAccountStateStore.BoundaryStakeInput pending = future.get();
                    if (pending != null) pending.close();
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                } catch (ExecutionException | CancellationException ignored) {
                    return;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Run reward calculation and update AdaPot with the results.
     * Called BEFORE governance — governance treasury delta is applied as post-reward adjustment.
     */
    private void calculateAndStoreRewards(int previousEpoch, int newEpoch,
                                          GovernanceEpochProcessor.GovernanceEpochResult govResult) {
        // Get previous AdaPot
        BigInteger prevTreasury = BigInteger.ZERO;
        BigInteger prevReserves = BigInteger.ZERO;
        // Placeholder until step 7 finalises the deposit pot from the post-POOLREAP, post-governance state
        BigInteger previousDeposits = BigInteger.ZERO;

        if (adaPotTracker != null && adaPotTracker.isEnabled()) {
            var prevPot = adaPotTracker.getAdaPot(previousEpoch);
            if (prevPot.isEmpty()) {
                prevPot = adaPotTracker.getLatestAdaPot(previousEpoch);
                if (prevPot.isPresent()) {
                    log.info("Using latest available AdaPot (not epoch {}) as previous", previousEpoch);
                }
            }
            if (prevPot.isPresent()) {
                prevTreasury = prevPot.get().treasury();
                prevReserves = prevPot.get().reserves();
                previousDeposits = prevPot.get().deposits();
            } else {
                throw new IllegalStateException("No AdaPot found for previous epoch " + previousEpoch
                        + "; reward calculation cannot proceed with zero treasury/reserves");
            }
        }

        // Resolve param provider (prefer tracker if available)
        EpochParamProvider effectiveParams = (paramTracker != null && paramTracker.isEnabled())
                ? paramTracker : paramProvider;

        // Calculate and distribute rewards (delta-aware batch for rollback safety)
        rewardCalculator.beginRewardBatch(newEpoch, "rewards");
        Optional<EpochCalculationResult> resultOpt = rewardCalculator.calculateAndDistribute(
                newEpoch, prevTreasury, prevReserves, effectiveParams, networkMagic);
        if (resultOpt.isEmpty()) {
            handleEpochCalcError(newEpoch, "Reward calculation returned no result for epoch " + newEpoch);
        }
        try {
            // Include AdaPot in the same atomic batch as reward credits + boundary delta.
            // This ensures crash between commit and step marker doesn't lose AdaPot.
            if (resultOpt.isPresent() && adaPotTracker != null && adaPotTracker.isEnabled()
                    && snapshotCreator != null) {
                var result = resultOpt.get();
                var newPot = new AccountStateCborCodec.AdaPot(
                        result.getTreasury(),
                        result.getReserves(),
                        previousDeposits,
                        rewardCalculator.getEpochFees(newEpoch - 1),
                        result.getTotalDistributedRewards(),
                        result.getTotalUndistributedRewards() != null
                                ? result.getTotalUndistributedRewards() : BigInteger.ZERO,
                        result.getTotalRewardsPot() != null
                                ? result.getTotalRewardsPot() : BigInteger.ZERO,
                        result.getTotalPoolRewardsPot() != null
                                ? result.getTotalPoolRewardsPot() : BigInteger.ZERO
                );
                adaPotTracker.storeAdaPotBatch(newEpoch, newPot,
                        rewardCalculator.getRewardBatch(), rewardCalculator.getRewardDeltaOps(),
                        snapshotCreator);
            }
            long boundarySlot = snapshotCreator.slotForEpochStart(newEpoch);
            rewardCalculator.commitRewardBatch(boundarySlot, DefaultAccountStateStore.PHASE_REWARDS);
        } catch (org.rocksdb.RocksDBException e) {
            throw new RuntimeException("Failed to commit reward boundary delta for epoch " + newEpoch, e);
        }
    }

    /**
     * Set the epoch's deposit pot to Haskell's {@code totalObligation} of the state after this boundary (ADR-058):
     * POOLREAP and governance have committed, and no block of the new epoch has been applied yet, so the
     * obligations are those {@code EPOCH} writes into {@code utxosDeposited} and db-sync records for the epoch.
     * <p>
     * Every earlier phase has committed its own batch, so there is no pending batch here, and this write is not
     * journaled. Rollback still removes it: the rewards phase journaled this key with its pre-boundary value
     * (absent), which undoing the boundary restores, and {@code rollbackInternal} deletes every AdaPot beyond the
     * rollback target's epoch. Recomputing it on a resume reads the same committed state.
     * <p>
     * The exception is {@link #processEpochBoundary}'s "re-processing first" path: an incomplete earlier boundary
     * re-processed after an epoch of blocks reads that later state here, like the other steps it re-runs.
     */
    private AccountStateCborCodec.AdaPot finalizeDeposits(int epoch, AccountStateCborCodec.AdaPot pot) {
        if (snapshotCreator == null) return pot;
        var obligations = snapshotCreator.depositObligations();
        var finalPot = pot.withDepositObligations(obligations);
        adaPotTracker.storeAdaPot(epoch, finalPot);
        log.info("AdaPot deposits for epoch {}: total={}, stakeKeys={}, pools={}, dreps={}, proposals={}",
                epoch, obligations.total(), obligations.stakeKeys(), obligations.pools(), obligations.dreps(),
                obligations.proposals());
        return finalPot;
    }

    private boolean shouldCalculateRewards(int newEpoch) {
        if (rewardCalculator == null || !rewardCalculator.isEnabled() || cfNetworkConfig == null) {
            return false;
        }
        return newEpoch > cfNetworkConfig.getShelleyStartEpoch();
    }

    private int shelleyStartEpochFromParams() {
        if (paramProvider == null) return -1;
        return paramProvider.getEpochSlotCalc().slotToEpoch(paramProvider.getShelleyStartSlot());
    }

    private void handleEpochCalcError(int epoch, String message) {
        log.error(message);
        if (exitOnEpochCalcError) {
            log.error("Exiting (exit-on-epoch-calc-error=true). Debug epoch {} before continuing.", epoch);
            System.exit(1);
            throw new IllegalStateException(message);
        }

        throw new IllegalStateException(message);
    }

    /**
     * Verify calculated AdaPot against expected values from classpath JSON.
     * Only runs when: (1) expected JSON file exists for this network, AND (2) epoch has an entry.
     * Any mismatch (even 1 lovelace) is an error.
     */
    private void verifyAdaPot(int epoch, BigInteger treasury, BigInteger reserves) {
        var expected = getExpectedAdaPots();
        if (expected == null || expected.isEmpty()) return; // no expected file for this network

        var exp = expected.get(epoch);
        if (exp == null) return; // no expected data for this epoch — skip silently

        BigInteger treasuryDiff = treasury.subtract(exp.treasury);
        BigInteger reservesDiff = reserves.subtract(exp.reserves);

        if (treasuryDiff.signum() == 0 && reservesDiff.signum() == 0) {
            log.info("AdaPot verification PASSED for epoch {}", epoch);
            return;
        }

        // Mismatch detected
        log.error("AdaPot verification FAILED for epoch {}! treasuryDiff={}, reservesDiff={}",
                epoch, treasuryDiff, reservesDiff);
        if (treasuryDiff.signum() != 0) {
            log.error("  Treasury: expected={}, actual={}, diff={}", exp.treasury, treasury, treasuryDiff);
        }
        if (reservesDiff.signum() != 0) {
            log.error("  Reserves: expected={}, actual={}, diff={}", exp.reserves, reserves, reservesDiff);
        }

        lastVerificationError = new VerificationError(epoch,
                exp.treasury, treasury, treasuryDiff, exp.reserves, reserves, reservesDiff);

        if (exitOnEpochCalcError) {
            log.error("Exiting (exit-on-epoch-calc-error=true). Debug epoch {} before continuing.", epoch);
            System.exit(1);
        } else {
            log.error("Continuing despite mismatch (exit-on-epoch-calc-error=false). " +
                    "Check /api/v1/node/epoch-calc-status for details.");
        }
    }

    private Map<Integer, ExpectedAdaPot> getExpectedAdaPots() {
        if (expectedAdaPots != null) return expectedAdaPots;

        String filename = switch ((int) networkMagic) {
            case 1 -> "expected_ada_pots_preprod.json";
            case 2 -> "expected_ada_pots_preview.json";
            case 764824073 -> "expected_ada_pots_mainnet.json";
            case 4 -> "expected_ada_pots_sanchonet.json";
            default -> null;
        };

        if (filename == null) {
            expectedAdaPots = Map.of();
            return expectedAdaPots;
        }

        try (var is = getClass().getClassLoader().getResourceAsStream(filename)) {
            if (is == null) {
                log.info("No expected AdaPot file found: {}", filename);
                expectedAdaPots = Map.of();
                return expectedAdaPots;
            }
            var mapper = new ObjectMapper();
            List<Map<String, Object>> pots = mapper.readValue(is, new TypeReference<>() {});
            var map = new ConcurrentHashMap<Integer, ExpectedAdaPot>();
            for (var pot : pots) {
                int epochNo = ((Number) pot.get("epoch_no")).intValue();
                BigInteger t = new BigInteger(pot.get("treasury").toString());
                BigInteger r = new BigInteger(pot.get("reserves").toString());
                map.put(epochNo, new ExpectedAdaPot(t, r));
            }
            expectedAdaPots = map;
            log.info("Loaded {} expected AdaPot entries from {}", map.size(), filename);
        } catch (Exception e) {
            log.warn("Failed to load expected AdaPot file {}: {}", filename, e.getMessage());
            expectedAdaPots = Map.of();
        }
        return expectedAdaPots;
    }

    private record ExpectedAdaPot(BigInteger treasury, BigInteger reserves) {}

    /**
     * Bootstrap AdaPot at the Shelley start epoch using the cf-rewards NetworkConfig
     * initial reserves and treasury values. This must run once before any reward calculation.
     */
    private void bootstrapAdaPotIfNeeded(int newEpoch) {
        if (adaPotTracker == null || !adaPotTracker.isEnabled()) return;

        var networkConfig = cfNetworkConfig;
        if (networkConfig == null) {
            throw new IllegalStateException("cfNetworkConfig not available for AdaPot bootstrap at epoch " + newEpoch);
        }
        int shelleyStartEpoch = networkConfig.getShelleyStartEpoch();
        // For a Shelley-start chain (devnet), the first processed boundary may jump
        // several epochs at once (restart/restore against wall-clock slots), so any
        // boundary can be the bootstrapping one. The no-existing-pot check below keeps
        // this idempotent. Networks with shelleyStartEpoch > 0 bootstrap only at that epoch.
        boolean firstBoundaryAfterGenesisStart = shelleyStartEpoch == 0 && newEpoch >= 1;
        if (newEpoch != shelleyStartEpoch && !firstBoundaryAfterGenesisStart) return;

        // Only bootstrap if no AdaPot exists yet for any epoch
        var existing = adaPotTracker.getLatestAdaPot(newEpoch);
        if (existing.isPresent()) return;

        BigInteger initialReserves = networkConfig.getShelleyInitialReserves();
        BigInteger initialTreasury = networkConfig.getShelleyInitialTreasury();

        if (initialReserves == null) initialReserves = BigInteger.ZERO;
        if (initialTreasury == null) initialTreasury = BigInteger.ZERO;

        // No Shelley certificate precedes the Shelley start, so the pot starts with no deposit obligations
        // (a devnet with genesis staking already has its genesis pot and does not reach here)
        var noDeposits = new DepositObligations(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
        var pot = new AccountStateCborCodec.AdaPot(
                initialTreasury, initialReserves,
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO).withDepositObligations(noDeposits);
        adaPotTracker.storeAdaPot(shelleyStartEpoch, pot);
        log.info("AdaPot bootstrapped at shelley start epoch {}: treasury={}, reserves={}",
                shelleyStartEpoch, initialTreasury, initialReserves);
    }
}
