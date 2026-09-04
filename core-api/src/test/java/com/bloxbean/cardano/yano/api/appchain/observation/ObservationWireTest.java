package com.bloxbean.cardano.yano.api.appchain.observation;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import java.util.Random;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationWireTest {

    @Test
    void disabledProfileHasStableCanonicalBytesAndDigest() {
        ObservationProfileV1 profile = ObservationProfileV1.disabled();

        assertThat(HexUtil.encodeHexString(profile.encode()))
                .isEqualTo("981901000000000000000080000000000000000000000000000000");
        assertThat(HexUtil.encodeHexString(profile.digest()))
                .isEqualTo("0c9a8e9e8c81ad1f1f5874a4ea764f36c366533cac941e0b78b4a03de7c179d5");
        assertThat(ObservationProfileV1.decode(profile.encode()).encode()).isEqualTo(profile.encode());
    }

    @Test
    void everyPersistentRecordRoundTripsCanonically() {
        ObservationDefinition definition = definition();
        ObservationSubscription subscription = subscription(definition);
        ObservationRound round = round(definition, subscription.subscriptionId());
        ObservationReport report = signedReport(definition, round, 1, new byte[]{9});
        ObservationCertificate certificate = certificate(round, List.of(report));
        ObservationResult result = result(round, certificate);

        assertThat(ObservationDefinition.decode(definition.encode()).encode()).isEqualTo(definition.encode());
        assertThat(ObservationSubscription.decode(subscription.encode()).encode()).isEqualTo(subscription.encode());
        assertThat(ObservationRound.decode(round.encode()).encode()).isEqualTo(round.encode());
        assertThat(ObservationReport.decode(report.encode()).encode()).isEqualTo(report.encode());
        assertThat(ObservationCertificate.decode(certificate.encode()).encode()).isEqualTo(certificate.encode());
        assertThat(ObservationResult.decode(result.encode()).encode()).isEqualTo(result.encode());
    }

    @Test
    void canonicalConformanceVectorDigestsAreStable() {
        ObservationDefinition definition = definition();
        ObservationSubscription subscription = subscription(definition);
        ObservationRound round = round(definition, subscription.subscriptionId());
        ObservationReport report = signedReport(definition, round, 1, new byte[]{9});
        ObservationCertificate certificate = certificate(round, List.of(report));
        ObservationResult result = result(round, certificate);
        String actual = String.join("/",
                hexDigest(definition.encode()), hexDigest(subscription.encode()),
                hexDigest(round.encode()), hexDigest(report.encode()),
                HexUtil.encodeHexString(report.signingDigest()),
                hexDigest(certificate.encode()), hexDigest(result.encode()));

        assertThat(actual).isEqualTo("e8053588040f000c3458e87c929e499cbae5e68c2d12f39a4392b06eabc736b9"
                + "/9e3baea8893a07272aa277fc4827701955f0cb95187bbd077d3029c7636e37d9"
                + "/39b34f1188e2dade8f10fd33cfee065c2dc3b4073bdaa48a8c2cd72c3a7f9354"
                + "/4f4b74c33a7048eea565ad91cd18c1c942230a1d6b64aaa8e99cbc71c5001069"
                + "/48b3bb537f21f7f80363c38dc50dc1a63560822e5e4fb7b07f5b10a6098fcd1f"
                + "/17afbd48f69598210c7727f993da966c0d047d4c5a3c6f44ccea29be3df30351"
                + "/351ead0c1592779bdba60e7363bc7fc76de97588fc197358bcd03979af80af4d");
    }

    @Test
    void decodersRejectTrailingTruncatedAndNonShortestCbor() {
        byte[] canonical = ObservationProfileV1.disabled().encode();
        assertThatThrownBy(() -> ObservationProfileV1.decode(
                Arrays.copyOf(canonical, canonical.length - 1)))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] trailing = Arrays.copyOf(canonical, canonical.length + 1);
        assertThatThrownBy(() -> ObservationProfileV1.decode(trailing))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] nonShortest = new byte[canonical.length + 1];
        nonShortest[0] = canonical[0];
        nonShortest[1] = canonical[1];
        nonShortest[2] = 0x18;
        nonShortest[3] = 0x01;
        System.arraycopy(canonical, 3, nonShortest, 4, canonical.length - 3);
        assertThatThrownBy(() -> ObservationProfileV1.decode(nonShortest))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tickIsOnlyABoundedVerifiedL1SlotWakeHint() {
        ObservationTick tick = new ObservationTick(1,
                ObservationAnchorType.VERIFIED_L1_SLOT, 1234);
        assertThat(ObservationTick.decode(tick.encode())).isEqualTo(tick);
        assertThatThrownBy(() -> new ObservationTick(
                1, ObservationAnchorType.APP_HEIGHT, 1234))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportSignatureDigestCoversEveryClaimFieldButNotSignature() {
        ObservationDefinition definition = definition();
        ObservationRound round = round(definition, filled(20));
        ObservationReport first = signedReport(definition, round, 1, new byte[]{9});
        ObservationReport differentValue = signedReport(definition, round, 1, new byte[]{10});
        ObservationReport differentSourceAnchor = report(
                definition, round, 1, new byte[]{9}, 44, filled64(8));
        ObservationReport differentSignature = report(
                definition, round, 1, new byte[]{9}, 43, filled64(7));

        assertThat(first.signingDigest()).isNotEqualTo(differentValue.signingDigest());
        assertThat(first.signingDigest()).isNotEqualTo(differentSourceAnchor.signingDigest());
        assertThat(first.signingDigest()).isEqualTo(differentSignature.signingDigest());
    }

    @Test
    void exactCertificateVerificationIsOrderIndependentAndResultIdentityIgnoresSubset() {
        ObservationDefinition definition = definition(2, 1, 3);
        ObservationRound round = round(definition, filled(20), 2, 3);
        ObservationReport one = signedReport(definition, round, 1, new byte[]{9});
        ObservationReport two = signedReport(definition, round, 2, new byte[]{9});
        ObservationReport three = signedReport(definition, round, 3, new byte[]{9});
        ObservationCertificate left = certificate(round, List.of(one, two));
        ObservationCertificate right = certificate(round, List.of(two, three));

        assertThat(left.resultId()).isEqualTo(right.resultId());
        assertThat(verify(definition, round, left, List.of(filled(1), filled(2), filled(3))))
                .isTrue();
        assertThat(verify(definition, round, right, List.of(filled(1), filled(2), filled(3))))
                .isTrue();

        List<ObservationReport> reversed = new ArrayList<>(left.reports());
        Collections.reverse(reversed);
        assertThatThrownBy(() -> certificate(round, reversed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical order");
    }

    @Test
    void verifierRejectsReplayEquivocationAndInsufficientSourceDiversity() {
        ObservationDefinition definition = definition(2, 2, 3);
        ObservationRound round = round(definition, filled(20), 2, 3);
        ObservationReport one = signedReport(definition, round, 1, new byte[]{9});
        ObservationReport two = signedReport(definition, round, 2, new byte[]{9});
        ObservationCertificate certificate = certificate(round, List.of(one, two));

        assertThat(verify(definition, round, certificate, List.of(filled(1), filled(2), filled(3))))
                .isFalse();

        ObservationRound anotherRound = round(definition, filled(21), 2, 3);
        assertThat(verify(definition, anotherRound, certificate,
                List.of(filled(1), filled(2), filled(3)))).isFalse();

        assertThatThrownBy(() -> new ObservationCertificate(1, round.subscriptionId(),
                round.roundNumber(), round.membershipDigest(), round.definitionDigest(),
                round.policyDigest(), round.sourceSetDigest(), List.of(one, one),
                new byte[]{9}, new byte[0], resultId(round, new byte[]{9})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate reporter/source");
    }

    @Test
    void roundPinsSafeLiveFiveMemberQuorums() {
        ObservationDefinition definition = definition(4, 1, 5);
        assertThat(round(definition, filled(20), 4, 5).finalityQuorum()).isEqualTo(4);

        assertThatThrownBy(() -> new ObservationRound(1, filled(20), 0,
                ObservationAnchorType.APP_HEIGHT, 11, 11, 15, 5, 30, 0,
                definition.digest(), filled(5), 2, filled(6), 5, 3, 1,
                ObservationReporterMode.ACTIVE_MEMBERS, filled(7), 5, 1, 3,
                filled(8), filled(9)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quorum");
    }

    @Test
    void resultAdmissionAppliesStructuralStaleDuplicateAndConflictPrecedence() {
        ObservationDefinition definition = definition();
        ObservationRound round = round(definition, filled(20));
        ObservationCertificate certificate = certificate(round,
                List.of(signedReport(definition, round, 1, new byte[]{9})));

        ObservationResultAdmission stale = new ObservationResultAdmission(
                (subscription, number) -> ObservationResultAdmission.RoundState.UNKNOWN,
                ignored -> false);
        assertThat(stale.classify(certificate.encode()))
                .isEqualTo(ObservationResultAdmission.Verdict.STALE_NOOP);
        assertThatThrownBy(() -> stale.classify(new byte[]{1, 2, 3}))
                .isInstanceOf(ObservationResultAdmission.StructuralRejection.class);

        ObservationResultAdmission active = new ObservationResultAdmission(
                (subscription, number) -> ObservationResultAdmission.RoundState.ACTIVE,
                ignored -> true);
        assertThat(active.classify(certificate.encode()))
                .isEqualTo(ObservationResultAdmission.Verdict.ACCEPTED);
        assertThat(active.classify(certificate.encode()))
                .isEqualTo(ObservationResultAdmission.Verdict.DUPLICATE_NOOP);

        byte[] otherId = filled(99);
        ObservationCertificate conflicting = new ObservationCertificate(1,
                round.subscriptionId(), round.roundNumber(), round.membershipDigest(),
                round.definitionDigest(), round.policyDigest(), round.sourceSetDigest(),
                certificate.reports(), certificate.output(), certificate.policyTrace(), otherId);
        assertThatThrownBy(() -> active.classify(conflicting.encode()))
                .isInstanceOf(ObservationResultAdmission.StructuralRejection.class)
                .hasMessageContaining("conflicting");
    }

    @Test
    void boundedDecodersFailClosedForFuzzedBytes() {
        List<Consumer<byte[]>> decoders = List.of(
                ObservationDefinition::decode,
                ObservationSubscription::decode,
                ObservationRound::decode,
                ObservationReport::decode,
                ObservationCertificate::decode,
                ObservationResult::decode,
                ObservationProfileV1::decode,
                ObservationTick::decode);
        Random random = new Random(37);
        for (int iteration = 0; iteration < 5_000; iteration++) {
            byte[] bytes = new byte[random.nextInt(513)];
            random.nextBytes(bytes);
            for (Consumer<byte[]> decoder : decoders) {
                try {
                    decoder.accept(bytes);
                } catch (IllegalArgumentException expected) {
                    // The only permitted failure class at the untrusted-byte boundary.
                }
            }
        }
    }

    private static boolean verify(ObservationDefinition definition, ObservationRound round,
                                  ObservationCertificate certificate, List<byte[]> reporters) {
        return ObservationCertificateVerifier.verify(definition, round, certificate,
                enabledProfile(definition), filled(30), "chain", filled(31), reporters,
                (publicKey, digest, signature) -> Arrays.equals(signature, signature(digest)),
                (ignoredDefinition, ignoredRound, ignoredReport) -> true,
                new ExactValueQuorumPolicy());
    }

    private static ObservationDefinition definition() {
        return definition(1, 1, 3);
    }

    private static ObservationProfileV1 enabledProfile(ObservationDefinition definition) {
        return new ObservationProfileV1(1, true, 1, 1, 1, 1, 1, 1, 1,
                List.of(definition), 100, 50, 50, 10, 20,
                definition.maxReports(), definition.maxSources(), 100_000,
                10_000, 500_000, 10, 500_000, 10, 100, 10);
    }

    private static ObservationDefinition definition(int reportThreshold,
                                                    int sourceThreshold,
                                                    int maxReports) {
        return new ObservationDefinition(1, "orders-v1", 1, filled(1), filled(2), filled(3),
                filled(4), ObservationReporterMode.ACTIVE_MEMBERS, filled(5),
                reportThreshold == 4 ? 1 : 0, reportThreshold,
                sourceThreshold, false, "https-get-v1", filled(6), "canonical-json-v1", "none-v1",
                "exact-value-quorum-v1", filled(7), filled(8), "distinct-source-v1", "round-anchor-v1",
                "inline-v1", 1, 1024, 1024, 1024, maxReports,
                Math.min(4, maxReports));
    }

    private static ObservationSubscription subscription(ObservationDefinition definition) {
        return new ObservationSubscription(1, filled(20), "orders", "status",
                definition.digest(), new byte[]{1, 2}, 10,
                ObservationAnchorType.APP_HEIGHT, 11, 0, 20, 0,
                ObservationSubscriptionStatus.ACTIVE, 11, 0, null);
    }

    private static ObservationRound round(ObservationDefinition definition, byte[] subscriptionId) {
        return round(definition, subscriptionId, definition.reportThreshold(),
                definition.reportThreshold());
    }

    private static ObservationRound round(ObservationDefinition definition, byte[] subscriptionId,
                                          int reportThreshold, int reporterCount) {
        int faultBound = reporterCount == 5 ? 1 : 0;
        List<byte[]> reporters = IntStream.rangeClosed(1, reporterCount)
                .mapToObj(ObservationWireTest::filled).toList();
        return new ObservationRound(1, subscriptionId, 0, ObservationAnchorType.APP_HEIGHT,
                11, 11, 15, 5, 30, 0, definition.digest(), filled(5), 2,
                filled(6), reporterCount, reportThreshold, faultBound,
                ObservationReporterMode.ACTIVE_MEMBERS,
                ObservationHashes.reporterSetDigest(reporters), reporterCount, faultBound,
                reportThreshold, filled(8), filled(9));
    }

    private static ObservationReport signedReport(ObservationDefinition definition,
                                                  ObservationRound round, int reporter,
                                                  byte[] value) {
        ObservationReport unsigned = report(
                definition, round, reporter, value, 43, new byte[64]);
        return report(definition, round, reporter, value, 43,
                signature(unsigned.signingDigest()));
    }

    private static ObservationReport report(ObservationDefinition definition,
                                            ObservationRound round, int reporter,
                                            byte[] value, long sourceAnchor, byte[] signature) {
        return new ObservationReport(1, filled(30), "chain", filled(31),
                enabledProfile(definition).digest(),
                round.definitionDigest(), round.subscriptionId(), round.roundNumber(),
                round.membershipDigest(), round.reporterSetDigest(), filled(reporter),
                new byte[]{1}, value, new byte[]{2}, new byte[]{3}, 1, sourceAnchor, signature);
    }

    private static ObservationCertificate certificate(ObservationRound round,
                                                      List<ObservationReport> reports) {
        byte[] output = reports.getFirst().value();
        return new ObservationCertificate(1, round.subscriptionId(), round.roundNumber(),
                round.membershipDigest(), round.definitionDigest(), round.policyDigest(),
                round.sourceSetDigest(), reports, output, new byte[0], resultId(round, output));
    }

    private static ObservationResult result(ObservationRound round,
                                            ObservationCertificate certificate) {
        byte[] valueDigest = ObservationHashes.digest(certificate.output());
        return new ObservationResult(1, certificate.resultId(), round.subscriptionId(),
                round.roundNumber(), round.definitionDigest(), ObservationResultStatus.VALUE,
                certificate.output(), valueDigest, certificate.digest(),
                1, certificate.reports().size(), new byte[0], 12);
    }

    private static byte[] resultId(ObservationRound round, byte[] output) {
        return ObservationHashes.resultId(round.subscriptionId(), round.roundNumber(),
                round.definitionDigest(), ObservationResultStatus.VALUE,
                ObservationHashes.digest(output));
    }

    private static byte[] signature(byte[] digest) {
        byte[] signature = new byte[64];
        System.arraycopy(digest, 0, signature, 0, 32);
        System.arraycopy(digest, 0, signature, 32, 32);
        return signature;
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] filled64(int value) {
        byte[] bytes = new byte[64];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static String hexDigest(byte[] bytes) {
        return HexUtil.encodeHexString(ObservationHashes.digest(bytes));
    }
}
