package com.bloxbean.cardano.yano.runtime.config;

import com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds;
import com.bloxbean.cardano.yaci.core.model.PoolVotingThresholds;
import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.util.CostModelUtil;
import com.bloxbean.cardano.yano.runtime.genesis.ConwayGenesisData;
import com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default {@link EpochParamProvider} that reads protocol params from genesis files.
 * <p>
 * Preferred construction: {@link #fromNetworkGenesisConfig(NetworkGenesisConfig, long)}
 * which reads all values from parsed genesis. Legacy constructors are deprecated.
 */
public class DefaultEpochParamProvider implements EpochParamProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultEpochParamProvider.class);

    /**
     * Known first non-Byron slot values per network magic.
     * These are historical facts, not derivable from genesis files alone.
     * Only used at config/bootstrap edges.
     */
    private static final long MAINNET_FIRST_NON_BYRON_SLOT = 4492800;   // epoch 208 * 21600
    private static final long PREPROD_FIRST_NON_BYRON_SLOT = 86400;     // epoch 4 * 21600

    private final BigInteger keyDeposit;
    private final BigInteger poolDeposit;
    private final Integer minFeeA;
    private final Integer minFeeB;
    private final Integer maxBlockSize;
    private final Integer maxTxSize;
    private final Integer maxBlockHeaderSize;
    private final Integer maxEpoch;
    private final String extraEntropy;
    private final BigInteger minUtxo;
    private final long epochLength;
    private final long byronSlotsPerEpoch;
    private final long shelleyStartSlot;
    private final long securityParam;
    private final double activeSlotsCoeff;

    // Genesis protocol params for reward calculation
    private final int genesisNOpt;
    private final BigDecimal genesisDecentralization;
    private final BigDecimal genesisRho;
    private final BigDecimal genesisTau;
    private final BigDecimal genesisA0;
    private final UnitInterval genesisDecentralizationInterval;
    private final UnitInterval genesisRhoInterval;
    private final UnitInterval genesisTauInterval;
    private final NonNegativeInterval genesisA0Interval;
    private final int genesisProtocolMajor;
    private final int genesisProtocolMinor;
    private final BigInteger genesisMinPoolCost;
    private final Map<String, Object> genesisCostModels;
    private final Map<String, Object> genesisAlonzoCostModels;
    private final Map<String, Object> genesisConwayCostModels;
    private final BigDecimal genesisPriceMem;
    private final BigDecimal genesisPriceStep;
    private final NonNegativeInterval genesisPriceMemInterval;
    private final NonNegativeInterval genesisPriceStepInterval;
    private final BigInteger genesisMaxTxExMem;
    private final BigInteger genesisMaxTxExSteps;
    private final BigInteger genesisMaxBlockExMem;
    private final BigInteger genesisMaxBlockExSteps;
    private final BigInteger genesisMaxValSize;
    private final Integer genesisCollateralPercent;
    private final Integer genesisMaxCollateralInputs;
    private final BigInteger genesisCoinsPerUtxoWord;

    // Conway governance params
    private final int genesisGovActionLifetime;
    private final int genesisDRepActivity;
    private final BigInteger genesisGovActionDeposit;
    private final BigInteger genesisDRepDeposit;
    private final int genesisCommitteeMinSize;
    private final int genesisCommitteeMaxTermLength;
    private final DrepVoteThresholds genesisDrepVotingThresholds;
    private final PoolVotingThresholds genesisPoolVotingThresholds;
    private final BigDecimal genesisMinFeeRefScriptCostPerByte;
    private final NonNegativeInterval genesisMinFeeRefScriptCostPerByteInterval;

    // --- Factory method (preferred) ---

    /**
     * Create a provider from a parsed {@link NetworkGenesisConfig} and a resolved firstNonByronSlot.
     * <p>
     * This is the preferred production construction path. All values come from genesis — no hardcoded
     * network-specific switches.
     *
     * @param config             parsed genesis config
     * @param firstNonByronSlot  first non-Byron era slot for epoch math (NOT CF shelleyStartEpoch)
     */
    public static DefaultEpochParamProvider fromNetworkGenesisConfig(
            NetworkGenesisConfig config, long firstNonByronSlot) {
        ShelleyGenesisData shelley = config.getShelleyGenesisData();
        var alonzo = config.getAlonzoGenesisData();
        ConwayGenesisData conway = config.getConwayGenesisData();

        var provider = new DefaultEpochParamProvider(
                shelley.epochLength(),
                config.getByronSlotsPerEpoch(),
                firstNonByronSlot,
                shelley.securityParam(),
                shelley.activeSlotsCoeff(),
                BigInteger.valueOf(shelley.keyDeposit()),
                BigInteger.valueOf(shelley.poolDeposit()),
                shelley.minFeeA(),
                shelley.minFeeB(),
                shelley.maxBlockBodySize(),
                shelley.maxTxSize(),
                shelley.maxBlockHeaderSize(),
                shelley.eMax(),
                shelley.extraEntropy(),
                BigInteger.valueOf(shelley.minUTxOValue()),
                shelley.nOpt(),
                shelley.decentralisationParam(),
                shelley.rho(),
                shelley.tau(),
                shelley.a0(),
                toUnitInterval(shelley.decentralisationParam()),
                toUnitInterval(shelley.rho()),
                toUnitInterval(shelley.tau()),
                toNonNegativeInterval(shelley.a0()),
                (int) shelley.protocolMajor(),
                (int) shelley.protocolMinor(),
                BigInteger.valueOf(shelley.minPoolCost()),
                alonzo != null ? alonzo.costModels() : null,
                conway != null ? conway.costModels() : null,
                mergeCostModels(alonzo != null ? alonzo.costModels() : null,
                        conway != null ? conway.costModels() : null),
                alonzo != null ? alonzo.priceMem() : null,
                alonzo != null ? alonzo.priceStep() : null,
                alonzo != null ? alonzo.priceMemInterval() : null,
                alonzo != null ? alonzo.priceStepInterval() : null,
                alonzo != null ? alonzo.maxTxExMem() : null,
                alonzo != null ? alonzo.maxTxExSteps() : null,
                alonzo != null ? alonzo.maxBlockExMem() : null,
                alonzo != null ? alonzo.maxBlockExSteps() : null,
                alonzo != null ? alonzo.maxValSize() : null,
                alonzo != null ? alonzo.collateralPercent() : null,
                alonzo != null ? alonzo.maxCollateralInputs() : null,
                alonzo != null ? alonzo.coinsPerUtxoWord() : null,
                conway
        );

        log.info("DefaultEpochParamProvider built from genesis: epochLength={}, byronSlotsPerEpoch={}, " +
                        "firstNonByronSlot={}, securityParam={}, nOpt={}, conway={}",
                shelley.epochLength(), config.getByronSlotsPerEpoch(), firstNonByronSlot,
                shelley.securityParam(), shelley.nOpt(), conway != null ? "available" : "none");

        return provider;
    }

    /**
     * Resolve the first non-Byron slot for a given network.
     * <p>
     * Order: known-network constants → no Byron genesis means 0 → unknown + Byron = error.
     * <p>
     * TODO (Phase 6): Add persisted era metadata lookup for restart/custom network support.
     * Currently only uses known-network constants, which covers all production networks.
     *
     * @param protocolMagic   network magic
     * @param hasByronGenesis whether a Byron genesis file is configured
     * @return first non-Byron slot
     * @throws IllegalStateException if unknown network has Byron genesis but no persisted metadata
     */
    public static long resolveFirstNonByronSlot(long protocolMagic, boolean hasByronGenesis) {
        // Known networks
        return switch ((int) protocolMagic) {
            case 764824073 -> MAINNET_FIRST_NON_BYRON_SLOT;
            case 1 -> PREPROD_FIRST_NON_BYRON_SLOT;
            case 2, 4 -> 0; // preview, sanchonet: no Byron era for epoch math
            default -> {
                if (!hasByronGenesis) {
                    yield 0; // No Byron genesis → assume no Byron era
                }
                throw new IllegalStateException(
                        "Unknown network (magic=" + protocolMagic + ") with Byron genesis configured. "
                                + "Cannot determine first non-Byron slot. Either sync from genesis "
                                + "or provide explicit first-non-byron-slot configuration.");
            }
        };
    }

    // --- Full constructor (internal) ---

    private DefaultEpochParamProvider(long epochLength, long byronSlotsPerEpoch, long shelleyStartSlot,
                                      long securityParam, double activeSlotsCoeff,
                                      BigInteger keyDeposit, BigInteger poolDeposit,
                                      Integer minFeeA, Integer minFeeB,
                                      Integer maxBlockSize, Integer maxTxSize, Integer maxBlockHeaderSize,
                                      Integer maxEpoch, String extraEntropy, BigInteger minUtxo,
                                      int nOpt, BigDecimal decentralization,
                                      BigDecimal rho, BigDecimal tau, BigDecimal a0,
                                      UnitInterval decentralizationInterval,
                                      UnitInterval rhoInterval,
                                      UnitInterval tauInterval,
                                      NonNegativeInterval a0Interval,
                                      int protoMajor, int protoMinor, BigInteger minPoolCost,
                                      Map<String, Object> alonzoCostModels,
                                      Map<String, Object> conwayCostModels,
                                      Map<String, Object> costModels,
                                      BigDecimal priceMem, BigDecimal priceStep,
                                      NonNegativeInterval priceMemInterval, NonNegativeInterval priceStepInterval,
                                      BigInteger maxTxExMem, BigInteger maxTxExSteps,
                                      BigInteger maxBlockExMem, BigInteger maxBlockExSteps,
                                      BigInteger maxValSize, Integer collateralPercent,
                                      Integer maxCollateralInputs, BigInteger coinsPerUtxoWord,
                                      ConwayGenesisData conway) {
        this.epochLength = epochLength;
        this.byronSlotsPerEpoch = byronSlotsPerEpoch;
        this.shelleyStartSlot = shelleyStartSlot;
        this.securityParam = securityParam;
        this.activeSlotsCoeff = activeSlotsCoeff;
        this.keyDeposit = keyDeposit;
        this.poolDeposit = poolDeposit;
        this.minFeeA = minFeeA;
        this.minFeeB = minFeeB;
        this.maxBlockSize = maxBlockSize;
        this.maxTxSize = maxTxSize;
        this.maxBlockHeaderSize = maxBlockHeaderSize;
        this.maxEpoch = maxEpoch;
        this.extraEntropy = extraEntropy;
        this.minUtxo = minUtxo;
        this.genesisNOpt = nOpt;
        this.genesisDecentralization = decentralization;
        this.genesisRho = rho;
        this.genesisTau = tau;
        this.genesisA0 = a0;
        this.genesisDecentralizationInterval = decentralizationInterval;
        this.genesisRhoInterval = rhoInterval;
        this.genesisTauInterval = tauInterval;
        this.genesisA0Interval = a0Interval;
        this.genesisProtocolMajor = protoMajor;
        this.genesisProtocolMinor = protoMinor;
        this.genesisMinPoolCost = minPoolCost;
        this.genesisCostModels = costModels;
        this.genesisAlonzoCostModels = alonzoCostModels;
        this.genesisConwayCostModels = conwayCostModels;
        this.genesisPriceMem = priceMem;
        this.genesisPriceStep = priceStep;
        this.genesisPriceMemInterval = priceMemInterval;
        this.genesisPriceStepInterval = priceStepInterval;
        this.genesisMaxTxExMem = maxTxExMem;
        this.genesisMaxTxExSteps = maxTxExSteps;
        this.genesisMaxBlockExMem = maxBlockExMem;
        this.genesisMaxBlockExSteps = maxBlockExSteps;
        this.genesisMaxValSize = maxValSize;
        this.genesisCollateralPercent = collateralPercent;
        this.genesisMaxCollateralInputs = maxCollateralInputs;
        this.genesisCoinsPerUtxoWord = coinsPerUtxoWord;

        // Conway params from genesis or defaults
        if (conway != null) {
            this.genesisGovActionLifetime = conway.govActionLifetime();
            this.genesisDRepActivity = conway.dRepActivity();
            this.genesisGovActionDeposit = conway.govActionDeposit();
            this.genesisDRepDeposit = conway.dRepDeposit();
            this.genesisCommitteeMinSize = conway.committeeMinSize();
            this.genesisCommitteeMaxTermLength = conway.committeeMaxTermLength();
            this.genesisDrepVotingThresholds = conway.drepVotingThresholds();
            this.genesisPoolVotingThresholds = conway.poolVotingThresholds();
            this.genesisMinFeeRefScriptCostPerByte = conway.minFeeRefScriptCostPerByte();
            this.genesisMinFeeRefScriptCostPerByteInterval = conway.minFeeRefScriptCostPerByteInterval();
        } else {
            // Interface defaults
            this.genesisGovActionLifetime = 6;
            this.genesisDRepActivity = 20;
            this.genesisGovActionDeposit = new BigInteger("100000000000");
            this.genesisDRepDeposit = new BigInteger("500000000");
            this.genesisCommitteeMinSize = 7;
            this.genesisCommitteeMaxTermLength = 146;
            this.genesisDrepVotingThresholds = null;
            this.genesisPoolVotingThresholds = null;
            this.genesisMinFeeRefScriptCostPerByte = null;
            this.genesisMinFeeRefScriptCostPerByteInterval = null;
        }
    }


    @Override
    public BigInteger getKeyDeposit(long epoch) {
        return keyDeposit;
    }

    @Override
    public BigInteger getPoolDeposit(long epoch) {
        return poolDeposit;
    }

    @Override
    public Integer getMinFeeA(long epoch) {
        return minFeeA;
    }

    @Override
    public Integer getMinFeeB(long epoch) {
        return minFeeB;
    }

    @Override
    public Integer getMaxBlockSize(long epoch) {
        return maxBlockSize;
    }

    @Override
    public Integer getMaxTxSize(long epoch) {
        return maxTxSize;
    }

    @Override
    public Integer getMaxBlockHeaderSize(long epoch) {
        return maxBlockHeaderSize;
    }

    @Override
    public Integer getMaxEpoch(long epoch) {
        return maxEpoch;
    }

    @Override
    public String getExtraEntropy(long epoch) {
        return extraEntropy;
    }

    @Override
    public BigInteger getMinUtxo(long epoch) {
        return minUtxo;
    }

    @Override
    public Map<String, Object> getCostModels(long epoch) {
        return CostModelUtil.canonicalCostModels(genesisCostModels);
    }

    @Override
    public Map<String, Object> getAlonzoCostModels(long epoch) {
        return genesisAlonzoCostModels;
    }

    @Override
    public Map<String, Object> getConwayCostModels(long epoch) {
        return genesisConwayCostModels;
    }

    @Override
    public Map<String, Object> getCostModelsRaw(long epoch) {
        return CostModelUtil.canonicalRawCostModels(genesisCostModels);
    }

    @Override
    public BigDecimal getPriceMem(long epoch) {
        return genesisPriceMem;
    }

    @Override
    public NonNegativeInterval getPriceMemInterval(long epoch) {
        return genesisPriceMemInterval;
    }

    @Override
    public BigDecimal getPriceStep(long epoch) {
        return genesisPriceStep;
    }

    @Override
    public NonNegativeInterval getPriceStepInterval(long epoch) {
        return genesisPriceStepInterval;
    }

    @Override
    public BigInteger getMaxTxExMem(long epoch) {
        return genesisMaxTxExMem;
    }

    @Override
    public BigInteger getMaxTxExSteps(long epoch) {
        return genesisMaxTxExSteps;
    }

    @Override
    public BigInteger getMaxBlockExMem(long epoch) {
        return genesisMaxBlockExMem;
    }

    @Override
    public BigInteger getMaxBlockExSteps(long epoch) {
        return genesisMaxBlockExSteps;
    }

    @Override
    public BigInteger getMaxValSize(long epoch) {
        return genesisMaxValSize;
    }

    @Override
    public Integer getCollateralPercent(long epoch) {
        return genesisCollateralPercent;
    }

    @Override
    public Integer getMaxCollateralInputs(long epoch) {
        return genesisMaxCollateralInputs;
    }

    @Override
    public BigInteger getCoinsPerUtxoWord(long epoch) {
        return genesisCoinsPerUtxoWord;
    }

    @Override
    public long getEpochLength() {
        return epochLength;
    }

    @Override
    public long getByronSlotsPerEpoch() {
        return byronSlotsPerEpoch;
    }

    @Override
    public long getShelleyStartSlot() {
        return shelleyStartSlot;
    }

    @Override
    public long getSecurityParam() {
        return securityParam;
    }

    @Override
    public double getActiveSlotsCoeff() {
        return activeSlotsCoeff;
    }

    @Override
    public int getNOpt(long epoch) {
        return genesisNOpt;
    }

    @Override
    public BigDecimal getDecentralization(long epoch) {
        return genesisDecentralization;
    }

    @Override
    public UnitInterval getDecentralizationInterval(long epoch) {
        return genesisDecentralizationInterval;
    }

    @Override
    public BigDecimal getRho(long epoch) {
        return genesisRho;
    }

    @Override
    public UnitInterval getRhoInterval(long epoch) {
        return genesisRhoInterval;
    }

    @Override
    public BigDecimal getTau(long epoch) {
        return genesisTau;
    }

    @Override
    public UnitInterval getTauInterval(long epoch) {
        return genesisTauInterval;
    }

    @Override
    public BigDecimal getA0(long epoch) {
        return genesisA0;
    }

    @Override
    public NonNegativeInterval getA0Interval(long epoch) {
        return genesisA0Interval;
    }

    @Override
    public int getProtocolMajor(long epoch) {
        return genesisProtocolMajor;
    }

    @Override
    public int getProtocolMinor(long epoch) {
        return genesisProtocolMinor;
    }

    @Override
    public BigInteger getMinPoolCost(long epoch) {
        return genesisMinPoolCost;
    }

    // --- Conway governance parameter overrides ---

    @Override
    public int getGovActionLifetime(long epoch) {
        return genesisGovActionLifetime;
    }

    @Override
    public int getDRepActivity(long epoch) {
        return genesisDRepActivity;
    }

    @Override
    public BigInteger getGovActionDeposit(long epoch) {
        return genesisGovActionDeposit;
    }

    @Override
    public BigInteger getDRepDeposit(long epoch) {
        return genesisDRepDeposit;
    }

    @Override
    public int getCommitteeMinSize(long epoch) {
        return genesisCommitteeMinSize;
    }

    @Override
    public int getCommitteeMaxTermLength(long epoch) {
        return genesisCommitteeMaxTermLength;
    }

    @Override
    public DrepVoteThresholds getDrepVotingThresholds(long epoch) {
        return genesisDrepVotingThresholds;
    }

    @Override
    public PoolVotingThresholds getPoolVotingThresholds(long epoch) {
        return genesisPoolVotingThresholds;
    }

    @Override
    public BigDecimal getMinFeeRefScriptCostPerByte(long epoch) {
        return genesisMinFeeRefScriptCostPerByte;
    }

    @Override
    public NonNegativeInterval getMinFeeRefScriptCostPerByteInterval(long epoch) {
        return genesisMinFeeRefScriptCostPerByteInterval;
    }

    private static Map<String, Object> mergeCostModels(Map<String, Object> older, Map<String, Object> newer) {
        if ((older == null || older.isEmpty()) && (newer == null || newer.isEmpty())) {
            return null;
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        if (older != null) merged.putAll(older);
        if (newer != null) merged.putAll(newer);
        return merged;
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
