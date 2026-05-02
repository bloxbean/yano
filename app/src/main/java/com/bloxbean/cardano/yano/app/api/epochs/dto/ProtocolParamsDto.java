package com.bloxbean.cardano.yano.app.api.epochs.dto;

import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ProtocolParamsDto {
    private Integer epoch;
    private Integer minFeeA;
    private Integer minFeeB;
    private Integer maxBlockSize;
    private Integer maxTxSize;
    private Integer maxBlockHeaderSize;
    private String keyDeposit;
    private String poolDeposit;
    @JsonProperty("e_max")
    private Integer eMax;
    @JsonProperty("n_opt")
    private Integer nOpt;
    private BigDecimal a0;
    private BigDecimal rho;
    private BigDecimal tau;
    private BigDecimal decentralisationParam;
    private String extraEntropy;
    private Integer protocolMajorVer;
    private Integer protocolMinorVer;
    private String minUtxo;
    private String minPoolCost;
    private String nonce;

    //Alonzo changes
//    private Map<String, Map<String, Long>> costModels;
    private Map<String, Object> costModels;
    private Map<String, Object> costModelsRaw;
    private BigDecimal priceMem;
    private BigDecimal priceStep;
    private String maxTxExMem;
    private String maxTxExSteps;
    private String maxBlockExMem;
    private String maxBlockExSteps;
    private String maxValSize;
    private BigDecimal collateralPercent;
    private Integer maxCollateralInputs;

    //Cost per UTxO word for Alonzo.
    //Cost per UTxO byte for Babbage and later.
    private String coinsPerUtxoSize;
    @Deprecated
    private String coinsPerUtxoWord;

    //Conway era
    private BigDecimal pvtMotionNoConfidence;
    private BigDecimal pvtCommitteeNormal;
    private BigDecimal pvtCommitteeNoConfidence;
    private BigDecimal pvtHardForkInitiation;
    @JsonProperty("pvt_p_p_security_group")
    private BigDecimal pvtPPSecurityGroup;

    private BigDecimal dvtMotionNoConfidence;
    private BigDecimal dvtCommitteeNormal;
    private BigDecimal dvtCommitteeNoConfidence;
    private BigDecimal dvtUpdateToConstitution;
    private BigDecimal dvtHardForkInitiation;
    @JsonProperty("dvt_p_p_network_group")
    private BigDecimal dvtPPNetworkGroup;
    @JsonProperty("dvt_p_p_economic_group")
    private BigDecimal dvtPPEconomicGroup;
    @JsonProperty("dvt_p_p_technical_group")
    private BigDecimal dvtPPTechnicalGroup;
    @JsonProperty("dvt_p_p_gov_group")
    private BigDecimal dvtPPGovGroup;
    private BigDecimal dvtTreasuryWithdrawal;

    private Integer committeeMinSize;
    private Integer committeeMaxTermLength;
    private Integer govActionLifetime;
    private BigInteger govActionDeposit;
    private BigInteger drepDeposit;
    private Integer drepActivity;
    private BigDecimal minFeeRefScriptCostPerByte;

    //To align with Blockfrost
    @JsonProperty("pvtpp_security_group")
    public BigDecimal getPvtppSecurityGroup() {
        return pvtPPSecurityGroup;
    }

    @JsonProperty("e_max")
    public Integer getEMax() {
        return eMax;
    }

    @JsonProperty("n_opt")
    public Integer getNOpt() {
        return nOpt;
    }

    public static ProtocolParamsDto from(LedgerStateProvider.ProtocolParamsSnapshot snapshot) {
        return from(snapshot, null);
    }

    public static ProtocolParamsDto from(LedgerStateProvider.ProtocolParamsSnapshot snapshot, String epochNonce) {
        ProtocolParamsDto dto = new ProtocolParamsDto();
        dto.setEpoch(snapshot.epoch());
        dto.setMinFeeA(snapshot.minFeeA());
        dto.setMinFeeB(snapshot.minFeeB());
        dto.setMaxBlockSize(snapshot.maxBlockSize());
        dto.setMaxTxSize(snapshot.maxTxSize());
        dto.setMaxBlockHeaderSize(snapshot.maxBlockHeaderSize());
        dto.setKeyDeposit(lovelace(snapshot.keyDeposit()));
        dto.setPoolDeposit(lovelace(snapshot.poolDeposit()));
        dto.setEMax(snapshot.eMax());
        dto.setNOpt(snapshot.nOpt());
        dto.setA0(snapshot.a0());
        dto.setRho(snapshot.rho());
        dto.setTau(snapshot.tau());
        dto.setDecentralisationParam(snapshot.decentralisationParam() != null
                ? snapshot.decentralisationParam()
                : BigDecimal.ZERO);
        dto.setExtraEntropy(snapshot.extraEntropy());
        dto.setProtocolMajorVer(snapshot.protocolMajorVer());
        dto.setProtocolMinorVer(snapshot.protocolMinorVer());
        dto.setMinUtxo(lovelace(blockfrostMinUtxo(snapshot)));
        dto.setMinPoolCost(lovelace(snapshot.minPoolCost()));
        dto.setNonce(snapshot.nonce() != null ? snapshot.nonce() : epochNonce);
        dto.setCostModels(snapshot.costModels());
        dto.setCostModelsRaw(snapshot.costModelsRaw());
        dto.setPriceMem(snapshot.priceMem());
        dto.setPriceStep(snapshot.priceStep());
        dto.setMaxTxExMem(lovelace(snapshot.maxTxExMem()));
        dto.setMaxTxExSteps(lovelace(snapshot.maxTxExSteps()));
        dto.setMaxBlockExMem(lovelace(snapshot.maxBlockExMem()));
        dto.setMaxBlockExSteps(lovelace(snapshot.maxBlockExSteps()));
        dto.setMaxValSize(lovelace(snapshot.maxValSize()));
        dto.setCollateralPercent(snapshot.collateralPercent() != null
                ? BigDecimal.valueOf(snapshot.collateralPercent())
                : null);
        dto.setMaxCollateralInputs(snapshot.maxCollateralInputs());
        dto.setCoinsPerUtxoSize(lovelace(firstNonNull(snapshot.coinsPerUtxoSize(), snapshot.coinsPerUtxoWord())));
        dto.setCoinsPerUtxoWord(lovelace(firstNonNull(snapshot.coinsPerUtxoWord(), snapshot.coinsPerUtxoSize())));
        dto.setPvtMotionNoConfidence(snapshot.pvtMotionNoConfidence());
        dto.setPvtCommitteeNormal(snapshot.pvtCommitteeNormal());
        dto.setPvtCommitteeNoConfidence(snapshot.pvtCommitteeNoConfidence());
        dto.setPvtHardForkInitiation(snapshot.pvtHardForkInitiation());
        dto.setPvtPPSecurityGroup(snapshot.pvtPPSecurityGroup());
        dto.setDvtMotionNoConfidence(snapshot.dvtMotionNoConfidence());
        dto.setDvtCommitteeNormal(snapshot.dvtCommitteeNormal());
        dto.setDvtCommitteeNoConfidence(snapshot.dvtCommitteeNoConfidence());
        dto.setDvtUpdateToConstitution(snapshot.dvtUpdateToConstitution());
        dto.setDvtHardForkInitiation(snapshot.dvtHardForkInitiation());
        dto.setDvtPPNetworkGroup(snapshot.dvtPPNetworkGroup());
        dto.setDvtPPEconomicGroup(snapshot.dvtPPEconomicGroup());
        dto.setDvtPPTechnicalGroup(snapshot.dvtPPTechnicalGroup());
        dto.setDvtPPGovGroup(snapshot.dvtPPGovGroup());
        dto.setDvtTreasuryWithdrawal(snapshot.dvtTreasuryWithdrawal());
        dto.setCommitteeMinSize(snapshot.committeeMinSize());
        dto.setCommitteeMaxTermLength(snapshot.committeeMaxTermLength());
        dto.setGovActionLifetime(snapshot.govActionLifetime());
        dto.setGovActionDeposit(snapshot.govActionDeposit());
        dto.setDrepDeposit(snapshot.drepDeposit());
        dto.setDrepActivity(snapshot.drepActivity());
        dto.setMinFeeRefScriptCostPerByte(snapshot.minFeeRefScriptCostPerByte());
        return dto;
    }

    private static BigInteger blockfrostMinUtxo(LedgerStateProvider.ProtocolParamsSnapshot snapshot) {
        return firstNonNull(snapshot.minUtxo(), snapshot.coinsPerUtxoSize(), snapshot.coinsPerUtxoWord());
    }

    private static BigInteger firstNonNull(BigInteger... values) {
        for (BigInteger value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static String lovelace(BigInteger value) {
        return value != null ? value.toString() : null;
    }
}
