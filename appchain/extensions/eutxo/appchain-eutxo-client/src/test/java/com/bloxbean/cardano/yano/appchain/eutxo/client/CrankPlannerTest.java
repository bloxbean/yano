package com.bloxbean.cardano.yano.appchain.eutxo.client;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-UTXO-009 SP-M5: crank planning — armed-only, single-shard,
 * skip-nullified, capped at the governed exit batch, deterministic.
 */
class CrankPlannerTest {
    private static final long ROOT_UPDATED = 1_000L;
    private static final long DELAY = 21_600L;
    private static final long ARMED_SLOT = ROOT_UPDATED + DELAY + 500L;

    @Test
    void unarmedRootYieldsNoWorkEvenWithOwedClaims() {
        List<EutxoWithdrawalClaim> owed =
                ExitTransactionBuilderTest.sameShardClaims(2);
        int shard = ExitTransactionBuilderTest.shardOf(owed.getFirst());

        CrankPlanner.Plan plan = CrankPlanner.plan(
                shard, owed, ROOT_UPDATED, DELAY,
                ROOT_UPDATED + DELAY, // boundary: not strictly past
                6, id -> false);

        assertThat(plan.armed()).isFalse();
        assertThat(plan.hasWork()).isFalse();
        assertThat(plan.claims()).isEmpty();
    }

    @Test
    void selectsOnlyThisShardsUnnullifiedClaimsAndSumsTheBounty() {
        List<EutxoWithdrawalClaim> shardClaims =
                ExitTransactionBuilderTest.sameShardClaims(3);
        int shard = ExitTransactionBuilderTest.shardOf(shardClaims.getFirst());
        // Add noise from other shards plus one already-nullified claim.
        List<EutxoWithdrawalClaim> owed = new ArrayList<>(shardClaims);
        owed.addAll(otherShardClaims(shard, 3));
        String nullified = shardClaims.get(1).claimId();

        CrankPlanner.Plan plan = CrankPlanner.plan(
                shard, owed, ROOT_UPDATED, DELAY, ARMED_SLOT, 6,
                Set.of(nullified)::contains);

        assertThat(plan.armed()).isTrue();
        assertThat(plan.hasWork()).isTrue();
        assertThat(plan.claims()).containsExactly(
                shardClaims.get(0), shardClaims.get(2));
        assertThat(plan.bounty()).isEqualTo(
                shardClaims.get(0).bounty().add(shardClaims.get(2).bounty()));
        assertThat(plan.capped()).isFalse();

        // Every selected claim really belongs to the shard.
        for (EutxoWithdrawalClaim claim : plan.claims()) {
            assertThat(NullifierShardMirror.shardOf(
                    HexFormat.of().parseHex(claim.claimId()))).isEqualTo(shard);
        }
    }

    @Test
    void capsAtTheGovernedExitBatchAndReportsRemainingWork() {
        List<EutxoWithdrawalClaim> owed =
                ExitTransactionBuilderTest.sameShardClaims(5);
        int shard = ExitTransactionBuilderTest.shardOf(owed.getFirst());

        CrankPlanner.Plan plan = CrankPlanner.plan(
                shard, owed, ROOT_UPDATED, DELAY, ARMED_SLOT, 3, id -> false);

        assertThat(plan.claims()).hasSize(3);
        assertThat(plan.claims()).containsExactlyElementsOf(owed.subList(0, 3));
        assertThat(plan.capped()).isTrue();
        assertThat(plan.bounty()).isEqualTo(BigInteger.valueOf(6_000_000L));
    }

    @Test
    void planFeedsTheExitBuilderDirectly() {
        List<EutxoWithdrawalClaim> owed =
                ExitTransactionBuilderTest.sameShardClaims(2);
        int shard = ExitTransactionBuilderTest.shardOf(owed.getFirst());

        CrankPlanner.Plan plan = CrankPlanner.plan(
                shard, owed, ROOT_UPDATED, DELAY, ARMED_SLOT, 6, id -> false);

        ExitTransactionBuilder.Plan exit = ExitTransactionBuilder.build(
                plan.claims(),
                List.of(new ExitTransactionBuilder.VaultInput(
                        ExitTransactionBuilderTest.outpoint(0x11),
                        BigInteger.valueOf(40_000_000L))),
                EutxoCliShared.VAULT, EutxoCliShared.CRANKER,
                BigInteger.valueOf(300_000L), BigInteger.valueOf(2_000_000L),
                ROOT_UPDATED, DELAY, ARMED_SLOT, 7_200L, 6,
                ExitTransactionBuilderTest.execution());

        assertThat(exit.shard()).isEqualTo(shard);
        assertThat(exit.orderedClaimIds()).containsExactlyElementsOf(
                plan.claims().stream().map(EutxoWithdrawalClaim::claimId).toList());
        assertThat(exit.bountyLovelace()).isEqualTo(plan.bounty());
    }

    private static List<EutxoWithdrawalClaim> otherShardClaims(
            int excludedShard, int count) {
        List<EutxoWithdrawalClaim> others = new ArrayList<>();
        for (int seed = 0; seed < 512 && others.size() < count; seed++) {
            EutxoWithdrawalClaim claim = ExitTransactionBuilderTest.claim(seed);
            if (ExitTransactionBuilderTest.shardOf(claim) != excludedShard) {
                others.add(claim);
            }
        }
        return others;
    }

    /** Shared demo addresses for the planner→builder hand-off test. */
    private static final class EutxoCliShared {
        static final String VAULT = com.bloxbean.cardano.yano.appchain.eutxo
                .testkit.EutxoTestWallet.fromSeed(
                        ExitTransactionBuilderTest.fill(32, 0x54)).address();
        static final String CRANKER = com.bloxbean.cardano.yano.appchain.eutxo
                .testkit.EutxoTestWallet.fromSeed(
                        ExitTransactionBuilderTest.fill(32, 0xC4)).address();
    }
}
