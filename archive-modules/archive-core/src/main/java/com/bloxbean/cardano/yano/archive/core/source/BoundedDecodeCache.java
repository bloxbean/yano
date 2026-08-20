package com.bloxbean.cardano.yano.archive.core.source;

import java.util.HashMap;
import java.util.Map;

/**
 * Bounded memoisation for one block's address decoding.
 *
 * <p>Three properties matter, and each is a deliberate choice rather than a default:
 *
 * <ul>
 *   <li><strong>Bounded.</strong> Entries stop being admitted once {@code maxEntries} is
 *       reached. A block with pathologically many distinct addresses degrades to the
 *       uncached path instead of growing without limit.</li>
 *   <li><strong>No eviction.</strong> Admission simply stops; nothing is ever evicted. That
 *       removes eviction order as a variable entirely, so the decoded output cannot depend
 *       on which entries a policy happened to keep. A cache that evicted could, in
 *       principle, produce different work orders; this one cannot.</li>
 *   <li><strong>Thread-confined.</strong> One instance belongs to one decode call on one
 *       thread and is discarded with it. It is deliberately not synchronised: sharing an
 *       instance across threads is a programming error, not a supported mode, and one
 *       decoder object may legitimately serve two worker threads with separate caches.</li>
 * </ul>
 *
 * <p>Memoisation cannot change results: it returns the value the same pure function would
 * have computed. Counters exist so that claim is observable rather than assumed.
 */
final class BoundedDecodeCache<V> {

    /** Distinct addresses per block are normally in the low hundreds; this is headroom. */
    static final int DEFAULT_MAX_ENTRIES = 4_096;

    private final int maxEntries;
    private final Map<String, V> entries;

    private long hits;
    private long misses;
    private long admissionsSkipped;

    BoundedDecodeCache(int maxEntries) {
        if (maxEntries < 0) throw new IllegalArgumentException("maxEntries must not be negative");
        this.maxEntries = maxEntries;
        this.entries = maxEntries == 0 ? Map.of() : new HashMap<>(Math.min(maxEntries, 256));
    }

    V get(String key) {
        if (maxEntries == 0) {
            misses++;
            return null;
        }
        V value = entries.get(key);
        if (value == null) misses++;
        else hits++;
        return value;
    }

    void put(String key, V value) {
        if (maxEntries == 0) return;
        if (entries.size() >= maxEntries) {
            admissionsSkipped++;
            return;
        }
        entries.put(key, value);
    }

    long hits() { return hits; }

    long misses() { return misses; }

    /** Lookups that could not be admitted because the bound was reached. */
    long admissionsSkipped() { return admissionsSkipped; }

    int size() { return entries.size(); }

    /** A cache with no capacity: every lookup misses. Used to prove caching changes nothing. */
    static <V> BoundedDecodeCache<V> disabled() {
        return new BoundedDecodeCache<>(0);
    }
}
