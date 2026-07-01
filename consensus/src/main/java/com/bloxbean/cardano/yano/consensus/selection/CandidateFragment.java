package com.bloxbean.cardano.yano.consensus.selection;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Continuous per-peer candidate fragment ordered from oldest candidate to tip.
 */
public record CandidateFragment(String peerId, List<CandidateHeader> headers) {
    public CandidateFragment {
        Objects.requireNonNull(peerId, "peerId");
        if (peerId.isBlank()) {
            throw new IllegalArgumentException("peerId must not be blank");
        }
        headers = headers != null ? List.copyOf(headers) : List.of();
        if (headers.isEmpty()) {
            throw new IllegalArgumentException("headers must not be empty");
        }
        for (CandidateHeader header : headers) {
            if (!peerId.equals(header.peerId())) {
                throw new IllegalArgumentException("all fragment headers must belong to the same peer");
            }
        }
        for (int i = 1; i < headers.size(); i++) {
            CandidateHeader previous = headers.get(i - 1);
            CandidateHeader current = headers.get(i);
            if (!previous.blockHash().equals(current.previousHash())) {
                throw new IllegalArgumentException("fragment headers must be previous-hash continuous");
            }
        }
    }

    public CandidateHeader tip() {
        return headers.getLast();
    }

    public CandidateHeader first() {
        return headers.getFirst();
    }

    public long blocksInWindow(long fromSlotInclusive) {
        return headers.stream()
                .filter(header -> header.slot() >= fromSlotInclusive)
                .count();
    }

    public Optional<CanonicalHeaderPoint> findIntersection(CanonicalChainView canonical,
                                                           long currentCanonicalSlot,
                                                           long rollbackWindowSlots) {
        Objects.requireNonNull(canonical, "canonical");
        String previousHash = first().previousHash();
        if (previousHash == null || previousHash.isBlank()) {
            return Optional.empty();
        }
        return canonical.findByHash(previousHash)
                .filter(point -> withinRollbackWindow(point, currentCanonicalSlot, rollbackWindowSlots));
    }

    public boolean intersectsWithinRollbackWindow(CanonicalChainView canonical,
                                                  long currentCanonicalSlot,
                                                  long rollbackWindowSlots) {
        return findIntersection(canonical, currentCanonicalSlot, rollbackWindowSlots).isPresent();
    }

    private static boolean withinRollbackWindow(CanonicalHeaderPoint point,
                                                long currentCanonicalSlot,
                                                long rollbackWindowSlots) {
        if (point.slot() >= currentCanonicalSlot) {
            return true;
        }
        return currentCanonicalSlot - point.slot() <= Math.max(0L, rollbackWindowSlots);
    }
}
