package org.yanoproject.ledger.conformance.diversity.ledgerrules;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.diversity.NodeDiversity;
import org.yanoproject.ledger.conformance.engines.JavaViewEngine;
import org.yanoproject.ledger.conformance.runner.CaseResult;
import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.ConformanceRunner;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-060 §3: the {@code ledger-rules} report, engine {@code java-julc} over pragma's fixtures.
 */
class LedgerRulesDiversityReportTest {

    static final String SCENARIO = "ledger-rules";
    static final String FIXTURES = "node-diversity-conformance/scenarios/ledger-rules/fixtures";

    @Test
    void writesTheReport() {
        Path fixtures = NodeDiversity.requireCorpus(FIXTURES);
        List<ConformanceCase> cases = new AmaruScenarioLoader(fixtures).loadAll().stream()
                .map(ConformanceCase::of)
                .toList();
        List<CaseResult> results = ConformanceRunner.run(new JavaViewEngine(), cases);

        ObjectNode report = NodeDiversity.report(SCENARIO);
        ArrayNode entries = report.putArray("scenarios");
        int matched = 0;
        for (CaseResult result : results) {
            String value = LedgerRulesResult.of(result);
            entries.addObject().put("id", result.testCase().id().substring(0, 5)).put("result", value);
            if (result.constructorFound()) {
                matched++;
            }
        }
        NodeDiversity.write(SCENARIO, report);
        System.out.printf("ledger-rules: %d of %d match the fixtures%n", matched, results.size());

        assertThat(results).isNotEmpty().allSatisfy(r -> assertThat(r.testCase().id()).matches("\\d{5}-.*"));
    }
}
