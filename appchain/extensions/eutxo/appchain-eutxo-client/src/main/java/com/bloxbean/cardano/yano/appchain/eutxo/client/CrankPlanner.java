package com.bloxbean.cardano.yano.appchain.eutxo.client;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * ADR-UTXO-009 SP-M5: selects the claims a permissionless cranker can exit
 * from one nullifier shard right now. A claim is exitable when the accepted
 * root thread has gone stale beyond its governed {@code fallbackDelaySlots}
 * (armed), the claim belongs to the target shard, and it has not already been
 * nullified (settled via A2 or a prior exit). Selection is deterministic
 * (input order preserved) and capped at the governed {@code maxExitBatch};
 * the {@link Plan} reports the total bounty the cranker would earn.
 *
 * <p>The "already nullified" test is injected as a predicate over the claim
 * id hex — the caller supplies it from a {@link NullifierShardMirror} it
 * maintains or reconstructs from L1, so this planner stays free of any trie
 * dependency.
 */
public final class CrankPlanner {
    private CrankPlanner() {
    }

    public static Plan plan(
            int shard,
            List<EutxoWithdrawalClaim> owedClaims,
            long rootUpdatedAtSlot,
            long fallbackDelaySlots,
            long currentSlot,
            int maxExitBatch,
            Predicate<String> alreadyNullified
    ) {
        if (shard < 0 || shard >= NullifierShardMirror.SHARD_COUNT) {
            throw new IllegalArgumentException("shard must be 0.."
                    + (NullifierShardMirror.SHARD_COUNT - 1));
        }
        Objects.requireNonNull(owedClaims, "owedClaims");
        Objects.requireNonNull(alreadyNullified, "alreadyNullified");
        if (fallbackDelaySlots <= 0) {
            throw new IllegalArgumentException("fallbackDelaySlots must be positive");
        }
        if (maxExitBatch <= 0) {
            throw new IllegalArgumentException("maxExitBatch must be positive");
        }

        boolean armed = currentSlot - rootUpdatedAtSlot > fallbackDelaySlots;
        if (!armed) {
            return new Plan(shard, false, List.of(), BigInteger.ZERO, false);
        }

        List<EutxoWithdrawalClaim> selected = new ArrayList<>();
        BigInteger bounty = BigInteger.ZERO;
        boolean capped = false;
        for (EutxoWithdrawalClaim claim : owedClaims) {
            if (NullifierShardMirror.shardOf(
                    java.util.HexFormat.of().parseHex(claim.claimId())) != shard) {
                continue;
            }
            if (alreadyNullified.test(claim.claimId())) {
                continue;
            }
            if (selected.size() == maxExitBatch) {
                capped = true;
                break;
            }
            selected.add(claim);
            bounty = bounty.add(claim.bounty());
        }
        return new Plan(shard, true, List.copyOf(selected), bounty, capped);
    }

    /**
     * The exitable batch for one shard.
     *
     * @param shard   the target shard
     * @param armed   whether the root thread is stale beyond its fallback delay
     * @param claims  the claims to exit (empty if not armed or none provable)
     * @param bounty  total bounty the cranker earns for this batch
     * @param capped  more provable claims remain beyond {@code maxExitBatch}
     */
    public record Plan(
            int shard,
            boolean armed,
            List<EutxoWithdrawalClaim> claims,
            BigInteger bounty,
            boolean capped
    ) {
        public Plan {
            claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
            bounty = Objects.requireNonNull(bounty, "bounty");
        }

        public boolean hasWork() {
            return armed && !claims.isEmpty();
        }
    }
}
