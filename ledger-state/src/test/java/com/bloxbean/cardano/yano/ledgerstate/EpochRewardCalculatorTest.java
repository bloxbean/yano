package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.era.EraProvider;
import org.cardanofoundation.rewards.calculation.config.NetworkConfig;
import org.cardanofoundation.rewards.calculation.domain.ProtocolParameters;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EpochRewardCalculatorTest {

    @Test
    void rewardProtocolParamsNormalizeRemovedDecentralizationToZero() {
        EpochParamProvider provider = new EpochParamProvider() {
            @Override
            public BigInteger getKeyDeposit(long epoch) {
                return BigInteger.valueOf(2_000_000);
            }

            @Override
            public BigInteger getPoolDeposit(long epoch) {
                return BigInteger.valueOf(500_000_000);
            }

            @Override
            public BigDecimal getDecentralization(long epoch) {
                return null;
            }
        };

        var calculator = new EpochRewardCalculator(null, null, null, true);
        var params = calculator.buildProtocolParameters(provider, 40);

        assertThat(params.getDecentralisation()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void postVasilRewardRulesStartAfterBabbageTransitionEpoch() {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        calculator.setEraProvider(firstBabbageEpoch(2));
        var networkConfig = NetworkConfig.builder()
                .vasilHardforkEpoch(999)
                .build();

        assertThat(calculator.usesPostVasilRewardRules(2, networkConfig)).isFalse();
        assertThat(calculator.usesPostVasilRewardRules(3, networkConfig)).isTrue();
    }

    @Test
    void postVasilRewardRulesIgnoreInferredEraStartEpoch() {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        calculator.setEraProvider(new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return eraValue == Era.Babbage.getValue() ? 0 : null;
            }
        });
        var networkConfig = NetworkConfig.builder()
                .vasilHardforkEpoch(3)
                .build();

        assertThat(calculator.usesPostVasilRewardRules(2, networkConfig)).isFalse();
    }

    @Test
    void rewardNetworkConfigUsesKnownBabbageEpochForCfRules() {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        calculator.setEraProvider(firstBabbageEpoch(2));
        var configured = NetworkConfig.builder()
                .networkMagic(4)
                .vasilHardforkEpoch(3)
                .build();

        var effective = calculator.resolveEffectiveRewardNetworkConfig(configured);

        assertThat(effective).isNotSameAs(configured);
        assertThat(effective.getNetworkMagic()).isEqualTo(4);
        assertThat(effective.getVasilHardforkEpoch()).isEqualTo(2);
        assertThat(configured.getVasilHardforkEpoch()).isEqualTo(3);
    }

    @Test
    void rewardNetworkConfigIgnoresInferredBabbageEpochForKnownNetworkCfRules() {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        calculator.setEraProvider(new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return eraValue == Era.Babbage.getValue() ? 0 : null;
            }
        });
        var configured = NetworkConfig.builder()
                .networkMagic(2)
                .vasilHardforkEpoch(3)
                .build();

        assertThat(calculator.resolveEffectiveRewardNetworkConfig(configured)).isSameAs(configured);
    }

    @Test
    void rewardNetworkConfigUsesInferredBabbageEpochZeroForCustomNetworkCfRules() {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        calculator.setEraProvider(new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return eraValue == Era.Babbage.getValue() ? 0 : null;
            }
        });
        var configured = NetworkConfig.builder()
                .networkMagic(42)
                .vasilHardforkEpoch(Integer.MAX_VALUE)
                .build();

        var effective = calculator.resolveEffectiveRewardNetworkConfig(configured);

        assertThat(effective).isNotSameAs(configured);
        assertThat(effective.getNetworkMagic()).isEqualTo(42);
        assertThat(effective.getVasilHardforkEpoch()).isZero();
    }

    @Test
    void rewardNetworkConfigTreatsSanchonetAsDerivedNetworkForCfRules() {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        calculator.setEraProvider(new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return eraValue == Era.Babbage.getValue() ? 0 : null;
            }
        });
        var configured = NetworkConfig.builder()
                .networkMagic(4)
                .vasilHardforkEpoch(Integer.MAX_VALUE)
                .build();

        var effective = calculator.resolveEffectiveRewardNetworkConfig(configured);

        assertThat(effective).isNotSameAs(configured);
        assertThat(effective.getNetworkMagic()).isEqualTo(4);
        assertThat(effective.getVasilHardforkEpoch()).isZero();
    }

    @Test
    void postVasilRewardRulesIgnoreObsoleteDecentralizationAndUsePoolBlockCount() {
        var calculator = new EpochRewardCalculator(null, null, null, true);
        calculator.setEraProvider(firstBabbageEpoch(2));
        calculator.setLedgerStateProvider(new PoolParamsProvider(Set.of("pool1")));

        var protocolParams = ProtocolParameters.builder()
                .decentralisation(BigDecimal.ONE)
                .treasuryGrowRate(new BigDecimal("0.2"))
                .monetaryExpandRate(new BigDecimal("0.003"))
                .optimalPoolCount(150)
                .poolOwnerInfluence(new BigDecimal("0.3"))
                .build();
        var blockCounts = Map.of(
                "genesis-delegate", 4_319L,
                "pool1", 1L);
        var networkConfig = NetworkConfig.builder()
                .vasilHardforkEpoch(999)
                .build();

        var transitionEpoch = calculator.resolveRewardRuleContext(
                2, 0, protocolParams, blockCounts, 4_320L, networkConfig);
        assertThat(transitionEpoch.protocolParameters().getDecentralisation()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(transitionEpoch.blockCount()).isEqualTo(4_320L);
        assertThat(transitionEpoch.nonOBFTBlockCount()).isZero();

        var postVasil = calculator.resolveRewardRuleContext(
                3, 1, protocolParams, blockCounts, 4_320L, networkConfig);
        assertThat(postVasil.protocolParameters().getDecentralisation()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(postVasil.blockCount()).isEqualTo(1L);
        assertThat(postVasil.nonOBFTBlockCount()).isEqualTo(1L);
    }

    private static EraProvider firstBabbageEpoch(int epoch) {
        return new EraProvider() {
            @Override
            public Integer resolveFirstEpochOrNull(int eraValue) {
                return eraValue == Era.Babbage.getValue() ? epoch : null;
            }

            @Override
            public Integer resolveKnownFirstEpochOrNull(int eraValue) {
                return eraValue == Era.Babbage.getValue() ? epoch : null;
            }
        };
    }

    private static final class PoolParamsProvider implements LedgerStateProvider {
        private final Set<String> registeredPools;

        private PoolParamsProvider(Set<String> registeredPools) {
            this.registeredPools = registeredPools;
        }

        @Override
        public Optional<BigInteger> getRewardBalance(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public Optional<BigInteger> getStakeDeposit(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public Optional<String> getDelegatedPool(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public Optional<DRepDelegation> getDRepDelegation(int credType, String credentialHash) {
            return Optional.empty();
        }

        @Override
        public boolean isStakeCredentialRegistered(int credType, String credentialHash) {
            return false;
        }

        @Override
        public BigInteger getTotalDeposited() {
            return BigInteger.ZERO;
        }

        @Override
        public boolean isPoolRegistered(String poolHash) {
            return registeredPools.contains(poolHash);
        }

        @Override
        public Optional<BigInteger> getPoolDeposit(String poolHash) {
            return Optional.empty();
        }

        @Override
        public Optional<Long> getPoolRetirementEpoch(String poolHash) {
            return Optional.empty();
        }

        @Override
        public Optional<PoolParams> getPoolParams(String poolHash, int epoch) {
            if (!registeredPools.contains(poolHash)) {
                return Optional.empty();
            }
            return Optional.of(new PoolParams(
                    BigInteger.valueOf(500_000_000),
                    0.0,
                    BigInteger.ZERO,
                    BigInteger.ZERO,
                    "e0" + "00".repeat(28),
                    Set.of()));
        }
    }
}
