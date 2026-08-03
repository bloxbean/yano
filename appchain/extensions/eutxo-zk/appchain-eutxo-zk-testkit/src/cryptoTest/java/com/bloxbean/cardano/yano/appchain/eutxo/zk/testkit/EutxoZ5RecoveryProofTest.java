package com.bloxbean.cardano.yano.appchain.eutxo.zk.testkit;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkRecoveryBundle;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoBatchProofEngine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoZ5RecoveryProofTest {

    @Test
    void proofBundleSurvivesOperatorLossWithoutLeakingWitness() {
        try (EutxoBatchProofEngine engine =
                     EutxoBatchProofEngine
                             .singleParticipantDevelopmentSetup()) {
            var transition = EutxoZ5DataAvailabilityTest.transition(
                    engine.verificationKey().digestHex(),
                    new byte[32], 7, 100, 60);
            var proof = engine.prove(
                    transition.statement(),
                    transition.witness(),
                    "operator-that-can-disappear");
            var bundle = new EutxoZkRecoveryBundle(
                    transition.batchData(), proof,
                    engine.verificationKey());

            assertThat(bundle.statementDigest())
                    .isEqualTo(transition.statement().digestHex());
            assertThat(engine.verify(bundle.proof())).isTrue();
            assertThat(EutxoZkRecoveryBundle.decode(
                    bundle.canonicalBytes()).canonicalBytes())
                    .isEqualTo(bundle.canonicalBytes());
            assertThat(indexOf(
                    bundle.batchData().canonicalBytes(),
                    EutxoZ5DataAvailabilityTest.scalarBytes(
                            transition.witness().ownerSecret())))
                    .isNegative();
        }
    }

    private static int indexOf(byte[] value, byte[] candidate) {
        for (int start = 0;
             start <= value.length - candidate.length;
             start++) {
            boolean matches = true;
            for (int offset = 0;
                 offset < candidate.length;
                 offset++) {
                if (value[start + offset] != candidate[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return start;
            }
        }
        return -1;
    }
}
