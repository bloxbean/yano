package com.bloxbean.cardano.yano.appchain.eutxo.zk.prover;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkPublicInputs;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkVerificationKey;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoKeyPaymentBatchCircuit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoProverServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-24T10:15:30Z"), ZoneOffset.UTC);

    @TempDir
    Path temporary;

    @Test
    void jobSurvivesRestartAndFailureRequiresExplicitRetry() {
        Fixtures fixtures = fixtures();
        EutxoProverStore store = new EutxoProverStore(
                temporary.resolve("restart"));
        EutxoProverJob queued = store.create(
                fixtures.statement(), fixtures.batchData(), fixtures.witness(),
                CLOCK.instant(), 10);
        store.save(new EutxoProverJob(
                queued.id(), EutxoProverJob.Status.RUNNING, 1,
                queued.createdAt(), queued.updatedAt(), "", ""));

        FailingOnceBackend backend = new FailingOnceBackend();
        try (EutxoProverService service = service(store, backend)) {
            assertThat(store.find(queued.id()).orElseThrow().status())
                    .isEqualTo(EutxoProverJob.Status.QUEUED);
            assertThat(service.workOnce().orElseThrow().status())
                    .isEqualTo(EutxoProverJob.Status.FAILED);
            assertThat(service.metrics().failed()).isEqualTo(1);
            assertThat(service.retry(queued.id()).status())
                    .isEqualTo(EutxoProverJob.Status.QUEUED);
            assertThat(service.workOnce().orElseThrow().status())
                    .isEqualTo(EutxoProverJob.Status.PROVED);
            assertThat(service.health().healthy()).isTrue();
            assertThat(store.proof(queued.id())).isPresent();
            EutxoProverJob replay = new EutxoFinalizedBatchIngestor(service)
                    .ingest("payments", 7, new byte[32], fixtures.witness());
            assertThat(replay.id()).isEqualTo(queued.id());
            assertThat(replay.status())
                    .isEqualTo(EutxoProverJob.Status.PROVED);
        }
    }

    @Test
    void timeoutCancellationCapacityAndWitnessPermissionsAreEnforced()
            throws Exception {
        Fixtures fixtures = fixtures();
        EutxoProverStore store = new EutxoProverStore(
                temporary.resolve("bounds"));
        EutxoProofBackend slow = new FailingOnceBackend() {
            @Override
            public EutxoZkProofArtifact prove(
                    EutxoZkStatement statement,
                    EutxoKeyPaymentBatch witness,
                    String proverId
            ) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", interrupted);
                }
                return super.prove(statement, witness, proverId);
            }
        };
        try (EutxoProverService service = new EutxoProverService(
                "slow", store, slow, CLOCK,
                Duration.ofMillis(20), 2, 1)) {
            EutxoProverJob job = service.submit(
                    fixtures.statement(), fixtures.batchData(), fixtures.witness());
            assertThat(service.workOnce().orElseThrow().lastError())
                    .contains("timed out");
            assertThat(service.retry(job.id()).status())
                    .isEqualTo(EutxoProverJob.Status.QUEUED);
            assertThat(service.cancel(job.id()).status())
                    .isEqualTo(EutxoProverJob.Status.CANCELLED);
        }

        Path witnessPath = store.root().resolve("witnesses")
                .resolve(fixtures.statement().digestHex() + ".witness");
        if (Files.getFileStore(witnessPath).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(witnessPath))
                    .isEqualTo(Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE));
        }
    }

    @Test
    void twoIndependentProversUsingOneCeremonyBundleProveSameStatement() {
        Fixtures fixtures = fixtures();
        Path keys = temporary.resolve("ceremony");
        EutxoZkProofArtifact first;
        EutxoZkVerificationKey verificationKey;
        try (ZerojEutxoProofBackend setup =
                     ZerojEutxoProofBackend.singleParticipantDevelopmentSetup(keys)) {
            verificationKey = setup.verificationKey();
            first = setup.prove(
                    fixtures.statement(), fixtures.witness(), "prover-a");
            assertThat(setup.verify(first)).isTrue();
        }

        EutxoZkProofArtifact second;
        try (ZerojEutxoProofBackend independent =
                     ZerojEutxoProofBackend.loadCeremonyBundle(keys)) {
            assertThat(independent.verificationKey().digestHex())
                    .isEqualTo(verificationKey.digestHex());
            second = independent.prove(
                    fixtures.statement(), fixtures.witness(), "prover-b");
            assertThat(independent.verify(first)).isTrue();
            assertThat(independent.verify(second)).isTrue();
        }

        assertThat(first.statementDigest())
                .isEqualTo(second.statementDigest());
        assertThat(first.verificationKeyDigest())
                .isEqualTo(second.verificationKeyDigest());
        assertThat(first.proverId()).isNotEqualTo(second.proverId());
    }

    private EutxoProverService service(
            EutxoProverStore store,
            EutxoProofBackend backend
    ) {
        return new EutxoProverService(
                "test-prover", store, backend, CLOCK,
                Duration.ofSeconds(30), 3, 10);
    }

    private static Fixtures fixtures() {
        EutxoKeyPaymentBatch witness = new EutxoKeyPaymentBatch(
                List.of(
                        payment(100, 70),
                        payment(70, 25)),
                BigInteger.valueOf(424242));
        EutxoZkPublicInputs inputs =
                EutxoKeyPaymentBatchCircuit.publicInputs(
                        new byte[32], witness);
        EutxoZkBatchData batchData = new EutxoZkBatchData(
                witness.payments(), inputs.ownerCommitment());
        EutxoZkStatement statement = new EutxoZkStatement(
                "payments", 7,
                EutxoZkProfile.Z1_BOUNDED_KEY_PAYMENTS,
                inputs, batchData.commitment());
        return new Fixtures(witness, batchData, statement);
    }

    private static EutxoKeyPaymentBatch.Payment payment(
            long input,
            long first
    ) {
        return new EutxoKeyPaymentBatch.Payment(
                BigInteger.valueOf(input),
                BigInteger.valueOf(first),
                BigInteger.valueOf(input - first));
    }

    private record Fixtures(
            EutxoKeyPaymentBatch witness,
            EutxoZkBatchData batchData,
            EutxoZkStatement statement
    ) {
    }

    private static class FailingOnceBackend implements EutxoProofBackend {
        private final AtomicInteger attempts = new AtomicInteger();
        private final EutxoZkVerificationKey key =
                new EutxoZkVerificationKey(
                        EutxoZkProfile.Z1_BOUNDED_KEY_PAYMENTS.id(),
                        EutxoZkProfile.Z1_BOUNDED_KEY_PAYMENTS.circuitId(),
                        new byte[48], new byte[96],
                        new byte[96], new byte[96],
                        Collections.nCopies(6, new byte[48]));

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
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("synthetic failure");
            }
            return new EutxoZkProofArtifact(
                    statement.digestHex(), key.digestHex(), proverId,
                    statement, new byte[48], new byte[96], new byte[48], 1);
        }

        @Override
        public boolean verify(EutxoZkProofArtifact artifact) {
            return key.digestHex().equals(artifact.verificationKeyDigest());
        }
    }
}
