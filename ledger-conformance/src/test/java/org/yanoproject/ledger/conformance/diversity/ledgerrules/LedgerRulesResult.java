package org.yanoproject.ledger.conformance.diversity.ledgerrules;

import org.yanoproject.ledger.conformance.runner.CaseResult;
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruCorpusNames;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.Expected;

/**
 * The {@code result} of one ledger-rules report entry (ADR-060 §3), chosen as the Haskell reference chooses it
 * (Amaru's {@code validation-rules} checker, {@code Command/ValidatePhaseOne/Run.hs}, which produces cardano-node's
 * report): {@code "pass"} when accepted; the fixture's predicate when it is among the failures, in any position
 * (Haskell's STS order puts {@code UTXOW} before {@code CERTS}); otherwise the first failure under its corpus name, or
 * its bare Haskell constructor when no corpus name maps to it. A corpus name sharing {@code UTXOS.CollectErrors} also
 * needs its {@code CollectError} in the failure's detail ({@link AmaruCorpusNames#detailMatches}).
 */
final class LedgerRulesResult {

    static final String PASS = "pass";
    static final String DECODING_FAILURE = "DecodingFailure";

    private LedgerRulesResult() {
    }

    static String of(CaseResult result) {
        Observation observation = result.observation();
        if (observation.valid()) {
            return PASS;
        }
        if (expectedFound(result)) {
            return switch (result.testCase().expected()) {
                case Expected.Predicate p -> p.corpusName();
                case Expected.DecodingFailure d -> DECODING_FAILURE;
                case Expected.Pass p -> throw new IllegalStateException("a rejected case cannot match Pass");
            };
        }
        Observation.Failure first = observation.first();
        if (DECODING_FAILURE.equals(first.constructor())) {
            return DECODING_FAILURE;
        }
        return AmaruCorpusNames.corpusName(first.qualifiedName(), first.raw()).orElse(first.constructor());
    }

    /** {@link CaseResult#constructorFound()}, with the detail a corpus name sharing {@code CollectErrors} needs. */
    static boolean expectedFound(CaseResult result) {
        if (!(result.testCase().expected() instanceof Expected.Predicate p)) {
            return result.constructorFound();
        }
        return result.observation().failures().stream()
                .anyMatch(f -> result.testCase().acceptedFirst().contains(f.qualifiedName())
                        && AmaruCorpusNames.detailMatches(p.corpusName(), f.raw()));
    }
}
