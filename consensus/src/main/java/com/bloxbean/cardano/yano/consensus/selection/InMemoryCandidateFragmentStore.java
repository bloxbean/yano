package com.bloxbean.cardano.yano.consensus.selection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral bounded candidate-fragment store.
 */
public final class InMemoryCandidateFragmentStore implements CandidateFragmentStore {
    private static final int DEFAULT_MAX_HEADERS = 16_384;

    private final Map<String, Map<String, CandidateHeader>> byPeer = new ConcurrentHashMap<>();
    private final int maxHeaders;

    public InMemoryCandidateFragmentStore() {
        this(DEFAULT_MAX_HEADERS);
    }

    public InMemoryCandidateFragmentStore(int maxHeaders) {
        if (maxHeaders <= 0) {
            throw new IllegalArgumentException("maxHeaders must be positive");
        }
        this.maxHeaders = maxHeaders;
    }

    @Override
    public void put(CandidateHeader header) {
        byPeer.computeIfAbsent(header.peerId(), ignored -> new ConcurrentHashMap<>())
                .put(header.blockHash(), header);
        evictOverflow();
    }

    @Override
    public Optional<CandidateFragment> fragmentEndingAt(String peerId, String blockHash) {
        if (peerId == null || blockHash == null) {
            return Optional.empty();
        }
        Map<String, CandidateHeader> peerHeaders = byPeer.get(peerId);
        if (peerHeaders == null) {
            return Optional.empty();
        }
        CandidateHeader header = peerHeaders.get(blockHash);
        if (header == null) {
            return Optional.empty();
        }

        List<CandidateHeader> reversed = new ArrayList<>();
        Set<String> seenHashes = new HashSet<>();
        while (header != null && seenHashes.add(header.blockHash())) {
            reversed.add(header);
            String previousHash = header.previousHash();
            if (previousHash == null || previousHash.isBlank()) {
                break;
            }
            header = peerHeaders.get(previousHash);
        }
        java.util.Collections.reverse(reversed);
        return Optional.of(new CandidateFragment(peerId, reversed));
    }

    @Override
    public List<CandidateFragment> fragmentsAfter(long blockNumber) {
        return byPeer.entrySet().stream()
                .flatMap(entry -> tipHashes(entry.getValue()).stream()
                        .map(hash -> fragmentEndingAt(entry.getKey(), hash))
                        .flatMap(Optional::stream))
                .filter(fragment -> fragment.tip().blockNumber() > blockNumber)
                .sorted(Comparator
                        .comparingLong((CandidateFragment fragment) -> fragment.tip().blockNumber())
                        .thenComparing(fragment -> fragment.tip().blockHash())
                        .thenComparing(CandidateFragment::peerId))
                .toList();
    }

    @Override
    public void pruneBeforeSlot(long slot) {
        byPeer.values().forEach(headers -> headers.values().removeIf(header -> header.slot() < slot));
        byPeer.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private List<String> tipHashes(Map<String, CandidateHeader> headers) {
        Set<String> referenced = new HashSet<>();
        headers.values().stream()
                .map(CandidateHeader::previousHash)
                .filter(hash -> hash != null && !hash.isBlank())
                .forEach(referenced::add);
        return headers.values().stream()
                .map(CandidateHeader::blockHash)
                .filter(hash -> !referenced.contains(hash))
                .sorted()
                .toList();
    }

    private void evictOverflow() {
        int overflow = size() - maxHeaders;
        if (overflow <= 0) {
            return;
        }
        byPeer.entrySet().stream()
                .flatMap(entry -> entry.getValue().values().stream())
                .sorted(Comparator
                        .comparingLong(CandidateHeader::slot)
                        .thenComparingLong(CandidateHeader::receivedAtMillis)
                        .thenComparing(CandidateHeader::peerId)
                        .thenComparing(CandidateHeader::blockHash))
                .limit(overflow)
                .toList()
                .forEach(header -> {
                    Map<String, CandidateHeader> peerHeaders = byPeer.get(header.peerId());
                    if (peerHeaders != null) {
                        peerHeaders.remove(header.blockHash(), header);
                        if (peerHeaders.isEmpty()) {
                            byPeer.remove(header.peerId(), peerHeaders);
                        }
                    }
                });
    }

    private int size() {
        return byPeer.values().stream().mapToInt(Map::size).sum();
    }
}
