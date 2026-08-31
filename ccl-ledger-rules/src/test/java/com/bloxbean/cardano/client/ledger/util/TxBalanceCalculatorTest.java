package com.bloxbean.cardano.client.ledger.util;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.ledger.slice.PoolsSlice;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRetirement;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TxBalanceCalculatorTest {
    private static final BigInteger POOL_DEPOSIT = BigInteger.valueOf(500_000_000L);
    private static final byte[] POOL_ID = HexUtil.decodeHexString(
            "01010101010101010101010101010101010101010101010101010101");
    private static final byte[] SECOND_POOL_ID = HexUtil.decodeHexString(
            "02020202020202020202020202020202020202020202020202020202");

    @Test
    void activePoolReregistrationDoesNotChargeAnotherDeposit() {
        BigInteger deposits = TxBalanceCalculator.computeTotalDeposits(
                List.of(poolRegistration()), protocolParams(), pools(true));

        assertThat(deposits).isZero();
    }

    @Test
    void newPoolIsChargedOnlyOnceForRepeatedRegistrationsInOneTransaction() {
        BigInteger deposits = TxBalanceCalculator.computeTotalDeposits(
                List.of(poolRegistration(), poolRegistration()), protocolParams(), pools(false));

        assertThat(deposits).isEqualTo(POOL_DEPOSIT);
    }

    @Test
    void twoDistinctNewPoolsAreChargedTwoDeposits() {
        BigInteger deposits = TxBalanceCalculator.computeTotalDeposits(
                List.of(poolRegistration(POOL_ID), poolRegistration(SECOND_POOL_ID)),
                protocolParams(), pools(false));

        assertThat(deposits).isEqualTo(POOL_DEPOSIT.multiply(BigInteger.TWO));
    }

    @Test
    void registrationAfterEffectiveRetirementStartsANewChargedLifecycle() {
        BigInteger deposits = TxBalanceCalculator.computeTotalDeposits(
                List.of(poolRegistration()), protocolParams(), pools(false));

        assertThat(deposits).isEqualTo(POOL_DEPOSIT);
    }

    @Test
    void legacyOverloadDeduplicatesNewPoolRegistrationsWithinTransaction() {
        BigInteger deposits = TxBalanceCalculator.computeTotalDeposits(
                List.of(poolRegistration(), poolRegistration()), protocolParams());

        assertThat(deposits).isEqualTo(POOL_DEPOSIT);
    }

    @Test
    void retirementAndReregistrationOrderDoesNotCreateANewLifecycleDeposit() {
        Certificate retirement = PoolRetirement.builder().poolKeyHash(POOL_ID).epoch(12).build();

        assertThat(TxBalanceCalculator.computeTotalDeposits(
                List.of(retirement, poolRegistration()), protocolParams(), pools(true))).isZero();
        assertThat(TxBalanceCalculator.computeTotalDeposits(
                List.of(poolRegistration(), retirement), protocolParams(), pools(true))).isZero();
    }

    private static PoolRegistration poolRegistration() {
        return poolRegistration(POOL_ID);
    }

    private static PoolRegistration poolRegistration(byte[] poolId) {
        return PoolRegistration.builder().operator(poolId).build();
    }

    private static ProtocolParams protocolParams() {
        return ProtocolParams.builder().poolDeposit(POOL_DEPOSIT.toString()).build();
    }

    private static PoolsSlice pools(boolean registered) {
        return new PoolsSlice() {
            @Override
            public boolean isRegistered(String poolId) {
                return registered && HexUtil.encodeHexString(POOL_ID).equals(poolId);
            }

            @Override
            public long getRetirementEpoch(String poolId) {
                return -1;
            }
        };
    }
}
