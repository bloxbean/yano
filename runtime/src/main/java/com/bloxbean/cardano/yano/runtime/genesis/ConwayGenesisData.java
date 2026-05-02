package com.bloxbean.cardano.yano.runtime.genesis;

import com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds;
import com.bloxbean.cardano.yaci.core.model.PoolVotingThresholds;
import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

/**
 * Data extracted from a conway-genesis.json file.
 * Contains Conway-era governance parameters.
 *
 * @param govActionLifetime        epochs a governance action remains active
 * @param govActionDeposit         deposit required for governance actions (lovelace)
 * @param dRepDeposit              deposit required for DRep registration (lovelace)
 * @param dRepActivity             epochs of DRep inactivity before expiry
 * @param committeeMinSize         minimum committee size
 * @param committeeMaxTermLength   maximum committee member term length (epochs)
 * @param drepVotingThresholds     DRep voting thresholds
 * @param poolVotingThresholds     SPO voting thresholds
 * @param minFeeRefScriptCostPerByte reference script fee coefficient
 */
public record ConwayGenesisData(
        int govActionLifetime,
        BigInteger govActionDeposit,
        BigInteger dRepDeposit,
        int dRepActivity,
        int committeeMinSize,
        int committeeMaxTermLength,
        DrepVoteThresholds drepVotingThresholds,
        PoolVotingThresholds poolVotingThresholds,
        BigDecimal minFeeRefScriptCostPerByte,
        NonNegativeInterval minFeeRefScriptCostPerByteInterval,
        Map<String, Object> costModels
) {}
