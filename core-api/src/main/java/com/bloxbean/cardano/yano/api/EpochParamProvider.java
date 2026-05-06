package com.bloxbean.cardano.yano.api;

import com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds;
import com.bloxbean.cardano.yaci.core.model.PoolVotingThresholds;
import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

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

    // --- Blockfrost-compatible protocol parameter fields ---

    default Integer getMinFeeA(long epoch) { return null; }

    default Integer getMinFeeB(long epoch) { return null; }

    default Integer getMaxBlockSize(long epoch) { return null; }

    default Integer getMaxTxSize(long epoch) { return null; }

    default Integer getMaxBlockHeaderSize(long epoch) { return null; }

    default Integer getMaxEpoch(long epoch) { return null; }

    default String getExtraEntropy(long epoch) { return null; }

    default BigInteger getMinUtxo(long epoch) { return null; }

    default Map<String, Object> getCostModels(long epoch) { return null; }

    default Map<String, Object> getCostModelsRaw(long epoch) { return null; }

    /** Cost models introduced through Alonzo genesis. */
    default Map<String, Object> getAlonzoCostModels(long epoch) { return getCostModels(epoch); }

    /** Cost models introduced through Conway genesis. */
    default Map<String, Object> getConwayCostModels(long epoch) { return null; }

    default BigDecimal getPriceMem(long epoch) { return null; }

    default BigDecimal getPriceStep(long epoch) { return null; }

    default BigInteger getMaxTxExMem(long epoch) { return null; }

    default BigInteger getMaxTxExSteps(long epoch) { return null; }

    default BigInteger getMaxBlockExMem(long epoch) { return null; }

    default BigInteger getMaxBlockExSteps(long epoch) { return null; }

    default BigInteger getMaxValSize(long epoch) { return null; }

    default Integer getCollateralPercent(long epoch) { return null; }

    default Integer getMaxCollateralInputs(long epoch) { return null; }

    default BigInteger getCoinsPerUtxoSize(long epoch) { return null; }

    default BigInteger getCoinsPerUtxoWord(long epoch) { return null; }

    default BigDecimal getMinFeeRefScriptCostPerByte(long epoch) { return null; }

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
    default BigDecimal getRho(long epoch) { return new BigDecimal("0.003"); }

    /** Exact monetary expansion rate, when available. */
    default UnitInterval getRhoInterval(long epoch) { return toUnitInterval(getRho(epoch)); }

    /** Treasury growth rate (τ). Fraction of reward pot going to treasury. */
    default BigDecimal getTau(long epoch) { return new BigDecimal("0.2"); }

    /** Exact treasury growth rate, when available. */
    default UnitInterval getTauInterval(long epoch) { return toUnitInterval(getTau(epoch)); }

    /** Pool influence factor (a₀). Higher = more influence of pledge on rewards. */
    default BigDecimal getA0(long epoch) { return new BigDecimal("0.3"); }

    /** Exact pool influence factor, when available. */
    default NonNegativeInterval getA0Interval(long epoch) { return toNonNegativeInterval(getA0(epoch)); }

    /** Decentralization parameter (d). 0 = fully decentralized. Pre-Alonzo only. */
    default BigDecimal getDecentralization(long epoch) { return BigDecimal.ZERO; }

    /** Exact decentralization parameter, when available. */
    default UnitInterval getDecentralizationInterval(long epoch) { return toUnitInterval(getDecentralization(epoch)); }

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

    /** Exact memory execution price, when available. */
    default NonNegativeInterval getPriceMemInterval(long epoch) { return toNonNegativeInterval(getPriceMem(epoch)); }

    /** Exact step execution price, when available. */
    default NonNegativeInterval getPriceStepInterval(long epoch) { return toNonNegativeInterval(getPriceStep(epoch)); }

    /** Exact reference script fee coefficient, when available. */
    default NonNegativeInterval getMinFeeRefScriptCostPerByteInterval(long epoch) {
        return toNonNegativeInterval(getMinFeeRefScriptCostPerByte(epoch));
    }

    private static UnitInterval toUnitInterval(BigDecimal value) {
        if (value == null) return null;
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = stripped.scale();
        if (scale <= 0) {
            return new UnitInterval(stripped.toBigIntegerExact(), BigInteger.ONE);
        }
        BigInteger denominator = BigInteger.TEN.pow(scale);
        BigInteger numerator = stripped.movePointRight(scale).toBigIntegerExact();
        return new UnitInterval(numerator, denominator);
    }

    private static NonNegativeInterval toNonNegativeInterval(BigDecimal value) {
        UnitInterval interval = toUnitInterval(value);
        return interval != null
                ? new NonNegativeInterval(interval.getNumerator(), interval.getDenominator())
                : null;
    }
}
