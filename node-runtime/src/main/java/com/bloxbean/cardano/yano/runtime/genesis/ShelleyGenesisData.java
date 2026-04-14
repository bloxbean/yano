package com.bloxbean.cardano.yano.runtime.genesis;

import java.math.BigInteger;
import java.util.Map;

/**
 * Data extracted from a shelley-genesis.json file.
 *
 * @param initialFunds         hex-encoded address bytes → lovelace
 * @param networkMagic         protocol magic number
 * @param epochLength          number of slots per epoch
 * @param slotLength           slot duration in seconds
 * @param systemStart          ISO-8601 system start timestamp
 * @param maxLovelaceSupply    max lovelace supply
 * @param activeSlotsCoeff     fraction of slots that are active (0.0–1.0)
 * @param securityParam        k value (finality confirmation depth)
 * @param maxKESEvolutions     max KES key evolutions
 * @param slotsPerKESPeriod    slots per KES period
 * @param updateQuorum         governance update quorum
 * @param protocolMajor        genesis protocol version major
 * @param protocolMinor        genesis protocol version minor
 * @param rho                  monetary expansion rate (protocolParams.rho)
 * @param tau                  treasury growth rate (protocolParams.tau)
 * @param a0                   pool pledge influence (protocolParams.a0)
 * @param nOpt                 optimal number of stake pools (protocolParams.nOpt)
 * @param minPoolCost          minimum pool cost in lovelace (protocolParams.minPoolCost)
 * @param keyDeposit           stake key deposit in lovelace (protocolParams.keyDeposit)
 * @param poolDeposit          pool registration deposit in lovelace (protocolParams.poolDeposit)
 * @param decentralisationParam initial decentralisation parameter (0.0–1.0)
 */
public record ShelleyGenesisData(
        Map<String, BigInteger> initialFunds,
        long networkMagic,
        long epochLength,
        double slotLength,
        String systemStart,
        long maxLovelaceSupply,
        double activeSlotsCoeff,
        long securityParam,
        long maxKESEvolutions,
        long slotsPerKESPeriod,
        long updateQuorum,
        long protocolMajor,
        long protocolMinor,
        // Protocol params from protocolParams section
        double rho,
        double tau,
        double a0,
        int nOpt,
        long minPoolCost,
        long keyDeposit,
        long poolDeposit,
        double decentralisationParam
) {}
