CREATE TABLE archive_identity (
    singleton INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
    archive_id TEXT NOT NULL,
    engine TEXT NOT NULL CHECK (engine = 'sqlite'),
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    network_magic INTEGER NOT NULL,
    genesis_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE archive_schema (
    dataset TEXT NOT NULL PRIMARY KEY,
    projection_version INTEGER NOT NULL CHECK (projection_version > 0),
    installed_at INTEGER NOT NULL
);

CREATE TABLE archive_generation (
    singleton INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
    generation INTEGER NOT NULL CHECK (generation >= 0)
);
INSERT INTO archive_generation(singleton, generation) VALUES (1, 0);

CREATE TABLE archive_commits (
    job_id TEXT NOT NULL PRIMARY KEY,
    dataset TEXT NOT NULL,
    projection_version INTEGER NOT NULL,
    source_kind TEXT NOT NULL CHECK (source_kind IN ('BLOCK', 'EPOCH')),
    range_start INTEGER NOT NULL,
    range_end INTEGER NOT NULL CHECK (range_end >= range_start),
    start_slot INTEGER NOT NULL,
    start_hash BLOB NOT NULL,
    end_slot INTEGER NOT NULL,
    end_hash BLOB NOT NULL,
    source_state_version TEXT NOT NULL,
    backend_generation INTEGER NOT NULL,
    ordered_digest TEXT NOT NULL,
    committed_at INTEGER NOT NULL
);

CREATE TABLE archive_commit_counts (
    job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    table_name TEXT NOT NULL,
    row_count INTEGER NOT NULL CHECK (row_count >= 0),
    PRIMARY KEY (job_id, table_name)
);

CREATE TABLE archive_coverage (
    job_id TEXT NOT NULL PRIMARY KEY REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    dataset TEXT NOT NULL,
    projection_version INTEGER NOT NULL,
    source_kind TEXT NOT NULL CHECK (source_kind IN ('BLOCK', 'EPOCH')),
    range_start INTEGER NOT NULL,
    range_end INTEGER NOT NULL CHECK (range_end >= range_start),
    backend_generation INTEGER NOT NULL
);
CREATE INDEX archive_coverage_dataset_range
    ON archive_coverage(dataset, range_start, range_end);

CREATE TABLE archive_invalidations (
    invalidation_id TEXT NOT NULL PRIMARY KEY,
    dataset TEXT NOT NULL,
    source_kind TEXT NOT NULL CHECK (source_kind IN ('BLOCK', 'EPOCH')),
    range_start INTEGER NOT NULL,
    range_end INTEGER NOT NULL CHECK (range_end >= range_start),
    invalidated_at INTEGER NOT NULL
);
CREATE INDEX archive_invalidations_dataset_range
    ON archive_invalidations(dataset, range_start, range_end);

CREATE TABLE chain_transaction (
    tx_hash BLOB NOT NULL PRIMARY KEY,
    block_hash BLOB NOT NULL,
    block_number INTEGER NOT NULL,
    slot INTEGER NOT NULL,
    epoch INTEGER NOT NULL,
    block_time INTEGER NOT NULL,
    tx_index INTEGER NOT NULL,
    valid INTEGER NOT NULL CHECK (valid IN (0, 1)),
    fee INTEGER,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE
);
CREATE INDEX chain_transaction_block_order ON chain_transaction(block_number, tx_index, tx_hash);
CREATE INDEX chain_transaction_archive_job ON chain_transaction(archive_job_id);

CREATE TABLE stake_addresses (
    stake_address_id INTEGER PRIMARY KEY,
    stake_address TEXT NOT NULL UNIQUE,
    stake_credential_type TEXT NOT NULL,
    stake_credential BLOB NOT NULL,
    UNIQUE (stake_credential_type, stake_credential)
);
CREATE INDEX stake_addresses_credential
    ON stake_addresses(stake_credential_type, stake_credential);

CREATE TABLE account_events_data (
    stake_address_id INTEGER NOT NULL REFERENCES stake_addresses(stake_address_id),
    event_type TEXT NOT NULL,
    tx_hash BLOB NOT NULL,
    block_hash BLOB NOT NULL,
    block_number INTEGER NOT NULL,
    slot INTEGER NOT NULL,
    epoch INTEGER NOT NULL,
    block_time INTEGER NOT NULL,
    tx_index INTEGER NOT NULL,
    event_index INTEGER NOT NULL,
    pool_hash BLOB,
    drep_type TEXT,
    drep_credential BLOB,
    amount INTEGER,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    PRIMARY KEY (stake_address_id, slot, tx_index, event_index, event_type, tx_hash)
);
CREATE INDEX account_events_stake_order
    ON account_events_data(stake_address_id, slot, tx_index, event_index, event_type, tx_hash);
CREATE INDEX account_events_tx_hash ON account_events_data(tx_hash);
CREATE INDEX account_events_archive_job ON account_events_data(archive_job_id);

CREATE VIEW account_events AS
SELECT s.stake_credential, s.stake_credential_type, s.stake_address,
       e.event_type, e.tx_hash, e.block_hash, e.block_number, e.slot, e.epoch,
       e.block_time, e.tx_index, e.event_index, e.pool_hash, e.drep_type,
       e.drep_credential, e.amount, e.archive_job_id
FROM account_events_data e JOIN stake_addresses s USING (stake_address_id);

CREATE TRIGGER insert_account_events INSTEAD OF INSERT ON account_events BEGIN
    INSERT OR IGNORE INTO stake_addresses(stake_address, stake_credential_type, stake_credential)
    VALUES (NEW.stake_address, NEW.stake_credential_type, NEW.stake_credential);
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM stake_addresses WHERE stake_address=NEW.stake_address
          AND stake_credential_type=NEW.stake_credential_type
          AND stake_credential=NEW.stake_credential)
      THEN RAISE(ABORT, 'stake address dimension conflict') END;
    INSERT INTO account_events_data VALUES (
        (SELECT stake_address_id FROM stake_addresses WHERE stake_address=NEW.stake_address),
        NEW.event_type, NEW.tx_hash, NEW.block_hash, NEW.block_number, NEW.slot, NEW.epoch,
        NEW.block_time, NEW.tx_index, NEW.event_index, NEW.pool_hash, NEW.drep_type,
        NEW.drep_credential, NEW.amount, NEW.archive_job_id);
END;

CREATE TABLE address_transactions (
    subject_type TEXT NOT NULL,
    subject_key BLOB NOT NULL,
    address TEXT,
    stake_address TEXT,
    tx_hash BLOB NOT NULL,
    block_hash BLOB NOT NULL,
    block_number INTEGER NOT NULL,
    slot INTEGER NOT NULL,
    epoch INTEGER NOT NULL,
    block_time INTEGER NOT NULL,
    tx_index INTEGER NOT NULL,
    input_count INTEGER NOT NULL,
    output_count INTEGER NOT NULL,
    collateral_input_count INTEGER NOT NULL,
    collateral_return_count INTEGER NOT NULL,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    PRIMARY KEY (subject_type, subject_key, tx_hash)
);
CREATE INDEX address_transactions_subject_order
    ON address_transactions(subject_type, subject_key, block_number, tx_index, tx_hash);
CREATE INDEX address_transactions_tx_hash ON address_transactions(tx_hash);
CREATE INDEX address_transactions_archive_job ON address_transactions(archive_job_id);

CREATE TABLE addresses (
    address_id INTEGER PRIMARY KEY,
    address TEXT NOT NULL UNIQUE,
    network_id INTEGER,
    address_type TEXT NOT NULL,
    payment_credential_type TEXT,
    payment_credential BLOB
);
CREATE INDEX addresses_payment_credential ON addresses(payment_credential_type, payment_credential);

CREATE TABLE transaction_outputs_data (
    tx_hash BLOB NOT NULL,
    output_index INTEGER NOT NULL,
    tx_index INTEGER NOT NULL,
    origin_type TEXT NOT NULL,
    address_id INTEGER NOT NULL REFERENCES addresses(address_id),
    stake_address_id INTEGER REFERENCES stake_addresses(stake_address_id),
    lovelace INTEGER NOT NULL,
    datum_kind TEXT NOT NULL,
    datum_hash BLOB,
    inline_datum_cbor BLOB,
    reference_script_hash BLOB,
    reference_script_type TEXT,
    reference_script_cbor BLOB,
    is_collateral_return INTEGER NOT NULL CHECK (is_collateral_return IN (0, 1)),
    block_hash BLOB,
    block_number INTEGER,
    slot INTEGER,
    epoch INTEGER,
    block_time INTEGER,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    PRIMARY KEY (tx_hash, output_index)
);
CREATE INDEX transaction_outputs_address_order
    ON transaction_outputs_data(address_id, block_number, tx_index, output_index, tx_hash);
CREATE INDEX transaction_outputs_stake_order
    ON transaction_outputs_data(stake_address_id, block_number, tx_index, output_index, tx_hash);
CREATE INDEX transaction_outputs_block_order
    ON transaction_outputs_data(block_number, tx_index, output_index, tx_hash);
CREATE INDEX transaction_outputs_archive_job ON transaction_outputs_data(archive_job_id);

CREATE VIEW transaction_outputs AS
SELECT o.tx_hash, o.output_index, o.tx_index, o.origin_type, a.address, a.network_id,
       a.address_type, a.payment_credential_type, a.payment_credential,
       s.stake_address, s.stake_credential_type, s.stake_credential,
       o.lovelace, o.datum_kind, o.datum_hash, o.inline_datum_cbor,
       o.reference_script_hash, o.reference_script_type, o.reference_script_cbor,
       o.is_collateral_return, o.block_hash, o.block_number, o.slot, o.epoch,
       o.block_time, o.archive_job_id
FROM transaction_outputs_data o
JOIN addresses a USING (address_id)
LEFT JOIN stake_addresses s USING (stake_address_id);

CREATE TRIGGER insert_transaction_outputs INSTEAD OF INSERT ON transaction_outputs BEGIN
    INSERT OR IGNORE INTO addresses(address, network_id, address_type,
                                    payment_credential_type, payment_credential)
    VALUES (NEW.address, NEW.network_id, NEW.address_type,
            NEW.payment_credential_type, NEW.payment_credential);
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM addresses WHERE address=NEW.address
          AND network_id IS NEW.network_id AND address_type=NEW.address_type
          AND payment_credential_type IS NEW.payment_credential_type
          AND payment_credential IS NEW.payment_credential)
      THEN RAISE(ABORT, 'address dimension conflict') END;
    INSERT OR IGNORE INTO stake_addresses(stake_address, stake_credential_type, stake_credential)
    SELECT NEW.stake_address, NEW.stake_credential_type, NEW.stake_credential
    WHERE NEW.stake_address IS NOT NULL;
    SELECT CASE WHEN NEW.stake_address IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM stake_addresses WHERE stake_address=NEW.stake_address
          AND stake_credential_type=NEW.stake_credential_type
          AND stake_credential=NEW.stake_credential)
      THEN RAISE(ABORT, 'stake address dimension conflict') END;
    INSERT INTO transaction_outputs_data VALUES (
        NEW.tx_hash, NEW.output_index, NEW.tx_index, NEW.origin_type,
        (SELECT address_id FROM addresses WHERE address=NEW.address),
        (SELECT stake_address_id FROM stake_addresses WHERE stake_address=NEW.stake_address),
        NEW.lovelace, NEW.datum_kind, NEW.datum_hash, NEW.inline_datum_cbor,
        NEW.reference_script_hash, NEW.reference_script_type, NEW.reference_script_cbor,
        NEW.is_collateral_return, NEW.block_hash, NEW.block_number, NEW.slot, NEW.epoch,
        NEW.block_time, NEW.archive_job_id);
END;

CREATE TABLE transaction_output_assets (
    tx_hash BLOB NOT NULL,
    output_index INTEGER NOT NULL,
    policy_id BLOB NOT NULL,
    asset_name BLOB NOT NULL,
    quantity TEXT NOT NULL CHECK (quantity <> '' AND quantity NOT GLOB '*[^0-9]*'),
    block_number INTEGER,
    slot INTEGER,
    epoch INTEGER,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    PRIMARY KEY (tx_hash, output_index, policy_id, asset_name),
    FOREIGN KEY (tx_hash, output_index) REFERENCES transaction_outputs_data(tx_hash, output_index) ON DELETE CASCADE
);
CREATE INDEX transaction_output_assets_identity_order
    ON transaction_output_assets(policy_id, asset_name, block_number, tx_hash, output_index);
CREATE INDEX transaction_output_assets_archive_job ON transaction_output_assets(archive_job_id);

CREATE TABLE transaction_inputs (
    spending_tx_hash BLOB NOT NULL,
    spending_tx_index INTEGER NOT NULL,
    input_index INTEGER NOT NULL,
    input_role TEXT NOT NULL,
    referenced_tx_hash BLOB NOT NULL,
    referenced_output_index INTEGER NOT NULL,
    consumes_output INTEGER NOT NULL CHECK (consumes_output IN (0, 1)),
    block_hash BLOB NOT NULL,
    block_number INTEGER NOT NULL,
    slot INTEGER NOT NULL,
    epoch INTEGER NOT NULL,
    block_time INTEGER NOT NULL,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    PRIMARY KEY (spending_tx_hash, input_role, input_index)
);
CREATE INDEX transaction_inputs_outpoint
    ON transaction_inputs(referenced_tx_hash, referenced_output_index, consumes_output);
CREATE INDEX transaction_inputs_block_order
    ON transaction_inputs(block_number, spending_tx_index, input_index, spending_tx_hash);
CREATE INDEX transaction_inputs_archive_job ON transaction_inputs(archive_job_id);

CREATE TABLE transaction_datums (
    tx_hash BLOB NOT NULL,
    tx_index INTEGER NOT NULL,
    datum_hash BLOB NOT NULL,
    datum_cbor BLOB NOT NULL,
    block_hash BLOB NOT NULL,
    block_number INTEGER NOT NULL,
    slot INTEGER NOT NULL,
    epoch INTEGER NOT NULL,
    block_time INTEGER NOT NULL,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    PRIMARY KEY (tx_hash, datum_hash)
);
CREATE INDEX transaction_datums_tx_order
    ON transaction_datums(tx_hash, datum_hash);
CREATE INDEX transaction_datums_hash
    ON transaction_datums(datum_hash, epoch, tx_hash);
CREATE INDEX transaction_datums_block_order
    ON transaction_datums(block_number, tx_index, tx_hash, datum_hash);
CREATE INDEX transaction_datums_archive_job ON transaction_datums(archive_job_id);

CREATE TABLE transaction_redeemers (
    tx_hash BLOB NOT NULL,
    tx_index INTEGER NOT NULL,
    purpose TEXT NOT NULL,
    redeemer_index INTEGER NOT NULL,
    redeemer_cbor BLOB NOT NULL,
    redeemer_data_hash BLOB,
    execution_mem TEXT NOT NULL CHECK (execution_mem <> '' AND execution_mem NOT GLOB '*[^0-9]*'),
    execution_steps TEXT NOT NULL CHECK (execution_steps <> '' AND execution_steps NOT GLOB '*[^0-9]*'),
    block_hash BLOB NOT NULL,
    block_number INTEGER NOT NULL,
    slot INTEGER NOT NULL,
    epoch INTEGER NOT NULL,
    block_time INTEGER NOT NULL,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    PRIMARY KEY (tx_hash, purpose, redeemer_index)
);
CREATE INDEX transaction_redeemers_tx_order
    ON transaction_redeemers(tx_hash, purpose, redeemer_index);
CREATE INDEX transaction_redeemers_block_order
    ON transaction_redeemers(block_number, tx_index, purpose, redeemer_index, tx_hash);
CREATE INDEX transaction_redeemers_archive_job ON transaction_redeemers(archive_job_id);

CREATE TABLE rewards_data (
    stake_address_id INTEGER NOT NULL REFERENCES stake_addresses(stake_address_id),
    pool_hash BLOB,
    reward_type TEXT NOT NULL,
    earned_epoch INTEGER NOT NULL,
    spendable_epoch INTEGER NOT NULL,
    amount INTEGER NOT NULL,
    source_id TEXT NOT NULL,
    boundary_block_hash BLOB NOT NULL,
    boundary_block_number INTEGER NOT NULL,
    boundary_slot INTEGER NOT NULL,
    boundary_block_time INTEGER NOT NULL,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    PRIMARY KEY (stake_address_id, earned_epoch, reward_type, source_id)
);
CREATE INDEX rewards_stake_order
    ON rewards_data(stake_address_id, earned_epoch, reward_type, source_id);
CREATE INDEX rewards_epoch_order ON rewards_data(earned_epoch, stake_address_id, reward_type, source_id);
CREATE INDEX rewards_archive_job ON rewards_data(archive_job_id);

CREATE VIEW rewards AS
SELECT s.stake_credential, s.stake_credential_type, s.stake_address, r.pool_hash,
       r.reward_type, r.earned_epoch, r.spendable_epoch, r.amount, r.source_id,
       r.boundary_block_hash, r.boundary_block_number, r.boundary_slot,
       r.boundary_block_time, r.archive_job_id
FROM rewards_data r JOIN stake_addresses s USING (stake_address_id);

CREATE TRIGGER insert_rewards INSTEAD OF INSERT ON rewards BEGIN
    INSERT OR IGNORE INTO stake_addresses(stake_address, stake_credential_type, stake_credential)
    VALUES (NEW.stake_address, NEW.stake_credential_type, NEW.stake_credential);
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM stake_addresses WHERE stake_address=NEW.stake_address
          AND stake_credential_type=NEW.stake_credential_type
          AND stake_credential=NEW.stake_credential)
      THEN RAISE(ABORT, 'stake address dimension conflict') END;
    INSERT INTO rewards_data VALUES (
        (SELECT stake_address_id FROM stake_addresses WHERE stake_address=NEW.stake_address),
        NEW.pool_hash, NEW.reward_type, NEW.earned_epoch, NEW.spendable_epoch, NEW.amount,
        NEW.source_id, NEW.boundary_block_hash, NEW.boundary_block_number, NEW.boundary_slot,
        NEW.boundary_block_time, NEW.archive_job_id);
END;

CREATE TABLE epoch_stakes_data (
    epoch INTEGER NOT NULL,
    stake_address_id INTEGER NOT NULL REFERENCES stake_addresses(stake_address_id),
    pool_hash BLOB,
    amount INTEGER NOT NULL,
    boundary_block_hash BLOB NOT NULL,
    boundary_block_number INTEGER NOT NULL,
    boundary_slot INTEGER NOT NULL,
    boundary_block_time INTEGER NOT NULL,
    source_state_version TEXT NOT NULL,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    PRIMARY KEY (epoch, stake_address_id)
);
CREATE INDEX epoch_stakes_stake ON epoch_stakes_data(stake_address_id, epoch);
CREATE INDEX epoch_stakes_archive_job ON epoch_stakes_data(archive_job_id);

CREATE VIEW epoch_stakes AS
SELECT e.epoch, s.stake_credential_type, s.stake_credential, s.stake_address,
       e.pool_hash, e.amount, e.boundary_block_hash, e.boundary_block_number,
       e.boundary_slot, e.boundary_block_time, e.source_state_version, e.archive_job_id
FROM epoch_stakes_data e JOIN stake_addresses s USING (stake_address_id);

CREATE TRIGGER insert_epoch_stakes INSTEAD OF INSERT ON epoch_stakes BEGIN
    INSERT OR IGNORE INTO stake_addresses(stake_address, stake_credential_type, stake_credential)
    VALUES (NEW.stake_address, NEW.stake_credential_type, NEW.stake_credential);
    SELECT CASE WHEN NOT EXISTS (
        SELECT 1 FROM stake_addresses WHERE stake_address=NEW.stake_address
          AND stake_credential_type=NEW.stake_credential_type
          AND stake_credential=NEW.stake_credential)
      THEN RAISE(ABORT, 'stake address dimension conflict') END;
    INSERT INTO epoch_stakes_data VALUES (
        NEW.epoch, (SELECT stake_address_id FROM stake_addresses WHERE stake_address=NEW.stake_address),
        NEW.pool_hash, NEW.amount, NEW.boundary_block_hash, NEW.boundary_block_number,
        NEW.boundary_slot, NEW.boundary_block_time, NEW.source_state_version, NEW.archive_job_id);
END;

CREATE TABLE drep_distributions (
    epoch INTEGER NOT NULL,
    drep_type TEXT NOT NULL,
    drep_credential BLOB,
    amount INTEGER NOT NULL,
    stored_expiry INTEGER,
    dormant_epochs INTEGER NOT NULL,
    effective_expiry INTEGER,
    active INTEGER NOT NULL CHECK (active IN (0, 1)),
    boundary_block_hash BLOB NOT NULL,
    boundary_block_number INTEGER NOT NULL,
    boundary_slot INTEGER NOT NULL,
    boundary_block_time INTEGER NOT NULL,
    source_state_version TEXT NOT NULL,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX drep_distributions_logical_key
    ON drep_distributions(epoch, drep_type, COALESCE(drep_credential, X''));
CREATE INDEX drep_distributions_credential ON drep_distributions(drep_type, drep_credential, epoch);
CREATE INDEX drep_distributions_archive_job ON drep_distributions(archive_job_id);

CREATE TABLE ada_pots (
    epoch INTEGER NOT NULL PRIMARY KEY,
    treasury INTEGER NOT NULL,
    reserves INTEGER NOT NULL,
    deposits INTEGER NOT NULL,
    fees INTEGER NOT NULL,
    distributed INTEGER NOT NULL,
    undistributed INTEGER NOT NULL,
    rewards_pot INTEGER NOT NULL,
    pool_rewards_pot INTEGER NOT NULL,
    boundary_block_hash BLOB NOT NULL,
    boundary_block_number INTEGER NOT NULL,
    boundary_slot INTEGER NOT NULL,
    boundary_block_time INTEGER NOT NULL,
    source_state_version TEXT NOT NULL,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE
);
CREATE INDEX ada_pots_archive_job ON ada_pots(archive_job_id);

CREATE TABLE governance_proposal_statuses (
    epoch INTEGER NOT NULL,
    tx_hash BLOB NOT NULL,
    governance_action_index INTEGER NOT NULL,
    action_type TEXT NOT NULL,
    observation_phase TEXT NOT NULL,
    status_code TEXT NOT NULL,
    decision_reason TEXT,
    deposit INTEGER NOT NULL,
    return_address BLOB NOT NULL,
    submitted_epoch INTEGER NOT NULL,
    expires_after_epoch INTEGER NOT NULL,
    boundary_block_hash BLOB NOT NULL,
    boundary_block_number INTEGER NOT NULL,
    boundary_slot INTEGER NOT NULL,
    boundary_block_time INTEGER NOT NULL,
    source_state_version TEXT NOT NULL,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    PRIMARY KEY (epoch, tx_hash, governance_action_index, observation_phase)
);
CREATE INDEX governance_proposal_statuses_action
    ON governance_proposal_statuses(tx_hash, governance_action_index, epoch, observation_phase);
CREATE INDEX governance_proposal_statuses_archive_job ON governance_proposal_statuses(archive_job_id);

CREATE VIEW transactions AS SELECT * FROM chain_transaction;

CREATE VIEW transaction_output_amounts AS
SELECT tx_hash, output_index, X'' AS policy_id, X'' AS asset_name,
       CAST(lovelace AS TEXT) AS quantity, 1 AS is_lovelace
FROM transaction_outputs
UNION ALL
SELECT tx_hash, output_index, policy_id, asset_name, quantity, 0
FROM transaction_output_assets;

CREATE VIEW output_lifecycle AS
SELECT o.*, i.spending_tx_hash, i.block_number AS spent_block_number, i.slot AS spent_slot
FROM transaction_outputs o
LEFT JOIN transaction_inputs i
  ON i.referenced_tx_hash = o.tx_hash
 AND i.referenced_output_index = o.output_index
 AND i.consumes_output = 1;

CREATE VIEW unspent_outputs AS
SELECT * FROM output_lifecycle WHERE spending_tx_hash IS NULL;

CREATE VIEW address_utxo_amounts AS
SELECT o.address, o.stake_address, o.payment_credential, o.stake_credential,
       o.tx_hash, o.output_index, a.policy_id, a.asset_name,
       a.quantity, a.is_lovelace, o.block_number, o.slot, o.epoch
FROM transaction_outputs o
JOIN transaction_output_amounts a USING (tx_hash, output_index);

CREATE VIEW address_asset_flow AS
SELECT address, stake_address, payment_credential, stake_credential,
       tx_hash, output_index, policy_id, asset_name, quantity,
       is_lovelace, block_number, slot, epoch, 'received' AS direction
FROM address_utxo_amounts
UNION ALL
SELECT o.address, o.stake_address, o.payment_credential, o.stake_credential,
       i.spending_tx_hash, i.referenced_output_index,
       a.policy_id, a.asset_name, a.quantity, a.is_lovelace,
       i.block_number, i.slot, i.epoch, 'spent'
FROM transaction_inputs i
JOIN transaction_outputs o
  ON o.tx_hash = i.referenced_tx_hash AND o.output_index = i.referenced_output_index
JOIN transaction_output_amounts a
  ON a.tx_hash = o.tx_hash AND a.output_index = o.output_index
WHERE i.consumes_output = 1;
