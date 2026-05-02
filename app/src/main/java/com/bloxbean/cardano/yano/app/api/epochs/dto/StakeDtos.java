package com.bloxbean.cardano.yano.app.api.epochs.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public class StakeDtos {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TotalStakeDto(
            @JsonProperty("epoch") int epoch,
            @JsonProperty("active_stake") String activeStake
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PoolStakeDto(
            @JsonProperty("epoch") int epoch,
            @JsonProperty("pool_id") String poolId,
            @JsonProperty("pool_hash") String poolHash,
            @JsonProperty("active_stake") String activeStake
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PoolStakeDelegatorDto(
            @JsonProperty("stake_address") String stakeAddress,
            @JsonProperty("pool_id") String poolId,
            @JsonProperty("amount") String amount
    ) {}

    private StakeDtos() {}
}
