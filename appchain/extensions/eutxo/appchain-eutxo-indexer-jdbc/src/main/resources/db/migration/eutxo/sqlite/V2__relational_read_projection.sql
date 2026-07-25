CREATE TABLE projection_state (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    projected_height INTEGER NOT NULL DEFAULT 0
);

INSERT INTO projection_state(singleton_id, projected_height)
VALUES (1, 0);

CREATE TABLE indexed_transaction (
    event_sequence INTEGER PRIMARY KEY,
    transaction_id TEXT NOT NULL UNIQUE,
    message_id TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL,
    canonical_payload BLOB NOT NULL,
    app_height INTEGER NOT NULL,
    FOREIGN KEY (app_height) REFERENCES source_block(app_height)
        ON DELETE CASCADE
);

CREATE INDEX indexed_transaction_height_idx
    ON indexed_transaction(app_height);

CREATE TABLE indexed_transaction_input (
    transaction_id TEXT NOT NULL,
    input_outpoint TEXT NOT NULL,
    parent_transaction_id TEXT NOT NULL,
    address TEXT NOT NULL,
    lovelace TEXT NOT NULL,
    app_height INTEGER NOT NULL,
    PRIMARY KEY (transaction_id, input_outpoint),
    FOREIGN KEY (app_height) REFERENCES source_block(app_height)
        ON DELETE CASCADE
);

CREATE INDEX indexed_transaction_input_parent_idx
    ON indexed_transaction_input(parent_transaction_id, transaction_id);

CREATE TABLE indexed_transaction_output (
    outpoint TEXT PRIMARY KEY,
    transaction_id TEXT NOT NULL,
    address TEXT NOT NULL,
    lovelace TEXT NOT NULL,
    app_height INTEGER NOT NULL,
    FOREIGN KEY (app_height) REFERENCES source_block(app_height)
        ON DELETE CASCADE
);

CREATE INDEX indexed_transaction_output_address_idx
    ON indexed_transaction_output(address, outpoint);

CREATE TABLE indexed_address_activity (
    address TEXT NOT NULL,
    event_sequence INTEGER NOT NULL,
    transaction_id TEXT NOT NULL,
    app_height INTEGER NOT NULL,
    PRIMARY KEY (address, event_sequence, transaction_id),
    FOREIGN KEY (app_height) REFERENCES source_block(app_height)
        ON DELETE CASCADE
);

CREATE INDEX indexed_address_activity_page_idx
    ON indexed_address_activity(address, event_sequence DESC);

CREATE TABLE indexed_deposit (
    event_sequence INTEGER PRIMARY KEY,
    accepted_outpoint TEXT NOT NULL UNIQUE,
    mirrored_outpoint TEXT NOT NULL UNIQUE,
    mirrored_transaction_id TEXT NOT NULL,
    address TEXT NOT NULL,
    lovelace TEXT NOT NULL,
    canonical_payload BLOB NOT NULL,
    app_height INTEGER NOT NULL,
    FOREIGN KEY (app_height) REFERENCES source_block(app_height)
        ON DELETE CASCADE
);

CREATE INDEX indexed_deposit_address_idx
    ON indexed_deposit(address, event_sequence DESC);

CREATE INDEX indexed_deposit_mirrored_transaction_idx
    ON indexed_deposit(mirrored_transaction_id);

CREATE TABLE indexed_withdrawal_version (
    app_height INTEGER NOT NULL,
    event_ordinal INTEGER NOT NULL,
    event_sequence INTEGER NOT NULL,
    claim_id TEXT NOT NULL,
    status TEXT NOT NULL,
    withdrawal_outpoint TEXT NOT NULL,
    canonical_payload BLOB NOT NULL,
    PRIMARY KEY (app_height, event_ordinal),
    FOREIGN KEY (app_height) REFERENCES source_block(app_height)
        ON DELETE CASCADE
);

CREATE INDEX indexed_withdrawal_sequence_idx
    ON indexed_withdrawal_version(event_sequence, app_height DESC);

CREATE INDEX indexed_withdrawal_claim_idx
    ON indexed_withdrawal_version(claim_id, app_height DESC);

CREATE INDEX indexed_withdrawal_outpoint_idx
    ON indexed_withdrawal_version(withdrawal_outpoint);
