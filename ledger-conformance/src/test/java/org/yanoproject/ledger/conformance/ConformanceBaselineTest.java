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
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.conformance.runner.ScenarioCases;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 2 baseline: every engine ({@code scalus-legacy}, {@code scalus-legacy+supplementary},
 * {@code scalus-engine}, {@code java-legacy}, and {@code amaru} in {@code -PwithAmaru=true} builds) over the Amaru
 * scenarios and the mutation matrix, written to {@code build/conformance/baseline.md} (and, under
 * {@code conformanceReport}, to {@code ledger-conformance/docs/baseline-2026-09.md}).
 *
 * <p>The baseline is informational: engines are expected to fail cases. The test asserts that the runner answers
 * every case for every engine and, with the reference engine present, that Amaru still matches the corpus (275 of
 * 276, the remaining scenario being the PV 9 one it refuses by design) and every mutant. Without the corpus the
 * scenario half is left out of the report.</p>
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
        runs.stream().filter(r -> r.engine().name().equals(BaselineEngines.AMARU)).findFirst()
                .ifPresent(ConformanceBaselineTest::assertReference);

        String report = new BaselineReport(runs).toMarkdown();
        Path written = ConformanceSettings.write("baseline.md", "conformance.baseline.file", report);
        System.out.println("Baseline report: " + written);
        assertThat(report).contains("## Summary", "## Mutation matrix");
    }

    /** Amaru is the reference: its gate result must still hold through this harness. */
    private static void assertReference(EngineRun amaru) {
        List<CaseResult> misses = amaru.scenarios().stream().filter(r -> !r.constructorMatch()).toList();
        if (!amaru.scenarios().isEmpty()) {
            assertThat(amaru.scenarios().size() - misses.size()).as("amaru scenario matches")
                    .isEqualTo(AmaruScenarioLoader.EXPECTED_SCENARIOS - 1);
            assertThat(misses).singleElement().satisfies(miss -> {
                assertThat(miss.testCase().env().protocolMajor()).isLessThan(10);
                assertThat(Observation.isEraNotSupported(miss.observation().first())).isTrue();
            });
        }
        assertThat(amaru.mutations()).as("amaru over the mutation matrix")
                .allMatch(ConformanceBaselineTest::matchesOrRecordedAmaruDivergence);
    }

    /**
     * A mutant Amaru judges as Haskell does, or one whose fault Amaru names differently from Haskell and for which
     * that divergence is recorded ({@code Mutation#amaruReports()}).
     */
    private static boolean matchesOrRecordedAmaruDivergence(CaseResult result) {
        if (result.constructorMatch()) {
            return true;
        }
        String id = result.testCase().id();
        return Mutations.all().stream()
                .filter(m -> m.caseId().equals(id) && m.amaruReports() != null)
                .anyMatch(m -> !result.observation().valid()
                        && m.amaruReports().equals(result.observation().first().qualifiedName()));
    }

    private static long count(List<CaseResult> results, boolean verdict) {
        return results.stream().filter(verdict ? CaseResult::verdictMatch : CaseResult::constructorMatch).count();
    }
}
