package com.bloxbean.cardano.yano.consensus.selection;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral candidate header store used by tests and early multi-peer observation.
 */
public final class InMemoryCandidateHeaderStore implements CandidateHeaderStore {
    private static final int DEFAULT_MAX_OBSERVATIONS = 16_384;

    private final Map<ObservationKey, CandidateHeader> observations = new ConcurrentHashMap<>();
    private final int maxObservations;

    public InMemoryCandidateHeaderStore() {
        this(DEFAULT_MAX_OBSERVATIONS);
    }

    public InMemoryCandidateHeaderStore(int maxObservations) {
        if (maxObservations <= 0) {
            throw new IllegalArgumentException("maxObservations must be positive");
        }
        this.maxObservations = maxObservations;
    }

    @Override
    public void put(CandidateHeader header) {
        observations.put(new ObservationKey(header.peerId(), header.blockHash()), header);
        evictOverflow();
    }

    @Override
    public Optional<CandidateHeader> get(String blockHash) {
        return observations.values().stream()
                .filter(header -> header.blockHash().equals(blockHash))
                .max(Comparator
                        .comparingLong(CandidateHeader::receivedAtMillis)
                        .thenComparing(CandidateHeader::peerId));
    }

    @Override
    public List<CandidateHeader> all() {
        return observations.values().stream()
                .sorted(Comparator
                        .comparingLong(CandidateHeader::blockNumber)
                        .thenComparing(CandidateHeader::blockHash)
                        .thenComparing(CandidateHeader::peerId))
                .toList();
    }

    @Override
    public void pruneBeforeSlot(long slot) {
        observations.values().removeIf(header -> header.slot() < slot);
    }

    private void evictOverflow() {
        int overflow = observations.size() - maxObservations;
        if (overflow <= 0) {
            return;
        }

        observations.entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<ObservationKey, CandidateHeader>>comparingLong(entry -> entry.getValue().slot())
                        .thenComparingLong(entry -> entry.getValue().receivedAtMillis())
                        .thenComparing(entry -> entry.getKey().peerId())
                        .thenComparing(entry -> entry.getKey().blockHash()))
                .limit(overflow)
                .forEach(entry -> observations.remove(entry.getKey(), entry.getValue()));
    }

    private record ObservationKey(String peerId, String blockHash) {
    }
}
