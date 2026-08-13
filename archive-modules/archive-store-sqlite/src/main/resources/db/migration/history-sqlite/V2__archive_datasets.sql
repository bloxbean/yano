CREATE TABLE chain_transaction (
    tx_hash BLOB NOT NULL PRIMARY KEY,
    block_hash BLOB NOT NULL,
    block_number INTEGER NOT NULL,
    slot INTEGER NOT NULL,
    epoch INTEGER NOT NULL,
    block_time INTEGER NOT NULL,
    tx_index INTEGER NOT NULL,
    valid INTEGER NOT NULL CHECK (valid IN (0, 1)),
    fee INTEGER NOT NULL,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE
);
CREATE INDEX chain_transaction_block_order ON chain_transaction(block_number, tx_index, tx_hash);
CREATE INDEX chain_transaction_archive_job ON chain_transaction(archive_job_id);

CREATE TABLE account_events (
    stake_credential BLOB NOT NULL,
    stake_credential_type TEXT NOT NULL,
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
    drep_credential BLOB,
    amount INTEGER,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    PRIMARY KEY (stake_credential, slot, tx_index, event_index, event_type, tx_hash)
);
CREATE INDEX account_events_stake_order
    ON account_events(stake_credential, slot, tx_index, event_index, event_type, tx_hash);
CREATE INDEX account_events_tx_hash ON account_events(tx_hash);
CREATE INDEX account_events_archive_job ON account_events(archive_job_id);

CREATE TABLE address_transactions (
    subject_type TEXT NOT NULL,
    subject_key BLOB NOT NULL,
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
    address_key BLOB NOT NULL PRIMARY KEY,
    raw_address BLOB NOT NULL,
    display_address TEXT,
    network_id INTEGER,
    address_type TEXT NOT NULL,
    payment_credential_type TEXT,
    payment_credential BLOB,
    stake_reference_type TEXT NOT NULL,
    stake_credential_type TEXT,
    stake_credential BLOB,
    pointer_slot INTEGER,
    pointer_tx_index INTEGER,
    pointer_cert_index INTEGER,
    first_seen_block_number INTEGER,
    first_seen_slot INTEGER,
    first_seen_epoch INTEGER
);
CREATE INDEX addresses_stake_credential ON addresses(stake_credential_type, stake_credential, address_key);
CREATE INDEX addresses_payment_credential ON addresses(payment_credential_type, payment_credential, address_key);
CREATE INDEX addresses_first_seen ON addresses(first_seen_block_number, address_key);

CREATE TABLE transaction_outputs (
    tx_hash BLOB NOT NULL,
    output_index INTEGER NOT NULL,
    tx_index INTEGER NOT NULL,
    origin_type TEXT NOT NULL,
    address_key BLOB NOT NULL REFERENCES addresses(address_key),
    payment_credential BLOB,
    stake_credential BLOB,
    lovelace INTEGER NOT NULL,
    datum_kind TEXT NOT NULL,
    datum_hash BLOB,
    reference_script_hash BLOB,
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
    ON transaction_outputs(address_key, block_number, tx_index, output_index, tx_hash);
CREATE INDEX transaction_outputs_stake_order
    ON transaction_outputs(stake_credential, block_number, tx_index, output_index, tx_hash);
CREATE INDEX transaction_outputs_block_order
    ON transaction_outputs(block_number, tx_index, output_index, tx_hash);
CREATE INDEX transaction_outputs_archive_job ON transaction_outputs(archive_job_id);

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
    FOREIGN KEY (tx_hash, output_index) REFERENCES transaction_outputs(tx_hash, output_index) ON DELETE CASCADE
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

CREATE TABLE datums (
    datum_hash BLOB NOT NULL PRIMARY KEY,
    cbor BLOB NOT NULL
);

CREATE TABLE scripts (
    script_hash BLOB NOT NULL PRIMARY KEY,
    script_type TEXT NOT NULL,
    cbor BLOB NOT NULL
);

CREATE TABLE rewards (
    stake_credential BLOB NOT NULL,
    stake_credential_type TEXT NOT NULL,
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
    PRIMARY KEY (stake_credential, earned_epoch, reward_type, source_id)
);
CREATE INDEX rewards_stake_order
    ON rewards(stake_credential, earned_epoch, reward_type, source_id);
CREATE INDEX rewards_epoch_order ON rewards(earned_epoch, stake_credential, reward_type, source_id);
CREATE INDEX rewards_archive_job ON rewards(archive_job_id);

CREATE TABLE epoch_stakes (
    epoch INTEGER NOT NULL,
    stake_credential_type TEXT NOT NULL,
    stake_credential BLOB NOT NULL,
    pool_hash BLOB,
    amount INTEGER NOT NULL,
    boundary_block_hash BLOB NOT NULL,
    boundary_block_number INTEGER NOT NULL,
    boundary_slot INTEGER NOT NULL,
    boundary_block_time INTEGER NOT NULL,
    source_state_version TEXT NOT NULL,
    archive_job_id TEXT NOT NULL REFERENCES archive_commits(job_id) ON DELETE CASCADE,
    PRIMARY KEY (epoch, stake_credential)
);
CREATE INDEX epoch_stakes_stake ON epoch_stakes(stake_credential, epoch);
CREATE INDEX epoch_stakes_archive_job ON epoch_stakes(archive_job_id);

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
SELECT o.address_key, o.tx_hash, o.output_index, a.policy_id, a.asset_name,
       a.quantity, a.is_lovelace, o.block_number, o.slot, o.epoch
FROM transaction_outputs o
JOIN transaction_output_amounts a USING (tx_hash, output_index);

CREATE VIEW address_asset_flow AS
SELECT address_key, tx_hash, output_index, policy_id, asset_name, quantity,
       is_lovelace, block_number, slot, epoch, 'received' AS direction
FROM address_utxo_amounts
UNION ALL
SELECT o.address_key, i.spending_tx_hash, i.referenced_output_index,
       a.policy_id, a.asset_name, a.quantity, a.is_lovelace,
       i.block_number, i.slot, i.epoch, 'spent'
FROM transaction_inputs i
JOIN transaction_outputs o
  ON o.tx_hash = i.referenced_tx_hash AND o.output_index = i.referenced_output_index
JOIN transaction_output_amounts a
  ON a.tx_hash = o.tx_hash AND a.output_index = o.output_index
WHERE i.consumes_output = 1;
