package org.yanoproject.api.utxo.index;

import java.util.List;

/** Committed-only, namespace-scoped reads. Never observes pending batch writes. */
public interface IndexReader {
    byte[] getCommitted(String table, byte[] key);
    Page scanCommitted(String table, byte[] prefix, String cursor, int limit);

    record Entry(byte[] key, byte[] value) {
        public Entry { key = key.clone(); value = value.clone(); }
        @Override public byte[] key() { return key.clone(); }
        @Override public byte[] value() { return value.clone(); }
    }
    record Page(List<Entry> entries, String nextCursor) {
        public Page { entries = List.copyOf(entries); }
    }
}
