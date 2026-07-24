package com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

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
        assertThat(EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex())
                .isEqualTo("cfe1767761cbe05c7e2b82f951222fbb"
                        + "9df34afa5eb1f39fb8a5c1cc2af87d45");
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

    @Test
    void z2ArtifactsRoundTripCanonicallyAndRejectMutation() {
        var payment = new EutxoKeyPaymentBatch.Payment(
                BigInteger.TEN, BigInteger.valueOf(6), BigInteger.valueOf(4));
        var inputs = new EutxoZkSettlementPublicInputs(
                BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3),
                BigInteger.valueOf(4), BigInteger.ONE,
                BigInteger.valueOf(5), BigInteger.valueOf(6),
                BigInteger.valueOf(7));
        var batch = new EutxoZkBatchData(List.of(payment));
        var statement = new EutxoZkStatement(
                "payments", 12, 0,
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT,
                inputs, batch.commitment());
        var key = new EutxoZkVerificationKey(
                statement.profile().id(),
                statement.profile().circuitId(),
                new byte[48], new byte[96], new byte[96], new byte[96],
                java.util.Collections.nCopies(9, new byte[48]));
        var proof = new EutxoZkProofArtifact(
                statement.digestHex(), key.digestHex(), "prover-a",
                statement, new byte[48], new byte[96], new byte[48], 9);

        assertThat(EutxoZkBatchData.decode(batch.canonicalBytes())
                .canonicalBytes()).isEqualTo(batch.canonicalBytes());
        assertThat(batch.canonicalBytes())
                .hasSize(EutxoZkBatchData.CANONICAL_BYTES);
        byte[] nonCanonicalPadding = batch.canonicalBytes();
        nonCanonicalPadding[nonCanonicalPadding.length - 1] = 1;
        assertThatThrownBy(() ->
                EutxoZkBatchData.decode(nonCanonicalPadding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("padding");
        assertThat(EutxoZkStatement.decode(statement.canonicalBytes())
                .canonicalBytes()).isEqualTo(statement.canonicalBytes());
        assertThat(EutxoZkVerificationKey.decode(key.canonicalBytes())
                .canonicalBytes()).isEqualTo(key.canonicalBytes());
        assertThat(EutxoZkProofArtifact.decode(proof.canonicalBytes())
                .canonicalBytes()).isEqualTo(proof.canonicalBytes());

        byte[] mutated = proof.canonicalBytes();
        mutated[mutated.length - 1] ^= 1;
        EutxoZkProofArtifact decoded = EutxoZkProofArtifact.decode(mutated);
        assertThat(decoded.digestHex()).isNotEqualTo(proof.digestHex());
        assertThatThrownBy(() -> EutxoZkProofArtifact.decode(
                Arrays.copyOf(mutated, mutated.length - 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(int value) {
        byte[] bytes = new byte[32];
        bytes[31] = (byte) value;
        return bytes;
    }
}
