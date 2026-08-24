package com.bloxbean.cardano.yano.runtime.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class LegacyAccountHistoryRemovalTest {
    @TempDir
    Path tempDir;

    @Test
    void newChainStateDoesNotCreateLegacyHistoryColumnFamilies() throws Exception {
        Path database = tempDir.resolve("chainstate");
        try (DirectRocksDBChainState ignored = new DirectRocksDBChainState(database.toString())) {
            // Opening a new chain state materializes the supported core column families.
        }

        List<String> names;
        try (Options options = new Options()) {
            names = RocksDB.listColumnFamilies(options, database.toString()).stream()
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .toList();
        }

        assertFalse(names.contains("account_history"));
        assertFalse(names.contains("account_history_delta"));
    }
}
