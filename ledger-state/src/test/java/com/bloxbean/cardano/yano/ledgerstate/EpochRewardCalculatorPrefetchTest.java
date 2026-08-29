package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.account.RewardType;
import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.cardanofoundation.rewards.calculation.domain.PoolRewardCalculationResult;
import org.cardanofoundation.rewards.calculation.domain.PoolState;
import org.cardanofoundation.rewards.calculation.domain.Reward;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EpochRewardCalculatorPrefetchTest {
    @TempDir
    Path tempDir;

    @Test
    void byteKeyPrefetchProducesSameRewardStateAsHexCreditPath() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true);
            String poolHash = "91".repeat(28);
            String byteLeader = "0:" + "11".repeat(28);
            String byteMember = "1:" + "22".repeat(28);
            String hexLeaderHash = "33".repeat(28);
            String hexMemberHash = "44".repeat(28);

            putAccount(rocks, 0, "11".repeat(28), 100, 2_000_000);
            putAccount(rocks, 1, "22".repeat(28), 200, 3_000_000);
            putAccount(rocks, 0, hexLeaderHash, 100, 2_000_000);
            putAccount(rocks, 1, hexMemberHash, 200, 3_000_000);

            Reward memberReward = Reward.builder()
                    .stakeAddress(byteMember)
                    .poolId(poolHash)
                    .amount(BigInteger.valueOf(13))
                    .build();
            Reward leaderMemberReward = Reward.builder()
                    .stakeAddress(byteLeader)
                    .poolId(poolHash)
                    .amount(BigInteger.valueOf(7))
                    .build();
            PoolRewardCalculationResult result = PoolRewardCalculationResult.builder()
                    .epoch(8)
                    .poolId(poolHash)
                    .rewardAddress(byteLeader)
                    .operatorReward(BigInteger.valueOf(11))
                    .memberRewards(new HashSet<>(Set.of(
                            memberReward, leaderMemberReward)))
                    .build();
            var input = new StreamingEpochRewardOrchestrator.PoolRewardInput(
                    PoolState.builder().poolId(poolHash).build(), Set.of(), Set.of(),
                    Map.of(
                            byteLeader, BoundaryCredentialKey.fromAddress(byteLeader),
                            byteMember, BoundaryCredentialKey.fromAddress(byteMember)));

            var calculator = new EpochRewardCalculator(
                    rocks.db(), rocks.cfState(), rocks.cfSnapshot(), true);
            calculator.setAccountStateStore(store);
            calculator.beginRewardBatch(10, "rewards");

            assertThat(calculator.distributePrefetchedPoolReward(10, input, result))
                    .containsExactly(1, 2);
            calculator.creditReward(0, hexLeaderHash, BigInteger.valueOf(11),
                    8, RewardType.LEADER, poolHash);
            calculator.creditReward(0, hexLeaderHash, BigInteger.valueOf(7),
                    8, RewardType.MEMBER, poolHash);
            calculator.creditReward(1, hexMemberHash, BigInteger.valueOf(13),
                    8, RewardType.MEMBER, poolHash);
            calculator.commitRewardBatch(store.slotForEpochStart(10),
                    DefaultAccountStateStore.PHASE_REWARDS);

            assertEquivalentState(rocks,
                    BoundaryCredentialKey.fromAddress(byteLeader),
                    BoundaryCredentialKey.of(0, hexLeaderHash));
            assertEquivalentState(rocks,
                    BoundaryCredentialKey.fromAddress(byteMember),
                    BoundaryCredentialKey.of(1, hexMemberHash));
        }
    }

    private static void putAccount(TestRocksDBHelper rocks, int credentialType,
                                   String credentialHash, long reward, long deposit)
            throws Exception {
        rocks.db().put(rocks.cfState(),
                DefaultAccountStateStore.accountKey(credentialType, credentialHash),
                AccountStateCborCodec.encodeStakeAccount(
                        BigInteger.valueOf(reward), BigInteger.valueOf(deposit)));
    }

    private static void assertEquivalentState(
            TestRocksDBHelper rocks,
            BoundaryCredentialKey byteCredential,
            BoundaryCredentialKey hexCredential) throws Exception {
        assertThat(rocks.db().get(rocks.cfState(),
                DefaultAccountStateStore.accountKey(byteCredential)))
                .containsExactly(rocks.db().get(rocks.cfState(),
                        DefaultAccountStateStore.accountKey(hexCredential)));
        assertThat(rocks.db().get(rocks.cfState(),
                DefaultAccountStateStore.accumulatedRewardKey(byteCredential)))
                .containsExactly(rocks.db().get(rocks.cfState(),
                        DefaultAccountStateStore.accumulatedRewardKey(hexCredential)));
    }
}
