package org.yanoproject.ledger.conformance.blueprint;

import org.yanoproject.ledger.rules.fixtures.blueprint.BlueprintVector;
import org.yanoproject.ledger.rules.fixtures.blueprint.BlueprintVectorLoader;
import org.yanoproject.ledger.rules.fixtures.blueprint.NewEpochStateDecoder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The Java engine's results over the pinned cardano-blueprint vectors, computed once per test JVM and shared by the
 * gate ({@code BlueprintVectorGateTest}) and the coverage matrix.
 */
public final class BlueprintVectorResults {

    /**
     * @param loader        the loader (its root is the vectors directory)
     * @param results       the runner's result per vector, by vector id
     * @param finalStates   the decoded final state of every vector (decoder coverage)
     */
    public record Run(BlueprintVectorLoader loader, List<BlueprintVectorRunner.VectorResult> results,
                      List<NewEpochStateDecoder.Decoded> finalStates) {

        /**
         * Verdict-level evidence for the coverage matrix: per constructor, per protocol version, the number of vector
         * transactions Haskell rejected that the Java engine rejects with that constructor first, before any skipped
         * epoch boundary, in vectors whose parameters did not change outside the events. The vectors record only that
         * Haskell rejected the transaction, not the constructor.
         */
        public Map<String, Map<Integer, Integer>> rejectionEvidence() {
            Map<String, Map<Integer, Integer>> evidence = new TreeMap<>();
            for (BlueprintVectorRunner.VectorResult result : results) {
                if (result.stateCheck().paramsChangedOutsideEvents()) {
                    continue;
                }
                for (BlueprintVectorRunner.TxResult tx : result.transactions()) {
                    if (!tx.expected() && !tx.observation().valid() && !tx.afterBoundary()) {
                        evidence.computeIfAbsent(tx.observation().first().qualifiedName(), k -> new TreeMap<>())
                                .merge(result.protocolMajor(), 1, Integer::sum);
                    }
                }
            }
            return evidence;
        }
    }

    private static Run run;

    private BlueprintVectorResults() {
    }

    /** @return the run, or empty when the vectors are not configured */
    public static synchronized Optional<Run> get() {
        if (run != null) {
            return Optional.of(run);
        }
        Optional<Path> dir = BlueprintVectorLoader.locateVectorsDir();
        if (dir.isEmpty()) {
            return Optional.empty();
        }
        BlueprintVectorLoader loader = new BlueprintVectorLoader(dir.get());
        List<BlueprintVector> vectors = loader.loadAll();
        NewEpochStateDecoder decoder = loader.decoder();
        BlueprintVectorRunner runner = new BlueprintVectorRunner(decoder);
        List<BlueprintVectorRunner.VectorResult> results = vectors.stream().map(runner::run).toList();
        List<NewEpochStateDecoder.Decoded> finals = vectors.stream().map(v -> decoder.decode(v.finalState())).toList();
        run = new Run(loader, results, finals);
        return Optional.of(run);
    }
}
