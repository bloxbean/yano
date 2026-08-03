package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2KeyRegistration;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoJubjubAuthorizationProofTest {

    @Test
    void developmentProfileVerifiesExactL2SignatureAndRealProof()
            throws Exception {
        var transaction = EutxoJubjubAuthorizationCircuitTest.transaction(
                BigInteger.valueOf(101));
        var engine = new ZerojPoseidonValidityEngine(
                "jubjub-test", EutxoProfile.V1);
        String credential = transaction.authorizations()
                .getFirst().paymentCredential();
        var registration = new EutxoL2KeyRegistration(
                credential,
                engine.authorizationProfile(),
                1,
                transaction.authorizations().getFirst().publicKey(),
                EutxoL2KeyRegistration.Status.ACTIVE);

        assertThat(engine.verifyAuthorization(
                transaction, List.of(registration)).accepted()).isTrue();
        var statement =
                EutxoJubjubAuthorizationCircuit.statement(transaction);
        BigInteger[] witness =
                EutxoJubjubAuthorizationCircuit.witness(transaction);
        assertThat(witness).isNotEmpty();

        try (var setup = EutxoGroth16DevelopmentSetup.create(
                EutxoJubjubAuthorizationCircuit.circuit())) {
            var proof = setup.prove(statement.ordered(), witness);
            assertThat(setup.verify(proof)).isTrue();
            assertThat(setup.constraintCount()).isGreaterThan(5_000);
            assertThat(setup.publicInputCount()).isEqualTo(2);
            var tampered = new EutxoGroth16DevelopmentSetup.GenericProofArtifact(
                    List.of(
                            statement.messageCommitment().add(BigInteger.ONE),
                            statement.publicKeyCommitment()),
                    proof.proof(),
                    proof.compressedProof(),
                    proof.proofMillis());
            assertThat(setup.verify(tampered)).isFalse();
        }
    }
}
