DROP VIEW transactions;
ALTER TABLE chain_transaction RENAME TO chain_transaction_old;

CREATE TABLE chain_transaction (
    tx_hash BLOB PRIMARY KEY,
    block_hash BLOB NOT NULL,
    block_number INTEGER NOT NULL,
    slot INTEGER NOT NULL,
    epoch INTEGER NOT NULL,
    block_time INTEGER NOT NULL,
    tx_index INTEGER NOT NULL,
    valid INTEGER NOT NULL,
    fee INTEGER,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE
);

INSERT INTO chain_transaction SELECT * FROM chain_transaction_old;
DROP TABLE chain_transaction_old;
CREATE INDEX chain_transaction_block_order ON chain_transaction(block_number, tx_index, tx_hash);
CREATE INDEX chain_transaction_archive_job ON chain_transaction(archive_job_id);
CREATE VIEW transactions AS SELECT * FROM chain_transaction;
