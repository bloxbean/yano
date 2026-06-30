package com.bloxbean.cardano.yano.runtime.tx.diffusion;

import com.bloxbean.cardano.yano.runtime.chain.MemPool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class PeerTxState {
    private static final int MAX_TRACKED_TX_IDS = 4_096;
    private static final int REJECTIONS_BEFORE_COOLDOWN = 3;

    private final String peerId;
    private PeerClass peerClass;
    private final LinkedHashMap<String, Boolean> announcedToPeer = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> requestedFromPeer = new LinkedHashMap<>();
    private int rejectedCount;
    private long cooldownUntilMillis;
    private long inFlightBytes;

    PeerTxState(String peerId, PeerClass peerClass) {
        this.peerId = requireText(peerId, "peerId");
        this.peerClass = peerClass != null ? peerClass : PeerClass.DOWNSTREAM;
    }

    synchronized void updatePeerClass(PeerClass peerClass) {
        if (peerClass != null) {
            this.peerClass = peerClass;
        }
    }

    synchronized boolean reserveLocalSubmitForward(String txHash) {
        String normalized = TxIdAndSize.normalizeHash(txHash);
        if (announcedToPeer.containsKey(normalized)) {
            return false;
        }
        putBounded(announcedToPeer, normalized, Boolean.TRUE);
        return true;
    }

    synchronized TxRequestPlan planRequests(TxDiffusionMode mode,
                                            List<TxIdAndSize> announced,
                                            MemPool memPool,
                                            int maxInFlightTxs,
                                            long maxInFlightBytes,
                                            long nowMillis) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(memPool, "memPool");
        if (announced == null || announced.isEmpty()) {
            return TxRequestPlan.empty();
        }
        if (!mode.networkIngressAllowed(peerClass) || nowMillis < cooldownUntilMillis) {
            return new TxRequestPlan(List.of(), 0, announced.size(), 0);
        }

        List<TxIdAndSize> requested = new ArrayList<>();
        int ignored = 0;
        int rejected = 0;
        long selectedBytes = 0;
        int availableInFlight = Math.max(0, maxInFlightTxs - requestedFromPeer.size());
        for (TxIdAndSize tx : announced) {
            if (requested.size() >= availableInFlight) {
                rejected++;
                continue;
            }
            String hash = tx.txHash();
            if (memPool.contains(hash) || requestedFromPeer.containsKey(hash)) {
                ignored++;
                continue;
            }
            int size = Math.max(1, tx.size());
            if (maxInFlightBytes > 0 && inFlightBytes + selectedBytes + size > maxInFlightBytes) {
                rejected++;
                continue;
            }
            requested.add(tx);
            selectedBytes += size;
        }

        for (TxIdAndSize tx : requested) {
            addRequested(tx.txHash(), Math.max(1, tx.size()));
        }
        return new TxRequestPlan(requested, ignored, rejected, selectedBytes);
    }

    synchronized boolean takeRequestedFromPeer(String txHash) {
        Integer size = requestedFromPeer.remove(TxIdAndSize.normalizeHash(txHash));
        if (size == null) {
            return false;
        }
        inFlightBytes = Math.max(0L, inFlightBytes - size);
        return true;
    }

    synchronized void recordRejection(long cooldownMillis, long nowMillis) {
        rejectedCount++;
        if (rejectedCount >= REJECTIONS_BEFORE_COOLDOWN && cooldownMillis > 0) {
            cooldownUntilMillis = Math.max(cooldownUntilMillis, nowMillis + cooldownMillis);
        }
    }

    synchronized int inFlightTxs() {
        return requestedFromPeer.size();
    }

    synchronized long inFlightBytes() {
        return inFlightBytes;
    }

    String peerId() {
        return peerId;
    }

    private static <T> void putBounded(LinkedHashMap<String, T> map, String key, T value) {
        map.put(key, value);
        while (map.size() > MAX_TRACKED_TX_IDS) {
            var it = map.entrySet().iterator();
            if (!it.hasNext()) {
                return;
            }
            it.next();
            it.remove();
        }
    }

    private void addRequested(String txHash, int size) {
        requestedFromPeer.put(txHash, size);
        inFlightBytes += size;
        while (requestedFromPeer.size() > MAX_TRACKED_TX_IDS) {
            var it = requestedFromPeer.entrySet().iterator();
            if (!it.hasNext()) {
                return;
            }
            Map.Entry<String, Integer> eldest = it.next();
            it.remove();
            inFlightBytes = Math.max(0L, inFlightBytes - eldest.getValue());
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
