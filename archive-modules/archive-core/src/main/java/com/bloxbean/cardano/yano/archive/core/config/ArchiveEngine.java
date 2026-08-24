package com.bloxbean.cardano.yano.archive.core.config;

/**
 * Storage engines an archive can be opened with.
 *
 * <p>Only DuckLake ships. SQLITE was removed with its module: the projection writes through a
 * ProjectionSinkProvider and only DuckLake has one, so naming SQLITE here selected a reader for
 * an archive nothing could have written. Keeping a value that cannot work is not
 * forward-compatibility, it is an invitation to a configuration that fails at open.
 *
 * <p>SQLite has not left the stack. DuckLake's catalog is a SQLite database and its transaction
 * locator opens one directly over JDBC; both are internal to that engine.
 */
public enum ArchiveEngine {
    DUCKLAKE
}
