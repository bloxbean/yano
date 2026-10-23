package org.yanoproject.ledger.conformance.diversity.uplc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UplcEvaluationTest {

    /** The cost model: julc's defaults. */
    private static final long[] DEFAULTS = null;

    @Test
    void anAlphaEquivalentResultIsReportedAsTheExpectedText() {
        String expected = "(program 1.0.0 (lam z (con integer 1)))";
        UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate(null,
                "(program 1.0.0 [(lam x (lam y x)) (con integer 1)])", expected, 3, 11, DEFAULTS);

        assertThat(outcome.evaluation()).isEqualTo(expected);
        assertThat(outcome.budget()).isNotNull();
        assertThat(outcome.budget().cpuSteps()).isPositive();
    }

    @Test
    void anotherResultIsReportedAsJulcPrintsIt() {
        UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate(null, "(program 1.0.0 (con integer 2))",
                "(program 1.0.0 (con integer 1))", 3, 11, DEFAULTS);

        assertThat(outcome.evaluation()).isEqualTo("(program 1.0.0 (con integer 2))");
    }

    @Test
    void theFlatProgramIsEvaluatedWhenPresent() {
        // (program 1.0.0 (lam i-0 i-0)), Flat
        UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate("010000200101", "(program 1.0.0 (con integer 1))",
                "(program 1.0.0 (lam x x))", 3, 11, DEFAULTS);

        assertThat(outcome.evaluation()).isEqualTo("(program 1.0.0 (lam x x))");
    }

    @Test
    void aTextThatDoesNotParseIsADecodeErrorEvenWithAFlatProgram() {
        // the reference harness parses the text first (constr-07: a negative tag in text, 2^64 - 5 in Flat)
        assertThat(UplcEvaluation.evaluate("010000200101", "(program 1.1.0 (constr -5))", "parse/decode error", 3,
                11, DEFAULTS).evaluation()).isEqualTo(UplcEvaluation.PARSE_ERROR);
    }

    @Test
    void errorLabels() {
        assertThat(UplcEvaluation.evaluate(null, "(program 1.0.0 (error))", "evaluation failure", 3, 11, DEFAULTS)
                .evaluation()).isEqualTo(UplcEvaluation.EVALUATION_FAILURE);
        assertThat(UplcEvaluation.evaluate(null, "(program 1.0.0 [(lam x [x x]) (lam x [x x])])", "out of budget", 3,
                11, DEFAULTS).evaluation()).isEqualTo(UplcEvaluation.OUT_OF_BUDGET);
        assertThat(UplcEvaluation.evaluate(null, "(program 1.0.0 (con integer", "parse/decode error", 3, 11, DEFAULTS)
                .evaluation()).isEqualTo(UplcEvaluation.PARSE_ERROR);
        // case/constr need a builtin-free 1.1.0 program, which PlutusV1 gets only from protocol version 11
        assertThat(UplcEvaluation.evaluate(null, "(program 1.1.0 (constr 0))", "parse/decode error", 1, 10, DEFAULTS)
                .evaluation()).isEqualTo(UplcEvaluation.PARSE_ERROR);
        assertThat(UplcEvaluation.evaluate(null, "(program 1.1.0 (constr 0))", "parse/decode error", 1, 10, DEFAULTS)
                .budget()).isNull();
    }
}
