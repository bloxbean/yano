package com.bloxbean.cardano.yano.appchain.eutxo.zk.prover;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkVerificationKey;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoKeyPaymentSettlementCircuit;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoProverFailoverCoordinatorTest {

    @Test
    void unavailableAndFailingCandidatesFailOverDeterministically() {
        FakeBackend working = new FakeBackend(false);
        FakeBackend failing = new FakeBackend(true);
        EutxoKeyPaymentBatch witness = new EutxoKeyPaymentBatch(
                List.of(new EutxoKeyPaymentBatch.Payment(
                        BigInteger.valueOf(100),
                        BigInteger.valueOf(60),
                        BigInteger.valueOf(40))),
                BigInteger.valueOf(424242));
        EutxoZkStatement statement = statement(
                witness, working.verificationKey().digestHex());
        var coordinator = new EutxoProverFailoverCoordinator(
                working.verificationKey().digestHex(),
                List.of(
                        new EutxoProverFailoverCoordinator.Candidate(
                                "at-capacity", 0, 1, () -> 1,
                                () -> true, failing),
                        new EutxoProverFailoverCoordinator.Candidate(
                                "failing", 1, 1, () -> 0,
                                () -> true, failing),
                        new EutxoProverFailoverCoordinator.Candidate(
                                "secondary", 2, 1, () -> 0,
                                () -> true, working)));

        var result = coordinator.prove(statement, witness);

        assertThat(result.proverId()).isEqualTo("secondary");
        assertThat(result.proof().statementDigest())
                .isEqualTo(statement.digestHex());
        assertThat(failing.attempts()).isEqualTo(1);
        assertThat(working.attempts()).isEqualTo(1);
    }

    private static EutxoZkStatement statement(
            EutxoKeyPaymentBatch witness,
            String verificationKeyDigest
    ) {
        EutxoZkBatchData data = new EutxoZkBatchData(witness.payments());
        var inputs = EutxoKeyPaymentSettlementCircuit.publicInputs(
                "payments", 0, verificationKeyDigest,
                new byte[32], witness, data.commitment(),
                EutxoKeyPaymentSettlementCircuit
                        .withdrawalCommitment(witness));
        return new EutxoZkStatement(
                "payments", 1, 0,
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT,
                inputs, data.commitment());
    }

    private static final class FakeBackend implements EutxoProofBackend {
        private final AtomicInteger attempts = new AtomicInteger();
        private final boolean fails;
        private final EutxoZkVerificationKey key = new EutxoZkVerificationKey(
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.id(),
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.circuitId(),
                new byte[48], new byte[96], new byte[96], new byte[96],
                Collections.nCopies(9, new byte[48]));

        private FakeBackend(boolean fails) {
            this.fails = fails;
        }

        @Override
        public EutxoZkVerificationKey verificationKey() {
            return key;
        }

        @Override
        public EutxoZkProofArtifact prove(
                EutxoZkStatement statement,
                EutxoKeyPaymentBatch witness,
                String proverId
        ) {
            attempts.incrementAndGet();
            if (fails) {
                throw new IllegalStateException("synthetic outage");
            }
            return new EutxoZkProofArtifact(
                    statement.digestHex(), key.digestHex(), proverId,
                    statement, new byte[48], new byte[96], new byte[48], 1);
        }

        @Override
        public boolean verify(EutxoZkProofArtifact artifact) {
            return key.digestHex().equals(artifact.verificationKeyDigest());
        }

        int attempts() {
            return attempts.get();
        }
    }
}
