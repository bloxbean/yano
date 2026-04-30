package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.api.EpochParamProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

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
}
