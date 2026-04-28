package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.NetworkGenesisValues;
import org.cardanofoundation.rewards.calculation.config.NetworkConfig;

import java.math.BigInteger;

/**
 * Builds the CF rewards {@link NetworkConfig} from {@link NetworkGenesisValues}.
 * <p>
 * This is the single place where Yano's genesis-derived values are mapped to the
 * CF library's reward calculation config. No hardcoded network-specific switches.
 */
public class NetworkConfigBuilder {

    /**
     * Build a CF NetworkConfig from NetworkGenesisValues.
     *
     * @param values genesis-derived values (from node-runtime's NetworkGenesisValuesFactory)
     * @return CF NetworkConfig ready for reward calculation
     */
    public static NetworkConfig build(NetworkGenesisValues values) {
        return NetworkConfig.builder()
                .networkMagic(values.networkMagic())
                .totalLovelace(BigInteger.valueOf(values.totalLovelace()))
                .poolDepositInLovelace(values.poolDeposit())
                .expectedSlotsPerEpoch(values.expectedSlotsPerEpoch())
                .shelleyInitialReserves(values.shelleyInitialReserves())
                .shelleyInitialTreasury(values.shelleyInitialTreasury())
                .shelleyInitialUtxo(values.shelleyInitialUtxo())
                .genesisConfigSecurityParameter(values.securityParam())
                .shelleyStartEpoch(values.shelleyStartEpoch())
                .allegraHardforkEpoch(values.allegraHardforkEpoch())
                .vasilHardforkEpoch(values.vasilHardforkEpoch())
                .bootstrapAddressAmount(values.bootstrapAddressAmount())
                .activeSlotCoefficient(values.activeSlotsCoeff())
                .randomnessStabilisationWindow(values.randomnessStabilisationWindow())
                .shelleyStartDecentralisation(values.decentralisationParam())
                .shelleyStartTreasuryGrowRate(values.treasuryGrowth())
                .shelleyStartMonetaryExpandRate(values.monetaryExpansion())
                .shelleyStartOptimalPoolCount(values.optimalPoolCount())
                .shelleyStartPoolOwnerInfluence(values.cfPoolOwnerInfluence())
                .build();
    }
}
