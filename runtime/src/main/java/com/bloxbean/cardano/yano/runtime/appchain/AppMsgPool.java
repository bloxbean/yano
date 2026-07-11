package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pending verified app messages awaiting sequencing (FIFO by arrival).
 * Dedup against the pool itself is by message id; dedup against finalized
 * history is the caller's job (ledger message index). TTL expiry is swept
 * on drain and on a periodic tick.
 */
final class AppMsgPool {
    private final LinkedHashMap<String, AppMessage> pending = new LinkedHashMap<>();
    private final int maxSize;

    /** Outcome of {@link #add} — callers surface FULL (backpressure) vs DUPLICATE. */
    enum AddResult { ADDED, FULL, DUPLICATE }

    AppMsgPool(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }

    synchronized AddResult add(AppMessage message) {
        if (pending.containsKey(message.getMessageIdHex())) {
            return AddResult.DUPLICATE;
        }
        if (pending.size() >= maxSize) {
            return AddResult.FULL;
        }
        pending.put(message.getMessageIdHex(), message);
        return AddResult.ADDED;
    }

    int capacity() {
        return maxSize;
    }

    synchronized boolean contains(String messageIdHex) {
        return pending.containsKey(messageIdHex);
    }

    /**
     * Per-message serialized-envelope overhead beyond the body (message-id 32B,
     * sender 32B, auth proof 64B, chain-id, topic, seqs, CBOR framing). Used to
     * ESTIMATE the serialized block size so selection tracks the wire size, not
     * just summed bodies. The engine still serializes-and-trims for the exact
     * guarantee (block-bytes fix).
     */
    static final int ENVELOPE_OVERHEAD_BYTES = 220;

    /**
     * Oldest-first snapshot of up to {@code maxMessages} messages whose estimated
     * serialized size (body + envelope overhead) stays within {@code maxBytes},
     * skipping expired.
     */
    synchronized List<AppMessage> drainCandidates(int maxMessages, long maxBytes) {
        sweepExpired();
        List<AppMessage> selected = new ArrayList<>();
        long bytes = 0;
        for (AppMessage message : pending.values()) {
            if (selected.size() >= maxMessages) {
                break;
            }
            long cost = message.getSize() + ENVELOPE_OVERHEAD_BYTES;
            if (bytes + cost > maxBytes && !selected.isEmpty()) {
                break;
            }
            selected.add(message);
            bytes += cost;
        }
        return selected;
    }

    /** Remove finalized messages (called after a block commits). */
    synchronized void remove(List<AppMessage> messages) {
        for (AppMessage message : messages) {
            pending.remove(message.getMessageIdHex());
        }
    }

    synchronized void sweepExpired() {
        long now = System.currentTimeMillis() / 1000;
        pending.values().removeIf(m -> m.isExpired(now));
    }

    /** Drop everything pending (admin drain, ADR 006 E5.4). @return dropped count */
    synchronized int clear() {
        int dropped = pending.size();
        pending.clear();
        return dropped;
    }

    synchronized int size() {
        return pending.size();
    }
}
