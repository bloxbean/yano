package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoZkContractsTest {

    @Test
    void witnessCodecIsCanonicalAndBounded() {
        EutxoValidityWitness witness = new EutxoValidityWitness(
                "zeroj-poseidon-v1",
                bytes(1),
                bytes(2),
                bytes(3),
                "ab".repeat(32),
                42,
                3);

        assertThat(EutxoValidityWitness.decode(witness.encode())).isEqualTo(witness);
        assertThat(EutxoValidityWitness.decode(witness.encode()).encode())
                .isEqualTo(witness.encode());
        assertThatThrownBy(() -> EutxoValidityWitness.decode(
                java.util.Arrays.copyOf(witness.encode(), witness.encode().length - 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void z0PublicInputOrderIsExplicit() {
        assertThat(EutxoZkProfile.Z0_SINGLE_KEY_PAYMENT.digestHex())
                .isEqualTo("f2478e0573535b9c0de7e66d66a7e671"
                        + "565999c6c36096d1d7d1413fa2b0e406");
        assertThat(EutxoZkProfile.Z1_BOUNDED_KEY_PAYMENTS.digestHex())
                .isEqualTo("d495d0ad6a1d7babd00ba53de5bd9019"
                        + "224ac81fb3c68f33dd902e5e5e9282b3");
        EutxoZkPublicInputs inputs = new EutxoZkPublicInputs(
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                BigInteger.valueOf(4), BigInteger.ONE);
        assertThat(inputs.ordered()).containsExactly(
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                BigInteger.valueOf(4), BigInteger.ONE);
        assertThatThrownBy(() -> new EutxoZkPublicInputs(
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                BigInteger.valueOf(4), BigInteger.valueOf(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void batchContractRejectsInflationAndMoreThanFourPayments() {
        var payment = new EutxoKeyPaymentBatch.Payment(
                BigInteger.TEN, BigInteger.valueOf(6), BigInteger.valueOf(4));
        assertThat(new EutxoKeyPaymentBatch(
                java.util.List.of(payment), BigInteger.valueOf(9)).payments())
                .containsExactly(payment);
        assertThatThrownBy(() -> new EutxoKeyPaymentBatch.Payment(
                BigInteger.TEN, BigInteger.valueOf(6), BigInteger.valueOf(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conserve");
        assertThatThrownBy(() -> new EutxoKeyPaymentBatch(
                java.util.Collections.nCopies(5, payment), BigInteger.valueOf(9)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(int value) {
        byte[] bytes = new byte[32];
        bytes[31] = (byte) value;
        return bytes;
    }
}
