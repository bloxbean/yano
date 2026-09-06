package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCertificate;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResultStatus;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.bloxbean.cardano.yano.runtime.appchain.ObservationJournalTest.filled;
import static com.bloxbean.cardano.yano.runtime.appchain.ObservationJournalTest.ledger;
import static com.bloxbean.cardano.yano.runtime.appchain.ObservationJournalTest.report;
import static org.assertj.core.api.Assertions.assertThat;

class ObservationJournalCrashTest {
    private static final String SEED = "21".repeat(32);

    @Test
    void abruptProcessExitRetainsSigningIdentityAndReadyWork(@TempDir Path directory) throws Exception {
        for (String boundary : List.of("PREPARED", "SIGNED", "REPORT", "CERTIFICATE")) {
            verifyCrashBoundary(boundary, directory.resolve(boundary));
        }
    }

    private void verifyCrashBoundary(String boundary, Path directory) throws Exception {
        Files.createDirectories(directory);
        Path output = directory.resolve("child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("yano.test.runtime-classpath"), Probe.class.getName(),
                directory.resolve("ledger").toString(), boundary)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(30, TimeUnit.SECONDS)).as("crash probe completed").isTrue();
            assertThat(child.exitValue()).as(Files.readString(output)).isEqualTo(87);
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
        AppMessageSigner signer = new AppMessageSigner(SEED);
        ObservationReport original = report(signer.publicKey(), new byte[]{7});
        try (AppLedgerStore store = ledger(directory.resolve("ledger"))) {
            ObservationJournal journal = journal(store, signer);
            assertThat(journal.localCandidate(original.subscriptionId(), 0).orElseThrow().encode())
                    .isEqualTo(original.encode());
            assertThat(journal.prepareLocalReport(report(signer.publicKey(), new byte[]{8})).encode())
                    .isEqualTo(original.encode());
            var reports = journal.reports(original.subscriptionId(), 0, 10);
            assertThat(reports).hasSize(boundary.equals("REPORT") || boundary.equals("CERTIFICATE") ? 1 : 0);
            reports.forEach(retained -> assertThat(AppMessageSigner.verify(retained.signature(),
                    retained.signingDigest(), retained.reporterPublicKey())).isTrue());
            assertThat(journal.readyCertificates(10)).hasSize(boundary.equals("CERTIFICATE") ? 1 : 0);
        }
    }

    private static ObservationJournal journal(AppLedgerStore store, AppMessageSigner signer) {
        return new ObservationJournal(store, filled(1), "chain", signer.publicKey(), filled(3),
                100, 1024 * 1024);
    }

    public static class Probe {
        public static void main(String[] args) {
            AppMessageSigner signer = new AppMessageSigner(SEED);
            // Intentionally no close/shutdown hooks: this models process loss,
            // not a graceful flush on exit.
            AppLedgerStore store = ledger(Path.of(args[0]));
            ObservationJournal journal = journal(store, signer);
            ObservationReport candidate = journal.prepareLocalReport(report(signer.publicKey(), new byte[]{7}));
            haltAt(args[1], "PREPARED");
            ObservationReport signed = new ObservationReport(candidate.version(), candidate.chainGenesisId(),
                    candidate.chainId(), candidate.consensusProfileDigest(), candidate.observationProfileDigest(),
                    candidate.definitionDigest(), candidate.subscriptionId(), candidate.roundNumber(),
                    candidate.membershipDigest(), candidate.reporterSetDigest(), candidate.reporterPublicKey(),
                    candidate.sourceId(), candidate.value(), candidate.evidence(), candidate.sourceVersion(),
                    candidate.freshnessAnchorType(), candidate.freshnessAnchor(), signer.sign(candidate.signingDigest()));
            haltAt(args[1], "SIGNED");
            journal.persistReport(signed);
            haltAt(args[1], "REPORT");
            byte[] resultId = ObservationHashes.resultId(signed.subscriptionId(), signed.roundNumber(),
                    signed.definitionDigest(), ObservationResultStatus.VALUE, ObservationHashes.digest(signed.value()));
            journal.persistCertificate(new ObservationCertificate(1, signed.subscriptionId(), 0,
                    signed.membershipDigest(), signed.definitionDigest(), filled(9), filled(10),
                    List.of(signed), signed.value(), new byte[0], resultId));
            haltAt(args[1], "CERTIFICATE");
            throw new IllegalArgumentException("Unknown crash boundary");
        }

        private static void haltAt(String requested, String current) {
            if (requested.equals(current)) Runtime.getRuntime().halt(87);
        }
    }
}
