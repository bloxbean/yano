package com.bloxbean.cardano.yano.api;

import com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds;
import com.bloxbean.cardano.yaci.core.model.PoolVotingThresholds;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;

import java.math.BigInteger;

/**
 * Provides epoch-scoped protocol parameters.
 * <p>
 * Default values are for test stubs and backward compatibility only.
 * Production code should use {@code DefaultEpochParamProvider.fromNetworkGenesisConfig()}
 * which reads actual values from genesis files.
 */
public interface EpochParamProvider {
    BigInteger getKeyDeposit(long epoch);
    BigInteger getPoolDeposit(long epoch);

    /** Shelley epoch length in slots. Default: 432000 (5 days at 1s slots). */
    default long getEpochLength() { return 432000; }

    /** Byron slots per epoch. Only relevant for mainnet/preprod with Byron era. Default: 21600. */
    default long getByronSlotsPerEpoch() { return 21600; }

    /**
     * Returns the first non-Byron era start slot, used for epoch/slot conversion.
     * <p>
     * This is NOT the same as CF NetworkConfig's {@code shelleyStartEpoch} which is used
     * for reward/AdaPot initial-state semantics. For example, preview has
     * {@code getShelleyStartSlot() = 0} but CF {@code shelleyStartEpoch = 1}.
     * <p>
     * Legacy name preserved for compatibility.
     *
     * @return first non-Byron slot (0 = no Byron era, e.g. preview/sanchonet/devnet)
     */
    default long getShelleyStartSlot() { return 0; }

    /**
     * Create an {@link EpochSlotCalc} from this provider's values.
     * Ensures all consumers use the same epoch/slot math.
     */
    default EpochSlotCalc getEpochSlotCalc() {
        return new EpochSlotCalc(getEpochLength(), getByronSlotsPerEpoch(), getShelleyStartSlot());
    }

    // --- Reward calculation parameters (defaults = Shelley mainnet genesis values) ---

    /** Monetary expansion rate (ρ). Fraction of reserves going to rewards. */
    default java.math.BigDecimal getRho(long epoch) { return new java.math.BigDecimal("0.003"); }

    /** Treasury growth rate (τ). Fraction of reward pot going to treasury. */
    default java.math.BigDecimal getTau(long epoch) { return new java.math.BigDecimal("0.2"); }

    /** Pool influence factor (a₀). Higher = more influence of pledge on rewards. */
    default java.math.BigDecimal getA0(long epoch) { return new java.math.BigDecimal("0.3"); }

    /** Decentralization parameter (d). 0 = fully decentralized. Pre-Alonzo only. */
    default java.math.BigDecimal getDecentralization(long epoch) { return java.math.BigDecimal.ZERO; }

    /** Target number of pools (k / nOpt). */
    default int getNOpt(long epoch) { return 500; }

    /** Minimum pool cost in lovelace. */
    default BigInteger getMinPoolCost(long epoch) { return new BigInteger("170000000"); }

    /** Protocol major version for the given epoch. */
    default int getProtocolMajor(long epoch) { return 9; }

    /** Protocol minor version for the given epoch. */
    default int getProtocolMinor(long epoch) { return 0; }

    // --- Conway governance parameters ---

    /** Governance action lifetime in epochs. Default: 6 (preprod/mainnet). */
    default int getGovActionLifetime(long epoch) { return 6; }

    /** DRep activity window in epochs. Default: 20 (preprod/mainnet). */
    default int getDRepActivity(long epoch) { return 20; }

    /** Governance action deposit in lovelace. Default: 100,000 ADA. */
    default BigInteger getGovActionDeposit(long epoch) { return new BigInteger("100000000000"); }

    /** DRep deposit in lovelace. Default: 500 ADA. */
    default BigInteger getDRepDeposit(long epoch) { return new BigInteger("500000000"); }

    /** Committee minimum size. Default: 7. */
    default int getCommitteeMinSize(long epoch) { return 7; }

    /** Committee maximum term length in epochs. Default: 146. */
    default int getCommitteeMaxTermLength(long epoch) { return 146; }

    /**
     * DRep voting thresholds from Conway genesis (or the effective value after on-chain
     * updates, once that plumbing lands). May be null during tests or in non-Conway eras.
     * Production implementations MUST return a non-null value once Conway era is active,
     * otherwise governance ratification will fall back to possibly-wrong hard-coded defaults.
     */
    default DrepVoteThresholds getDrepVotingThresholds(long epoch) { return null; }

    /**
     * Pool voting thresholds from Conway genesis (or the effective value after on-chain
     * updates, once that plumbing lands). May be null during tests or in non-Conway eras.
     * Production implementations MUST return a non-null value once Conway era is active.
     */
    default PoolVotingThresholds getPoolVotingThresholds(long epoch) { return null; }

    /** Security parameter k (finality confirmation depth). Default: 2160 (mainnet). */
    default long getSecurityParam() { return 2160; }

    /** Active slots coefficient f. Default: 0.05 (mainnet). */
    default double getActiveSlotsCoeff() { return 0.05; }

    /** Randomness stabilisation window = floor(4k/f) slots. */
    default long getRandomnessStabilisationWindow() {
        return Math.round((4.0 * getSecurityParam()) / getActiveSlotsCoeff());
    }
}
