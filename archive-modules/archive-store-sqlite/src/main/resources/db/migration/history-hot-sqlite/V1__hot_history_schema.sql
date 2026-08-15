CREATE TABLE hot_schema (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    schema_version INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
INSERT INTO hot_schema(singleton, schema_version, created_at)
VALUES (1, 1, unixepoch());

CREATE TABLE hot_fact_schema (
    dataset TEXT NOT NULL,
    table_name TEXT NOT NULL,
    projection_version INTEGER NOT NULL,
    PRIMARY KEY (dataset, table_name)
);

CREATE TABLE block_checkpoints (
    dataset TEXT NOT NULL,
    track TEXT NOT NULL,
    block_number INTEGER NOT NULL,
    slot INTEGER NOT NULL,
    block_hash BLOB NOT NULL,
    parent_hash BLOB NOT NULL,
    PRIMARY KEY (dataset, track, block_number)
);

CREATE TABLE projection_progress (
    dataset TEXT NOT NULL,
    track TEXT NOT NULL,
    coordinate INTEGER NOT NULL,
    slot INTEGER NOT NULL,
    block_hash BLOB NOT NULL,
    backend_generation INTEGER NOT NULL,
    PRIMARY KEY (dataset, track)
);

CREATE TABLE archive_receipts (
    job_id TEXT PRIMARY KEY,
    dataset TEXT NOT NULL,
    backend_generation INTEGER NOT NULL,
    ordered_digest TEXT NOT NULL
);

CREATE TABLE block_body_requirements (
    dataset TEXT PRIMARY KEY,
    block_number INTEGER NOT NULL
);

CREATE TABLE block_body_leases (
    lease_id TEXT PRIMARY KEY,
    start_block INTEGER NOT NULL,
    end_block INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);
CREATE INDEX block_body_leases_expiry ON block_body_leases(expires_at, start_block);

CREATE TABLE resolver_seeds (
    namespace TEXT PRIMARY KEY,
    base_block INTEGER NOT NULL
);

CREATE TABLE resolver_outputs (
    namespace TEXT NOT NULL,
    tx_hash BLOB NOT NULL,
    output_index INTEGER NOT NULL,
    address_key BLOB,
    address TEXT,
    payment_credential BLOB,
    stake_credential_type TEXT,
    stake_credential BLOB,
    created_block_number INTEGER,
    created_slot INTEGER,
    created_block_hash BLOB,
    source_kind TEXT NOT NULL CHECK (source_kind IN ('BLOCK', 'SEED')),
    PRIMARY KEY (namespace, tx_hash, output_index)
) WITHOUT ROWID;
-- Activation seeds have no creation coordinate and dominate the live resolver
-- on an already-synced node. Excluding them avoids a large, useless NULL-key
-- index while retaining the exact rollback/cleanup access path for block rows.
CREATE INDEX resolver_outputs_created
  ON resolver_outputs(namespace, created_block_number)
  WHERE source_kind = 'BLOCK';

CREATE TABLE resolver_spends (
    namespace TEXT NOT NULL,
    referenced_tx_hash BLOB NOT NULL,
    referenced_output_index INTEGER NOT NULL,
    spending_tx_hash BLOB NOT NULL,
    spending_block_number INTEGER NOT NULL,
    spending_slot INTEGER NOT NULL,
    spending_block_hash BLOB NOT NULL,
    input_role TEXT NOT NULL CHECK (input_role IN ('ordinary', 'collateral')),
    PRIMARY KEY (namespace, referenced_tx_hash, referenced_output_index),
    FOREIGN KEY (namespace, referenced_tx_hash, referenced_output_index)
      REFERENCES resolver_outputs(namespace, tx_hash, output_index) ON DELETE CASCADE
);
CREATE INDEX resolver_spends_block ON resolver_spends(namespace, spending_block_number);

CREATE TABLE pointer_registrations (
    dataset TEXT NOT NULL,
    namespace TEXT NOT NULL,
    pointer_slot INTEGER NOT NULL,
    pointer_tx_index INTEGER NOT NULL,
    pointer_cert_index INTEGER NOT NULL,
    credential_type TEXT NOT NULL,
    credential BLOB NOT NULL,
    registered_block_number INTEGER NOT NULL,
    registered_block_hash BLOB NOT NULL,
    PRIMARY KEY (dataset, namespace, pointer_slot, pointer_tx_index, pointer_cert_index)
);
CREATE INDEX pointer_registrations_credential
  ON pointer_registrations(dataset, namespace, credential_type, credential);

CREATE TABLE pointer_deregistrations (
    dataset TEXT NOT NULL,
    namespace TEXT NOT NULL,
    credential_type TEXT NOT NULL,
    credential BLOB NOT NULL,
    block_number INTEGER NOT NULL,
    block_hash BLOB NOT NULL,
    slot INTEGER NOT NULL,
    tx_index INTEGER NOT NULL,
    cert_index INTEGER NOT NULL,
    PRIMARY KEY (dataset, namespace, credential_type, credential,
                 block_number, slot, tx_index, cert_index)
);
CREATE INDEX pointer_deregistrations_credential
  ON pointer_deregistrations(dataset, namespace, credential_type, credential,
                             block_number, slot, tx_index, cert_index);
