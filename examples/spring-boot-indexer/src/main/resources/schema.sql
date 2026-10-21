-- One row per block the indexer has processed. The newest row is the cursor;
-- older rows let startup find the last block Yano and the indexer agree on.
CREATE TABLE IF NOT EXISTS indexed_block (
    block_number BIGINT      PRIMARY KEY,
    slot         BIGINT      NOT NULL,
    block_hash   VARCHAR(64) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_indexed_block_slot ON indexed_block (slot);

-- The scoped data: transaction metadata under the configured label.
CREATE TABLE IF NOT EXISTS tx_metadata (
    tx_hash      VARCHAR(64) NOT NULL,
    label        BIGINT      NOT NULL,
    body         TEXT        NOT NULL,
    block_number BIGINT      NOT NULL,
    slot         BIGINT      NOT NULL,
    PRIMARY KEY (tx_hash, label)
);

CREATE INDEX IF NOT EXISTS idx_tx_metadata_slot ON tx_metadata (slot);
