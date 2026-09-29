package org.yanoproject.ledger.conformance.gate;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.ConformanceSettings;
import org.yanoproject.ledger.conformance.blueprint.AmaruKnownFailures;
import org.yanoproject.ledger.conformance.blueprint.BlueprintReport;
import org.yanoproject.ledger.conformance.blueprint.BlueprintReport.Recorded;
import org.yanoproject.ledger.conformance.blueprint.BlueprintVectorResults;
import org.yanoproject.ledger.conformance.blueprint.BlueprintVectorRunner;
import org.yanoproject.ledger.conformance.blueprint.BlueprintVectorRunner.StateCheck;
import org.yanoproject.ledger.conformance.blueprint.BlueprintVectorRunner.Status;
import org.yanoproject.ledger.conformance.blueprint.BlueprintVectorRunner.VectorResult;
import org.yanoproject.ledger.conformance.blueprint.WithParameters;
import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.rules.fixtures.blueprint.BlueprintVector;
import org.yanoproject.ledger.rules.fixtures.blueprint.BlueprintVectorLoader;
import org.yanoproject.ledger.rules.fixtures.blueprint.NewEpochStateDecoder;


import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ADR-056 Phase 7b gate: the Java engine over the pinned cardano-blueprint ledger conformance vectors (Conway runs
 * of the Haskell Imp tests; {@code BlueprintVectorLoader} has the pin, {@code BlueprintVectorRunner} the harness).
 *
 * <ul>
 *   <li>the vectors are the pinned set, and every initial and final state decodes (fail closed otherwise);</li>
 *   <li>every vector Amaru's harness passes, the Java engine passes too (ADR-056 Phase 7: "blueprint Conway vectors at
 *       or above Amaru's recorded pass set"), unless the exception is recorded in {@link #RECORDED} with its cause;</li>
 *   <li>the tallies per Imp spec and protocol version are pinned ({@link #PINNED}), so a vector that moves between
 *       statuses changes a count;</li>
 *   <li>every vector that stays in one epoch with unchanged parameters reaches exactly the vector's final state.</li>
 * </ul>
 *
 * <p>Reads the vendored vectors ({@code ledger-conformance/vectors/cardano-blueprint}, which the build passes by
 * default; {@code -PblueprintVectors=<directory>} points at another copy). Skips (a JUnit assumption) only when run
 * without the build's system property, for example from an IDE without it.</p>
 */
class BlueprintVectorGateTest {

    /** A vector with a coin above 2^63-1 (an evaluator limit until ADR-056 Phase 7c; it passes now). */
    static final String MAXBOUND_WORD64 =
            "conway/pass-enact-withdrawals-exceeding-maxbound-word64-submitted-in-a-single-proposal/0";

    /**
     * Vectors that exposed engine bugs fixed in ADR-056 Phase 7b: PlutusV1 over a reference script (Conway's
     * transTxOutV1, Conway/TxInfo.hs:306-335, does not reject one; only Babbage's did).
     */
    static final Set<String> FIXED = Set.of("conway/pass-utxos-can-use-reference-scripts/0",
            "conway/pass-utxos-can-use-regular-inputs-for-reference/0");

    /** The Alonzo vectors whose Imp test removes a cost model with modifyPParams, which the vector records as no event. */
    static final Map<String, String> NO_COST_MODEL = Map.of(
            "alonzo/fail-utxos-no-cost-model/0", "PlutusV1",
            "alonzo/fail-utxos-plutusv2-no-cost-model/0", "PlutusV2",
            "alonzo/fail-utxos-plutusv3-no-cost-model/0", "PlutusV3");

    /** Vectors Amaru passes and the Java engine does not, with the cause. The gate asserts this exact set. */
    static final Map<String, Recorded> RECORDED = recorded();

    private static Map<String, Recorded> recorded() {
        Map<String, Recorded> m = new LinkedHashMap<>();
        m.put("conway/fail-certs-withdrawing-the-wrong-amount/0", new Recorded("harness: requires the skipped epoch boundary, not an engine bug",
                "tx 1 submits a proposal (deposit 123, return account e03c875c…); the four skipped "
                        + "PassEpoch events expire it and refund the deposit to that account (Conway/Rules/Epoch.hs:179-190, "
                        + "returnProposalDeposits), so Haskell rejects tx 7's zero withdrawal as incomplete. Amaru passes "
                        + "the vector for another reason, most likely because its harness keeps the partial effects of a "
                        + "failing transaction (its failures file notes this for fail-utxos-failing-native-script-govpolicy): "
                        + "tx 6, which fails, spends the same input as tx 7."));
        return m;
    }

    /**
     * Pinned per Imp spec and protocol version: vectors, transactions, passed, failed, requires epoch,
     * undecodable or unsupported.
     */
    static final Map<String, List<Integer>> PINNED = Map.of(
            "shelley PV 9", List.of(11, 17, 11, 0, 0, 0),
            "allegra PV 9", List.of(1, 1, 1, 0, 0, 0),
            "mary PV 9", List.of(2, 3, 2, 0, 0, 0),
            "alonzo PV 9", List.of(98, 299, 98, 0, 0, 0),
            "babbage PV 9", List.of(3, 10, 3, 0, 0, 0),
            "conway PV 10", List.of(205, 2157, 195, 0, 10, 0));

    /** Transactions whose verdict matches Haskell's, over all vectors. */
    static final int TRANSACTIONS_MATCHED = 2472;

    /**
     * For every vector that does not pass, the indexes of all its transactions whose verdict differs from Haskell's
     * (the first is the one the status comes from). Pinned so a new disagreement after a skipped boundary, inside a
     * vector that is already {@code requires epoch}, cannot go unnoticed.
     */
    static final Map<String, List<Integer>> MISMATCHES = Map.ofEntries(
            Map.entry("conway/fail-certs-withdrawing-the-wrong-amount/0", List.of(7)),
            Map.entry("conway/fail-deleg-with-non-zero-reward-balance/0", List.of(2)),
            Map.entry("conway/fail-gov-empty-prevgovid-after-the-first-constitution-was-enacted/0", List.of(10)),
            Map.entry("conway/fail-gov-policy-is-respected-by-proposals/0", List.of(10, 13)),
            Map.entry("conway/pass-deleg-delegate-retire-and-re-register-pool/0", List.of(6)),
            Map.entry("conway/pass-deleg-deregistering-returns-the-deposit/0", List.of(6)),
            Map.entry("conway/pass-enact-cc-re-election/0", List.of(25)),
            Map.entry("conway/pass-utxos-alwayssucceeds-plutus-govpolicy-validates/0", List.of(12, 15)),
            Map.entry("conway/pass-utxos-updating-costmodels-and-setting-the-govpolicy-afterwards-succeeds/0",
                    List.of(12, 22, 25)),
            Map.entry("conway/pass-utxos-updating-costmodels-with-alwaysfails-govpolicy-does-not-validate/0",
                    List.of(10, 22)));

    /** Vectors whose final state the harness reaches exactly (they stay in one epoch with unchanged parameters). */
    static final int FINAL_STATES_MATCHED = 233;

    @Test
    void theVectorsAreThePinnedSet() {
        BlueprintVectorLoader loader = new BlueprintVectorLoader(BlueprintVectorLoader.requireVectorsDir());
        loader.verifyPin();
        assertThat(loader.vectorFiles()).hasSize(BlueprintVectorLoader.EXPECTED_VECTORS);
        assertThat(loader.pparamsCount()).isEqualTo(BlueprintVectorLoader.EXPECTED_PPARAMS);
    }

    @Test
    void amarusKnownFailuresMatchTheCheckout() {
        Set<String> pinned = AmaruKnownFailures.pinned();
        assertThat(pinned).hasSize(27);
        AmaruKnownFailures.fromAmaruCheckout().ifPresent(checkout -> assertThat(checkout)
                .as("the pinned list equals the Amaru checkout's rules-conformance.failures.toml")
                .containsExactlyElementsOf(pinned));
    }

    @Test
    void everyStateDecodes() {
        BlueprintVectorResults.Run run = run();
        List<String> undecodable = new ArrayList<>();
        run.results().stream().filter(r -> r.status() == Status.UNDECODABLE)
                .forEach(r -> undecodable.add(r.vector().id() + " (initial): " + r.reason()));
        for (int i = 0; i < run.finalStates().size(); i++) {
            NewEpochStateDecoder.Decoded fin = run.finalStates().get(i);
            if (!fin.ok()) {
                undecodable.add(run.results().get(i).vector().id() + " (final): " + fin.unsupported());
            }
        }
        assertThat(undecodable).as("states the decoder cannot read").isEmpty();
    }

    @Test
    void javaPassesEveryVectorAmaruPasses() {
        BlueprintVectorResults.Run run = run();
        Set<String> amaruFailures = AmaruKnownFailures.pinned();
        Map<String, String> exceptions = new TreeMap<>();
        for (VectorResult r : run.results()) {
            if (!amaruFailures.contains(r.vector().id()) && r.status() != Status.PASSED) {
                exceptions.put(r.vector().id(), r.status() + ": " + r.reason());
            }
        }
        exceptions.forEach((id, why) -> System.out.println("  Amaru passes, java does not: " + id + " — " + why));
        assertThat(exceptions.keySet()).as("vectors Amaru passes that the Java engine does not (record each in RECORDED "
                + "with its cause, or fix the engine)").containsExactlyInAnyOrderElementsOf(RECORDED.keySet());
        List<String> improvements = run.results().stream()
                .filter(r -> amaruFailures.contains(r.vector().id()) && r.status() == Status.PASSED)
                .map(r -> r.vector().id()).toList();
        System.out.printf("Blueprint vectors vs Amaru: Amaru passes %d, java passes %d; java passes %d of Amaru's %d "
                        + "known failures; %d recorded exceptions%n", run.results().size() - amaruFailures.size(),
                run.results().stream().filter(r -> r.status() == Status.PASSED).count(), improvements.size(),
                amaruFailures.size(), RECORDED.size());
    }

    @Test
    void talliesArePinned() {
        BlueprintVectorResults.Run run = run();
        Map<String, List<Integer>> tallies = new TreeMap<>();
        BlueprintReport.byGroup(run.results()).forEach((key, results) -> {
            int[] c = BlueprintReport.counts(results);
            tallies.put(key, List.of(c[0], c[1], c[2], c[3], c[4], c[5]));
            System.out.printf("Blueprint vectors, %s: %d vectors, %d transactions, %d passed, %d failed, %d require "
                    + "epoch, %d undecodable%n", key, c[0], c[1], c[2], c[3], c[4], c[5]);
        });
        assertThat(tallies).as("tallies per Imp spec and protocol version").isEqualTo(new TreeMap<>(PINNED));
    }

    @Test
    void transactionLevelAgreementIsPinned() {
        BlueprintVectorResults.Run run = run();
        long matched = run.results().stream().flatMap(r -> r.transactions().stream())
                .filter(BlueprintVectorRunner.TxResult::matches).count();
        Map<String, List<Integer>> mismatches = new TreeMap<>();
        for (VectorResult r : run.results()) {
            List<Integer> indexes = r.transactions().stream().filter(t -> !t.matches())
                    .map(BlueprintVectorRunner.TxResult::index).toList();
            if (!indexes.isEmpty() || r.status() != Status.PASSED) {
                mismatches.put(r.vector().id(), indexes);
            }
        }
        mismatches.forEach((id, indexes) -> System.out.println("  mismatching transactions: " + id + " " + indexes));
        assertThat(mismatches).as("mismatching transactions per vector").isEqualTo(new TreeMap<>(MISMATCHES));
        assertThat(matched).as("transactions whose verdict matches Haskell's").isEqualTo(TRANSACTIONS_MATCHED);
    }

    @Test
    void finalStatesMatchWhereTheVectorStaysInOneEpoch() {
        BlueprintVectorResults.Run run = run();
        List<String> differs = run.results().stream()
                .filter(r -> r.stateCheck().outcome() == StateCheck.Outcome.DIFFERS
                        || r.stateCheck().outcome() == StateCheck.Outcome.FINAL_UNDECODABLE)
                .map(r -> r.vector().id() + ": " + r.stateCheck().differences()).toList();
        assertThat(differs).as("final states the harness does not reach").isEmpty();
        assertThat(run.results().stream().filter(r -> r.stateCheck().outcome() == StateCheck.Outcome.MATCHED).count())
                .isEqualTo(FINAL_STATES_MATCHED);
    }

    @Test
    void theFixedEngineFindingsPass() {
        BlueprintVectorResults.Run run = run();
        for (VectorResult r : run.results()) {
            if (FIXED.contains(r.vector().id())) {
                assertThat(r.status()).as(r.vector().id()).isEqualTo(Status.PASSED);
            }
            if (r.vector().id().equals(MAXBOUND_WORD64)) {
                // ADR-056 Phase 7c: tx 9's treasury withdrawal above 2^63-1 (a Word64 Coin in Haskell) reaches the
                // guardrail script's context exactly (julc reads integers as BigIntegers; java-scalus through the Scalus
                // bridge's WideIntegers, BlueprintVectorScalusTest), so the vector passes, as in
                // Haskell; before, the evaluator failed closed with ENGINE.CoinOutOfEvaluatorRange.
                assertThat(r.status()).as(r.vector().id()).isEqualTo(Status.PASSED);
                assertThat(r.transactions()).allMatch(BlueprintVectorRunner.TxResult::matches);
            }
        }
    }

    /**
     * The no-cost-model vectors at the parameters their failing transaction met in Haskell: the vector's final state,
     * after the Imp test removed the cost models with {@code modifyPParams} (which the vector records as no event).
     * The engine reports Haskell's {@code UTXOS.CollectErrors [NoCostModel]} and nothing else, so the script integrity
     * hash matches too: the harness's {@code PPViewHashesDontMatch} comes only from validating against the unmodified
     * initial parameters. The final parameters hold no cost model at all (an empty raw map), which the engine reads as
     * "no cost model for any language" ({@code ScriptIntegrity.costModel}, {@code LedgerView#protocolParams}).
     */
    @Test
    void noCostModelVectorsMatchHaskellAtTheirOwnParameters() {
        BlueprintVectorLoader loader = new BlueprintVectorLoader(BlueprintVectorLoader.requireVectorsDir());
        BlueprintVectorRunner runner = new BlueprintVectorRunner(loader.decoder());
        NO_COST_MODEL.forEach((id, language) -> {
            BlueprintVector vector = loader.load(loader.root().resolve("eras/" + id));
            BlueprintVector.Event.Tx failing = vector.transactions().getLast();
            assertThat(failing.success()).isFalse();
            NewEpochStateDecoder.Decoded fin = loader.decoder().decode(vector.finalState());
            assertThat(fin.params().getCostModelsRaw()).isEmpty();

            Observation atFinal = runner.validate(vector, failing, fin.view());
            assertThat(atFinal.failures()).as(id).singleElement().satisfies(f -> {
                assertThat(f.qualifiedName()).isEqualTo("UTXOS.CollectErrors");
                assertThat(f.raw()).isEqualTo("NoCostModel " + language);
            });

            // The same with only the script's language removed from the initial cost models.
            ProtocolParams withoutLanguage = loader.decoder().decode(vector.initialState()).params();
            LinkedHashMap<String, List<Long>> costModels = new LinkedHashMap<>(withoutLanguage.getCostModelsRaw());
            costModels.remove(language);
            withoutLanguage.setCostModelsRaw(costModels);
            Observation withoutIt = runner.validate(vector, failing, new WithParameters(fin.view(), withoutLanguage));
            assertThat(withoutIt.failures()).as(id).singleElement().satisfies(f ->
                    assertThat(f.raw()).isEqualTo("NoCostModel " + language));
        });
    }

    @Test
    void writesTheReport() {
        BlueprintVectorResults.Run run = run();
        String report = new BlueprintReport(run, AmaruKnownFailures.pinned(), RECORDED).toMarkdown();
        Path written = ConformanceSettings.write("blueprint-vectors.md", "conformance.blueprint.file", report);
        System.out.println("Blueprint vector report: " + written);
        assertThat(report).contains("## Summary", "## Comparison with Amaru", "## Final-state check");
    }

    private static BlueprintVectorResults.Run run() {
        BlueprintVectorResults.Run run = BlueprintVectorResults.get().orElse(null);
        Assumptions.assumeTrue(run != null, "cardano-blueprint vectors not configured (-PblueprintVectors)");
        return run;
    }
}
