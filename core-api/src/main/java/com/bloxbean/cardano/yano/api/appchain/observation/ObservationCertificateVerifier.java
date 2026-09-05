package com.bloxbean.cardano.yano.api.appchain.observation;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.List;
import java.util.Set;

/** Pure bounded verifier for an active round's self-contained certificate. */
public final class ObservationCertificateVerifier {
    private ObservationCertificateVerifier() {
    }

    public static boolean verify(
            ObservationDefinition definition,
            ObservationRound round,
            ObservationCertificate certificate,
            ObservationProfileV1 profile,
            byte[] expectedChainGenesisId,
            String expectedChainId,
            byte[] expectedConsensusProfileDigest,
            List<byte[]> authorizedReporters,
            ObservationSignatureVerifier signatureVerifier,
            ObservationEvidenceVerifier evidenceVerifier,
            ObservationReconciliationPolicy policy
    ) {
        return validate(definition, round, certificate, profile, expectedChainGenesisId,
                expectedChainId, expectedConsensusProfileDigest, authorizedReporters,
                signatureVerifier, evidenceVerifier, policy).valid();
    }

    /** Same bounded verification with a stable, non-sensitive diagnostic code. */
    public static Validation validate(
            ObservationDefinition definition,
            ObservationRound round,
            ObservationCertificate certificate,
            ObservationProfileV1 profile,
            byte[] expectedChainGenesisId,
            String expectedChainId,
            byte[] expectedConsensusProfileDigest,
            List<byte[]> authorizedReporters,
            ObservationSignatureVerifier signatureVerifier,
            ObservationEvidenceVerifier evidenceVerifier,
            ObservationReconciliationPolicy policy
    ) {
        Objects.requireNonNull(signatureVerifier, "signatureVerifier");
        Objects.requireNonNull(evidenceVerifier, "evidenceVerifier");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(profile, "profile");
        if (!profile.enabled()
                || profile.definitions().stream().noneMatch(candidate ->
                Arrays.equals(candidate.digest(), definition.digest()))
                || certificate.encode().length > profile.maxCertificateBytes()
                || certificate.reports().size() > profile.maxReportsPerRound()
                || !Arrays.equals(definition.digest(), round.definitionDigest())
                || definition.reporterMode() != round.reporterMode()
                || definition.reporterFaultBound() != round.reporterFaultBound()
                || (profile.roundRulesVersion() == 2
                    && definition.reporterMode() == ObservationReporterMode.ACTIVE_MEMBERS
                    ? !Arrays.equals(definition.reporterSetDigest(), ObservationHashes.activeMemberRuleDigest())
                        || round.reportThreshold() != Math.max(definition.reportThreshold(), round.finalityQuorum())
                    : definition.reportThreshold() != round.reportThreshold())
                || !Arrays.equals(certificate.subscriptionId(), round.subscriptionId())
                || certificate.roundNumber() != round.roundNumber()
                || !Arrays.equals(certificate.membershipDigest(), round.membershipDigest())
                || !Arrays.equals(certificate.definitionDigest(), round.definitionDigest())
                || !Arrays.equals(certificate.policyDigest(), round.policyDigest())
                || !Arrays.equals(certificate.sourceSetDigest(), round.sourceSetDigest())
                || certificate.reports().size() != round.reportThreshold()
                || (round.reportThreshold() < round.finalityQuorum()
                && !definition.certificateLocalUniqueness())
                || certificate.reports().size() > definition.maxReports()) {
            return Validation.reject("certificate_identity_or_bounds");
        }
        Set<ReporterSource> reporterSources = new HashSet<>();
        Set<ByteKey> sources = new HashSet<>();
        if (authorizedReporters.size() != round.reporterCount()
                || !Arrays.equals(ObservationHashes.reporterSetDigest(authorizedReporters),
                round.reporterSetDigest())) {
            return Validation.reject("reporter_set");
        }
        Set<ByteKey> allowed = new HashSet<>();
        for (byte[] reporter : authorizedReporters) {
            allowed.add(new ByteKey(reporter));
        }
        for (ObservationReport report : certificate.reports()) {
            if (!Arrays.equals(report.chainGenesisId(), expectedChainGenesisId)
                    || !report.chainId().equals(expectedChainId)
                    || !Arrays.equals(report.consensusProfileDigest(), expectedConsensusProfileDigest)
                    || !Arrays.equals(report.observationProfileDigest(), profile.digest())
                    || !Arrays.equals(report.definitionDigest(), round.definitionDigest())
                    || !Arrays.equals(report.subscriptionId(), round.subscriptionId())
                    || report.roundNumber() != round.roundNumber()
                    || !Arrays.equals(report.membershipDigest(), round.membershipDigest())
                    || !Arrays.equals(report.reporterSetDigest(), round.reporterSetDigest())) {
                return Validation.reject("report_identity");
            }
            if (report.value().length > definition.maxValueBytes()
                    || report.evidence().length > definition.maxEvidenceBytes()
                    || report.evidence().length > profile.maxEvidenceBytes()
                    || report.encode().length > profile.maxReportBytes()) {
                return Validation.reject("report_bounds");
            }
            if (!allowed.contains(new ByteKey(report.reporterPublicKey()))) {
                return Validation.reject("reporter_authorization");
            }
            if (!reporterSources.add(new ReporterSource(
                            new ByteKey(report.reporterPublicKey()), new ByteKey(report.sourceId())))
            ) {
                return Validation.reject("duplicate_reporter_source");
            }
            if (!signatureVerifier.verify(report.reporterPublicKey(),
                    report.signingDigest(), report.signature())) {
                return Validation.reject("report_signature");
            }
            if (!evidenceVerifier.verify(definition, round, report)) {
                return Validation.reject("report_evidence");
            }
            sources.add(new ByteKey(report.sourceId()));
        }
        if (sources.size() < definition.sourceThreshold()
                || sources.size() > definition.maxSources()) {
            return Validation.reject("source_threshold");
        }
        byte[] valueDigest = ObservationHashes.digest(certificate.output());
        byte[] expectedResult = ObservationHashes.resultId(round.subscriptionId(),
                round.roundNumber(), round.definitionDigest(), ObservationResultStatus.VALUE,
                valueDigest);
        if (!Arrays.equals(expectedResult, certificate.resultId())) {
            return Validation.reject("result_id");
        }
        if (!policy.verify(definition, round, certificate.reports(),
                certificate.output(), certificate.policyTrace())) {
            return Validation.reject("policy");
        }
        return Validation.accept();
    }

    public record Validation(boolean valid, String reason) {
        private static Validation accept() {
            return new Validation(true, "accepted");
        }

        private static Validation reject(String reason) {
            return new Validation(false, reason);
        }
    }

    private record ByteKey(byte[] value) {
        private ByteKey {
            value = Objects.requireNonNull(value, "value").clone();
        }

        @Override public boolean equals(Object other) {
            return other instanceof ByteKey key && Arrays.equals(value, key.value);
        }

        @Override public int hashCode() {
            return Arrays.hashCode(value);
        }
    }

    private record ReporterSource(ByteKey reporter, ByteKey source) {
    }
}
