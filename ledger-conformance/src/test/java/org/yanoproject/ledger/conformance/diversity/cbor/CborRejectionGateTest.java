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
        return new Sample(name, false, strictRejected, false, passed);
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
        assertThat(CborRejectionGate.violations(BASELINE, List.of(new Sample("valid", true, true, false, false))))
                .containsExactly("new report miss: valid");
    }

    /** Admission decodes with CCL's model before the strict reader, so a valid sample it rejects is a violation. */
    @Test
    void aValidSampleTheModelRejectsFails() {
        assertThat(CborRejectionGate.violations(BASELINE, List.of(new Sample("valid", true, false, true, true))))
                .containsExactly("valid sample the model rejects: valid");
        // one the report misses already is a report miss, of the baseline's or new
        assertThat(CborRejectionGate.violations(BASELINE, List.of(new Sample("missed", true, true, true, false))))
                .isEmpty();
        assertThat(CborRejectionGate.violations(BASELINE, List.of(invalid("rejected", true, true)))).isEmpty();
    }

    @Test
    void theBaselineHoldsBothSets() {
        Baseline baseline = CborRejectionGate.baseline();
        assertThat(baseline.reportMisses()).hasSize(65);
        // Yano's reader rejects every invalid sample since each rule is read directly (ADR 0003 M6).
        assertThat(baseline.strictAccepts()).isEmpty();
    }
}
