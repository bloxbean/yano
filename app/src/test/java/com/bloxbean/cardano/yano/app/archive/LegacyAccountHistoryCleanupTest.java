package com.bloxbean.cardano.yano.app.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyAccountHistoryCleanupTest {
    @TempDir
    Path tempDir;

    @Test
    void dropsOnlyLegacyColumnFamilies() throws Exception {
        RocksDB.loadLibrary();
        Path database = tempDir.resolve("chainstate");
        List<String> names = List.of("default", "blocks", "account_history", "account_history_delta");
        List<ColumnFamilyDescriptor> descriptors = names.stream()
                .map(name -> new ColumnFamilyDescriptor(name.getBytes(StandardCharsets.UTF_8)))
                .toList();
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try (DBOptions options = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);
             RocksDB ignored = RocksDB.open(options, database.toString(), descriptors, handles)) {
            // Opening creates the preview-era database shape used by the cleanup command.
        } finally {
            handles.forEach(ColumnFamilyHandle::close);
            descriptors.forEach(descriptor -> descriptor.getOptions().close());
        }

        assertEquals(2, LegacyAccountHistoryCleanup.cleanup(
                database, "DROP_LEGACY_ACCOUNT_HISTORY"));

        List<String> remaining;
        try (Options options = new Options()) {
            remaining = RocksDB.listColumnFamilies(options, database.toString()).stream()
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .toList();
        }
        assertTrue(remaining.contains("default"));
        assertTrue(remaining.contains("blocks"));
        assertFalse(remaining.contains("account_history"));
        assertFalse(remaining.contains("account_history_delta"));
    }
}
