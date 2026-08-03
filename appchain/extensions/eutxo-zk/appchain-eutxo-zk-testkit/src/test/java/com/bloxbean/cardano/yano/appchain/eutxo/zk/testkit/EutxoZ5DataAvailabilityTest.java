package com.bloxbean.cardano.yano.appchain.eutxo.zk.testkit;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchPublication;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkRecoveryBundle;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoBatchProofEngine;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoKeyPaymentSettlementCircuit;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoZ5DataAvailabilityTest {

    @Test
    void independentOracleReconstructsFromGenesisAndVerifiedSnapshot() {
        try (EutxoBatchProofEngine engine =
                     EutxoBatchProofEngine
                             .singleParticipantDevelopmentSetup()) {
            Transition first = transition(
                    engine, new byte[32], 7, 100, 60);
            Transition second = transition(
                    engine, scalarBytes(first.statement()
                            .publicInputs().nextRoot()),
                    8, 60, 25);
            EutxoReferenceReconstructor oracle =
                    new EutxoReferenceReconstructor();
            var genesis = snapshotBefore(first);

            var afterBoth = oracle.reconstruct(
                    genesis,
                    List.of(first.publication(), second.publication()));
            assertThat(afterBoth.root()).isEqualTo(
                    second.statement().publicInputs().nextRoot());

            var verifiedSnapshot = oracle.apply(
                    genesis, first.publication());
            assertThat(oracle.reconstruct(
                    verifiedSnapshot,
                    List.of(second.publication())))
                    .isEqualTo(afterBoth);
        }
    }

    @Test
    void missingReorderedOrCorruptPublicationCannotReconstruct() {
        try (EutxoBatchProofEngine engine =
                     EutxoBatchProofEngine
                             .singleParticipantDevelopmentSetup()) {
            Transition first = transition(
                    engine, new byte[32], 7, 100, 60);
            Transition second = transition(
                    engine, scalarBytes(first.statement()
                            .publicInputs().nextRoot()),
                    8, 60, 25);
            EutxoReferenceReconstructor oracle =
                    new EutxoReferenceReconstructor();
            var genesis = snapshotBefore(first);

            assertThatThrownBy(() -> oracle.reconstruct(
                    genesis, List.of(second.publication())))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> oracle.reconstruct(
                    genesis,
                    List.of(second.publication(), first.publication())))
                    .isInstanceOf(IllegalArgumentException.class);

            var corrupt = new EutxoZkBatchData(
                    List.of(new EutxoKeyPaymentBatch.Payment(
                            BigInteger.valueOf(100),
                            BigInteger.valueOf(59),
                            BigInteger.valueOf(41))));
            assertThatThrownBy(() -> new EutxoZkBatchPublication(
                    100, txId(9), 0,
                    first.statement(), corrupt))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void proofBundleSurvivesOperatorLossWithoutLeakingWitness() {
        try (EutxoBatchProofEngine engine =
                     EutxoBatchProofEngine
                             .singleParticipantDevelopmentSetup()) {
            Transition transition = transition(
                    engine, new byte[32], 7, 100, 60);
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
                    scalarBytes(
                            transition.witness().ownerSecret())))
                    .isNegative();
        }
    }

    private static Transition transition(
            EutxoBatchProofEngine engine,
            byte[] previousRoot,
            long height,
            long input,
            long first
    ) {
        var witness = new EutxoKeyPaymentBatch(
                List.of(new EutxoKeyPaymentBatch.Payment(
                        BigInteger.valueOf(input),
                        BigInteger.valueOf(first),
                        BigInteger.valueOf(input - first))),
                BigInteger.valueOf(424242));
        var batch = new EutxoZkBatchData(witness.payments());
        byte[] withdrawal =
                EutxoKeyPaymentSettlementCircuit
                        .withdrawalCommitment(witness);
        var inputs = EutxoKeyPaymentSettlementCircuit.publicInputs(
                "payments", 0,
                engine.verificationKey().digestHex(),
                previousRoot, witness,
                batch.commitment(), withdrawal);
        var statement = new EutxoZkStatement(
                "payments", height, 0,
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT,
                inputs, batch.commitment());
        var publication = new EutxoZkBatchPublication(
                100 + height, txId((int) height), 0,
                statement, batch);
        return new Transition(
                witness, batch, statement, publication);
    }

    private static EutxoReferenceReconstructor.Snapshot snapshotBefore(
            Transition transition
    ) {
        var inputs = transition.statement().publicInputs();
        return new EutxoReferenceReconstructor.Snapshot(
                transition.statement().chainId(),
                transition.statement().bridgeEpoch(),
                transition.statement().finalizedHeight() - 1,
                inputs.previousRoot(),
                inputs.settlementContext());
    }

    private static byte[] scalarBytes(BigInteger scalar) {
        byte[] source = scalar.toByteArray();
        int offset = source.length == 33 && source[0] == 0 ? 1 : 0;
        byte[] fixed = new byte[32];
        System.arraycopy(
                source, offset, fixed,
                fixed.length - (source.length - offset),
                source.length - offset);
        return fixed;
    }

    private static String txId(int value) {
        return "%064x".formatted(value);
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

    private record Transition(
            EutxoKeyPaymentBatch witness,
            EutxoZkBatchData batchData,
            EutxoZkStatement statement,
            EutxoZkBatchPublication publication
    ) {
    }
}
