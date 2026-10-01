package org.yanoproject.ledger.conformance.runner;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs engines over cases. The runner never fails on an engine's answer: a crash is recorded as an observation,
 * because the baseline is informational (ADR-056 Phase 2) and engines are expected to disagree with Haskell.
 */
public final class ConformanceRunner {

    private ConformanceRunner() {
    }

    public static List<CaseResult> run(ConformanceEngine engine, List<ConformanceCase> cases) {
        List<CaseResult> results = new ArrayList<>(cases.size());
        for (ConformanceCase testCase : cases) {
            results.add(run(engine, testCase));
        }
        return results;
    }

    public static CaseResult run(ConformanceEngine engine, ConformanceCase testCase) {
        long start = System.nanoTime();
        Observation observation;
        try {
            observation = engine.validate(testCase);
        } catch (Exception | LinkageError | StackOverflowError e) {
            observation = Observation.crash(e);
        }
        return new CaseResult(engine.name(), testCase, observation, (System.nanoTime() - start) / 1e6);
    }
}
