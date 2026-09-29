package org.yanoproject.ledger.conformance.gate;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.yanoproject.ledger.conformance.engines.JavaViewEngine;
import org.yanoproject.ledger.conformance.runner.CaseResult;
import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.ConformanceRunner;
import org.yanoproject.ledger.conformance.runner.ScenarioCases;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayRuleSet;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayRuleSets;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ADR-056 Phase 5 gate: the Java engine ({@code java-engine}, every rule family) against <em>all</em> 276 Amaru
 * scenarios. Each must match (verdict and constructor: the first failure, any constructor of Haskell's list where
 * Haskell reports several, or a constructor Amaru's checker reports under the same corpus name), or be a recorded
 * divergence with the engine reporting what Haskell reports.
 *
 * <p>Recorded divergences: {@link JavaEnginePhase3GateTest#DIVERGENCES} (Haskell-vs-Amaru, the engine reports
 * Haskell's constructor).</p>
 *
 * <p>Since ADR-056 Phase 5b the engine validates protocol version 9 (the bootstrap phase) too, so the corpus's protocol
 * version 9 scenarios ({@link #BOOTSTRAP_SCENARIOS}) must match their expected (Haskell-cross-checked) predicate, although
 * the Amaru reference engine refuses them ({@code ENGINE.EraNotSupported}, its own minimum, invariant 6).</p>
 *
 * <p>Since ADR-056 Phase 5c the gate is also one test per protocol version with a pinned tally ({@link #PER_VERSION}),
 * so a regression at one version cannot be masked by the others.</p>
 */
class JavaEnginePhase5GateTest {

    /** The corpus's protocol version 9 scenarios: validated by the Java engine since Phase 5b, refused by Amaru. */
    static final Set<String> BOOTSTRAP_SCENARIOS = Set.of("00203-fail-gov-hardfork-initiation-chains-two-majors");

    /**
     * The gate's tally at one protocol version.
     *
     * @param scenarios the corpus's scenarios at the version
     * @param matched   those matching Amaru and Haskell
     * @param divergent those matching Haskell where Amaru diverges ({@link JavaEnginePhase3GateTest#DIVERGENCES})
     * @param bootstrap those matching Haskell at protocol version 9, which Amaru refuses
     */
    record Tally(int scenarios, int matched, int divergent, int bootstrap) {
    }

    /**
     * Pinned per protocol version (ADR-056 Phase 5c): each version is its own test, so a regression at one version
     * cannot be masked by the others, and a scenario that moves between versions or categories changes a count.
     */
    static final Map<Integer, Tally> PER_VERSION = Map.of(
            9, new Tally(1, 0, 0, 1),
            10, new Tally(273, 272, 1, 0),
            11, new Tally(2, 2, 0, 0));

    /** One scenario's gate verdict. */
    private record Verdict(int protocolVersion, String family, String category, String miss) {
    }

    private static List<Verdict> verdicts;

    @Test
    void everyScenarioMatchesHaskell() {
        List<Verdict> all = verdicts();
        Map<String, Integer> perFamily = new TreeMap<>();
        all.forEach(v -> perFamily.merge(v.family(), 1, Integer::sum));
        Map<String, Long> perCategory = new TreeMap<>();
        all.forEach(v -> perCategory.merge(v.category(), 1L, Long::sum));
        System.out.printf("Phase 5 gate: %d scenarios %s, %d match Amaru and Haskell, %d match Haskell where Amaru "
                + "diverges, %d match Haskell at protocol version 9 (Amaru refuses them)%n", all.size(), perFamily,
                perCategory.getOrDefault("matched", 0L), perCategory.getOrDefault("divergent", 0L),
                perCategory.getOrDefault("bootstrap", 0L));
        assertThat(all.stream().map(Verdict::miss).filter(Objects::nonNull).toList()).as("Phase 5 gate misses")
                .isEmpty();
        assertThat(perCategory.getOrDefault("divergent", 0L)).isEqualTo(JavaEnginePhase3GateTest.DIVERGENCES.size());
        assertThat(perCategory.getOrDefault("bootstrap", 0L)).isEqualTo(BOOTSTRAP_SCENARIOS.size());
        assertThat(all).hasSize(AmaruScenarioLoader.EXPECTED_SCENARIOS);
        assertThat(all.stream().map(Verdict::protocolVersion).distinct().sorted().toList())
                .as("every protocol version of the corpus has a pinned tally")
                .isSubsetOf(PER_VERSION.keySet());
    }

    /** The gate per protocol version: every supported version is its own test. */
    @TestFactory
    Stream<DynamicTest> everyScenarioOfEveryProtocolVersionMatchesHaskell() {
        return ConwayRuleSets.all().stream().map(ConwayRuleSet::protocolVersion).map(version ->
                DynamicTest.dynamicTest("protocol version " + version, () -> {
                    List<Verdict> atVersion = verdicts().stream().filter(v -> v.protocolVersion() == version)
                            .toList();
                    List<String> misses = atVersion.stream().map(Verdict::miss).filter(Objects::nonNull).toList();
                    Tally tally = new Tally(atVersion.size(), count(atVersion, "matched"),
                            count(atVersion, "divergent"), count(atVersion, "bootstrap"));
                    System.out.printf("Phase 5 gate, protocol version %d: %s%n", version, tally);
                    assertThat(misses).as("Phase 5 gate misses at protocol version %d", version).isEmpty();
                    assertThat(tally).as("Phase 5 gate at protocol version %d", version)
                            .isEqualTo(PER_VERSION.get(version));
                }));
    }

    private static int count(List<Verdict> verdicts, String category) {
        return (int) verdicts.stream().filter(v -> v.category().equals(category)).count();
    }

    /** Runs the java engine over the corpus once. */
    private static synchronized List<Verdict> verdicts() {
        if (verdicts != null) {
            return verdicts;
        }
        List<ConformanceCase> all = ScenarioCases.cases().orElse(List.of());
        Assumptions.assumeFalse(all.isEmpty(), "the Amaru scenarios are not configured (-PamaruScenariosDir)");
        assertThat(all).hasSize(AmaruScenarioLoader.EXPECTED_SCENARIOS);

        JavaViewEngine engine = new JavaViewEngine();
        List<Verdict> result = new ArrayList<>();
        for (ConformanceCase testCase : all) {
            int version = testCase.env().protocolMajor();
            CaseResult caseResult = ConformanceRunner.run(engine, testCase);
            JavaEnginePhase3GateTest.Divergence divergence = JavaEnginePhase3GateTest.DIVERGENCES.get(testCase.id());
            String category;
            String miss = null;
            if (version < 10) {
                category = "bootstrap";
                if (!BOOTSTRAP_SCENARIOS.contains(testCase.id())) {
                    miss = testCase.id() + ": an unrecorded protocol version " + version + " scenario";
                } else if (!caseResult.constructorMatch()) {
                    miss = testCase.id() + " (protocol version 9): expected " + testCase.expectedLabel() + ", got "
                            + caseResult.observation().label() + " " + caseResult.observation().failures();
                }
            } else if (divergence != null) {
                category = "divergent";
                if (caseResult.observation().valid()
                        || !caseResult.observation().first().qualifiedName().equals(divergence.haskell())) {
                    miss = testCase.id() + ": Haskell reports " + divergence.haskell() + ", got "
                            + caseResult.observation().label();
                }
            } else {
                category = "matched";
                if (!caseResult.constructorMatch()) {
                    miss = testCase.id() + ": expected " + testCase.expectedLabel() + ", got "
                            + caseResult.observation().label() + " " + caseResult.observation().failures();
                }
            }
            result.add(new Verdict(version, testCase.family(), miss == null ? category : "miss", miss));
        }
        verdicts = List.copyOf(result);
        return verdicts;
    }
}
