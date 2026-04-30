package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolParamsMapperTest {

    @Test
    void nodeProtocolParamJsonUsesCardanoNodeFieldNames() throws Exception {
        String json = """
                {
                  "txFeePerByte": 44,
                  "txFeeFixed": 155381,
                  "maxBlockBodySize": 90112,
                  "maxTxSize": 16384,
                  "maxBlockHeaderSize": 1100,
                  "stakeAddressDeposit": 2000000,
                  "stakePoolDeposit": 500000000,
                  "poolRetireMaxEpoch": 18,
                  "stakePoolTargetNum": 500,
                  "poolPledgeInfluence": 0.3,
                  "monetaryExpansion": 0.003,
                  "treasuryCut": 0.2,
                  "protocolVersion": { "major": 10, "minor": 2 },
                  "costModels": { "PlutusV1": { "b": 2, "a": 1 } },
                  "executionUnitPrices": { "priceMemory": 0.0577, "priceSteps": 0.0000721 },
                  "maxTxExecutionUnits": { "memory": 16500000, "steps": 10000000000 },
                  "maxBlockExecutionUnits": { "memory": 72000000, "steps": 20000000000 },
                  "maxValueSize": 5000,
                  "collateralPercentage": 150,
                  "maxCollateralInputs": 3,
                  "utxoCostPerByte": 4310,
                  "poolVotingThresholds": { "ppSecurityGroup": 0.51 },
                  "dRepVotingThresholds": { "ppGovGroup": 0.75 },
                  "committeeMinSize": 3,
                  "committeeMaxTermLength": 146,
                  "govActionLifetime": 6,
                  "govActionDeposit": 100000000000,
                  "dRepDeposit": 500000000,
                  "dRepActivity": 20,
                  "minFeeRefScriptCostPerByte": 15
                }
                """;

        ProtocolParams pp = ProtocolParamsMapper.fromNodeProtocolParam(json);

        assertEquals(44, pp.getMinFeeA());
        assertEquals(155381, pp.getMinFeeB());
        assertEquals(90112, pp.getMaxBlockSize());
        assertEquals(16384, pp.getMaxTxSize());
        assertEquals("2000000", pp.getKeyDeposit());
        assertEquals("500000000", pp.getPoolDeposit());
        assertEquals(10, pp.getProtocolMajorVer());
        assertEquals(2, pp.getProtocolMinorVer());
        assertEquals(new BigDecimal("0.0577"), pp.getPriceMem());
        assertEquals(new BigDecimal("0.0000721"), pp.getPriceStep());
        assertEquals("16500000", pp.getMaxTxExMem());
        assertEquals("10000000000", pp.getMaxTxExSteps());
        assertEquals("72000000", pp.getMaxBlockExMem());
        assertEquals("20000000000", pp.getMaxBlockExSteps());
        assertEquals("5000", pp.getMaxValSize());
        assertEquals(new BigDecimal("150"), pp.getCollateralPercent());
        assertEquals("4310", pp.getCoinsPerUtxoSize());
        assertEquals(new BigDecimal("0.51"), pp.getPvtPPSecurityGroup());
        assertEquals(new BigDecimal("0.75"), pp.getDvtPPGovGroup());
        assertEquals(BigInteger.valueOf(100_000_000_000L), pp.getGovActionDeposit());
        assertEquals(BigInteger.valueOf(500_000_000L), pp.getDrepDeposit());
        assertEquals(List.of(1L, 2L), new ArrayList<>(pp.getCostModels().get("PlutusV1").values()));
    }

    @Test
    void nodeProtocolParamJsonMapsFlatConwayThresholdNames() throws Exception {
        String json = """
                {
                  "protocolVersion": { "major": 10, "minor": 0 },
                  "pvt_motion_no_confidence": 0.51,
                  "pvt_committee_normal": 0.52,
                  "pvt_committee_no_confidence": 0.53,
                  "pvt_hard_fork_initiation": 0.54,
                  "pvt_p_p_security_group": 0.55,
                  "dvt_motion_no_confidence": 0.61,
                  "dvt_committee_normal": 0.62,
                  "dvt_committee_no_confidence": 0.63,
                  "dvt_update_to_constitution": 0.64,
                  "dvt_hard_fork_initiation": 0.65,
                  "dvt_p_p_network_group": 0.66,
                  "dvt_p_p_economic_group": 0.67,
                  "dvt_p_p_technical_group": 0.68,
                  "dvt_p_p_gov_group": 0.69,
                  "dvt_treasury_withdrawal": 0.70
                }
                """;

        ProtocolParams pp = ProtocolParamsMapper.fromNodeProtocolParam(json);

        assertEquals(new BigDecimal("0.51"), pp.getPvtMotionNoConfidence());
        assertEquals(new BigDecimal("0.52"), pp.getPvtCommitteeNormal());
        assertEquals(new BigDecimal("0.53"), pp.getPvtCommitteeNoConfidence());
        assertEquals(new BigDecimal("0.54"), pp.getPvtHardForkInitiation());
        assertEquals(new BigDecimal("0.55"), pp.getPvtPPSecurityGroup());
        assertEquals(new BigDecimal("0.61"), pp.getDvtMotionNoConfidence());
        assertEquals(new BigDecimal("0.62"), pp.getDvtCommitteeNormal());
        assertEquals(new BigDecimal("0.63"), pp.getDvtCommitteeNoConfidence());
        assertEquals(new BigDecimal("0.64"), pp.getDvtUpdateToConstitution());
        assertEquals(new BigDecimal("0.65"), pp.getDvtHardForkInitiation());
        assertEquals(new BigDecimal("0.66"), pp.getDvtPPNetworkGroup());
        assertEquals(new BigDecimal("0.67"), pp.getDvtPPEconomicGroup());
        assertEquals(new BigDecimal("0.68"), pp.getDvtPPTechnicalGroup());
        assertEquals(new BigDecimal("0.69"), pp.getDvtPPGovGroup());
        assertEquals(new BigDecimal("0.7"), pp.getDvtTreasuryWithdrawal());
    }

    @Test
    void ledgerSnapshotMapsToCclProtocolParams() {
        ProtocolParams pp = ProtocolParamsMapper.fromSnapshot(snapshot(285, BigInteger.valueOf(5000)));

        assertEquals(44, pp.getMinFeeA());
        assertEquals("5000", pp.getMaxValSize());
        assertEquals("4310", pp.getCoinsPerUtxoSize());
        assertEquals("16500000", pp.getMaxTxExMem());
        assertEquals(new BigDecimal("0.0577"), pp.getPriceMem());
        assertEquals(new BigDecimal("150"), pp.getCollateralPercent());
        assertEquals(List.of(10L, 20L), new ArrayList<>(pp.getCostModels().get("PlutusV1").values()));
    }

    static LedgerStateProvider.ProtocolParamsSnapshot snapshot(int epoch, BigInteger maxValSize) {
        return new LedgerStateProvider.ProtocolParamsSnapshot(
                epoch,
                44,
                155381,
                90112,
                16384,
                1100,
                BigInteger.valueOf(2_000_000),
                BigInteger.valueOf(500_000_000),
                18,
                500,
                new BigDecimal("0.3"),
                new BigDecimal("0.003"),
                new BigDecimal("0.2"),
                null,
                null,
                10,
                2,
                null,
                BigInteger.valueOf(170_000_000),
                null,
                Map.of("PlutusV1", Map.of("000", 10L, "001", 20L)),
                Map.of("PlutusV1", List.of(10L, 20L)),
                new BigDecimal("0.0577"),
                new BigDecimal("0.0000721"),
                BigInteger.valueOf(16_500_000),
                new BigInteger("10000000000"),
                BigInteger.valueOf(72_000_000),
                new BigInteger("20000000000"),
                maxValSize,
                150,
                3,
                BigInteger.valueOf(4310),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                3,
                146,
                6,
                BigInteger.valueOf(100_000_000_000L),
                BigInteger.valueOf(500_000_000L),
                20,
                new BigDecimal("15")
        );
    }
}
