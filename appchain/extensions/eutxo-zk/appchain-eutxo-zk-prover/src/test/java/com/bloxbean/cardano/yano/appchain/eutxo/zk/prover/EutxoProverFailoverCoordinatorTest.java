package com.bloxbean.cardano.yano.appchain.eutxo.zk.prover;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkPublicInputs;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkSettlementPublicInputs;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkVerificationKey;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoKeyPaymentBatchCircuit;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoKeyPaymentSettlementCircuit;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoProverFailoverCoordinatorTest {

    @Test
    void unavailableAndFailingCandidatesFailOverToMatchingBackend() {
        try (ZerojEutxoProofBackend working =
                     ZerojEutxoProofBackend.singleParticipantDevelopmentSetup()) {
            String digest = working.verificationKey().digestHex();
            EutxoKeyPaymentBatch batch = new EutxoKeyPaymentBatch(
                    List.of(new EutxoKeyPaymentBatch.Payment(
                            BigInteger.valueOf(100),
                            BigInteger.valueOf(60),
                            BigInteger.valueOf(40))),
                    BigInteger.valueOf(424242));
            EutxoZkStatement statement = statement(batch, digest);
            EutxoProofBackend failing = new DelegatingBackend(working) {
                @Override
                public EutxoZkProofArtifact prove(
                        EutxoZkStatement ignored,
                        EutxoKeyPaymentBatch witness,
                        String proverId
                ) {
                    throw new IllegalStateException("synthetic outage");
                }
            };
            var coordinator = new EutxoProverFailoverCoordinator(
                    digest,
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
            var result = coordinator.prove(statement, batch);
            assertThat(result.proverId()).isEqualTo("secondary");
            assertThat(result.proof().statementDigest())
                    .isEqualTo(statement.digestHex());
            assertThat(working.verify(result.proof())).isTrue();
        }
    }

    private static EutxoZkStatement statement(
            EutxoKeyPaymentBatch batch,
            String verificationKeyDigest
    ) {
        EutxoZkPublicInputs inputs =
                EutxoKeyPaymentBatchCircuit.publicInputs(new byte[32], batch);
        EutxoZkBatchData batchData =
                new EutxoZkBatchData(batch.payments());
        EutxoZkSettlementPublicInputs settlement =
                EutxoKeyPaymentSettlementCircuit.publicInputs(
                        "payments", 0, verificationKeyDigest,
                        new byte[32], batch, batchData.commitment(),
                        EutxoKeyPaymentSettlementCircuit
                                .withdrawalCommitment(batch));
        assertThat(settlement.batchInputs()).isEqualTo(inputs);
        return new EutxoZkStatement(
                "payments", 1, 0,
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT,
                settlement, batchData.commitment());
    }

    private static class DelegatingBackend implements EutxoProofBackend {
        private final EutxoProofBackend delegate;

        private DelegatingBackend(EutxoProofBackend delegate) {
            this.delegate = delegate;
        }

        @Override
        public EutxoZkVerificationKey verificationKey() {
            return delegate.verificationKey();
        }

        @Override
        public EutxoZkProofArtifact prove(
                EutxoZkStatement statement,
                EutxoKeyPaymentBatch witness,
                String proverId
        ) {
            return delegate.prove(statement, witness, proverId);
        }

        @Override
        public boolean verify(EutxoZkProofArtifact artifact) {
            return delegate.verify(artifact);
        }
    }
}
