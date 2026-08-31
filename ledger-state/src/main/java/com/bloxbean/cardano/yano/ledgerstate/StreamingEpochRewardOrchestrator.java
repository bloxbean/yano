package com.bloxbean.cardano.yano.ledgerstate;

import org.cardanofoundation.rewards.calculation.EpochCalculation;
import org.cardanofoundation.rewards.calculation.PoolRewardsCalculation;
import org.cardanofoundation.rewards.calculation.config.NetworkConfig;
import org.cardanofoundation.rewards.calculation.domain.Epoch;
import org.cardanofoundation.rewards.calculation.domain.EpochCalculationResult;
import org.cardanofoundation.rewards.calculation.domain.MirCertificate;
import org.cardanofoundation.rewards.calculation.domain.PoolRewardCalculationResult;
import org.cardanofoundation.rewards.calculation.domain.PoolState;
import org.cardanofoundation.rewards.calculation.domain.ProtocolParameters;
import org.cardanofoundation.rewards.calculation.domain.RetiredPool;
import org.cardanofoundation.rewards.calculation.domain.TreasuryCalculationResult;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded Yano-owned outer loop around cf-rewards' public per-pool API.
 * The library still owns every pool-level formula; this class reproduces only
 * the scalar accumulation performed by its epoch-level convenience method.
 */
final class StreamingEpochRewardOrchestrator {
    private StreamingEpochRewardOrchestrator() {
    }

    static EpochCalculationResult calculate(
            int epoch,
            BigInteger previousReserves,
            BigInteger previousTreasury,
            ProtocolParameters protocolParameters,
            Epoch epochInfo,
            Set<RetiredPool> retiredPools,
            HashSet<String> deregistered,
            List<MirCertificate> mirCertificates,
            Iterator<PoolRewardInput> pools,
            HashSet<String> lateDeregistered,
            HashSet<String> registeredSinceLast,
            HashSet<String> registeredUntilNow,
            HashSet<String> sharedPoolRewardAddresses,
            HashSet<String> deregisteredOnBoundary,
            NetworkConfig networkConfig,
            String resumeAfterPool,
            BigInteger initialDistributed,
            BigInteger initialUnspendable,
            PoolResultSink sink) {

        EpochCalculationResult scalarBaseline = EpochCalculation.calculateEpochRewardPots(
                epoch, previousReserves, previousTreasury, protocolParameters, epochInfo,
                retiredPools, deregistered, mirCertificates, List.of(), List.of(),
                lateDeregistered, registeredSinceLast, registeredUntilNow,
                sharedPoolRewardAddresses, deregisteredOnBoundary, networkConfig);
        int effectiveBlockCount = effectiveBlockCount(protocolParameters, epochInfo);

        BigInteger distributed = initialDistributed;
        BigInteger unspendable = initialUnspendable;
        while (pools.hasNext()) {
            PoolRewardInput input = pools.next();
            PoolState pool = input.pool();
            if (pool.getBlockCount() <= 0) continue;
            boolean replayed = resumeAfterPool != null
                    && pool.getPoolId().compareTo(resumeAfterPool) <= 0;

            HashSet<String> poolCredentials = new HashSet<>();
            poolCredentials.add(pool.getRewardAddress());
            pool.getDelegators().forEach(delegator ->
                    poolCredentials.add(delegator.getStakeAddress()));

            Set<String> poolDeregistered = input.deregistered() != null
                    ? input.deregistered() : intersection(deregistered, poolCredentials);
            Set<String> poolLateDeregistered = input.lateDeregistered() != null
                    ? input.lateDeregistered() : intersection(lateDeregistered, poolCredentials);
            boolean sharedPoolWithoutReward = epoch - 2 < networkConfig.getAllegraHardforkEpoch()
                    && sharedPoolRewardAddresses.contains(pool.getPoolId());

            PoolRewardCalculationResult result = PoolRewardsCalculation.calculatePoolRewardInEpoch(
                    pool.getPoolId(), pool, effectiveBlockCount, protocolParameters,
                    scalarBaseline.getTotalAdaInCirculation(), epochInfo.getActiveStake(),
                    scalarBaseline.getTotalPoolRewardsPot(), pool.getOwnerActiveStake(),
                    pool.getOwners(), poolDeregistered, sharedPoolWithoutReward,
                    poolLateDeregistered, registeredSinceLast, networkConfig);

            if (!replayed) {
                distributed = distributed.add(zeroIfNull(result.getDistributedPoolReward()));
                unspendable = unspendable.add(zeroIfNull(result.getUnspendableEarnedRewards()));
            }
            sink.accept(input, result,
                    new RunningTotals(distributed, unspendable), replayed);
        }

        BigInteger undistributed = scalarBaseline.getTotalPoolRewardsPot().subtract(distributed);
        BigInteger reserves = scalarBaseline.getReserves().subtract(distributed).subtract(unspendable);
        BigInteger treasury = scalarBaseline.getTreasury().add(unspendable);
        TreasuryCalculationResult baselineTreasury = scalarBaseline.getTreasuryCalculationResult();
        TreasuryCalculationResult treasuryResult = TreasuryCalculationResult.builder()
                .epoch(epoch)
                .treasury(treasury)
                .totalRewardPot(baselineTreasury.getTotalRewardPot())
                .treasuryWithdrawals(baselineTreasury.getTreasuryWithdrawals())
                .unspendableEarnedRewards(unspendable)
                .unclaimedRefunds(baselineTreasury.getUnclaimedRefunds())
                .build();

        return EpochCalculationResult.builder()
                .epoch(epoch)
                .PoolRewardCalculationResults(List.of())
                .treasuryCalculationResult(treasuryResult)
                .totalAdaInCirculation(scalarBaseline.getTotalAdaInCirculation())
                .treasury(treasury)
                .reserves(reserves)
                .totalDistributedRewards(distributed)
                .totalUndistributedRewards(undistributed)
                .totalRewardsPot(scalarBaseline.getTotalRewardsPot())
                .totalPoolRewardsPot(scalarBaseline.getTotalPoolRewardsPot())
                .build();
    }

    private static int effectiveBlockCount(ProtocolParameters protocolParameters, Epoch epochInfo) {
        BigDecimal decentralisation = protocolParameters.getDecentralisation();
        if (decentralisation != null
                && decentralisation.compareTo(BigDecimal.ZERO) > 0
                && decentralisation.compareTo(BigDecimal.valueOf(0.8)) < 0) {
            return epochInfo.getNonOBFTBlockCount();
        }
        return epochInfo.getBlockCount();
    }

    private static Set<String> intersection(Set<String> source, Set<String> poolCredentials) {
        HashSet<String> result = new HashSet<>();
        for (String credential : source) {
            if (poolCredentials.contains(credential)) result.add(credential);
        }
        return result;
    }

    private static BigInteger zeroIfNull(BigInteger value) {
        return value != null ? value : BigInteger.ZERO;
    }

    record PoolRewardInput(PoolState pool, Set<String> deregistered,
                           Set<String> lateDeregistered,
                           Map<String, BoundaryCredentialKey> credentialKeys) {
        PoolRewardInput(PoolState pool, Set<String> deregistered,
                        Set<String> lateDeregistered) {
            this(pool, deregistered, lateDeregistered, Map.of());
        }

        static PoolRewardInput fromLegacy(PoolState pool) {
            return new PoolRewardInput(pool, null, null, Map.of());
        }
    }

    record RunningTotals(BigInteger distributed, BigInteger unspendable) {
    }

    @FunctionalInterface
    interface PoolResultSink {
        void accept(PoolRewardInput input, PoolRewardCalculationResult result,
                    RunningTotals totals,
                    boolean replayed);
    }
}
