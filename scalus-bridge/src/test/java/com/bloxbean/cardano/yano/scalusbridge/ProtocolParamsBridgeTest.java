package com.bloxbean.cardano.yano.scalusbridge;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import org.junit.jupiter.api.Test;
import scalus.cardano.ledger.NonNegativeInterval;
import scalus.cardano.ledger.UnitInterval;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolParamsBridgeTest {

    @Test
    void everyProtocolParameterMapsToItsOwnField() {
        ProtocolParams pp = baseParams(11);
        pp.setProtocolMinorVer(1);
        pp.setMinFeeA(45);
        pp.setMinFeeB(155382);
        pp.setMaxBlockSize(90113);
        pp.setMaxTxSize(16385);
        pp.setMaxBlockHeaderSize(1101);
        pp.setKeyDeposit("2000001");
        pp.setPoolDeposit("500000009");
        pp.setEMax(19);
        pp.setNOpt(501);
        pp.setA0(new BigDecimal("0.31"));
        pp.setRho(new BigDecimal("0.004"));
        pp.setTau(new BigDecimal("0.22"));
        pp.setMinPoolCost("340000001");

        var plutusV1 = new LinkedHashMap<String, Long>();
        plutusV1.put("000", 61L);
        plutusV1.put("001", 62L);
        var costModels = new LinkedHashMap<String, LinkedHashMap<String, Long>>();
        costModels.put("PlutusV1", plutusV1);
        pp.setCostModels(costModels);
        pp.setPriceMem(new BigDecimal("0.0577"));
        pp.setPriceStep(new BigDecimal("0.0000721"));
        pp.setMaxTxExMem("10000001");
        pp.setMaxTxExSteps("10000000001");
        pp.setMaxBlockExMem("50000001");
        pp.setMaxBlockExSteps("40000000001");
        pp.setMaxValSize("5001");
        pp.setCollateralPercent(new BigDecimal("152"));
        pp.setMaxCollateralInputs(4);
        pp.setCoinsPerUtxoSize("4311");

        pp.setGovActionDeposit(BigInteger.valueOf(100_000_000_003L));
        pp.setGovActionLifetime(7);
        pp.setDrepDeposit(BigInteger.valueOf(500_000_003L));
        pp.setDrepActivity(21);
        pp.setCommitteeMinSize(5);
        pp.setCommitteeMaxTermLength(148);
        pp.setMinFeeRefScriptCostPerByte(new BigDecimal("16"));
        pp.setPvtMotionNoConfidence(new BigDecimal("0.41"));
        pp.setPvtCommitteeNormal(new BigDecimal("0.42"));
        pp.setPvtCommitteeNoConfidence(new BigDecimal("0.43"));
        pp.setPvtHardForkInitiation(new BigDecimal("0.44"));
        pp.setPvtPPSecurityGroup(new BigDecimal("0.45"));
        pp.setDvtMotionNoConfidence(new BigDecimal("0.51"));
        pp.setDvtCommitteeNormal(new BigDecimal("0.52"));
        pp.setDvtCommitteeNoConfidence(new BigDecimal("0.53"));
        pp.setDvtUpdateToConstitution(new BigDecimal("0.54"));
        pp.setDvtHardForkInitiation(new BigDecimal("0.55"));
        pp.setDvtPPNetworkGroup(new BigDecimal("0.56"));
        pp.setDvtPPEconomicGroup(new BigDecimal("0.57"));
        pp.setDvtPPTechnicalGroup(new BigDecimal("0.58"));
        pp.setDvtPPGovGroup(new BigDecimal("0.59"));
        pp.setDvtTreasuryWithdrawal(new BigDecimal("0.60"));

        var scalusParams = ProtocolParamsBridge$.MODULE$.toScalusProtocolParams(pp);

        assertEquals(152L, scalusParams.collateralPercentage());
        assertEquals(148L, scalusParams.committeeMaxTermLength());
        assertEquals(5L, scalusParams.committeeMinSize());
        assertEquals(1, scalusParams.costModels().models().size());
        var convertedCostModel = scalusParams.costModels().models().values().head();
        assertEquals(2, convertedCostModel.size());
        assertEquals(61L, ((Number) convertedCostModel.apply(0)).longValue());
        assertEquals(62L, ((Number) convertedCostModel.apply(1)).longValue());
        assertEquals(21L, scalusParams.dRepActivity());
        assertEquals(500_000_003L, scalusParams.dRepDeposit());
        assertUnitInterval(scalusParams.dRepVotingThresholds().motionNoConfidence(), 51, 100);
        assertUnitInterval(scalusParams.dRepVotingThresholds().committeeNormal(), 13, 25);
        assertUnitInterval(scalusParams.dRepVotingThresholds().committeeNoConfidence(), 53, 100);
        assertUnitInterval(scalusParams.dRepVotingThresholds().updateToConstitution(), 27, 50);
        assertUnitInterval(scalusParams.dRepVotingThresholds().hardForkInitiation(), 11, 20);
        assertUnitInterval(scalusParams.dRepVotingThresholds().ppNetworkGroup(), 14, 25);
        assertUnitInterval(scalusParams.dRepVotingThresholds().ppEconomicGroup(), 57, 100);
        assertUnitInterval(scalusParams.dRepVotingThresholds().ppTechnicalGroup(), 29, 50);
        assertUnitInterval(scalusParams.dRepVotingThresholds().ppGovGroup(), 59, 100);
        assertUnitInterval(scalusParams.dRepVotingThresholds().treasuryWithdrawal(), 3, 5);
        assertNonNegativeInterval(scalusParams.executionUnitPrices().priceMemory(), 577, 10_000);
        assertNonNegativeInterval(scalusParams.executionUnitPrices().priceSteps(), 721, 10_000_000);
        assertEquals(100_000_000_003L, scalusParams.govActionDeposit());
        assertEquals(7L, scalusParams.govActionLifetime());
        assertEquals(90_113L, scalusParams.maxBlockBodySize());
        assertEquals(50_000_001L, scalusParams.maxBlockExecutionUnits().memory());
        assertEquals(40_000_000_001L, scalusParams.maxBlockExecutionUnits().steps());
        assertEquals(1_101L, scalusParams.maxBlockHeaderSize());
        assertEquals(4L, scalusParams.maxCollateralInputs());
        assertEquals(10_000_001L, scalusParams.maxTxExecutionUnits().memory());
        assertEquals(10_000_000_001L, scalusParams.maxTxExecutionUnits().steps());
        assertEquals(16_385L, scalusParams.maxTxSize());
        assertEquals(5_001L, scalusParams.maxValueSize());
        assertEquals(16L, scalusParams.minFeeRefScriptCostPerByte());
        assertEquals(340_000_001L, scalusParams.minPoolCost());
        assertEquals(0.004, scalusParams.monetaryExpansion());
        assertEquals(0.31, scalusParams.poolPledgeInfluence());
        assertEquals(19L, scalusParams.poolRetireMaxEpoch());
        assertUnitInterval(scalusParams.poolVotingThresholds().motionNoConfidence(), 41, 100);
        assertUnitInterval(scalusParams.poolVotingThresholds().committeeNormal(), 21, 50);
        assertUnitInterval(scalusParams.poolVotingThresholds().committeeNoConfidence(), 43, 100);
        assertUnitInterval(scalusParams.poolVotingThresholds().hardForkInitiation(), 11, 25);
        assertUnitInterval(scalusParams.poolVotingThresholds().ppSecurityGroup(), 9, 20);
        assertEquals(11, scalusParams.protocolVersion().major());
        assertEquals(1, scalusParams.protocolVersion().minor());
        assertEquals(2_000_001L, scalusParams.stakeAddressDeposit());
        assertEquals(500_000_009L, scalusParams.stakePoolDeposit());
        assertEquals(501L, scalusParams.stakePoolTargetNum());
        assertEquals(0.22, scalusParams.treasuryCut());
        // The A/B crossing is intentional: minFeeB is fixed and minFeeA is per byte.
        assertEquals(155_382L, scalusParams.txFeeFixed());
        assertEquals(45L, scalusParams.txFeePerByte());
        assertEquals(4_311L, scalusParams.utxoCostPerByte());
    }

    @Test
    void executionUnitLimitsKeepMemoryAndStepsInScalusOrder() {
        ProtocolParams pp = baseParams(11);
        addAlonzoParams(pp);
        addConwayParams(pp);

        var scalusParams = ProtocolParamsBridge$.MODULE$.toScalusProtocolParams(pp);

        assertEquals(50_000_000L, scalusParams.maxBlockExecutionUnits().memory());
        assertEquals(40_000_000_000L, scalusParams.maxBlockExecutionUnits().steps());
        assertEquals(10_000_000L, scalusParams.maxTxExecutionUnits().memory());
        assertEquals(10_000_000_000L, scalusParams.maxTxExecutionUnits().steps());
    }

    @Test
    void intervalProtocolParamsKeepExactDecimalRationals() {
        ProtocolParams pp = baseParams(4);
        pp.setPriceMem(new BigDecimal("0.0577"));
        pp.setPriceStep(new BigDecimal("0.0000721"));
        pp.setDvtPPEconomicGroup(new BigDecimal("0.75"));

        var scalusParams = ProtocolParamsBridge$.MODULE$.toScalusProtocolParams(pp);

        NonNegativeInterval priceMem = scalusParams.executionUnitPrices().priceMemory();
        assertEquals(577L, priceMem.numerator());
        assertEquals(10_000L, priceMem.denominator());

        NonNegativeInterval priceStep = scalusParams.executionUnitPrices().priceSteps();
        assertEquals(721L, priceStep.numerator());
        assertEquals(10_000_000L, priceStep.denominator());

        UnitInterval economicThreshold = scalusParams.dRepVotingThresholds().ppEconomicGroup();
        assertEquals(3L, economicThreshold.numerator());
        assertEquals(4L, economicThreshold.denominator());
    }

    @Test
    void alonzoOrLaterRequiresMaxValueSizeBeforeScalusConversion() {
        ProtocolParams pp = baseParams(5);
        addAlonzoParams(pp);
        pp.setMaxValSize(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ProtocolParamsBridge$.MODULE$.toScalusProtocolParams(pp));

        assertTrue(ex.getMessage().contains("maxValSize"));
    }

    @Test
    void preBabbageUsesCoinsPerUtxoWordFallback() {
        ProtocolParams pp = baseParams(5);
        addAlonzoParams(pp);
        pp.setCoinsPerUtxoSize(null);
        pp.setCoinsPerUtxoWord("34482");

        var scalusParams = ProtocolParamsBridge$.MODULE$.toScalusProtocolParams(pp);

        assertEquals(4310L, scalusParams.utxoCostPerByte());
    }

    @Test
    void babbageOrLaterRequiresCoinsPerUtxoSize() {
        ProtocolParams pp = baseParams(7);
        addAlonzoParams(pp);
        pp.setCoinsPerUtxoSize(null);
        pp.setCoinsPerUtxoWord("34482");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ProtocolParamsBridge$.MODULE$.toScalusProtocolParams(pp));

        assertTrue(ex.getMessage().contains("coinsPerUtxoSize"));
    }

    @Test
    void conwayRequiresVotingThresholds() {
        ProtocolParams pp = baseParams(9);
        addAlonzoParams(pp);
        addConwayParams(pp);
        pp.setDvtMotionNoConfidence(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ProtocolParamsBridge$.MODULE$.toScalusProtocolParams(pp));

        assertTrue(ex.getMessage().contains("dvtMotionNoConfidence"));
    }

    @Test
    void conwayRefScriptFeeMustBeIntegralForCurrentScalusModel() {
        ProtocolParams pp = baseParams(9);
        addAlonzoParams(pp);
        addConwayParams(pp);
        pp.setMinFeeRefScriptCostPerByte(new BigDecimal("15.5"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ProtocolParamsBridge$.MODULE$.toScalusProtocolParams(pp));

        assertTrue(ex.getMessage().contains("minFeeRefScriptCostPerByte"));
    }

    private ProtocolParams baseParams(int protocolMajor) {
        ProtocolParams pp = new ProtocolParams();
        pp.setProtocolMajorVer(protocolMajor);
        pp.setProtocolMinorVer(0);
        pp.setMinFeeA(44);
        pp.setMinFeeB(155381);
        pp.setMaxBlockSize(90112);
        pp.setMaxTxSize(16384);
        pp.setMaxBlockHeaderSize(1100);
        pp.setKeyDeposit("2000000");
        pp.setPoolDeposit("500000000");
        pp.setEMax(18);
        pp.setNOpt(500);
        pp.setA0(new BigDecimal("0.3"));
        pp.setRho(new BigDecimal("0.003"));
        pp.setTau(new BigDecimal("0.2"));
        pp.setMinPoolCost("340000000");
        return pp;
    }

    private void addAlonzoParams(ProtocolParams pp) {
        pp.setCostModels(costModels());
        pp.setPriceMem(new BigDecimal("0.0577"));
        pp.setPriceStep(new BigDecimal("0.0000721"));
        pp.setMaxTxExMem("10000000");
        pp.setMaxTxExSteps("10000000000");
        pp.setMaxBlockExMem("50000000");
        pp.setMaxBlockExSteps("40000000000");
        pp.setMaxValSize("5000");
        pp.setCollateralPercent(new BigDecimal("150"));
        pp.setMaxCollateralInputs(3);
        pp.setCoinsPerUtxoSize("4310");
    }

    private void addConwayParams(ProtocolParams pp) {
        pp.setGovActionDeposit(BigDecimal.valueOf(100_000_000_000L).toBigInteger());
        pp.setGovActionLifetime(6);
        pp.setDrepDeposit(BigDecimal.valueOf(500_000_000L).toBigInteger());
        pp.setDrepActivity(20);
        pp.setCommitteeMinSize(3);
        pp.setCommitteeMaxTermLength(146);
        pp.setMinFeeRefScriptCostPerByte(new BigDecimal("15"));
        pp.setPvtMotionNoConfidence(new BigDecimal("0.51"));
        pp.setPvtCommitteeNormal(new BigDecimal("0.51"));
        pp.setPvtCommitteeNoConfidence(new BigDecimal("0.51"));
        pp.setPvtHardForkInitiation(new BigDecimal("0.51"));
        pp.setPvtPPSecurityGroup(new BigDecimal("0.51"));
        pp.setDvtMotionNoConfidence(new BigDecimal("0.67"));
        pp.setDvtCommitteeNormal(new BigDecimal("0.67"));
        pp.setDvtCommitteeNoConfidence(new BigDecimal("0.6"));
        pp.setDvtUpdateToConstitution(new BigDecimal("0.75"));
        pp.setDvtHardForkInitiation(new BigDecimal("0.6"));
        pp.setDvtPPNetworkGroup(new BigDecimal("0.67"));
        pp.setDvtPPEconomicGroup(new BigDecimal("0.67"));
        pp.setDvtPPTechnicalGroup(new BigDecimal("0.67"));
        pp.setDvtPPGovGroup(new BigDecimal("0.75"));
        pp.setDvtTreasuryWithdrawal(new BigDecimal("0.67"));
    }

    private LinkedHashMap<String, LinkedHashMap<String, Long>> costModels() {
        var plutusV1 = new LinkedHashMap<String, Long>();
        plutusV1.put("000", 1L);
        plutusV1.put("001", 2L);
        var costModels = new LinkedHashMap<String, LinkedHashMap<String, Long>>();
        costModels.put("PlutusV1", plutusV1);
        return costModels;
    }

    private void assertUnitInterval(UnitInterval actual, long numerator, long denominator) {
        assertEquals(numerator, actual.numerator());
        assertEquals(denominator, actual.denominator());
    }

    private void assertNonNegativeInterval(NonNegativeInterval actual, long numerator, long denominator) {
        assertEquals(numerator, actual.numerator());
        assertEquals(denominator, actual.denominator());
    }
}
