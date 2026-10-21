package org.yanoproject.ledger.conformance.gate;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.blueprint.BlueprintReport;
import org.yanoproject.ledger.conformance.blueprint.BlueprintVectorResults;
import org.yanoproject.ledger.conformance.blueprint.BlueprintVectorRunner;
import org.yanoproject.ledger.conformance.blueprint.BlueprintVectorRunner.TxResult;
import org.yanoproject.ledger.conformance.blueprint.BlueprintVectorRunner.VectorResult;
import org.yanoproject.ledger.conformance.engines.JavaViewEngine;
import org.yanoproject.ledger.rules.fixtures.blueprint.BlueprintVector;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 7c: the cardano-blueprint vectors with engine {@code java-scalus} against the run of
 * {@link BlueprintVectorGateTest} (engine {@code java-julc}). The rules are the same, so every transaction must get the
 * same verdict and the same first failure; a difference is a phase-2 difference, to be explained against Haskell.
 */
class BlueprintVectorScalusTest {

    @Test
    void scalusGivesTheJulcVerdictOnEveryVectorTransaction() {
        BlueprintVectorResults.Run julc = BlueprintVectorResults.get().orElse(null);
        Assumptions.assumeTrue(julc != null, "cardano-blueprint vectors not configured (-PblueprintVectors)");
        BlueprintVectorRunner scalus = new BlueprintVectorRunner(julc.loader().decoder(),
                JavaViewEngine.createScalus(new ScalusScriptPhaseEvaluator()));

        List<VectorResult> results = new ArrayList<>();
        List<String> differences = new ArrayList<>();
        for (VectorResult reference : julc.results()) {
            BlueprintVector vector = reference.vector();
            VectorResult result = scalus.run(vector);
            results.add(result);
            if (result.status() != reference.status()) {
                differences.add(vector.id() + ": status " + result.status() + ", julc " + reference.status());
            }
            for (int i = 0; i < Math.min(result.transactions().size(), reference.transactions().size()); i++) {
                TxResult tx = result.transactions().get(i);
                TxResult ref = reference.transactions().get(i);
                if (!outcome(tx).equals(outcome(ref))) {
                    differences.add(vector.id() + " tx " + tx.index() + ": Scalus " + outcome(tx) + ", julc "
                            + outcome(ref));
                }
            }
        }
        Map<String, List<Integer>> tallies = new TreeMap<>();
        BlueprintReport.byGroup(results).forEach((key, group) -> {
            int[] c = BlueprintReport.counts(group);
            tallies.put(key, List.of(c[0], c[1], c[2], c[3], c[4], c[5]));
            System.out.printf("Blueprint vectors (java-scalus), %s: %d vectors, %d transactions, %d passed, %d failed, "
                    + "%d require epoch, %d undecodable%n", key, c[0], c[1], c[2], c[3], c[4], c[5]);
        });
        differences.forEach(d -> System.out.println("  Scalus vs julc: " + d));

        assertThat(differences).as("vector transactions where Scalus and julc phase 2 differ").isEmpty();
        assertThat(tallies).isEqualTo(new TreeMap<>(BlueprintVectorGateTest.PINNED));
    }

    private static String outcome(TxResult tx) {
        return tx.observation().valid() ? "valid" : tx.observation().first().qualifiedName();
    }
}
