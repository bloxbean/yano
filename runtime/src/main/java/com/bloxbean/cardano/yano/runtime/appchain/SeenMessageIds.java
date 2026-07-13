package com.bloxbean.cardano.yano.runtime.appchain;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wire-level dedup of recently seen message ids, TTL-bounded (ADR 008.2 §2.9,
 * I2.3). Entries live until the MESSAGE's own expiry — an id can no longer age
 * out of a count-bounded window while the message is still admissible (the
 * re-diffusion loophole of the old 100k set). A hard entry cap remains as a
 * memory backstop only; this layer is best-effort — the ledger message index
 * stays the authoritative dedup for finalized ids.
 */
final class SeenMessageIds {

    private final ConcurrentHashMap<String, Long> expiryByIdHex = new ConcurrentHashMap<>();
    private final int maxEntries;

    SeenMessageIds(int maxEntries) {
        this.maxEntries = Math.max(1_000, maxEntries);
    }

    /**
     * Record an id with its message expiry.
     *
     * @return true if the id was NOT already present (first sighting)
     */
    boolean markSeen(String messageIdHex, long expiresAtSeconds) {
        return expiryByIdHex.putIfAbsent(messageIdHex, expiresAtSeconds) == null;
    }

    /** Drop expired entries; enforce the hard cap (arbitrary eviction, WARN-worthy). */
    int sweep(long nowSeconds) {
        expiryByIdHex.values().removeIf(expiresAt -> expiresAt < nowSeconds);
        int excess = expiryByIdHex.size() - maxEntries;
        int capEvicted = 0;
        if (excess > 0) {
            Iterator<Map.Entry<String, Long>> iterator = expiryByIdHex.entrySet().iterator();
            while (capEvicted < excess && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
                capEvicted++;
            }
        }
        return capEvicted;
    }

    int size() {
        return expiryByIdHex.size();
    }
}
