package org.yanoproject.ledger.conformance.runner;

import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The Amaru scenarios as cases, when the corpus is configured. */
public final class ScenarioCases {

    private static List<AmaruScenario> cached;

    private ScenarioCases() {
    }

    /** @return the scenarios, or empty when {@code amaru.scenarios.dir} / {@code AMARU_SCENARIOS_DIR} is not set */
    public static synchronized Optional<List<AmaruScenario>> scenarios() {
        if (cached == null) {
            Optional<Path> dir = AmaruScenarioLoader.locateScenariosDir();
            if (dir.isEmpty()) {
                return Optional.empty();
            }
            cached = new AmaruScenarioLoader(dir.get()).loadAll();
        }
        return Optional.of(cached);
    }

    /** @return the scenarios as cases, or empty when the corpus is not configured */
    public static Optional<List<ConformanceCase>> cases() {
        return scenarios().map(list -> list.stream().map(ConformanceCase::of).toList());
    }
}
