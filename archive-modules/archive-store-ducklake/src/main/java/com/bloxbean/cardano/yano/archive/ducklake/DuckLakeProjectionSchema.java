package com.bloxbean.cardano.yano.archive.ducklake;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Tables the ADR-039 projection sink owns, created inside the same DuckLake catalog as
 * the archive data so a receipt and the rows it describes commit in one transaction.
 *
 * <p>A receipt in a separate store could not be atomic with the rows, and the whole
 * exactly-once-effect argument depends on that atomicity: cleanup is authorised by a
 * verified receipt, never by the sink's current maximum block.
 */
final class DuckLakeProjectionSchema {

    /** The sink's own receipt table; written on every commit, so it must be compacted too. */
    static final String RECEIPTS_TABLE = "projection_receipts";

    /**
     * Genesis bootstrap receipt and completion marker, one row at most.
     *
     * <p>Separate from the block receipt log because genesis belongs to no block range, and
     * because block receipts are keyed by first block - a genesis receipt at block 0 would
     * collide with the real block-0 batch.
     */
    static final String GENESIS_TABLE = "projection_genesis";
    static final String ARTIFACT_IDENTITY_TABLE = "projection_artifact_identity";
    static final String ARTIFACT_ENROLLMENT_TABLE = "projection_artifact_enrollment";
    static final String EPOCH_COVERAGE_TABLE = "projection_epoch_coverage";
    static final String EPOCH_GAP_INTERVAL_TABLE = "projection_epoch_gap_interval";

    private DuckLakeProjectionSchema() {}

    static void initialize(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake.projection_identity ("
                    + "fingerprint VARCHAR NOT NULL, installed_at TIMESTAMP NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake." + RECEIPTS_TABLE + " ("
                    + "first_block BIGINT NOT NULL, last_block BIGINT NOT NULL, block_count BIGINT NOT NULL, "
                    + "identity_fingerprint VARCHAR NOT NULL, first_envelope_id VARCHAR NOT NULL, "
                    + "last_envelope_id VARCHAR NOT NULL, ordered_digest VARCHAR NOT NULL, "
                    + "row_counts VARCHAR NOT NULL, committed_at TIMESTAMP NOT NULL, "
                    + "last_slot BIGINT, last_block_hash BLOB)");
            // Preview archives written before exact restart verification do not carry the sink
            // endpoint. Keep the schema readable, but coordinate() will refuse such an archive
            // rather than inventing a hash and weakening the pre-drain canonicality check.
            sql.execute("ALTER TABLE history_lake." + RECEIPTS_TABLE
                    + " ADD COLUMN IF NOT EXISTS last_slot BIGINT");
            sql.execute("ALTER TABLE history_lake." + RECEIPTS_TABLE
                    + " ADD COLUMN IF NOT EXISTS last_block_hash BLOB");
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake." + GENESIS_TABLE + " ("
                    + "identity VARCHAR NOT NULL, row_digest VARCHAR NOT NULL, row_count BIGINT NOT NULL, "
                    + "total_lovelace VARCHAR NOT NULL, committed_at TIMESTAMP NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake." + ARTIFACT_IDENTITY_TABLE + " ("
                    + "wire_form VARCHAR NOT NULL, installed_at TIMESTAMP NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake." + ARTIFACT_ENROLLMENT_TABLE + " ("
                    + "dataset VARCHAR NOT NULL, projected_from_epoch INTEGER, origin VARCHAR NOT NULL, "
                    + "installed_at TIMESTAMP NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake." + EPOCH_COVERAGE_TABLE + " ("
                    + "dataset VARCHAR NOT NULL, semantic_epoch INTEGER NOT NULL, "
                    + "boundary_block_number BIGINT NOT NULL, boundary_slot BIGINT NOT NULL, "
                    + "boundary_hash BLOB NOT NULL, outcome VARCHAR NOT NULL, row_count BIGINT, "
                    + "content_digest VARCHAR, failure_class VARCHAR, failure_detail VARCHAR, "
                    + "recorded_at TIMESTAMP NOT NULL)");
            // Preview archives may contain ADR-044's first coverage shape. Additive columns are
            // safe and keep their COMPLETE rows; no completeness claim is rewritten.
            sql.execute("ALTER TABLE history_lake." + EPOCH_COVERAGE_TABLE
                    + " ADD COLUMN IF NOT EXISTS content_digest VARCHAR");
            sql.execute("ALTER TABLE history_lake." + EPOCH_COVERAGE_TABLE
                    + " ADD COLUMN IF NOT EXISTS failure_detail VARCHAR");
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake." + EPOCH_GAP_INTERVAL_TABLE + " ("
                    + "dataset VARCHAR NOT NULL, from_epoch INTEGER NOT NULL, through_epoch INTEGER NOT NULL, "
                    + "from_slot BIGINT NOT NULL, from_hash BLOB NOT NULL, through_slot BIGINT NOT NULL, "
                    + "through_hash BLOB NOT NULL, is_open BOOLEAN NOT NULL, caused_by_epoch INTEGER NOT NULL, "
                    + "failure_class VARCHAR NOT NULL, recorded_at TIMESTAMP NOT NULL)");
        }
    }
}
