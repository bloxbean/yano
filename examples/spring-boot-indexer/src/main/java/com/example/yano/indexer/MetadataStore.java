package com.example.yano.indexer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * The indexer's own database. Every block is written in one transaction together with
 * its cursor row, so the cursor never claims a block whose data is missing.
 */
@Repository
public class MetadataStore {

    public record Entry(String txHash, long label, String body, long blockNumber, long slot) {
    }

    public record IndexedBlock(long blockNumber, long slot, String blockHash) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public MetadataStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public void saveBlock(IndexedBlock block, List<Entry> entries) {
        tx.executeWithoutResult(status -> {
            // Replacing the block's rows keeps a replayed block idempotent.
            jdbc.update("DELETE FROM tx_metadata WHERE block_number = ?", block.blockNumber());
            jdbc.update("DELETE FROM indexed_block WHERE block_number = ?", block.blockNumber());
            for (Entry e : entries) {
                jdbc.update("INSERT INTO tx_metadata (tx_hash, label, body, block_number, slot) VALUES (?, ?, ?, ?, ?)",
                        e.txHash(), e.label(), e.body(), e.blockNumber(), e.slot());
            }
            jdbc.update("INSERT INTO indexed_block (block_number, slot, block_hash) VALUES (?, ?, ?)",
                    block.blockNumber(), block.slot(), block.blockHash());
        });
    }

    /** Removes everything after {@code slot}; {@code -1} empties the index. */
    public void rollbackToSlot(long slot) {
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM tx_metadata WHERE slot > ?", slot);
            jdbc.update("DELETE FROM indexed_block WHERE slot > ?", slot);
        });
    }

    public Optional<IndexedBlock> lastBlock() {
        return jdbc.query("SELECT block_number, slot, block_hash FROM indexed_block "
                        + "ORDER BY block_number DESC LIMIT 1", MetadataStore::toBlock)
                .stream().findFirst();
    }

    public Optional<IndexedBlock> block(long blockNumber) {
        return jdbc.query("SELECT block_number, slot, block_hash FROM indexed_block WHERE block_number = ?",
                MetadataStore::toBlock, blockNumber).stream().findFirst();
    }

    public List<Entry> latest(int limit) {
        return jdbc.query("SELECT tx_hash, label, body, block_number, slot FROM tx_metadata "
                        + "ORDER BY slot DESC, tx_hash LIMIT ?",
                (rs, i) -> new Entry(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5)),
                limit);
    }

    private static IndexedBlock toBlock(ResultSet rs, int row) throws SQLException {
        return new IndexedBlock(rs.getLong(1), rs.getLong(2), rs.getString(3));
    }
}
