package org.yanoproject.ledger.conformance.diversity.uplc;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class UplcEvaluationTest {

    private static final long[] V1 = costModel("cost-model-pv11-v1.json");
    private static final long[] V3 = costModel("cost-model-pv11-v3.json");

    @Test
    void anAlphaEquivalentResultIsReportedAsTheExpectedText() {
        String expected = "(program 1.0.0 (lam z (con integer 1)))";
        UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate(null,
                "(program 1.0.0 [(lam x (lam y x)) (con integer 1)])", expected, 3, 11, V3);

        assertThat(outcome.evaluation()).isEqualTo(expected);
        assertThat(outcome.budget()).isNotNull();
        assertThat(outcome.budget().cpuSteps()).isPositive();
    }

    @Test
    void anotherResultIsReportedAsJulcPrintsIt() {
        UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate(null, "(program 1.0.0 (con integer 2))",
                "(program 1.0.0 (con integer 1))", 3, 11, V3);

        assertThat(outcome.evaluation()).isEqualTo("(program 1.0.0 (con integer 2))");
    }

    @Test
    void theFlatProgramIsEvaluatedWhenPresent() {
        // (program 1.0.0 (lam i-0 i-0)), Flat
        UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate("010000200101", "(program 1.0.0 (error))",
                "(program 1.0.0 (lam x x))", 3, 11, V3);

        assertThat(outcome.evaluation()).isEqualTo("(program 1.0.0 (lam x x))");
    }

    @Test
    void errorLabels() {
        assertThat(UplcEvaluation.evaluate(null, "(program 1.0.0 (error))", "evaluation failure", 3, 11, V3)
                .evaluation()).isEqualTo(UplcEvaluation.EVALUATION_FAILURE);
        assertThat(UplcEvaluation.evaluate(null, "(program 1.0.0 [(lam x [x x]) (lam x [x x])])", "out of budget", 3,
                11, V3).evaluation()).isEqualTo(UplcEvaluation.OUT_OF_BUDGET);
        assertThat(UplcEvaluation.evaluate(null, "(program 1.0.0 (con integer", "parse/decode error", 3, 11, V3)
                .evaluation()).isEqualTo(UplcEvaluation.PARSE_ERROR);
        // case/constr need a builtin-free 1.1.0 program, which PlutusV1 gets only from protocol version 11
        assertThat(UplcEvaluation.evaluate(null, "(program 1.1.0 (constr 0))", "parse/decode error", 1, 10, V1)
                .evaluation()).isEqualTo(UplcEvaluation.PARSE_ERROR);
        assertThat(UplcEvaluation.evaluate(null, "(program 1.1.0 (constr 0))", "parse/decode error", 1, 10, V1)
                .budget()).isNull();
    }

    private static long[] costModel(String name) {
        try {
            return UplcEvaluation.costModel(Path.of(Objects.requireNonNull(
                    UplcEvaluationTest.class.getResource(name)).toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
