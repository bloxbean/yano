package com.bloxbean.cardano.yano.api.appchain.observation;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompleteSourceMedianPolicyTest {
    @Test
    void everyReporterSubsetAndPermutationHasIdenticalOutput() {
        CompleteSourceMedianPolicy.Parameters parameters = parameters(false);
        CompleteSourceMedianPolicy policy = new CompleteSourceMedianPolicy(parameters);
        ObservationRound round = round(parameters);
        Random permutations = new Random(37);
        for (int omittedA = 0; omittedA < 5; omittedA++) {
            for (int omittedB = 0; omittedB < 5; omittedB++) {
                for (int omittedC = 0; omittedC < 5; omittedC++) {
                    List<ObservationReport> reports = claims(new int[]{omittedA, omittedB, omittedC},
                            new long[]{100, 102, 104}, 2);
                    Collections.shuffle(reports, permutations);
                    assertThat(ObservationFixedPoint.decode(policy.reconcile(round, reports)).units())
                            .isEqualTo(BigInteger.valueOf(102));
                }
            }
        }
    }

    @Test
    void aliasesCountAsOneSourceGroupAndOutliersDoNotChangeCoverage() {
        CompleteSourceMedianPolicy.Parameters parameters = parameters(true);
        CompleteSourceMedianPolicy policy = new CompleteSourceMedianPolicy(parameters);
        List<ObservationReport> reports = claims(new int[]{0, 1, 2}, new long[]{100, 102, 104}, 2);
        // Two aliases collapse to lower median 100; the second group is 104.
        assertThat(ObservationFixedPoint.decode(policy.reconcile(round(parameters), reports)).units())
                .isEqualTo(BigInteger.valueOf(100));
        CompleteSourceMedianPolicy.Parameters distinct = parameters(false);
        List<ObservationReport> outlier = claims(new int[]{0, 1, 2}, new long[]{100, 102, 900}, 2);
        assertThat(ObservationFixedPoint.decode(new CompleteSourceMedianPolicy(distinct)
                .reconcile(round(distinct), outlier)).units()).isEqualTo(BigInteger.valueOf(100));
        assertThatThrownBy(() -> policy.reconcile(round(parameters), outlier))
                .isInstanceOf(IllegalArgumentException.class); // Only one independent group survives.
        reports.removeLast();
        assertThatThrownBy(() -> policy.reconcile(round(parameters), reports))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conflictsDuplicatesStalenessAndScaleMismatchFailClosed() {
        CompleteSourceMedianPolicy.Parameters parameters = parameters(false);
        CompleteSourceMedianPolicy policy = new CompleteSourceMedianPolicy(parameters);
        ObservationRound round = round(parameters);
        for (int failure = 0; failure < 5; failure++) {
            List<ObservationReport> reports = claims(new int[]{0, 0, 0}, new long[]{100, 102, 104}, 2);
            switch (failure) {
                case 0 -> reports.set(0, claim(0, 1, 999, 2, 10));
                case 1 -> reports.set(0, reports.get(1));
                case 2 -> reports.set(0, claim(0, 1, 100, 2, 9));
                case 3 -> reports.set(0, claim(0, 1, 100, 3, 10));
                case 4 -> reports.set(0, claim(4, 1, 100, 2, 10));
                default -> throw new AssertionError();
            }
            assertThatThrownBy(() -> policy.reconcile(round, reports)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void policyEncodingRejectsTruncationTrailingDataAndNoncanonicalSourceOrder() {
        byte[] encoded = parameters(false).encode();
        assertThat(CompleteSourceMedianPolicy.Parameters.decode(encoded).encode()).isEqualTo(encoded);
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThatThrownBy(() -> CompleteSourceMedianPolicy.Parameters.decode(truncated))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> CompleteSourceMedianPolicy.Parameters.decode(Arrays.copyOf(encoded, encoded.length + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] swapped = encoded.clone();
        System.arraycopy(encoded, 62, swapped, 126, 64);
        System.arraycopy(encoded, 126, swapped, 62, 64);
        assertThatThrownBy(() -> CompleteSourceMedianPolicy.Parameters.decode(swapped))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CompleteSourceMedianPolicy.Parameters parameters(boolean aliases) {
        return new CompleteSourceMedianPolicy.Parameters(2, 2, 100_000, BigInteger.ZERO,
                BigInteger.valueOf(-1000), BigInteger.valueOf(1000), List.of(
                new CompleteSourceMedianPolicy.Source(id(0), id(10)),
                new CompleteSourceMedianPolicy.Source(id(1), id(aliases ? 10 : 11)),
                new CompleteSourceMedianPolicy.Source(id(2), id(12))));
    }

    private static ObservationRound round(CompleteSourceMedianPolicy.Parameters parameters) {
        return new ObservationRound(1, id(20), 0, ObservationAnchorType.APP_HEIGHT,
                10, 10, 12, 4, 20, 0, id(21), id(22), 0, id(23), 5, 4, 1,
                ObservationReporterMode.EXTERNAL_REPORTERS, id(24), 5, 1, 4,
                parameters.sourceSetDigest(), parameters.digest());
    }

    private static List<ObservationReport> claims(int[] excluded, long[] values, int scale) {
        List<ObservationReport> reports = new ArrayList<>();
        for (int source = 0; source < values.length; source++) {
            for (int reporter = 0; reporter < 5; reporter++) {
                if (reporter != excluded[source]) reports.add(claim(source, reporter, values[source], scale, 10));
            }
        }
        return reports;
    }

    private static ObservationReport claim(int source, int reporter, long units, int scale, long anchor) {
        return new ObservationReport(1, id(30), "chain", id(31), id(32), id(21), id(20), 0,
                id(23), id(24), id(40 + reporter), id(source),
                new ObservationFixedPoint(BigInteger.valueOf(units), scale).encode(), new byte[0],
                new byte[]{1}, ObservationAnchorType.APP_HEIGHT.code(), anchor, new byte[64]);
    }

    private static byte[] id(int value) {
        byte[] id = new byte[32];
        Arrays.fill(id, (byte) value);
        return id;
    }
}
