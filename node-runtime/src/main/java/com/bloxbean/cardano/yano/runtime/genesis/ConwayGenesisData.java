package com.bloxbean.cardano.yano.runtime.genesis;

import java.math.BigInteger;

/**
 * Data extracted from a conway-genesis.json file.
 * Contains Conway-era governance parameters.
 *
 * @param govActionLifetime       epochs a governance action remains active
 * @param govActionDeposit        deposit required for governance actions (lovelace)
 * @param dRepDeposit             deposit required for DRep registration (lovelace)
 * @param dRepActivity            epochs of DRep inactivity before expiry
 * @param committeeMinSize        minimum committee size
 * @param committeeMaxTermLength  maximum committee member term length (epochs)
 */
public record ConwayGenesisData(
        int govActionLifetime,
        BigInteger govActionDeposit,
        BigInteger dRepDeposit,
        int dRepActivity,
        int committeeMinSize,
        int committeeMaxTermLength
) {}
