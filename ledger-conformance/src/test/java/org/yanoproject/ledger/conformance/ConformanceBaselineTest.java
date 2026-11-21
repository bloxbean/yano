package org.yanoproject.ledger.conformance;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.engines.BaselineEngines;
import org.yanoproject.ledger.conformance.mutation.Mutations;
import org.yanoproject.ledger.conformance.report.BaselineReport;
import org.yanoproject.ledger.conformance.report.BaselineReport.EngineRun;
import org.yanoproject.ledger.conformance.runner.CaseResult;
import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.ConformanceEngine;
import org.yanoproject.ledger.conformance.runner.ConformanceRunner;
import org.yanoproject.ledger.conformance.runner.ScenarioCases;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 2 baseline: every engine of {@link BaselineEngines#all()} over the Amaru scenarios and the mutation
 * matrix, written to {@code build/conformance/baseline.md} (and, under {@code conformanceReport}, to
 * {@code ledger-conformance/docs/baseline-2026-09.md}).
 *
 * <p>The baseline is informational: engines are expected to fail cases. The test asserts that the runner answers
 * every case for every engine. Without the corpus the scenario half is left out of the report.</p>
 */
class ConformanceBaselineTest {

    @Test
    void measuresTheBaseline() {
        List<ConformanceCase> scenarios = ScenarioCases.cases().orElse(List.of());
        List<ConformanceCase> mutationCases = new ArrayList<>(Mutations.baseCases());
        mutationCases.addAll(Mutations.mutantCases());

        List<EngineRun> runs = new ArrayList<>();
        for (ConformanceEngine engine : BaselineEngines.all()) {
            List<CaseResult> scenarioResults = ConformanceRunner.run(engine, scenarios);
            List<CaseResult> mutationResults = ConformanceRunner.run(engine, mutationCases);
            assertThat(scenarioResults).hasSize(scenarios.size());
            assertThat(mutationResults).hasSize(mutationCases.size());
            runs.add(new EngineRun(engine, scenarioResults, mutationResults));
            System.out.printf(Locale.ROOT, "%-28s scenarios: verdict %3d, constructor %3d, found %3d of %d; "
                            + "mutants: %d of %d%n", engine.name(), count(scenarioResults, true),
                    count(scenarioResults, false), scenarioResults.stream().filter(CaseResult::constructorFound).count(),
                    scenarios.size(), mutationResults.stream().filter(CaseResult::constructorMatch).count(),
                    mutationResults.size());
        }

        if (!scenarios.isEmpty()) {
            assertThat(scenarios).hasSize(AmaruScenarioLoader.EXPECTED_SCENARIOS);
        }

        String report = new BaselineReport(runs).toMarkdown();
        Path written = ConformanceSettings.write("baseline.md", "conformance.baseline.file", report);
        System.out.println("Baseline report: " + written);
        assertThat(report).contains("## Summary", "## Mutation matrix");
    }

    private static long count(List<CaseResult> results, boolean verdict) {
        return results.stream().filter(verdict ? CaseResult::verdictMatch : CaseResult::constructorMatch).count();
    }
}
