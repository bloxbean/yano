package com.bloxbean.cardano.yano.runtime.genesis;

import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

/**
 * Data extracted from alonzo-genesis.json.
 */
public record AlonzoGenesisData(
        Map<String, Object> costModels,
        BigDecimal priceMem,
        BigDecimal priceStep,
        BigInteger maxTxExMem,
        BigInteger maxTxExSteps,
        BigInteger maxBlockExMem,
        BigInteger maxBlockExSteps,
        BigInteger maxValSize,
        Integer collateralPercent,
        Integer maxCollateralInputs,
        BigInteger coinsPerUtxoWord,
        NonNegativeInterval priceMemInterval,
        NonNegativeInterval priceStepInterval
) {}
