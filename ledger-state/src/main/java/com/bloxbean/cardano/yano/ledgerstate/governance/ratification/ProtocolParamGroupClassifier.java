package com.bloxbean.cardano.yano.ledgerstate.governance.ratification;

import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Classifies protocol parameter updates into parameter groups (network, economic, technical,
 * governance, security) following the Cardano Conway spec.
 * <p>
 * Reference: Haskell cardano-ledger Conway/PParams.hs, yaci-store ProtocolParamUtil.
 */
public class ProtocolParamGroupClassifier {

    public enum ParamGroup { NETWORK, ECONOMIC, TECHNICAL, GOVERNANCE, SECURITY }

    /**
     * Determine which parameter groups are affected by a ProtocolParamUpdate.
     * A group is affected if ANY field in that group has a non-null value.
     */
    public static List<ParamGroup> getAffectedGroups(ProtocolParamUpdate params) {
        List<ParamGroup> groups = new ArrayList<>();

        if (hasAny(params.getMinFeeA(), params.getMinFeeB(), params.getKeyDeposit(), params.getPoolDeposit(),
                params.getMinPoolCost(), params.getPriceMem(), params.getPriceStep(), params.getAdaPerUtxoByte(),
                params.getExpansionRate(), params.getTreasuryGrowthRate(), params.getMinFeeRefScriptCostPerByte())) {
            groups.add(ParamGroup.ECONOMIC);
        }

        if (hasAny(params.getMaxBlockSize(), params.getMaxTxSize(), params.getMaxBlockHeaderSize(),
                params.getMaxValSize(), params.getMaxTxExMem(), params.getMaxTxExSteps(),
                params.getMaxBlockExMem(), params.getMaxBlockExSteps(), params.getMaxCollateralInputs())) {
            groups.add(ParamGroup.NETWORK);
        }

        if (hasAny(params.getNOpt(), params.getPoolPledgeInfluence(), params.getCostModels(),
                params.getCollateralPercent(), params.getMaxEpoch())) {
            groups.add(ParamGroup.TECHNICAL);
        }

        if (hasAny(params.getPoolVotingThresholds(), params.getDrepVotingThresholds(), params.getCommitteeMinSize(),
                params.getCommitteeMaxTermLength(), params.getGovActionLifetime(), params.getGovActionDeposit(),
                params.getDrepDeposit(), params.getDrepActivity())) {
            groups.add(ParamGroup.GOVERNANCE);
        }

        if (hasAny(params.getMaxBlockSize(), params.getMaxTxSize(), params.getMaxBlockHeaderSize(),
                params.getMaxValSize(), params.getMaxBlockExMem(), params.getMaxBlockExSteps(), params.getMinFeeA(),
                params.getMinFeeB(), params.getGovActionDeposit(), params.getMinFeeRefScriptCostPerByte(),
                params.getAdaPerUtxoByte())) {
            groups.add(ParamGroup.SECURITY);
        }

        return groups;
    }

    /**
     * Compute the DRep threshold for a ParameterChange proposal.
     * Takes the MAX threshold across all affected non-security groups.
     * DReps don't vote on security-only changes.
     *
     * @param affectedGroups Groups affected by the parameter change
     * @param protocolParams Current epoch's resolved protocol parameters (with thresholds)
     * @return The DRep threshold, or 0 if no DRep vote required
     */
    public static BigDecimal computeDRepThreshold(List<ParamGroup> affectedGroups,
                                                  ProtocolParamUpdate protocolParams) {
        return computeDRepThreshold(affectedGroups, protocolParams != null ? protocolParams.getDrepVotingThresholds() : null);
    }

    /**
     * Overload that takes {@link com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds} directly.
     * <p>
     * Fail-safe semantics: when {@code drepThresholds} is null but the affected groups contain
     * a DRep-voting group (anything other than SECURITY), returns {@link BigDecimal#ONE} so that
     * ratification fails safely instead of silently using a hardcoded wrong value — prevents the
     * "0.67 fallback" bug class that surfaced at preview epoch 967 before Conway genesis
     * thresholds were plumbed through.
     * <p>
     * If the only affected group is SECURITY (or the groups list is empty), DReps do not vote at
     * all on this proposal, so the DRep threshold is 0 (no-op) even when thresholds are missing.
     */
    public static BigDecimal computeDRepThreshold(List<ParamGroup> affectedGroups,
                                                  com.bloxbean.cardano.yaci.core.model.DrepVoteThresholds drepThresholds) {
        boolean anyDRepVotingGroup = affectedGroups.stream().anyMatch(g -> g != ParamGroup.SECURITY);
        if (!anyDRepVotingGroup) return BigDecimal.ZERO; // no DRep voting required
        if (drepThresholds == null) return BigDecimal.ONE; // fail-safe: config-missing → fail
        BigDecimal maxThreshold = BigDecimal.ZERO;
        for (ParamGroup group : affectedGroups) {
            BigDecimal t = switch (group) {
                case NETWORK -> ratioToBigDecimal(drepThresholds.getDvtPPNetworkGroup());
                case ECONOMIC -> ratioToBigDecimal(drepThresholds.getDvtPPEconomicGroup());
                case TECHNICAL -> ratioToBigDecimal(drepThresholds.getDvtPPTechnicalGroup());
                case GOVERNANCE -> ratioToBigDecimal(drepThresholds.getDvtPPGovGroup());
                case SECURITY -> BigDecimal.ZERO; // DReps don't vote on security params
            };
            if (t.compareTo(maxThreshold) > 0) maxThreshold = t;
        }
        return maxThreshold;
    }

    /**
     * Check if SPO voting is required for a ParameterChange.
     * SPOs only vote on changes that affect the security group.
     */
    public static boolean isSpoVotingRequired(List<ParamGroup> affectedGroups) {
        return affectedGroups.contains(ParamGroup.SECURITY);
    }

    // Note: historical helpers `computeSpoThreshold`, `getDRepThresholdForAction`, and
    // `getSpoThresholdForAction` were removed because they contained hardcoded
    // 0.67 / 0.51 fallbacks that would silently mask a missing Conway genesis config —
    // the exact bug-class that caused preview-967. Threshold resolution now goes through
    // GovernanceEpochProcessor.resolveDRepThresholds / resolveSPOThresholds which read
    // from EpochParamTracker (on-chain updates) or EpochParamProvider (Conway genesis)
    // and fail-safe to BigDecimal.ONE when both are missing, so ratification fails loud
    // rather than silently using a wrong threshold.

    public static BigDecimal ratioToBigDecimal(com.bloxbean.cardano.yaci.core.types.UnitInterval ui) {
        if (ui == null) return BigDecimal.ZERO;
        if (ui.getDenominator() == null || ui.getDenominator().signum() == 0) return BigDecimal.ZERO;
        return new BigDecimal(ui.getNumerator()).divide(new BigDecimal(ui.getDenominator()), MathContext.DECIMAL128);
    }

    private static boolean hasAny(Object... values) {
        for (Object v : values) {
            if (v != null) return true;
        }
        return false;
    }
}
