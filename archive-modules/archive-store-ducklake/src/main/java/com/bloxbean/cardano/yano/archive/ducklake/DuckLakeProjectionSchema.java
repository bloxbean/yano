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

    private DuckLakeProjectionSchema() {}

    static void initialize(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake.projection_identity ("
                    + "fingerprint VARCHAR NOT NULL, installed_at TIMESTAMP NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake." + RECEIPTS_TABLE + " ("
                    + "first_block BIGINT NOT NULL, last_block BIGINT NOT NULL, block_count BIGINT NOT NULL, "
                    + "identity_fingerprint VARCHAR NOT NULL, first_envelope_id VARCHAR NOT NULL, "
                    + "last_envelope_id VARCHAR NOT NULL, ordered_digest VARCHAR NOT NULL, "
                    + "row_counts VARCHAR NOT NULL, committed_at TIMESTAMP NOT NULL)");
        }
    }
}
