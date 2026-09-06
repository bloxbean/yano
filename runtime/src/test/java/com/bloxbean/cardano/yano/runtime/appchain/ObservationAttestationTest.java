package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationAnchorType;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationAttestation;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationDefinition;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReporterMode;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRound;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSourceConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationAttestationTest {
    private static final String REPORTER_SEED = "31".repeat(32);
    private static final String ATTESTOR_SEED = "41".repeat(32);

    @Test
    void verifiesAuthorizedAttestationAndRejectsForgedOrReplayedClaims() {
        AppMessageSigner reporter = new AppMessageSigner(REPORTER_SEED);
        AppMessageSigner attestor = new AppMessageSigner(ATTESTOR_SEED);
        ObservationDefinition definition = definition(reporter.publicKey(), attestor.publicKey());
        ObservationProfileV1 profile = profile(definition);
        MemberGroup members = new MemberGroup(Set.of(reporter.publicKeyHex()), 1);
        AppChainConfig config = AppChainConfig.builder("attestation-test")
                .signingKeyHex(REPORTER_SEED)
                .memberKeysHex(members.members())
                .proposerKeyHex(reporter.publicKeyHex())
                .pluginSettings(Map.of(
                        ObservationSettings.PROFILE_HEX, HexUtil.encodeHexString(profile.encode()),
                        "observations.attestors.delivery", attestor.publicKeyHex(),
                        "observations.providers.delivery.type", ObservationProviders.HTTPS_ATTESTED,
                        "observations.providers.delivery.url", "https://example.com/attestation"))
                .build();
        ObservationSettings settings = ObservationSettings.from(config, members);
        ObservationRound round = round(definition, reporter.publicKey());

        ObservationAttestation valid = signedAttestation(attestor, definition,
                round.subscriptionId(), round.roundNumber(), new byte[]{7}, 9);
        ObservationReport report = signedReport(reporter, definition, round, valid);
        assertThat(settings.verifierRegistry().evidenceVerifier(definition)
                .verify(definition, round, report)).isTrue();

        byte[] forgedSignature = valid.signature();
        forgedSignature[0] ^= 1;
        ObservationAttestation forged = copy(valid, valid.subscriptionId(),
                valid.roundNumber(), valid.freshnessAnchor(), forgedSignature);
        assertThat(settings.verifierRegistry().evidenceVerifier(definition)
                .verify(definition, round, signedReport(reporter, definition, round, forged)))
                .isFalse();

        ObservationAttestation crossRound = signedAttestation(attestor, definition,
                filled(99), round.roundNumber() + 1, new byte[]{7}, 9);
        assertThat(settings.verifierRegistry().evidenceVerifier(definition)
                .verify(definition, round,
                        signedReport(reporter, definition, round, crossRound))).isFalse();

        ObservationAttestation staleProjection = signedAttestation(attestor, definition,
                round.subscriptionId(), round.roundNumber(), new byte[]{7}, 8);
        ObservationReport selectivelyRefreshed = signedReport(reporter, definition, round,
                staleProjection, 9);
        assertThat(settings.verifierRegistry().evidenceVerifier(definition)
                .verify(definition, round, selectivelyRefreshed)).isFalse();

        for (long outsideWindow : new long[]{7, 11}) {
            ObservationAttestation stale = signedAttestation(attestor, definition,
                    round.subscriptionId(), round.roundNumber(), new byte[]{7}, outsideWindow);
            assertThat(settings.verifierRegistry().evidenceVerifier(definition)
                    .verify(definition, round, signedReport(reporter, definition, round, stale)))
                    .isFalse();
        }
    }

    private static ObservationAttestation signedAttestation(
            AppMessageSigner signer, ObservationDefinition definition, byte[] subscriptionId,
            long roundNumber, byte[] claim, long freshness) {
        ObservationAttestation unsigned = new ObservationAttestation(1, definition.digest(),
                subscriptionId, roundNumber, signer.publicKey(), new byte[]{5}, claim,
                new byte[]{3}, 0, freshness, new byte[64]);
        return copy(unsigned, subscriptionId, roundNumber, freshness,
                signer.sign(unsigned.signingDigest()));
    }

    private static ObservationAttestation copy(ObservationAttestation source,
                                               byte[] subscriptionId, long roundNumber,
                                               long freshness, byte[] signature) {
        return new ObservationAttestation(source.version(), source.definitionDigest(),
                subscriptionId, roundNumber, source.signerPublicKey(), source.sourceId(),
                source.claim(), source.sourceVersion(), source.freshnessAnchorType(), freshness,
                signature);
    }

    private static ObservationReport signedReport(AppMessageSigner signer,
                                                  ObservationDefinition definition,
                                                  ObservationRound round,
                                                  ObservationAttestation attestation) {
        return signedReport(signer, definition, round, attestation,
                attestation.freshnessAnchor());
    }

    private static ObservationReport signedReport(AppMessageSigner signer,
                                                  ObservationDefinition definition,
                                                  ObservationRound round,
                                                  ObservationAttestation attestation,
                                                  long reportFreshness) {
        ObservationReport unsigned = new ObservationReport(1, filled(1), "attestation-test",
                filled(2), profile(definition).digest(), definition.digest(),
                round.subscriptionId(), round.roundNumber(), round.membershipDigest(),
                round.reporterSetDigest(), signer.publicKey(), attestation.sourceId(),
                attestation.claim(), attestation.encode(), attestation.sourceVersion(),
                attestation.freshnessAnchorType(), reportFreshness, new byte[64]);
        return new ObservationReport(unsigned.version(), unsigned.chainGenesisId(),
                unsigned.chainId(), unsigned.consensusProfileDigest(),
                unsigned.observationProfileDigest(), unsigned.definitionDigest(),
                unsigned.subscriptionId(), unsigned.roundNumber(), unsigned.membershipDigest(),
                unsigned.reporterSetDigest(), unsigned.reporterPublicKey(), unsigned.sourceId(),
                unsigned.value(), unsigned.evidence(), unsigned.sourceVersion(),
                unsigned.freshnessAnchorType(), unsigned.freshnessAnchor(),
                signer.sign(unsigned.signingDigest()));
    }

    private static ObservationDefinition definition(byte[] reporter, byte[] attestor) {
        return new ObservationDefinition(1, "delivery", 1, filled(10), filled(11),
                filled(12), filled(13), ObservationReporterMode.ACTIVE_MEMBERS,
                ObservationHashes.reporterSetDigest(List.of(reporter)), 0, 1, 1, true,
                ObservationProviders.HTTPS_ATTESTED,
                ObservationSourceConfiguration.attestedHttpsSourceDigest(
                        "https://example.com/attestation", "GET", List.of(attestor)),
                "identity-v1", ObservationSettings.ATTESTATION_EVIDENCE,
                ObservationSettings.EXACT_POLICY, filled(14), filled(15), "one-source-v1",
                "source-version-v1", "inline-v1", 1, 1024, 1024, 4096, 1, 1);
    }

    private static ObservationProfileV1 profile(ObservationDefinition definition) {
        return new ObservationProfileV1(1, true, 1, 1, 1, 1, 1, 1, 1,
                List.of(definition), 100, 100, 100, 10, 100, 1, 1,
                8192, 4096, 16_384, 10, 32_768, 1, 20, 3);
    }

    private static ObservationRound round(ObservationDefinition definition, byte[] reporter) {
        return new ObservationRound(1, filled(20), 4,
                ObservationAnchorType.APP_HEIGHT,
                8, 8, 10, 3, 13, 0, definition.digest(), filled(21), 0, filled(22),
                1, 1, 0, ObservationReporterMode.ACTIVE_MEMBERS,
                ObservationHashes.reporterSetDigest(List.of(reporter)), 1, 0, 1,
                definition.sourceConfigurationDigest(), definition.policyParametersDigest());
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
