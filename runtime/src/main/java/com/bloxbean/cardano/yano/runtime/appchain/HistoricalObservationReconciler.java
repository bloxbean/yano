package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.l1view.HistoricalObservationPointer;
import com.bloxbean.cardano.yano.api.appchain.l1view.HistoricalObservationResolver;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Threshold-independent recomputation gate for pointer-only historical recovery. */
final class HistoricalObservationReconciler {
    private final List<HistoricalObservationResolver> resolvers;
    private final int threshold;
    private final L1ObservationJournal journal;

    HistoricalObservationReconciler(List<HistoricalObservationResolver> resolvers,
                                    int threshold,
                                    L1ObservationJournal journal) {
        this.resolvers = List.copyOf(resolvers);
        this.threshold = threshold;
        this.journal = Objects.requireNonNull(journal, "journal");
        if (threshold < 1 || threshold > resolvers.size()) {
            throw new IllegalArgumentException("Invalid historical resolver threshold");
        }
    }

    L1Observation reconcile(HistoricalObservationPointer pointer) {
        Objects.requireNonNull(pointer, "pointer");
        if (!pointer.operatorConfirmed()) {
            throw new IllegalArgumentException("Explicit operator confirmation is required");
        }
        Set<String> resolverIds = new HashSet<>();
        L1Observation agreed = null;
        int matching = 0;
        for (HistoricalObservationResolver resolver : resolvers) {
            if (!resolverIds.add(Objects.requireNonNull(resolver.resolverId(), "resolverId"))) {
                throw new IllegalStateException("Duplicate historical resolver identity");
            }
            L1Observation candidate = resolver.resolve(pointer).orElse(null);
            if (candidate == null) continue;
            validatePointer(pointer, candidate);
            if (agreed == null) {
                agreed = candidate;
            } else if (!Arrays.equals(agreed.encode(), candidate.encode())) {
                throw new IllegalStateException("HISTORICAL_OBSERVATION_DISAGREEMENT");
            }
            matching++;
        }
        if (agreed == null || matching < threshold) {
            throw new IllegalStateException("HISTORICAL_OBSERVATION_QUORUM_UNAVAILABLE");
        }
        journal.observe(List.of(agreed));
        return agreed;
    }

    private static void validatePointer(HistoricalObservationPointer pointer,
                                        L1Observation observation) {
        if (!pointer.observerId().equals(observation.observerId())
                || !pointer.anchor().equals(observation.anchor())
                || pointer.eventOrdinal() != observation.eventOrdinal()
                || pointer.slot().filter(value -> value != observation.slot()).isPresent()
                || pointer.blockHash().filter(value ->
                !Arrays.equals(value, observation.blockHash())).isPresent()) {
            throw new IllegalStateException("Historical resolver returned another source event");
        }
    }
}
