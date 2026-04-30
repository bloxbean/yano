package com.bloxbean.cardano.yano.scalusbridge;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import org.junit.jupiter.api.Test;
import scalus.cardano.ledger.NonNegativeInterval;
import scalus.cardano.ledger.UnitInterval;

import java.math.BigDecimal;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolParamsBridgeTest {

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
}
