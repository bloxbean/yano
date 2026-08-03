package com.bloxbean.cardano.yano.appchain.eutxo.zk.testkit;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchPublication;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoKeyPaymentSettlementCircuit;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoZ5DataAvailabilityTest {
    private static final String VERIFICATION_KEY_DIGEST = "ab".repeat(32);

    @Test
    void independentOracleReconstructsFromGenesisAndVerifiedSnapshot() {
        Transition first = transition(
                VERIFICATION_KEY_DIGEST, new byte[32], 7, 100, 60);
        Transition second = transition(
                VERIFICATION_KEY_DIGEST,
                scalarBytes(first.statement().publicInputs().nextRoot()),
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

    @Test
    void missingReorderedOrCorruptPublicationCannotReconstruct() {
        Transition first = transition(
                VERIFICATION_KEY_DIGEST, new byte[32], 7, 100, 60);
        Transition second = transition(
                VERIFICATION_KEY_DIGEST,
                scalarBytes(first.statement().publicInputs().nextRoot()),
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

    static Transition transition(
            String verificationKeyDigest,
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
                verificationKeyDigest,
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

    static byte[] scalarBytes(BigInteger scalar) {
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

    record Transition(
            EutxoKeyPaymentBatch witness,
            EutxoZkBatchData batchData,
            EutxoZkStatement statement,
            EutxoZkBatchPublication publication
    ) {
    }
}
