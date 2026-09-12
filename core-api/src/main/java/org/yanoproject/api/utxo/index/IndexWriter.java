package org.yanoproject.api.utxo.index;

/** Callback/thread-scoped staging. The host alone owns commit, abort and savepoints. */
public interface IndexWriter extends IndexReader {
    void put(String table, byte[] key, byte[] value);
    void delete(String table, byte[] key);
    void deleteRange(String table, byte[] fromInclusive, byte[] toExclusive);
}
