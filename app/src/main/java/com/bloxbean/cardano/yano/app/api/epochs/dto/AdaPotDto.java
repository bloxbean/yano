package com.bloxbean.cardano.yano.app.api.epochs.dto;

import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigInteger;

public record AdaPotDto(
        int epoch,
        String treasury,
        String reserves,
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
        return new AdaPotDto(
                snapshot.epoch(),
                lovelace(snapshot.treasury()),
                lovelace(snapshot.reserves()),
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
