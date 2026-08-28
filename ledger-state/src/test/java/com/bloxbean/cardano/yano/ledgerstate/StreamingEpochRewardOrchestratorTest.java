package com.bloxbean.cardano.yano.ledgerstate;

import org.cardanofoundation.rewards.calculation.EpochCalculation;
import org.cardanofoundation.rewards.calculation.config.NetworkConfig;
import org.cardanofoundation.rewards.calculation.domain.Delegator;
import org.cardanofoundation.rewards.calculation.domain.Epoch;
import org.cardanofoundation.rewards.calculation.domain.PoolRewardCalculationResult;
import org.cardanofoundation.rewards.calculation.domain.PoolState;
import org.cardanofoundation.rewards.calculation.domain.ProtocolParameters;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingEpochRewardOrchestratorTest {
    @Test
    void poolAtATimeOuterLoopMatchesCfEpochCalculation() {
        String poolId = "42".repeat(28);
        String rewardAddress = "0:" + "11".repeat(28);
        String memberAddress = "0:" + "22".repeat(28);
        var delegators = new HashSet<Delegator>();
        delegators.add(Delegator.builder().stakeAddress(rewardAddress)
                .activeStake(BigInteger.valueOf(400_000_000L)).build());
        delegators.add(Delegator.builder().stakeAddress(memberAddress)
                .activeStake(BigInteger.valueOf(600_000_000L)).build());
        PoolState pool = PoolState.builder()
                .poolId(poolId).blockCount(10).activeStake(BigInteger.valueOf(1_000_000_000L))
                .delegators(delegators).epoch(8).rewardAddress(rewardAddress)
                .owners(new HashSet<>(Set.of(rewardAddress)))
                .ownerActiveStake(BigInteger.valueOf(400_000_000L))
                .poolFees(BigInteger.ZERO).margin(new BigDecimal("0.05"))
                .fixedCost(BigInteger.valueOf(340_000_000L))
                .pledge(BigInteger.valueOf(100_000_000L)).build();
        ProtocolParameters parameters = ProtocolParameters.builder()
                .decentralisation(BigDecimal.ZERO).treasuryGrowRate(new BigDecimal("0.2"))
                .monetaryExpandRate(new BigDecimal("0.003")).optimalPoolCount(500)
                .poolOwnerInfluence(new BigDecimal("0.3")).build();
        Epoch epochInfo = Epoch.builder().number(8).fees(BigInteger.valueOf(1_000_000L))
                .blockCount(100).nonOBFTBlockCount(100)
                .activeStake(BigInteger.valueOf(1_000_000_000L)).build();
        NetworkConfig network = NetworkConfig.builder()
                .networkMagic(1).totalLovelace(BigInteger.valueOf(45_000_000_000_000_000L))
                .poolDepositInLovelace(BigInteger.valueOf(500_000_000L))
                .expectedSlotsPerEpoch(432_000).genesisConfigSecurityParameter(2_160)
                .shelleyStartEpoch(0).allegraHardforkEpoch(0).vasilHardforkEpoch(0)
                .bootstrapAddressAmount(BigInteger.ZERO).activeSlotCoefficient(0.05)
                .shelleyInitialReserves(BigInteger.ZERO).shelleyInitialTreasury(BigInteger.ZERO)
                .shelleyInitialUtxo(BigInteger.ZERO).build();
        BigInteger reserves = BigInteger.valueOf(10_000_000_000_000_000L);
        BigInteger treasury = BigInteger.valueOf(1_000_000_000_000_000L);
        HashSet<String> empty = new HashSet<>();
        HashSet<String> registered = new HashSet<>(Set.of(rewardAddress));

        var expected = EpochCalculation.calculateEpochRewardPots(
                10, reserves, treasury, parameters, epochInfo, Set.of(), empty, List.of(),
                List.of(poolId), List.of(pool), empty, registered, registered,
                empty, empty, network);
        List<PoolRewardCalculationResult> streamedPools = new ArrayList<>();
        var actual = StreamingEpochRewardOrchestrator.calculate(
                10, reserves, treasury, parameters, epochInfo, Set.of(), empty, List.of(),
                List.of(StreamingEpochRewardOrchestrator.PoolRewardInput.fromLegacy(pool)).iterator(),
                empty, registered, registered, empty, empty, network,
                null, BigInteger.ZERO, BigInteger.ZERO,
                (poolResult, totals, replayed) -> streamedPools.add(poolResult));

        assertThat(actual.getTreasury()).isEqualTo(expected.getTreasury());
        assertThat(actual.getReserves()).isEqualTo(expected.getReserves());
        assertThat(actual.getTotalDistributedRewards())
                .isEqualTo(expected.getTotalDistributedRewards());
        assertThat(actual.getTotalUndistributedRewards())
                .isEqualTo(expected.getTotalUndistributedRewards());
        assertThat(actual.getTotalRewardsPot()).isEqualTo(expected.getTotalRewardsPot());
        assertThat(actual.getTotalPoolRewardsPot()).isEqualTo(expected.getTotalPoolRewardsPot());
        assertThat(streamedPools).hasSize(1);
        assertThat(streamedPools.getFirst().getDistributedPoolReward())
                .isEqualTo(expected.getPoolRewardCalculationResults().getFirst()
                        .getDistributedPoolReward());
        assertThat(streamedPools.getFirst().getUnspendableEarnedRewards())
                .isEqualTo(expected.getPoolRewardCalculationResults().getFirst()
                        .getUnspendableEarnedRewards());

        List<Boolean> replayFlags = new ArrayList<>();
        var resumed = StreamingEpochRewardOrchestrator.calculate(
                10, reserves, treasury, parameters, epochInfo, Set.of(), empty, List.of(),
                List.of(StreamingEpochRewardOrchestrator.PoolRewardInput.fromLegacy(pool)).iterator(),
                empty, registered, registered, empty, empty, network,
                poolId, expected.getTotalDistributedRewards(),
                expected.getPoolRewardCalculationResults().getFirst()
                        .getUnspendableEarnedRewards(),
                (poolResult, totals, replayed) -> replayFlags.add(replayed));
        assertThat(replayFlags).containsExactly(true);
        assertThat(resumed.getTreasury()).isEqualTo(expected.getTreasury());
        assertThat(resumed.getReserves()).isEqualTo(expected.getReserves());
        assertThat(resumed.getTotalDistributedRewards())
                .isEqualTo(expected.getTotalDistributedRewards());
    }
}
