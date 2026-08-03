package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkPublicInputs;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoZ0FeasibilityTest {

    @Test
    void providerIsDiscoverableAndCommitmentIsDeterministic() {
        var provider = ServiceLoader.load(
                        com.bloxbean.cardano.yano.appchain.eutxo.contracts
                                .EutxoValidityCommitmentProvider.class)
                .findFirst().orElseThrow();
        var first = provider.create("zk-chain", EutxoProfile.V1, Map.of());
        var second = provider.create("zk-chain", EutxoProfile.V1, Map.of());

        assertThat(provider.id()).isEqualTo(ZerojPoseidonValidityProvider.ID);
        assertThat(first.genesis().root()).isEqualTo(second.genesis().root());
        assertThat(first.genesis().witnessDescriptor())
                .isEqualTo(second.genesis().witnessDescriptor());
        assertThatThrownBy(() -> provider.create(
                "zk-chain", EutxoProfile.V1,
                Map.of("machines.eutxo.validity.profile", "wrong")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity mismatch");
    }

    @Test
    void realGroth16ProofVerifiesAndWrongPublicInputFails() throws Exception {
        byte[] previousRoot = MessageDigest.getInstance("SHA-256")
                .digest("previous".getBytes(StandardCharsets.UTF_8));
        byte[] transitionDigest = MessageDigest.getInstance("SHA-256")
                .digest("transition".getBytes(StandardCharsets.UTF_8));
        BigInteger ownerSecret = new BigInteger("12345678901234567890");
        EutxoZkPublicInputs inputs = EutxoKeyPaymentCircuit.publicInputs(
                previousRoot, transitionDigest, ownerSecret);

        try (var setup = EutxoGroth16DevelopmentSetup.create()) {
            var proof = setup.prove(inputs, ownerSecret);
            assertThat(setup.verify(proof)).isTrue();
            assertThat(setup.constraintCount()).isGreaterThan(500);
            assertThat(setup.publicInputCount()).isEqualTo(5);
            assertThat(proof.compressedProof().piA()).hasSize(48);
            assertThat(proof.compressedProof().piB()).hasSize(96);
            assertThat(proof.compressedProof().piC()).hasSize(48);
            System.out.printf(
                    "Z0_FEASIBILITY constraints=%d wires=%d publicInputs=%d "
                            + "setupMillis=%d proofMillis=%d%n",
                    setup.constraintCount(),
                    setup.wireCount(),
                    setup.publicInputCount(),
                    setup.setupMillis(),
                    proof.proofMillis());

            EutxoZkPublicInputs tamperedInputs = new EutxoZkPublicInputs(
                    inputs.previousRoot(),
                    inputs.nextRoot().add(BigInteger.ONE),
                    inputs.transitionDigest(),
                    inputs.ownerCommitment(),
                    BigInteger.ONE);
            var tampered = new EutxoGroth16DevelopmentSetup.ProofArtifact(
                    tamperedInputs,
                    proof.proof(),
                    proof.compressedProof(),
                    proof.proofMillis());
            assertThat(setup.verify(tampered)).isFalse();
        }
    }
}
