package org.yanoproject.ledger.conformance.mutation;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.yanoproject.ledger.conformance.engines.BaselineEngines;
import org.yanoproject.ledger.conformance.engines.JavaViewEngine;
import org.yanoproject.ledger.conformance.mutation.Mutations.WorldCase;
import org.yanoproject.ledger.conformance.mutation.Mutations.WorldExpectation;
import org.yanoproject.ledger.conformance.runner.ConformanceCase;
import org.yanoproject.ledger.conformance.runner.ConformanceEngine;
import org.yanoproject.ledger.conformance.runner.ConformanceRunner;
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayRuleSets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-protocol-version regression pinning of the mutation matrix (ADR-056 Phase 5c): every mutant is built and
 * validated in the world of every protocol version the engine supports, and each version is its own test, so a
 * regression at one version cannot be masked by the others.
 *
 * <p>In its own world a mutant must be rejected with exactly its Haskell failure list (as {@code MutationMatrixTest}
 * also checks). In the other worlds it must be judged the same, unless a protocol-version gate makes Haskell judge it
 * differently: {@link Mutations#WORLD_EXPECTATIONS} records those verdicts with the gate, and
 * {@link Mutations#worldCases(int)} refuses a replay whose list names a constructor that does not exist in that world.
 * With the Amaru reference engine in the build, the protocol version 10 and 11 worlds are cross-checked against it too
 * (Amaru refuses protocol version 9).</p>
 *
 * <p>The counts per world are pinned ({@link #PINNED}): a mutant that stops being validated, or a verdict that flips
 * between valid and invalid, changes its world's count.</p>
 */
class MutationWorldMatrixTest {

    private static final Optional<ConformanceEngine> REFERENCE = BaselineEngines.amaru();
    private static final ConformanceEngine JAVA_LEDGER = new JavaViewEngine();
    private static final ConformanceEngine JAVA_MEMPOOL = new JavaViewEngine(TxValidationRequest.Rule.MEMPOOL);

    /**
     * Per world: the cases (every mutant under {@code LEDGER}, plus the {@code MEMPOOL} expectations) and how many of
     * them Haskell accepts.
     */
    private record Counts(int cases, int valid) {
    }

    private static final Map<Integer, Counts> PINNED = Map.of(
            9, new Counts(96, 8),
            10, new Counts(96, 4),
            11, new Counts(95, 4));

    /** One test per world. */
    @TestFactory
    Stream<DynamicTest> everyMutantInEveryWorld() {
        return Mutations.WORLDS.stream().map(world -> DynamicTest.dynamicTest("protocol version " + world,
                () -> everyMutantInTheWorldOfProtocolVersion(world)));
    }

    private static void everyMutantInTheWorldOfProtocolVersion(int world) {
        List<WorldCase> cases = Mutations.worldCases(world);
        List<String> misses = new ArrayList<>();
        List<String> amaruMisses = new ArrayList<>();
        int valid = 0;
        for (WorldCase c : cases) {
            ConformanceCase testCase = c.testCase();
            ConformanceEngine engine = c.rule() == TxValidationRequest.Rule.MEMPOOL ? JAVA_MEMPOOL : JAVA_LEDGER;
            List<String> failures = names(ConformanceRunner.run(engine, testCase).observation());
            if (!failures.equals(c.expected())) {
                misses.add(c.id() + " (" + c.source() + "): Haskell " + label(c.expected()) + ", java-julc "
                        + label(failures));
            }
            if (c.expected().isEmpty()) {
                valid++;
            }
            if (world >= 10 && c.rule() == TxValidationRequest.Rule.LEDGER) {
                REFERENCE.ifPresent(amaru -> amaruDisagreement(amaru, c, testCase).ifPresent(amaruMisses::add));
            }
        }
        System.out.printf("Mutation world matrix, protocol version %d: %d cases (%d valid), %d misses, %d Amaru "
                + "disagreements%n", world, cases.size(), valid, misses.size(), amaruMisses.size());
        misses.forEach(m -> System.out.println("  java-julc: " + m));
        amaruMisses.forEach(m -> System.out.println("  amaru: " + m));
        assertThat(misses).as("protocol version %d: mutants the java engine judges other than Haskell", world)
                .isEmpty();
        assertThat(amaruMisses).as("protocol version %d: mutants Amaru judges other than recorded", world).isEmpty();
        assertThat(new Counts(cases.size(), valid)).as("protocol version %d: pinned counts", world)
                .isEqualTo(PINNED.get(world));
    }

    @Test
    void theWorldsAreTheSupportedProtocolVersions() {
        assertThat(Mutations.WORLDS).isEqualTo(IntStream.rangeClosed(ConwayRuleSets.SUPPORTED.min(),
                ConwayRuleSets.all().getLast().protocolVersion()).boxed().toList());
        assertThat(PINNED.keySet()).containsExactlyInAnyOrderElementsOf(Mutations.WORLDS);
    }

    @Test
    void everyWorldExpectationIsForAnotherWorldOrMempool() {
        Set<String> seen = new HashSet<>();
        for (WorldExpectation e : Mutations.WORLD_EXPECTATIONS) {
            Mutation mutation = Mutations.find(e.mutationId()).orElseThrow(
                    () -> new AssertionError("no mutation " + e.mutationId()));
            assertThat(seen.add(e.mutationId() + "@" + e.protocolMajor() + "/" + e.rule())).as("duplicate %s", e)
                    .isTrue();
            assertThat(Mutations.WORLDS).contains(e.protocolMajor());
            assertThat(e.haskell()).as("%s cites the Haskell gate", e).isNotBlank();
            if (e.rule() == TxValidationRequest.Rule.LEDGER) {
                assertThat(e.protocolMajor()).as("%s: its own world's list is the mutation's", e)
                        .isNotEqualTo(mutation.protocolMajor());
            }
            e.failures().forEach(f -> assertThat(Mutations.existsAt(f, e.protocolMajor()))
                    .as("%s: %s exists at protocol version %d", e.mutationId(), f, e.protocolMajor()).isTrue());
        }
    }

    /**
     * @return a description when Amaru's verdict is not Haskell's (or the recorded divergence, which holds in every
     *         world), empty when it agrees
     */
    private static Optional<String> amaruDisagreement(ConformanceEngine amaru, WorldCase c, ConformanceCase testCase) {
        Observation observation = ConformanceRunner.run(amaru, testCase).observation();
        List<String> failures = names(observation);
        // A recorded divergence holds in every world, except Amaru's refusal of protocol version 9, which is the
        // bootstrap world's alone.
        String recorded = Mutation.AMARU_REFUSES_PV9.equals(c.mutation().amaruReports()) ? null
                : c.mutation().amaruReports();
        if (Mutation.AMARU_ACCEPTS.equals(recorded) || c.expected().isEmpty()) {
            boolean agrees = observation.valid()
                    || (recorded != null && !Mutation.AMARU_ACCEPTS.equals(recorded) && failures.equals(
                    List.of(recorded)));
            return agrees ? Optional.empty() : Optional.of(c.id() + ": Haskell " + label(c.expected()) + ", Amaru "
                    + label(failures));
        }
        Set<String> allowed = recorded != null ? Set.of(recorded) : Set.copyOf(c.expected());
        boolean agrees = !observation.valid() && allowed.containsAll(failures);
        return agrees ? Optional.empty() : Optional.of(c.id() + ": Haskell " + label(c.expected()) + ", Amaru "
                + label(failures));
    }

    private static List<String> names(Observation observation) {
        return observation.failures().stream().map(Observation.Failure::qualifiedName).toList();
    }

    private static String label(List<String> failures) {
        return failures.isEmpty() ? "Valid" : failures.toString();
    }
}
