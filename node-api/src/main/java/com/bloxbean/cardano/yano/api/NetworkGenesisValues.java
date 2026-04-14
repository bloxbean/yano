package com.bloxbean.cardano.yano.api;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Provides derived network configuration values needed by ledger-state for
 * AdaPot bootstrap, reward calculation, and CF NetworkConfig construction.
 * <p>
 * This is NOT a pure genesis parse — it contains derived values (initial reserves,
 * hardfork epochs) that combine parsed genesis + known-network constants + era metadata.
 * <p>
 * The runtime adapter/factory in node-runtime is the ONLY place that constructs this.
 * ledger-state consumes it without depending on node-runtime.
 */
public interface NetworkGenesisValues {

    // --- Identity ---
    int networkMagic();

    // --- Topology ---
    long totalLovelace();
    long expectedSlotsPerEpoch();
    int securityParam();
    double activeSlotsCoeff();

    // --- Initial state at Shelley start ---
    BigInteger shelleyInitialReserves();
    BigInteger shelleyInitialTreasury();
    BigInteger shelleyInitialUtxo();

    /**
     * CF shelleyStartEpoch — used for reward/AdaPot initial-state semantics.
     * NOT the same as firstNonByronSlot (which is for epoch math).
     */
    int shelleyStartEpoch();

    // --- Hardfork epochs (from era metadata / known-network table) ---
    int allegraHardforkEpoch();
    int vasilHardforkEpoch();

    // --- Bootstrap / Allegra ---
    BigInteger bootstrapAddressAmount();

    // --- Shelley genesis protocol params ---
    BigInteger poolDeposit();
    BigDecimal decentralisationParam();
    BigDecimal monetaryExpansion();   // rho
    BigDecimal treasuryGrowth();     // tau
    BigDecimal poolPledgeInfluence(); // a0 from genesis
    int optimalPoolCount();           // nOpt

    /**
     * CF library's shelleyStartPoolOwnerInfluence.
     * This is NOT the same as genesis a0. CF uses 0.03 for all networks.
     */
    BigDecimal cfPoolOwnerInfluence();

    /**
     * Randomness stabilisation window = floor(4k/f) slots.
     */
    default long randomnessStabilisationWindow() {
        return Math.round((4.0 * securityParam()) / activeSlotsCoeff());
    }
}
