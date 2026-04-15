package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.ledgerstate.UtxoBalanceAggregator;
import com.bloxbean.cardano.yano.ledgerstate.export.EpochSnapshotExporter;
import com.bloxbean.cardano.yano.ledgerstate.governance.epoch.GovernanceEpochProcessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.rewards.calculation.domain.EpochCalculationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    // Optional epoch snapshot exporter for debugging (NOOP when disabled)
    private EpochSnapshotExporter snapshotExporter = EpochSnapshotExporter.NOOP;

    // Auto-checkpoint: creates a RocksDB checkpoint at epoch boundaries for fast rollback.
    // The callback receives the epoch number and creates the checkpoint externally.
    private volatile java.util.function.IntConsumer autoCheckpointCallback;
    private int autoCheckpointInterval = 0; // 0 = disabled, >0 = every N epochs

    // If true, System.exit(1) on AdaPot verification failure (development mode).
    // If false, log error and record for REST query (production mode).
    private boolean exitOnEpochCalcError = false;

    // Last verification error (null = OK). Queryable via REST endpoint.
    private volatile VerificationError lastVerificationError;

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


    /**
     * Set the governance epoch processor for Conway-era governance state tracking.
     */
    public void setGovernanceEpochProcessor(GovernanceEpochProcessor processor) {
        this.governanceEpochProcessor = processor;
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

    /**
     * Set the epoch snapshot exporter for debugging data export.
     * Propagates to the governance epoch processor if present.
     */
    public void setSnapshotExporter(EpochSnapshotExporter exporter) {
        this.snapshotExporter = exporter != null ? exporter : EpochSnapshotExporter.NOOP;
        if (governanceEpochProcessor != null) {
            governanceEpochProcessor.setSnapshotExporter(this.snapshotExporter);
        }
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
        if (lastState == null) return;
        int epoch = lastState[0];
        int step = lastState[1];
        if (step >= STEP_STARTED && step < STEP_COMPLETE) {
            log.info("Recovering interrupted epoch boundary for epoch {} (stopped at step {})", epoch, step);
            processEpochBoundary(epoch - 1, epoch);
        }

        // Repair missed PostEpochTransition: credit any uncredited reward_rest entries
        // from a completed boundary whose PostEpochTransition was not replayed (e.g.,
        // restart from an auto-checkpoint taken between STEP_COMPLETE and PostEpochTransition).
        snapshotCreator.creditPendingRewardRest();
    }

    public void processEpochBoundary(int previousEpoch, int newEpoch) {
        // Guard: defer ALL boundary work until cfNetworkConfig is available.
        // For unknown+Byron fresh sync, config is built lazily after boundary UTXO capture.
        // Byron epochs don't have Shelley rewards/AdaPot, so deferring is safe.
        if (cfNetworkConfig == null) {
            log.info("cfNetworkConfig not yet available — deferring epoch boundary for {} → {}", previousEpoch, newEpoch);
            return;
        }

        long start = System.currentTimeMillis();

        // Check that the previous epoch boundary completed. If not, re-process it first.
        if (snapshotCreator != null && newEpoch >= 3) {
            int[] lastState = snapshotCreator.getLastBoundaryState();
            if (lastState != null && lastState[0] == newEpoch - 1 && lastState[1] < STEP_COMPLETE) {
                log.warn("Previous boundary for epoch {} was incomplete (step {}), re-processing first",
                        lastState[0], lastState[1]);
                processEpochBoundary(newEpoch - 2, newEpoch - 1);
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
        }

        if (resumeFromStep <= STEP_STARTED) {
            log.info("Processing epoch boundary: {} → {}", previousEpoch, newEpoch);
        }

        // Mark boundary as started
        if (snapshotCreator != null && resumeFromStep <= STEP_STARTED) {
            snapshotCreator.setBoundaryStep(newEpoch, STEP_STARTED);
        }

        // 1. Finalize protocol parameters for the new epoch
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

        // 2. Bootstrap AdaPot at the Shelley start epoch (before any reward calculation)
        bootstrapAdaPotIfNeeded(newEpoch);

        // 2a. Allegra bootstrap UTXO removal is now self-contained in
        // DefaultUtxoStore.applyBlock() — triggered automatically when era >= Allegra.

        // 2b. Credit spendable MIR reward_rest to account balances BEFORE reward calculation.
        if (snapshotCreator != null && resumeFromStep <= STEP_STARTED) {
            snapshotCreator.creditMirRewardRest(newEpoch);
        }

        // Start UTXO balance scan in parallel (read-only, independent of reward calc).
        Future<java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger>> utxoBalancesFuture = null;
        if (snapshotCreator != null && resumeFromStep <= STEP_SNAPSHOT) {
            final int snapshotEpoch = previousEpoch;
            utxoBalancesFuture = utxoScanExecutor.submit(() -> snapshotCreator.aggregateUtxoBalances(snapshotEpoch));
        }

        // 3. Calculate rewards (skip if already committed from a previous interrupted run)
        if (resumeFromStep <= STEP_REWARDS) {
            if (rewardCalculator != null && rewardCalculator.isEnabled() && newEpoch >= 2) {
                calculateAndStoreRewards(previousEpoch, newEpoch, null);
            }
            if (snapshotCreator != null) {
                snapshotCreator.setBoundaryStep(newEpoch, STEP_REWARDS);
            }
        } else {
            log.info("Skipping reward calc for epoch {} (already committed in previous run)", newEpoch);
        }

        // 4. SNAP: Wait for parallel UTXO scan, then create delegation snapshot.
        java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> utxoBalances = null;
        if (resumeFromStep <= STEP_SNAPSHOT) {
            if (snapshotCreator != null) {
                java.util.Map<UtxoBalanceAggregator.CredentialKey, java.math.BigInteger> precomputedBalances = null;
                if (utxoBalancesFuture != null) {
                    try {
                        precomputedBalances = utxoBalancesFuture.get(300, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.warn("Parallel UTXO balance scan failed, falling back to inline: {}", e.getMessage());
                    }
                }
                utxoBalances = snapshotCreator.createAndCommitDelegationSnapshot(previousEpoch, precomputedBalances);
            }
            if (snapshotCreator != null) {
                snapshotCreator.setBoundaryStep(newEpoch, STEP_SNAPSHOT);
            }
        } else {
            log.info("Skipping snapshot for epoch {} (already committed in previous run)", previousEpoch);
        }

        // 4b. POOLREAP: Pool deposit refunds (after snapshot, before governance).
        if (resumeFromStep <= STEP_POOLREAP) {
            if (rewardCalculator != null && rewardCalculator.isEnabled()) {
                rewardCalculator.processPoolDepositRefunds(newEpoch);
            }
            if (snapshotCreator != null) {
                snapshotCreator.setBoundaryStep(newEpoch, STEP_POOLREAP);
            }
        } else {
            log.info("Skipping pool refunds for epoch {} (already committed in previous run)", newEpoch);
        }

        // 4c. PV10 hardfork: rebuild DRep delegation reverse index.
        // Matches Haskell's updateDRepDelegations (HardFork.hs) which rebuilds drepDelegs
        // from current forward delegations, removing stale PV9 entries and dangling delegations.
        // Only runs when Conway-or-later AND PV10+. No PV10 work in pre-Conway transitions.
        EpochParamProvider ep = (paramTracker != null && paramTracker.isEnabled())
                ? paramTracker : paramProvider;
        if (snapshotCreator != null && resumeFromStep <= STEP_GOVERNANCE && governanceEpochProcessor != null
                && snapshotCreator.isConwayOrLater(newEpoch) && ep.getProtocolMajor(newEpoch) >= 10) {
            try {
                Set<String> registeredDRepIds = governanceEpochProcessor.getRegisteredDRepIds();
                snapshotCreator.rebuildDRepDelegReverseIndexIfNeeded(newEpoch, registeredDRepIds, ep);
            } catch (Exception e) {
                // Consensus-critical: if rebuild fails, governance must not run with stale reverse index
                throw new RuntimeException("PV10 reverse-index rebuild failed at epoch " + newEpoch, e);
            }
        }

        // 5. Conway governance epoch processing (ratify, enact, expire, refund)
        // reward_rest from previous boundaries is already credited to PREFIX_ACCT.reward
        // in PostEpochTransition, so DRep distribution picks it up from account balances.
        GovernanceEpochProcessor.GovernanceEpochResult govResult = null;
        if (resumeFromStep <= STEP_GOVERNANCE) {
            if (governanceEpochProcessor != null) {
                try {
                    govResult = governanceEpochProcessor.processEpochBoundaryAndCommit(
                            previousEpoch, newEpoch, utxoBalances, null);
                } catch (Exception e) {
                    log.error("Governance epoch processing failed for {} → {}: {}",
                            previousEpoch, newEpoch, e.getMessage());
                }
            }
            if (snapshotCreator != null) {
                snapshotCreator.setBoundaryStep(newEpoch, STEP_GOVERNANCE);
            }
        } else {
            log.info("Skipping governance for epoch {} (already committed in previous run)", newEpoch);
        }

        // 6. Apply governance treasury delta to AdaPot (post-reward adjustment)
        if (govResult != null && adaPotTracker != null && adaPotTracker.isEnabled()) {
            BigInteger govTreasuryDelta = govResult.treasuryDelta().add(govResult.donations());
            if (govTreasuryDelta.signum() != 0) {
                var currentPot = adaPotTracker.getAdaPot(newEpoch);
                if (currentPot.isPresent()) {
                    var pot = currentPot.get();
                    BigInteger adjustedTreasury = pot.treasury().add(govTreasuryDelta);
                    adaPotTracker.storeAdaPot(newEpoch,
                            new AccountStateCborCodec.AdaPot(adjustedTreasury, pot.reserves(),
                                    pot.deposits(), pot.fees(), pot.distributed(),
                                    pot.undistributed(), pot.rewardsPot(), pot.poolRewardsPot()));
                    log.info("Governance adjusted treasury for epoch {}: delta={} (withdrawals={}, donations={})",
                            newEpoch, govTreasuryDelta, govResult.treasuryDelta(), govResult.donations());
                }
            }
        }

        // 7. Verify final AdaPot (after both reward calculation and governance adjustment)
        if (adaPotTracker != null && adaPotTracker.isEnabled() && newEpoch >= 2) {
            var finalPot = adaPotTracker.getAdaPot(newEpoch);
            if (finalPot.isPresent()) {
                verifyAdaPot(newEpoch, finalPot.get().treasury(), finalPot.get().reserves());

                // Export AdaPot snapshot for debugging
                if (snapshotExporter != EpochSnapshotExporter.NOOP) {
                    var p = finalPot.get();
                    snapshotExporter.exportAdaPot(newEpoch, new EpochSnapshotExporter.AdaPotEntry(
                            newEpoch, p.treasury(), p.reserves(), p.deposits(), p.fees(),
                            p.distributed(), p.undistributed(), p.rewardsPot(), p.poolRewardsPot()));
                }
            }
        }

        // Mark boundary as fully complete
        if (snapshotCreator != null) {
            snapshotCreator.setBoundaryStep(newEpoch, STEP_COMPLETE);
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
    }

    /**
     * Process post-epoch boundary.
     * POOLREAP is now done in processEpochBoundary (after snapshot, before governance)
     * matching the Haskell/Amaru order: Snapshot → POOLREAP → Governance.
     */
    public void processPostEpochBoundary(int newEpoch) {
        // POOLREAP moved to processEpochBoundary step 4b
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
            } else {
                log.warn("No AdaPot found for previous epoch {}, using zeros", previousEpoch);
            }
        }

        // Resolve param provider (prefer tracker if available)
        EpochParamProvider effectiveParams = (paramTracker != null && paramTracker.isEnabled())
                ? paramTracker : paramProvider;

        // Calculate and distribute rewards
        Optional<EpochCalculationResult> resultOpt = rewardCalculator.calculateAndDistribute(
                newEpoch, prevTreasury, prevReserves, effectiveParams, networkMagic);

        // Store updated AdaPot
        if (resultOpt.isPresent() && adaPotTracker != null && adaPotTracker.isEnabled()) {
            var result = resultOpt.get();

            var newPot = new AccountStateCborCodec.AdaPot(
                    result.getTreasury(),
                    result.getReserves(),
                    BigInteger.ZERO, // deposits tracked separately
                    rewardCalculator.getEpochFees(newEpoch - 1),
                    result.getTotalDistributedRewards(),
                    result.getTotalUndistributedRewards() != null
                            ? result.getTotalUndistributedRewards() : BigInteger.ZERO,
                    result.getTotalRewardsPot() != null
                            ? result.getTotalRewardsPot() : BigInteger.ZERO,
                    result.getTotalPoolRewardsPot() != null
                            ? result.getTotalPoolRewardsPot() : BigInteger.ZERO
            );
            adaPotTracker.storeAdaPot(newEpoch, newPot);
            // Note: verification moved to processEpochBoundary() after governance adjustment
        }
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

        // Only bootstrap if no AdaPot exists yet for any epoch
        var existing = adaPotTracker.getLatestAdaPot(newEpoch);
        if (existing.isPresent()) return;

        var networkConfig = cfNetworkConfig;
        int shelleyStartEpoch = networkConfig.getShelleyStartEpoch();

        BigInteger initialReserves = networkConfig.getShelleyInitialReserves();
        BigInteger initialTreasury = networkConfig.getShelleyInitialTreasury();

        if (initialReserves == null) initialReserves = BigInteger.ZERO;
        if (initialTreasury == null) initialTreasury = BigInteger.ZERO;

        var pot = new AccountStateCborCodec.AdaPot(
                initialTreasury, initialReserves,
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
        adaPotTracker.storeAdaPot(shelleyStartEpoch, pot);
        log.info("AdaPot bootstrapped at shelley start epoch {}: treasury={}, reserves={}",
                shelleyStartEpoch, initialTreasury, initialReserves);
    }
}
