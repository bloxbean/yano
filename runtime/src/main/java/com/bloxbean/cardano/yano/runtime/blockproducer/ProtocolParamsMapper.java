package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;
import com.bloxbean.cardano.yano.api.util.CostModelUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Converts Yano ledger snapshots and cardano-node protocol-param.json to CCL {@link ProtocolParams}.
 */
public class ProtocolParamsMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /**
     * Parse a cardano-node protocol parameters JSON string into a CCL {@link ProtocolParams}.
     */
    public static ProtocolParams fromNodeProtocolParam(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        ProtocolParams pp = new ProtocolParams();

        setInt(root, pp::setMinFeeA, "minFeeA", "min_fee_a", "txFeePerByte");
        setInt(root, pp::setMinFeeB, "minFeeB", "min_fee_b", "txFeeFixed");
        setInt(root, pp::setMaxBlockSize, "maxBlockSize", "max_block_size", "maxBlockBodySize");
        setInt(root, pp::setMaxTxSize, "maxTxSize", "max_tx_size");
        setInt(root, pp::setMaxBlockHeaderSize, "maxBlockHeaderSize", "max_block_header_size");
        setString(root, pp::setKeyDeposit, "keyDeposit", "key_deposit", "stakeAddressDeposit");
        setString(root, pp::setPoolDeposit, "poolDeposit", "pool_deposit", "stakePoolDeposit");
        setInt(root, pp::setEMax, "eMax", "e_max", "poolRetireMaxEpoch");
        setInt(root, pp::setNOpt, "nOpt", "n_opt", "stakePoolTargetNum");
        setBigDecimal(root, pp::setA0, "a0", "poolPledgeInfluence");
        setBigDecimal(root, pp::setRho, "rho", "monetaryExpansion");
        setBigDecimal(root, pp::setTau, "tau", "treasuryCut");
        setBigDecimal(root, pp::setDecentralisationParam, "decentralisationParam", "decentralization", "decentralisation_param");
        setText(root, pp::setExtraEntropy, "extraEntropy", "extra_entropy", "extraPraosEntropy");
        setString(root, pp::setMinUtxo, "minUtxo", "min_utxo", "minUTxOValue");
        setString(root, pp::setMinPoolCost, "minPoolCost", "min_pool_cost");
        setText(root, pp::setNonce, "nonce");

        JsonNode protocolVersion = node(root, "protocolVersion", "protocol_version");
        if (protocolVersion != null) {
            setInt(protocolVersion, pp::setProtocolMajorVer, "major");
            setInt(protocolVersion, pp::setProtocolMinorVer, "minor");
        } else {
            setInt(root, pp::setProtocolMajorVer, "protocolMajorVer", "protocol_major_ver");
            setInt(root, pp::setProtocolMinorVer, "protocolMinorVer", "protocol_minor_ver");
        }

        JsonNode costModels = node(root, "costModels", "cost_models");
        if (costModels != null && costModels.isObject()) {
            pp.setCostModels(toCostModels(MAPPER.convertValue(costModels, MAP_TYPE)));
        }

        JsonNode executionUnitPrices = node(root, "executionUnitPrices", "execution_unit_prices");
        if (executionUnitPrices != null) {
            setBigDecimal(executionUnitPrices, pp::setPriceMem, "priceMemory", "memory", "mem");
            setBigDecimal(executionUnitPrices, pp::setPriceStep, "priceSteps", "steps", "step");
        } else {
            setBigDecimal(root, pp::setPriceMem, "priceMem", "price_mem");
            setBigDecimal(root, pp::setPriceStep, "priceStep", "price_step");
        }

        JsonNode maxTxExecutionUnits = node(root, "maxTxExecutionUnits", "max_tx_execution_units");
        if (maxTxExecutionUnits != null) {
            setString(maxTxExecutionUnits, pp::setMaxTxExMem, "memory", "mem");
            setString(maxTxExecutionUnits, pp::setMaxTxExSteps, "steps", "step");
        } else {
            setString(root, pp::setMaxTxExMem, "maxTxExMem", "max_tx_ex_mem");
            setString(root, pp::setMaxTxExSteps, "maxTxExSteps", "max_tx_ex_steps");
        }

        JsonNode maxBlockExecutionUnits = node(root, "maxBlockExecutionUnits", "max_block_execution_units");
        if (maxBlockExecutionUnits != null) {
            setString(maxBlockExecutionUnits, pp::setMaxBlockExMem, "memory", "mem");
            setString(maxBlockExecutionUnits, pp::setMaxBlockExSteps, "steps", "step");
        } else {
            setString(root, pp::setMaxBlockExMem, "maxBlockExMem", "max_block_ex_mem");
            setString(root, pp::setMaxBlockExSteps, "maxBlockExSteps", "max_block_ex_steps");
        }

        setString(root, pp::setMaxValSize, "maxValSize", "max_val_size", "maxValueSize");
        setBigDecimal(root, pp::setCollateralPercent, "collateralPercent", "collateral_percent", "collateralPercentage");
        setInt(root, pp::setMaxCollateralInputs, "maxCollateralInputs", "max_collateral_inputs");
        setString(root, pp::setCoinsPerUtxoSize, "coinsPerUtxoSize", "coins_per_utxo_size", "utxoCostPerByte");
        setString(root, pp::setCoinsPerUtxoWord, "coinsPerUtxoWord", "coins_per_utxo_word", "utxoCostPerWord");

        JsonNode poolVotingThresholds = node(root, "poolVotingThresholds", "pool_voting_thresholds");
        setBigDecimal(poolVotingThresholds, root, pp::setPvtMotionNoConfidence,
                new String[]{"motionNoConfidence", "motion_no_confidence"},
                "pvtMotionNoConfidence", "pvt_motion_no_confidence");
        setBigDecimal(poolVotingThresholds, root, pp::setPvtCommitteeNormal,
                new String[]{"committeeNormal", "committee_normal"},
                "pvtCommitteeNormal", "pvt_committee_normal");
        setBigDecimal(poolVotingThresholds, root, pp::setPvtCommitteeNoConfidence,
                new String[]{"committeeNoConfidence", "committee_no_confidence"},
                "pvtCommitteeNoConfidence", "pvt_committee_no_confidence");
        setBigDecimal(poolVotingThresholds, root, pp::setPvtHardForkInitiation,
                new String[]{"hardForkInitiation", "hard_fork_initiation"},
                "pvtHardForkInitiation", "pvt_hard_fork_initiation");
        setBigDecimal(poolVotingThresholds, root, pp::setPvtPPSecurityGroup,
                new String[]{"ppSecurityGroup", "pp_security_group", "securityRelevantParamVotingThreshold"},
                "pvtPPSecurityGroup", "pvt_p_p_security_group");

        JsonNode drepVotingThresholds = node(root, "dRepVotingThresholds", "drepVotingThresholds", "drep_voting_thresholds");
        setBigDecimal(drepVotingThresholds, root, pp::setDvtMotionNoConfidence,
                new String[]{"motionNoConfidence", "motion_no_confidence"},
                "dvtMotionNoConfidence", "dvt_motion_no_confidence");
        setBigDecimal(drepVotingThresholds, root, pp::setDvtCommitteeNormal,
                new String[]{"committeeNormal", "committee_normal"},
                "dvtCommitteeNormal", "dvt_committee_normal");
        setBigDecimal(drepVotingThresholds, root, pp::setDvtCommitteeNoConfidence,
                new String[]{"committeeNoConfidence", "committee_no_confidence"},
                "dvtCommitteeNoConfidence", "dvt_committee_no_confidence");
        setBigDecimal(drepVotingThresholds, root, pp::setDvtUpdateToConstitution,
                new String[]{"updateToConstitution", "update_to_constitution"},
                "dvtUpdateToConstitution", "dvt_update_to_constitution");
        setBigDecimal(drepVotingThresholds, root, pp::setDvtHardForkInitiation,
                new String[]{"hardForkInitiation", "hard_fork_initiation"},
                "dvtHardForkInitiation", "dvt_hard_fork_initiation");
        setBigDecimal(drepVotingThresholds, root, pp::setDvtPPNetworkGroup,
                new String[]{"ppNetworkGroup", "pp_network_group"},
                "dvtPPNetworkGroup", "dvt_p_p_network_group");
        setBigDecimal(drepVotingThresholds, root, pp::setDvtPPEconomicGroup,
                new String[]{"ppEconomicGroup", "pp_economic_group"},
                "dvtPPEconomicGroup", "dvt_p_p_economic_group");
        setBigDecimal(drepVotingThresholds, root, pp::setDvtPPTechnicalGroup,
                new String[]{"ppTechnicalGroup", "pp_technical_group"},
                "dvtPPTechnicalGroup", "dvt_p_p_technical_group");
        setBigDecimal(drepVotingThresholds, root, pp::setDvtPPGovGroup,
                new String[]{"ppGovGroup", "pp_gov_group"},
                "dvtPPGovGroup", "dvt_p_p_gov_group");
        setBigDecimal(drepVotingThresholds, root, pp::setDvtTreasuryWithdrawal,
                new String[]{"treasuryWithdrawal", "treasury_withdrawal"},
                "dvtTreasuryWithdrawal", "dvt_treasury_withdrawal");

        setInt(root, pp::setCommitteeMinSize, "committeeMinSize", "committee_min_size");
        setInt(root, pp::setCommitteeMaxTermLength, "committeeMaxTermLength", "committee_max_term_length");
        setInt(root, pp::setGovActionLifetime, "govActionLifetime", "gov_action_lifetime");
        setBigInteger(root, pp::setGovActionDeposit, "govActionDeposit", "gov_action_deposit");
        setBigInteger(root, pp::setDrepDeposit, "dRepDeposit", "drepDeposit", "drep_deposit");
        setInt(root, pp::setDrepActivity, "dRepActivity", "drepActivity", "drep_activity");
        setBigDecimal(root, pp::setMinFeeRefScriptCostPerByte, "minFeeRefScriptCostPerByte", "min_fee_ref_script_cost_per_byte");

        return pp;
    }

    /**
     * Map a Yano {@link ProtocolParamsSnapshot} to a cardano-client-lib
     * {@link ProtocolParams} for tx construction (QuickTx / script evaluation).
     * Copies the full {@code costModelsRaw} — the raw PlutusV3 cost vector the
     * evaluator needs (which {@link #fromNodeProtocolParam(String)} does not
     * populate). Returns {@code null} if the snapshot is null or lacks fees.
     */
    public static ProtocolParams toCardanoClient(ProtocolParamsSnapshot snapshot) {
        if (snapshot == null || snapshot.minFeeA() == null || snapshot.minFeeB() == null) {
            return null;
        }
        LinkedHashMap<String, List<Long>> costModelsRaw = snapshot.costModelsRaw() != null
                ? new LinkedHashMap<>(snapshot.costModelsRaw())
                : new LinkedHashMap<>();
        return ProtocolParams.builder()
                .minFeeA(snapshot.minFeeA())
                .minFeeB(snapshot.minFeeB())
                .maxTxSize(snapshot.maxTxSize())
                .priceMem(snapshot.priceMem())
                .priceStep(snapshot.priceStep())
                .maxTxExMem(snapshot.maxTxExMem() != null ? snapshot.maxTxExMem().toString() : null)
                .maxTxExSteps(snapshot.maxTxExSteps() != null ? snapshot.maxTxExSteps().toString() : null)
                .collateralPercent(snapshot.collateralPercent() != null
                        ? BigDecimal.valueOf(snapshot.collateralPercent()) : null)
                .maxCollateralInputs(snapshot.maxCollateralInputs())
                .coinsPerUtxoSize(snapshot.coinsPerUtxoSize() != null
                        ? snapshot.coinsPerUtxoSize().toString() : null)
                .costModelsRaw(costModelsRaw)
                .build();
    }

    /**
     * Parse a cardano-node protocol-param.json into a CCL {@link ProtocolParams}
     * WITH the raw cost models (unlike {@link #fromNodeProtocolParam(String)},
     * which only sets the named/object cost models). Used as the static fallback
     * source for app-chain anchor tx construction.
     */
    public static ProtocolParams fromNodeProtocolParamToCardanoClient(String json, int epoch)
            throws IOException {
        return toCardanoClient(fromNodeProtocolParamSnapshot(json, epoch));
    }

    public static ProtocolParamsSnapshot fromNodeProtocolParamSnapshot(String json, int epoch) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode protocolVersion = node(root, "protocolVersion", "protocol_version");
        JsonNode executionUnitPrices = node(root, "executionUnitPrices", "execution_unit_prices");
        JsonNode maxTxExecutionUnits = node(root, "maxTxExecutionUnits", "max_tx_execution_units");
        JsonNode maxBlockExecutionUnits = node(root, "maxBlockExecutionUnits", "max_block_execution_units");
        JsonNode poolVotingThresholds = node(root, "poolVotingThresholds", "pool_voting_thresholds");
        JsonNode drepVotingThresholds = node(root, "dRepVotingThresholds", "drepVotingThresholds", "drep_voting_thresholds");

        Map<String, Object> costModelsInput = objectMap(node(root, "costModels", "cost_models"));
        Map<String, Object> costModelsRawInput = objectMap(node(root, "costModelsRaw", "cost_models_raw"));
        Map<String, ?> projectionInput = costModelsInput != null ? costModelsInput : costModelsRawInput;
        Map<String, ?> rawInput = costModelsRawInput != null ? costModelsRawInput : costModelsInput;

        return new ProtocolParamsSnapshot(
                epoch,
                intValue(root, "minFeeA", "min_fee_a", "txFeePerByte"),
                intValue(root, "minFeeB", "min_fee_b", "txFeeFixed"),
                intValue(root, "maxBlockSize", "max_block_size", "maxBlockBodySize"),
                intValue(root, "maxTxSize", "max_tx_size"),
                intValue(root, "maxBlockHeaderSize", "max_block_header_size"),
                bigIntegerValue(root, "keyDeposit", "key_deposit", "stakeAddressDeposit"),
                bigIntegerValue(root, "poolDeposit", "pool_deposit", "stakePoolDeposit"),
                intValue(root, "eMax", "e_max", "poolRetireMaxEpoch"),
                intValue(root, "nOpt", "n_opt", "stakePoolTargetNum"),
                bigDecimalValue(root, "a0", "poolPledgeInfluence"),
                bigDecimalValue(root, "rho", "monetaryExpansion"),
                bigDecimalValue(root, "tau", "treasuryCut"),
                bigDecimalValue(root, "decentralisationParam", "decentralization", "decentralisation_param"),
                textValue(root, "extraEntropy", "extra_entropy", "extraPraosEntropy"),
                protocolVersion != null
                        ? intValue(protocolVersion, "major")
                        : intValue(root, "protocolMajorVer", "protocol_major_ver"),
                protocolVersion != null
                        ? intValue(protocolVersion, "minor")
                        : intValue(root, "protocolMinorVer", "protocol_minor_ver"),
                bigIntegerValue(root, "minUtxo", "min_utxo", "minUTxOValue"),
                bigIntegerValue(root, "minPoolCost", "min_pool_cost"),
                textValue(root, "nonce"),
                CostModelUtil.canonicalCostModelsTyped(projectionInput),
                CostModelUtil.canonicalRawCostModelsTyped(rawInput),
                executionUnitPrices != null
                        ? bigDecimalValue(executionUnitPrices, "priceMemory", "memory", "mem")
                        : bigDecimalValue(root, "priceMem", "price_mem"),
                executionUnitPrices != null
                        ? bigDecimalValue(executionUnitPrices, "priceSteps", "steps", "step")
                        : bigDecimalValue(root, "priceStep", "price_step"),
                maxTxExecutionUnits != null
                        ? bigIntegerValue(maxTxExecutionUnits, "memory", "mem")
                        : bigIntegerValue(root, "maxTxExMem", "max_tx_ex_mem"),
                maxTxExecutionUnits != null
                        ? bigIntegerValue(maxTxExecutionUnits, "steps", "step")
                        : bigIntegerValue(root, "maxTxExSteps", "max_tx_ex_steps"),
                maxBlockExecutionUnits != null
                        ? bigIntegerValue(maxBlockExecutionUnits, "memory", "mem")
                        : bigIntegerValue(root, "maxBlockExMem", "max_block_ex_mem"),
                maxBlockExecutionUnits != null
                        ? bigIntegerValue(maxBlockExecutionUnits, "steps", "step")
                        : bigIntegerValue(root, "maxBlockExSteps", "max_block_ex_steps"),
                bigIntegerValue(root, "maxValSize", "max_val_size", "maxValueSize"),
                intValue(root, "collateralPercent", "collateral_percent", "collateralPercentage"),
                intValue(root, "maxCollateralInputs", "max_collateral_inputs"),
                bigIntegerValue(root, "coinsPerUtxoSize", "coins_per_utxo_size", "utxoCostPerByte"),
                bigIntegerValue(root, "coinsPerUtxoWord", "coins_per_utxo_word", "utxoCostPerWord"),
                bigDecimalValue(poolVotingThresholds, root,
                        new String[]{"motionNoConfidence", "motion_no_confidence"},
                        "pvtMotionNoConfidence", "pvt_motion_no_confidence"),
                bigDecimalValue(poolVotingThresholds, root,
                        new String[]{"committeeNormal", "committee_normal"},
                        "pvtCommitteeNormal", "pvt_committee_normal"),
                bigDecimalValue(poolVotingThresholds, root,
                        new String[]{"committeeNoConfidence", "committee_no_confidence"},
                        "pvtCommitteeNoConfidence", "pvt_committee_no_confidence"),
                bigDecimalValue(poolVotingThresholds, root,
                        new String[]{"hardForkInitiation", "hard_fork_initiation"},
                        "pvtHardForkInitiation", "pvt_hard_fork_initiation"),
                bigDecimalValue(poolVotingThresholds, root,
                        new String[]{"ppSecurityGroup", "pp_security_group", "securityRelevantParamVotingThreshold"},
                        "pvtPPSecurityGroup", "pvt_p_p_security_group", "pvtpp_security_group"),
                bigDecimalValue(drepVotingThresholds, root,
                        new String[]{"motionNoConfidence", "motion_no_confidence"},
                        "dvtMotionNoConfidence", "dvt_motion_no_confidence"),
                bigDecimalValue(drepVotingThresholds, root,
                        new String[]{"committeeNormal", "committee_normal"},
                        "dvtCommitteeNormal", "dvt_committee_normal"),
                bigDecimalValue(drepVotingThresholds, root,
                        new String[]{"committeeNoConfidence", "committee_no_confidence"},
                        "dvtCommitteeNoConfidence", "dvt_committee_no_confidence"),
                bigDecimalValue(drepVotingThresholds, root,
                        new String[]{"updateToConstitution", "update_to_constitution"},
                        "dvtUpdateToConstitution", "dvt_update_to_constitution"),
                bigDecimalValue(drepVotingThresholds, root,
                        new String[]{"hardForkInitiation", "hard_fork_initiation"},
                        "dvtHardForkInitiation", "dvt_hard_fork_initiation"),
                bigDecimalValue(drepVotingThresholds, root,
                        new String[]{"ppNetworkGroup", "pp_network_group"},
                        "dvtPPNetworkGroup", "dvt_p_p_network_group"),
                bigDecimalValue(drepVotingThresholds, root,
                        new String[]{"ppEconomicGroup", "pp_economic_group"},
                        "dvtPPEconomicGroup", "dvt_p_p_economic_group"),
                bigDecimalValue(drepVotingThresholds, root,
                        new String[]{"ppTechnicalGroup", "pp_technical_group"},
                        "dvtPPTechnicalGroup", "dvt_p_p_technical_group"),
                bigDecimalValue(drepVotingThresholds, root,
                        new String[]{"ppGovGroup", "pp_gov_group"},
                        "dvtPPGovGroup", "dvt_p_p_gov_group"),
                bigDecimalValue(drepVotingThresholds, root,
                        new String[]{"treasuryWithdrawal", "treasury_withdrawal"},
                        "dvtTreasuryWithdrawal", "dvt_treasury_withdrawal"),
                intValue(root, "committeeMinSize", "committee_min_size"),
                intValue(root, "committeeMaxTermLength", "committee_max_term_length"),
                intValue(root, "govActionLifetime", "gov_action_lifetime"),
                bigIntegerValue(root, "govActionDeposit", "gov_action_deposit"),
                bigIntegerValue(root, "dRepDeposit", "drepDeposit", "drep_deposit"),
                intValue(root, "dRepActivity", "drepActivity", "drep_activity"),
                bigDecimalValue(root, "minFeeRefScriptCostPerByte", "min_fee_ref_script_cost_per_byte")
        );
    }

    public static ProtocolParams fromSnapshot(ProtocolParamsSnapshot snapshot) {
        ProtocolParams pp = new ProtocolParams();
        pp.setMinFeeA(snapshot.minFeeA());
        pp.setMinFeeB(snapshot.minFeeB());
        pp.setMaxBlockSize(snapshot.maxBlockSize());
        pp.setMaxTxSize(snapshot.maxTxSize());
        pp.setMaxBlockHeaderSize(snapshot.maxBlockHeaderSize());
        pp.setKeyDeposit(string(snapshot.keyDeposit()));
        pp.setPoolDeposit(string(snapshot.poolDeposit()));
        pp.setEMax(snapshot.eMax());
        pp.setNOpt(snapshot.nOpt());
        pp.setA0(snapshot.a0());
        pp.setRho(snapshot.rho());
        pp.setTau(snapshot.tau());
        pp.setDecentralisationParam(snapshot.decentralisationParam());
        pp.setExtraEntropy(snapshot.extraEntropy());
        pp.setProtocolMajorVer(snapshot.protocolMajorVer());
        pp.setProtocolMinorVer(snapshot.protocolMinorVer());
        pp.setMinUtxo(string(snapshot.minUtxo()));
        pp.setMinPoolCost(string(snapshot.minPoolCost()));
        pp.setNonce(snapshot.nonce());
        Map<String, ?> costModels = nonEmpty(snapshot.costModelsRaw()) ? snapshot.costModelsRaw() : snapshot.costModels();
        pp.setCostModels(toCostModels(costModels));
        pp.setPriceMem(snapshot.priceMem());
        pp.setPriceStep(snapshot.priceStep());
        pp.setMaxTxExMem(string(snapshot.maxTxExMem()));
        pp.setMaxTxExSteps(string(snapshot.maxTxExSteps()));
        pp.setMaxBlockExMem(string(snapshot.maxBlockExMem()));
        pp.setMaxBlockExSteps(string(snapshot.maxBlockExSteps()));
        pp.setMaxValSize(string(snapshot.maxValSize()));
        pp.setCollateralPercent(snapshot.collateralPercent() != null ? BigDecimal.valueOf(snapshot.collateralPercent()) : null);
        pp.setMaxCollateralInputs(snapshot.maxCollateralInputs());
        pp.setCoinsPerUtxoSize(string(snapshot.coinsPerUtxoSize()));
        pp.setCoinsPerUtxoWord(string(snapshot.coinsPerUtxoWord()));
        pp.setPvtMotionNoConfidence(snapshot.pvtMotionNoConfidence());
        pp.setPvtCommitteeNormal(snapshot.pvtCommitteeNormal());
        pp.setPvtCommitteeNoConfidence(snapshot.pvtCommitteeNoConfidence());
        pp.setPvtHardForkInitiation(snapshot.pvtHardForkInitiation());
        pp.setPvtPPSecurityGroup(snapshot.pvtPPSecurityGroup());
        pp.setDvtMotionNoConfidence(snapshot.dvtMotionNoConfidence());
        pp.setDvtCommitteeNormal(snapshot.dvtCommitteeNormal());
        pp.setDvtCommitteeNoConfidence(snapshot.dvtCommitteeNoConfidence());
        pp.setDvtUpdateToConstitution(snapshot.dvtUpdateToConstitution());
        pp.setDvtHardForkInitiation(snapshot.dvtHardForkInitiation());
        pp.setDvtPPNetworkGroup(snapshot.dvtPPNetworkGroup());
        pp.setDvtPPEconomicGroup(snapshot.dvtPPEconomicGroup());
        pp.setDvtPPTechnicalGroup(snapshot.dvtPPTechnicalGroup());
        pp.setDvtPPGovGroup(snapshot.dvtPPGovGroup());
        pp.setDvtTreasuryWithdrawal(snapshot.dvtTreasuryWithdrawal());
        pp.setCommitteeMinSize(snapshot.committeeMinSize());
        pp.setCommitteeMaxTermLength(snapshot.committeeMaxTermLength());
        pp.setGovActionLifetime(snapshot.govActionLifetime());
        pp.setGovActionDeposit(snapshot.govActionDeposit());
        pp.setDrepDeposit(snapshot.drepDeposit());
        pp.setDrepActivity(snapshot.drepActivity());
        pp.setMinFeeRefScriptCostPerByte(snapshot.minFeeRefScriptCostPerByte());
        return pp;
    }

    public static ProtocolParams fromEpochParamProvider(EpochParamProvider provider, int epoch) {
        if (provider == null) {
            throw new IllegalArgumentException("EpochParamProvider must not be null");
        }

        var poolThresholds = provider.getPoolVotingThresholds(epoch);
        var drepThresholds = provider.getDrepVotingThresholds(epoch);
        return fromSnapshot(new ProtocolParamsSnapshot(
                epoch,
                provider.getMinFeeA(epoch),
                provider.getMinFeeB(epoch),
                provider.getMaxBlockSize(epoch),
                provider.getMaxTxSize(epoch),
                provider.getMaxBlockHeaderSize(epoch),
                provider.getKeyDeposit(epoch),
                provider.getPoolDeposit(epoch),
                provider.getMaxEpoch(epoch),
                provider.getNOpt(epoch),
                provider.getA0(epoch),
                provider.getRho(epoch),
                provider.getTau(epoch),
                provider.getDecentralization(epoch),
                provider.getExtraEntropy(epoch),
                provider.getProtocolMajor(epoch),
                provider.getProtocolMinor(epoch),
                provider.getMinUtxo(epoch),
                provider.getMinPoolCost(epoch),
                null,
                CostModelUtil.canonicalCostModelsTyped(provider.getCostModels(epoch)),
                CostModelUtil.canonicalRawCostModelsTyped(provider.getCostModelsRaw(epoch)),
                provider.getPriceMem(epoch),
                provider.getPriceStep(epoch),
                provider.getMaxTxExMem(epoch),
                provider.getMaxTxExSteps(epoch),
                provider.getMaxBlockExMem(epoch),
                provider.getMaxBlockExSteps(epoch),
                provider.getMaxValSize(epoch),
                provider.getCollateralPercent(epoch),
                provider.getMaxCollateralInputs(epoch),
                provider.getCoinsPerUtxoSize(epoch),
                provider.getCoinsPerUtxoWord(epoch),
                ratio(poolThresholds != null ? poolThresholds.getPvtMotionNoConfidence() : null),
                ratio(poolThresholds != null ? poolThresholds.getPvtCommitteeNormal() : null),
                ratio(poolThresholds != null ? poolThresholds.getPvtCommitteeNoConfidence() : null),
                ratio(poolThresholds != null ? poolThresholds.getPvtHardForkInitiation() : null),
                ratio(poolThresholds != null ? poolThresholds.getPvtPPSecurityGroup() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtMotionNoConfidence() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtCommitteeNormal() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtCommitteeNoConfidence() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtUpdateToConstitution() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtHardForkInitiation() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtPPNetworkGroup() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtPPEconomicGroup() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtPPTechnicalGroup() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtPPGovGroup() : null),
                ratio(drepThresholds != null ? drepThresholds.getDvtTreasuryWithdrawal() : null),
                provider.getCommitteeMinSize(epoch),
                provider.getCommitteeMaxTermLength(epoch),
                provider.getGovActionLifetime(epoch),
                provider.getGovActionDeposit(epoch),
                provider.getDRepDeposit(epoch),
                provider.getDRepActivity(epoch),
                provider.getMinFeeRefScriptCostPerByte(epoch)
        ));
    }

    private static LinkedHashMap<String, LinkedHashMap<String, Long>> toCostModels(Map<String, ?> costModels) {
        Map<String, LinkedHashMap<String, Long>> canonical = CostModelUtil.canonicalCostModelsTyped(costModels);
        if (canonical == null || canonical.isEmpty()) return null;

        LinkedHashMap<String, LinkedHashMap<String, Long>> result = new LinkedHashMap<>();
        canonical.forEach((language, model) -> {
            LinkedHashMap<String, Long> indexed = new LinkedHashMap<>();
            model.forEach((key, value) -> indexed.put(key, toLong(value)));
            result.put(language, indexed);
        });
        return result;
    }

    private static JsonNode node(JsonNode root, String... names) {
        if (root == null) return null;
        for (String name : names) {
            JsonNode value = root.get(name);
            if (value != null && !value.isNull()) return value;
        }
        return null;
    }

    private static boolean nonEmpty(Map<String, ?> value) {
        return value != null && !value.isEmpty();
    }

    private static BigDecimal ratio(UnitInterval interval) {
        return interval != null ? interval.safeRatio() : null;
    }

    private static void setInt(JsonNode root, Consumer<Integer> setter, String... names) {
        JsonNode value = node(root, names);
        if (value != null) setter.accept(value.intValue());
    }

    private static void setText(JsonNode root, Consumer<String> setter, String... names) {
        JsonNode value = node(root, names);
        if (value != null) setter.accept(value.asText());
    }

    private static void setString(JsonNode root, Consumer<String> setter, String... names) {
        JsonNode value = node(root, names);
        if (value != null) setter.accept(asString(value));
    }

    private static void setBigInteger(JsonNode root, Consumer<BigInteger> setter, String... names) {
        JsonNode value = node(root, names);
        if (value != null) setter.accept(value.bigIntegerValue());
    }

    private static void setBigDecimal(JsonNode root, Consumer<BigDecimal> setter, String... names) {
        JsonNode value = node(root, names);
        if (value != null) setter.accept(value.decimalValue());
    }

    private static void setBigDecimal(JsonNode nested, JsonNode root, Consumer<BigDecimal> setter,
                                      String[] nestedNames, String... rootNames) {
        JsonNode value = node(nested, nestedNames);
        if (value == null) value = node(root, rootNames);
        if (value != null) setter.accept(value.decimalValue());
    }

    private static Map<String, Object> objectMap(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        return MAPPER.convertValue(node, MAP_TYPE);
    }

    private static Integer intValue(JsonNode root, String... names) {
        JsonNode value = node(root, names);
        if (value == null) return null;
        return value.isTextual() ? Integer.parseInt(value.asText()) : value.intValue();
    }

    private static String textValue(JsonNode root, String... names) {
        JsonNode value = node(root, names);
        return value != null ? value.asText() : null;
    }

    private static BigInteger bigIntegerValue(JsonNode root, String... names) {
        JsonNode value = node(root, names);
        if (value == null) return null;
        return value.isTextual() ? new BigInteger(value.asText()) : value.bigIntegerValue();
    }

    private static BigDecimal bigDecimalValue(JsonNode root, String... names) {
        JsonNode value = node(root, names);
        if (value == null) return null;
        return value.isTextual() ? new BigDecimal(value.asText()) : value.decimalValue();
    }

    private static BigDecimal bigDecimalValue(JsonNode nested, JsonNode root,
                                              String[] nestedNames, String... rootNames) {
        JsonNode value = node(nested, nestedNames);
        if (value == null) value = node(root, rootNames);
        if (value == null) return null;
        return value.isTextual() ? new BigDecimal(value.asText()) : value.decimalValue();
    }

    private static String string(BigInteger value) {
        return value != null ? value.toString() : null;
    }

    private static String asString(JsonNode value) {
        if (value.isIntegralNumber()) return value.bigIntegerValue().toString();
        if (value.isNumber()) return value.decimalValue().stripTrailingZeros().toPlainString();
        return value.asText();
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String string) return Long.parseLong(string);
        throw new IllegalArgumentException("Unsupported cost model entry value: " + value);
    }
}
