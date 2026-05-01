package com.bloxbean.cardano.yano.wallet.core.tx;

import com.bloxbean.cardano.client.api.model.Amount;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuickTxPaymentTest {
    @Test
    void acceptsTokenOnlyPaymentsForQuickTxMinAdaHandling() {
        String unit = "a".repeat(56) + "74657374";

        QuickTxPayment payment = new QuickTxPayment(
                "addr_test1qpayer",
                BigInteger.ZERO,
                List.of(Amount.asset(unit, BigInteger.TEN)));

        assertThat(payment.amounts()).containsExactly(Amount.asset(unit, BigInteger.TEN));
    }

    @Test
    void rejectsEmptyPaymentsAndLovelaceInNativeAssetList() {
        assertThatThrownBy(() -> new QuickTxPayment("addr_test1qpayer", BigInteger.ZERO, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payment must include");

        assertThatThrownBy(() -> new QuickTxPayment(
                "addr_test1qpayer",
                BigInteger.ZERO,
                List.of(Amount.lovelace(BigInteger.ONE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain lovelace");
    }
}
