package com.bloxbean.cardano.yano.app.api.accounts.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

public class AccountStateDtos {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StakeRegistrationDto(
            @JsonProperty("stake_address") String stakeAddress,
            @JsonProperty("credential") String credential,
            @JsonProperty("credential_type") String credentialType,
            @JsonProperty("reward_balance") String rewardBalance,
            @JsonProperty("deposit") String deposit
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PoolDelegationDto(
            @JsonProperty("stake_address") String stakeAddress,
            @JsonProperty("credential") String credential,
            @JsonProperty("credential_type") String credentialType,
            @JsonProperty("pool_id") String poolId,
            @JsonProperty("pool_hash") String poolHash,
            @JsonProperty("slot") long slot,
            @JsonProperty("tx_index") int txIndex,
            @JsonProperty("cert_index") int certIndex
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DRepDelegationDto(
            @JsonProperty("stake_address") String stakeAddress,
            @JsonProperty("credential") String credential,
            @JsonProperty("credential_type") String credentialType,
            @JsonProperty("drep_id") String drepId,
            @JsonProperty("drep_type") String drepType,
            @JsonProperty("drep_hash") String drepHash,
            @JsonProperty("slot") long slot,
            @JsonProperty("tx_index") int txIndex,
            @JsonProperty("cert_index") int certIndex
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PoolDto(
            @JsonProperty("pool_id") String poolId,
            @JsonProperty("pool_hash") String poolHash,
            @JsonProperty("deposit") String deposit
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PoolRetirementDto(
            @JsonProperty("pool_id") String poolId,
            @JsonProperty("pool_hash") String poolHash,
            @JsonProperty("retirement_epoch") long retirementEpoch
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccountInfoDto(
            @JsonProperty("stake_address") String stakeAddress,
            @JsonProperty("active") boolean active,
            @JsonProperty("registered") boolean registered,
            @JsonProperty("active_epoch") Integer activeEpoch,
            @JsonProperty("delegation_epoch") Integer delegationEpoch,
            @JsonProperty("controlled_amount") String controlledAmount,
            @JsonProperty("withdrawable_amount") String withdrawableAmount,
            @JsonProperty("pool_id") String poolId,
            @JsonProperty("drep_id") String drepId,
            @JsonProperty("current_utxo_balance") String currentUtxoBalance,
            @JsonProperty("stake_deposit") String stakeDeposit,
            @JsonProperty("pool_hash") String poolHash,
            @JsonProperty("drep_type") String drepType,
            @JsonProperty("drep_hash") String drepHash
    ) {}

    public record WithdrawalHistoryDto(
            @JsonProperty("tx_hash") String txHash,
            @JsonProperty("amount") String amount,
            @JsonProperty("tx_slot") long txSlot,
            @JsonProperty("block_height") long blockHeight,
            @JsonProperty("tx_index") int txIndex
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DelegationHistoryDto(
            @JsonProperty("active_epoch") int activeEpoch,
            @JsonProperty("tx_hash") String txHash,
            @JsonProperty("amount") String amount,
            @JsonProperty("pool_id") String poolId,
            @JsonProperty("pool_hash") String poolHash,
            @JsonProperty("tx_slot") long txSlot,
            @JsonProperty("block_height") long blockHeight,
            @JsonProperty("tx_index") int txIndex,
            @JsonProperty("cert_index") int certIndex
    ) {}

    public record RegistrationHistoryDto(
            @JsonProperty("tx_hash") String txHash,
            @JsonProperty("action") String action,
            @JsonProperty("deposit") String deposit,
            @JsonProperty("tx_slot") long txSlot,
            @JsonProperty("block_height") long blockHeight,
            @JsonProperty("tx_index") int txIndex,
            @JsonProperty("cert_index") int certIndex
    ) {}

    public record MirHistoryDto(
            @JsonProperty("tx_hash") String txHash,
            @JsonProperty("pot") String pot,
            @JsonProperty("amount") String amount,
            @JsonProperty("earned_epoch") int earnedEpoch,
            @JsonProperty("tx_slot") long txSlot,
            @JsonProperty("block_height") long blockHeight,
            @JsonProperty("tx_index") int txIndex,
            @JsonProperty("cert_index") int certIndex
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccountStakeDto(
            @JsonProperty("stake_address") String stakeAddress,
            @JsonProperty("epoch") int epoch,
            @JsonProperty("amount") String amount,
            @JsonProperty("pool_id") String poolId,
            @JsonProperty("pool_hash") String poolHash,
            @JsonProperty("credential") String credential,
            @JsonProperty("credential_type") String credentialType
    ) {}

    private AccountStateDtos() {}
}
