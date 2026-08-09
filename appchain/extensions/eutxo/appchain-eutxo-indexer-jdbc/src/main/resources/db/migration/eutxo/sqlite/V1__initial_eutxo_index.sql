CREATE TABLE index_metadata (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    identity_digest TEXT NOT NULL,
    network TEXT NOT NULL,
    chain_id TEXT NOT NULL,
    state_machine_id TEXT NOT NULL,
    ledger_profile_digest TEXT NOT NULL,
    state_genesis_id TEXT NOT NULL,
    bridge_abi INTEGER NOT NULL,
    validity_profile_digest TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE source_block (
    app_height INTEGER PRIMARY KEY,
    app_block_hash TEXT NOT NULL UNIQUE,
    l1_slot INTEGER NOT NULL,
    l1_block_hash TEXT NOT NULL,
    transaction_sequence INTEGER NOT NULL,
    deposit_sequence INTEGER NOT NULL,
    withdrawal_sequence INTEGER NOT NULL,
    coverage TEXT NOT NULL
);

CREATE TABLE projection_event_journal (
    app_height INTEGER NOT NULL,
    event_ordinal INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    event_sequence INTEGER NOT NULL,
    record_id TEXT NOT NULL,
    canonical_payload BLOB NOT NULL,
    PRIMARY KEY (app_height, event_ordinal),
    FOREIGN KEY (app_height) REFERENCES source_block(app_height)
        ON DELETE CASCADE
);

CREATE INDEX projection_event_identity_idx
    ON projection_event_journal(event_type, event_sequence, record_id);

CREATE INDEX projection_event_record_idx
    ON projection_event_journal(event_type, record_id);
