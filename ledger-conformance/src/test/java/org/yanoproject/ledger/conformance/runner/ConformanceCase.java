package org.yanoproject.ledger.conformance.runner;

import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruCorpusNames;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.Expected;
import org.yanoproject.ledger.rules.fixtures.conformance.ConwayConstructorCatalogue;
import org.yanoproject.ledger.rules.view.LedgerView;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One engine-agnostic validation case: a transaction, the state to validate it against, and the expected
 * Haskell verdict. Amaru scenarios and mutants both become cases, so every engine runs through the same
 * runner.
 *
 * @param id        the scenario name ({@code 00056-fail-…}) or the mutant id ({@code mutant:fee-minus-one})
 * @param title     a human-readable title
 * @param kind      where the case comes from
 * @param txCbor    the transaction bytes
 * @param view      the ledger state
 * @param env       the validation environment
 * @param network   network magic, era history and global parameters (for engines that need them, e.g. Amaru)
 * @param constants Haskell-hardcoded values the case moves (Amaru scenarios only; otherwise
 *                  {@link AmaruScenario.LedgerConstants#NONE})
 * @param expected  the expected Haskell verdict
 * @param haskellFailures for an expected predicate that Haskell always reports together with others, Haskell's
 *                  whole failure list in its order (it contains the expected constructor); empty when the expected
 *                  constructor is the only one. An engine's first failure matches when it is any of them, so the
 *                  harness never favours an order other than Haskell's.
 */
public record ConformanceCase(String id, String title, Kind kind, byte[] txCbor, LedgerView view, ValidationEnv env,
                              AmaruScenario.Network network, AmaruScenario.LedgerConstants constants,
                              Expected expected, List<String> haskellFailures) {

    public enum Kind {
        /** An Amaru scenario (Haskell-cross-checked). */
        SCENARIO,
        /** A valid base transaction of the mutation matrix. */
        MUTATION_BASE,
        /** A single-fault mutant of a base transaction. */
        MUTANT
    }

    public ConformanceCase {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        txCbor = Objects.requireNonNull(txCbor, "txCbor").clone();
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(constants, "constants");
        Objects.requireNonNull(expected, "expected");
        haskellFailures = List.copyOf(haskellFailures);
        if (!haskellFailures.isEmpty() && (!(expected instanceof Expected.Predicate p)
                || !haskellFailures.contains(p.qualifiedName()))) {
            throw new IllegalArgumentException(id + ": the Haskell failure list must contain the expected constructor");
        }
    }

    @Override
    public byte[] txCbor() {
        return txCbor.clone();
    }

    /** @return a case for an Amaru scenario */
    public static ConformanceCase of(AmaruScenario scenario) {
        return new ConformanceCase(scenario.name(), scenario.title(), Kind.SCENARIO, scenario.txCbor(), scenario.view(),
                scenario.env(), scenario.network(), scenario.constants(), scenario.expected(),
                HaskellFailureLists.forScenario(scenario.name()));
    }

    /** @return {@code PASS}, {@code DECODING} or the expected constructor's rule family */
    public String family() {
        return switch (expected) {
            case Expected.Pass p -> "PASS";
            case Expected.DecodingFailure d -> "DECODING";
            case Expected.Predicate p -> ConwayConstructorCatalogue.get().find(p.qualifiedName())
                    .map(ConwayConstructorCatalogue.Entry::family)
                    .orElse(p.rule().name());
        };
    }

    /** @return {@code Pass}, {@code DecodingFailure} or {@code RULE.Constructor} */
    public String expectedLabel() {
        return switch (expected) {
            case Expected.Pass p -> "Pass";
            case Expected.DecodingFailure d -> "DecodingFailure";
            case Expected.Predicate p -> haskellFailures.isEmpty() ? p.qualifiedName()
                    : p.qualifiedName() + " (Haskell: " + String.join(", ", haskellFailures) + ")";
        };
    }

    /**
     * @return the constructors an engine's first failure may be: the Haskell list, or the expected constructor; for an
     *         Amaru scenario also the constructors Amaru's checker reports under the same corpus name
     *         ({@link AmaruCorpusNames#aliases(String)})
     */
    public Set<String> acceptedFirst() {
        Set<String> accepted = new LinkedHashSet<>();
        if (!haskellFailures.isEmpty()) {
            accepted.addAll(haskellFailures);
        } else if (expectedConstructor() != null) {
            accepted.add(expectedConstructor());
        }
        if (kind == Kind.SCENARIO && expected instanceof Expected.Predicate p) {
            accepted.addAll(AmaruCorpusNames.aliases(p.corpusName()));
        }
        return accepted;
    }

    /** @return the expected {@code RULE.Constructor}, or null for a pass or a decoding failure */
    public String expectedConstructor() {
        return expected instanceof Expected.Predicate p ? p.qualifiedName() : null;
    }

    @Override
    public String toString() {
        return id;
    }
}
