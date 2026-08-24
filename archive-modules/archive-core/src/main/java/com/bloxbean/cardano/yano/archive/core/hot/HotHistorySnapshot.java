package com.bloxbean.cardano.yano.archive.core.hot;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveRecord;

import java.util.List;
import java.util.Map;

/** Request-pinned, backend-neutral view of hot history. */
public interface HotHistorySnapshot extends AutoCloseable {
    default List<Entry> scanTable(ArchiveDatasetId dataset, String table) {
        return queryTable(dataset, table, Map.of(), null, null);
    }

    List<Entry> queryTable(ArchiveDatasetId dataset, String table, Map<String, Object> filters,
                           Long blockFromInclusive, Long blockToInclusive);

    @Override
    void close();

    record Entry(byte[] logicalKey, ArchiveRecord row) {
        public Entry {
            logicalKey = logicalKey.clone();
            if (row == null) throw new IllegalArgumentException("row is required");
        }

        @Override public byte[] logicalKey() { return logicalKey.clone(); }
    }
}
