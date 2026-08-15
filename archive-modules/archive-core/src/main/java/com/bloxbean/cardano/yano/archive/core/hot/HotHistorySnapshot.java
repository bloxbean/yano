package com.bloxbean.cardano.yano.archive.core.hot;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.List;

/** Request-pinned, backend-neutral view of hot history. */
public interface HotHistorySnapshot extends AutoCloseable {
    List<Entry> scan(ArchiveDatasetId dataset, byte[] logicalPrefix);

    @Override
    void close();

    record Entry(byte[] logicalKey, byte[] value) {
        public Entry {
            logicalKey = logicalKey.clone();
            value = value.clone();
        }

        @Override public byte[] logicalKey() { return logicalKey.clone(); }
        @Override public byte[] value() { return value.clone(); }
    }
}
