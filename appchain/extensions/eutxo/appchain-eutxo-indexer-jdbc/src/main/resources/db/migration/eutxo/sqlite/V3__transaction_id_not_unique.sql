-- A transaction_id is NOT unique across indexed transactions.
--
-- Two committed messages legitimately carry the same value:
--   * an undecodable transaction has no id at all, and the receipt normalises
--     that to the empty string — so the SECOND malformed submission ever made
--     collides with the first;
--   * a replayed transaction is committed and REJECTED as DUPLICATE_TRANSACTION,
--     repeating the id of the accepted one.
--
-- Either case aborted the projection with SQLITE_CONSTRAINT_UNIQUE and left the
-- whole lifecycle index permanently FAILED (the console then reports it as
-- simply "unavailable"). Identity belongs to message_id, which stays unique;
-- transaction_id keeps only a non-unique lookup index.
--
-- SQLite cannot drop a column constraint in place, so the table is rebuilt.
-- No other table references indexed_transaction, so no PRAGMA juggling is
-- needed (and Flyway forbids mixing PRAGMA with transactional statements).

CREATE TABLE indexed_transaction_v3 (
    event_sequence INTEGER PRIMARY KEY,
    transaction_id TEXT NOT NULL,
    message_id TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL,
    canonical_payload BLOB NOT NULL,
    app_height INTEGER NOT NULL,
    FOREIGN KEY (app_height) REFERENCES source_block(app_height)
        ON DELETE CASCADE
);

INSERT INTO indexed_transaction_v3 (
    event_sequence, transaction_id, message_id, status,
    canonical_payload, app_height)
SELECT event_sequence, transaction_id, message_id, status,
       canonical_payload, app_height
FROM indexed_transaction;

DROP TABLE indexed_transaction;

ALTER TABLE indexed_transaction_v3 RENAME TO indexed_transaction;

CREATE INDEX indexed_transaction_height_idx
    ON indexed_transaction(app_height);

CREATE INDEX indexed_transaction_txid_idx
    ON indexed_transaction(transaction_id);
