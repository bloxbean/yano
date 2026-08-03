package com.bloxbean.cardano.yano.appchain.eutxo.zk.prover;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkVerificationKey;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoCeremonyManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoProverCeremonyInteropTest {

    @TempDir
    Path temporary;

    @Test
    void twoIndependentProversUsingOneCeremonyBundleProveSameStatement() {
        Path keys = temporary.resolve("ceremony");
        EutxoZkProofArtifact first;
        EutxoZkVerificationKey verificationKey;
        EutxoCeremonyManifest manifest;
        try (ZerojEutxoProofBackend setup =
                     ZerojEutxoProofBackend.singleParticipantDevelopmentSetup(keys)) {
            verificationKey = setup.verificationKey();
            manifest = EutxoCeremonyManifest.development(
                    "z6-test-ceremony", keys, verificationKey);
            var fixtures = EutxoProverServiceTest.fixtures(
                    verificationKey.digestHex());
            first = setup.prove(
                    fixtures.statement(), fixtures.witness(), "prover-a");
            assertThat(setup.verify(first)).isTrue();
        }

        EutxoZkProofArtifact second;
        var fixtures = EutxoProverServiceTest.fixtures(
                verificationKey.digestHex());
        try (ZerojEutxoProofBackend independent =
                     ZerojEutxoProofBackend.loadCeremonyBundle(
                             keys, manifest)) {
            assertThat(independent.verificationKey().digestHex())
                    .isEqualTo(verificationKey.digestHex());
            second = independent.prove(
                    fixtures.statement(), fixtures.witness(), "prover-b");
            assertThat(independent.verify(first)).isTrue();
            assertThat(independent.verify(second)).isTrue();
        }

        TreeMap<String, String> corrupt = new TreeMap<>(
                manifest.fileDigests());
        corrupt.put(corrupt.firstKey(), "ff".repeat(32));
        var corruptManifest = new EutxoCeremonyManifest(
                manifest.ceremonyId(), manifest.kind(), manifest.method(),
                manifest.participantCount(), manifest.transcriptDigest(),
                manifest.profileDigest(), manifest.circuitId(),
                manifest.verificationKeyDigest(), corrupt);
        assertThatThrownBy(() ->
                ZerojEutxoProofBackend.loadCeremonyBundle(
                        keys, corruptManifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventory");

        assertThat(first.statementDigest())
                .isEqualTo(second.statementDigest());
        assertThat(first.verificationKeyDigest())
                .isEqualTo(second.verificationKeyDigest());
        assertThat(first.proverId()).isNotEqualTo(second.proverId());
    }
}
