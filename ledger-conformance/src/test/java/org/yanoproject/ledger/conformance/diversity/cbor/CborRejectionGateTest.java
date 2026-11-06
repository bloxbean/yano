package org.yanoproject.ledger.conformance.diversity.cbor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.ledger.conformance.diversity.cbor.CborRejectionGate.Baseline;
import static org.yanoproject.ledger.conformance.diversity.cbor.CborRejectionGate.Sample;

class CborRejectionGateTest {

    private static final Baseline BASELINE = new Baseline(Set.of("missed"), Set.of("accepted"));

    private static Sample invalid(String name, boolean strictRejected, boolean passed) {
        return new Sample(name, false, strictRejected, passed);
    }

    @Test
    void aRunLikeItsBaselinePasses() {
        assertThat(CborRejectionGate.violations(BASELINE, List.of(invalid("missed", true, false),
                invalid("accepted", false, true), invalid("rejected", true, true)))).isEmpty();
    }

    @Test
    void oneRejectionLostAndAnotherGainedWithEqualTotalsFails() {
        assertThat(CborRejectionGate.violations(BASELINE, List.of(invalid("accepted", true, true),
                invalid("rejected", false, true)))).containsExactly("strict rejection lost: rejected");
    }

    @Test
    void oneReportMissFixedAndAnotherAddedWithEqualTotalsFails() {
        assertThat(CborRejectionGate.violations(BASELINE, List.of(invalid("missed", true, true),
                invalid("rejected", true, false)))).containsExactly("new report miss: rejected");
    }

    @Test
    void gainingARejectionAndFixingAMissPass() {
        assertThat(CborRejectionGate.violations(BASELINE, List.of(invalid("accepted", true, true),
                invalid("missed", true, true)))).isEmpty();
    }

    @Test
    void aValidSampleTheStrictReaderRejectsIsAReportMissOnly() {
        assertThat(CborRejectionGate.violations(BASELINE, List.of(new Sample("valid", true, true, false))))
                .containsExactly("new report miss: valid");
    }

    @Test
    void theBaselineHoldsBothSets() {
        Baseline baseline = CborRejectionGate.baseline();
        assertThat(baseline.reportMisses()).hasSize(83);
        // On dataset d4709b12 Yano's reader accepts 11 invalid samples: 9 multi-assets past isMultiAssetSmallEnough
        // (yano#238) and 2 maps with an 8-byte length head.
        assertThat(baseline.strictAccepts()).hasSize(11);
    }
}
