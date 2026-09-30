package org.yanoproject.app.api.epochs.dto;

import org.yanoproject.api.account.LedgerStateProvider;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigInteger;

/**
 * The pots after the epoch's boundary. {@code deposits} is the ledger's deposit pot ({@code utxosDeposited}). The
 * {@code deposits_*} fields split it by category (ADR-058). They are {@code null} for a pot written before ADR-058,
 * whose {@code deposits} counts only stake-key and DRep deposits. {@code deposits_key + deposits_pool} is db-sync's
 * and Koios's {@code deposits_stake}.
 */
public record AdaPotDto(
        int epoch,
        String treasury,
        String reserves,
        String deposits,
        @JsonProperty("deposits_key")
        String depositsKey,
        @JsonProperty("deposits_pool")
        String depositsPool,
        @JsonProperty("deposits_drep")
        String depositsDrep,
        @JsonProperty("deposits_proposal")
        String depositsProposal,
        String fees,
        @JsonProperty("distributed_rewards")
        String distributedRewards,
        @JsonProperty("undistributed_rewards")
        String undistributedRewards,
        @JsonProperty("rewards_pot")
        String rewardsPot,
        @JsonProperty("pool_rewards_pot")
        String poolRewardsPot) {

    public static AdaPotDto from(LedgerStateProvider.AdaPotSnapshot snapshot) {
        var obligations = snapshot.depositObligations();
        return new AdaPotDto(
                snapshot.epoch(),
                lovelace(snapshot.treasury()),
                lovelace(snapshot.reserves()),
                lovelace(snapshot.deposits()),
                obligations != null ? lovelace(obligations.stakeKeys()) : null,
                obligations != null ? lovelace(obligations.pools()) : null,
                obligations != null ? lovelace(obligations.dreps()) : null,
                obligations != null ? lovelace(obligations.proposals()) : null,
                lovelace(snapshot.fees()),
                lovelace(snapshot.distributedRewards()),
                lovelace(snapshot.undistributedRewards()),
                lovelace(snapshot.rewardsPot()),
                lovelace(snapshot.poolRewardsPot())
        );
    }

    private static String lovelace(BigInteger value) {
        return value != null ? value.toString() : "0";
    }
}
