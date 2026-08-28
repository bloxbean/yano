package com.bloxbean.cardano.yano.runtime.chain;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyByronProjectionCfMigrationTest {
    static { RocksDB.loadLibrary(); }

    @TempDir Path directory;

    @Test
    void existingLegacyResolverIsDroppedBeforeChainStateIsPublished() throws Exception {
        Path path = directory.resolve("legacy");
        try (DBOptions options = new DBOptions().setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)) {
            List<ColumnFamilyDescriptor> descriptors = List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
                    new ColumnFamilyDescriptor("proj_byron_utxo".getBytes(StandardCharsets.UTF_8)));
            List<ColumnFamilyHandle> handles = new ArrayList<>();
            try (RocksDB db = RocksDB.open(options, path.toString(), descriptors, handles)) {
                db.put(handles.get(1), new byte[]{1}, new byte[]{2});
            } finally {
                handles.forEach(ColumnFamilyHandle::close);
            }
        }

        try (DirectRocksDBChainState chain = new DirectRocksDBChainState(path.toString())) {
            assertThat(chain.getColumnFamilyHandle("proj_byron_utxo")).isNull();
        }

        try (Options options = new Options()) {
            assertThat(RocksDB.listColumnFamilies(options, path.toString()).stream()
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8)))
                    .doesNotContain("proj_byron_utxo");
        }
    }

    @Test
    void freshDatabaseNeverCreatesLegacyResolver() throws Exception {
        Path path = directory.resolve("fresh");
        try (DirectRocksDBChainState ignored = new DirectRocksDBChainState(path.toString())) {
            // opened and closed
        }
        try (Options options = new Options()) {
            assertThat(RocksDB.listColumnFamilies(options, path.toString()).stream()
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8)))
                    .doesNotContain("proj_byron_utxo");
        }
    }

    @Test
    void dropFailureAbortsConstructionAndReleasesEveryNativeHandle() throws Exception {
        Path path = directory.resolve("drop-failure");
        try (DBOptions options = new DBOptions().setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)) {
            List<ColumnFamilyDescriptor> descriptors = List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
                    new ColumnFamilyDescriptor("proj_byron_utxo".getBytes(StandardCharsets.UTF_8)));
            List<ColumnFamilyHandle> handles = new ArrayList<>();
            try (RocksDB ignored = RocksDB.open(options, path.toString(), descriptors, handles)) {
                // legacy family exists
            } finally {
                handles.forEach(ColumnFamilyHandle::close);
            }
        }

        assertThatThrownBy(() -> new DirectRocksDBChainState(path.toString(), (db, handle) -> {
            throw new org.rocksdb.RocksDBException("synthetic legacy drop failure");
        }))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("synthetic legacy drop failure");

        // A failed constructor must not leave the DB locked or publish a half-open access
        // object. A normal retry can acquire it and finish the migration.
        try (DirectRocksDBChainState chain = new DirectRocksDBChainState(path.toString())) {
            assertThat(chain.getColumnFamilyHandle("proj_byron_utxo")).isNull();
        }
    }
}
