package org.yanoproject.ledger.conformance.runner;

import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.Expected;

/**
 * One engine's result for one case.
 *
 * <ul>
 *   <li><b>verdict match</b>: accepted exactly when the expected verdict is {@code Pass};</li>
 *   <li><b>constructor match</b>: the verdict matches and the engine's <em>first</em> failure is the expected
 *       Haskell rule and constructor, or, where Haskell always reports the fault with several constructors
 *       ({@link ConformanceCase#haskellFailures()}), any of them; a decoding failure must be reported as
 *       {@code DecodingFailure};</li>
 *   <li><b>constructor found</b>: an accepted constructor is among the engine's failures, in any position (engines
 *       that report every failure, like the copied Java rules, may order them differently from Haskell).</li>
 * </ul>
 *
 * @param engine      the engine name
 * @param testCase    the case
 * @param observation what the engine said
 * @param millis      wall time of the call
 */
public record CaseResult(String engine, ConformanceCase testCase, Observation observation, double millis) {

    public boolean verdictMatch() {
        return observation.valid() == testCase.expected() instanceof Expected.Pass;
    }

    public boolean constructorMatch() {
        return switch (testCase.expected()) {
            case Expected.Pass p -> observation.valid();
            case Expected.DecodingFailure d -> !observation.valid() && Observation.isDecodingFailure(observation.first());
            case Expected.Predicate p -> !observation.valid()
                    && testCase.acceptedFirst().contains(observation.first().qualifiedName());
        };
    }

    public boolean constructorFound() {
        return switch (testCase.expected()) {
            case Expected.Pass p -> observation.valid();
            case Expected.DecodingFailure d -> !observation.valid()
                    && observation.failures().stream().anyMatch(Observation::isDecodingFailure);
            case Expected.Predicate p -> !observation.valid() && observation.failures().stream()
                    .anyMatch(f -> testCase.acceptedFirst().contains(f.qualifiedName()));
        };
    }
}
