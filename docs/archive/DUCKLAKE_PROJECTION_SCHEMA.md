# DuckLake Projection Schema

This document is the column-level schema reference for Yano's DuckLake projection archive.
It describes the relations exposed under the attached DuckLake catalog, normally queried as
`history_lake.<relation>`.

For the end-to-end producer, staging, outbox, projection, acknowledgement, and recovery flow, see
[Archive Projection: End-to-End Design and Recovery](ARCHIVE_PROJECTION_DESIGN.md).

The schema is defined by `ArchiveSchemas`, `DuckLakeProjectionSchema`, and
`DuckLakeInitializer`. When every projection is selected, the catalog exposes:

- 13 physical history tables;
- 6 derived read-model views; and
- 8 projection identity, receipt, and coverage tables.

Epoch-artifact tables are created only when their corresponding artifact is selected. Block
history tables, projection control tables, and derived views are installed during projection
initialization. The transaction-locator sidecar is an implementation detail in a separate SQLite
file and is not part of this DuckLake schema.

## Conventions

- Hashes, credentials, addresses in raw form, scripts, datums, and redeemers use `BLOB`.
  Display a hash or credential with `lower(hex(column_name))`.
- Monetary values use lovelace unless the description says otherwise. One ADA is 1,000,000
  lovelace.
- `block_time` and `boundary_block_time` are Unix timestamps in seconds.
- `epoch` is the semantic epoch for epoch artifacts and the containing block's epoch for block
  projections. In `rewards`, it specifically means the epoch in which the reward was earned.
- `archive_job_id` identifies the deterministic projection job that wrote a row.
- A logical row key identifies a row in Yano's schema contract and stable pagination order. The
  current DuckLake DDL does not declare these keys as database `PRIMARY KEY` constraints;
  exactly-once batch receipts and deterministic replay enforce projection idempotency.
- A nullable block coordinate on `transaction_outputs` permits genesis/bootstrap outputs that do
  not belong to a normal block.
- Physical history tables that contain `epoch` are partitioned by that column.

## Projection-to-table map

| Projection selector | Source | Physical tables |
| --- | --- | --- |
| `transaction:v1` | Block | `chain_transaction` |
| `account-events:v1` | Block | `account_events` |
| `address-transaction:v1` | Block | `address_transactions` |
| `utxo-history:v1` | Block and genesis | `transaction_outputs`, `transaction_output_assets`, `transaction_inputs`, `transaction_datums`, `transaction_redeemers` |
| `reward:v1` | Epoch boundary | `rewards` |
| `epoch-stake:v1` | Epoch boundary | `epoch_stakes` |
| `drep-distribution:v1` | Epoch boundary | `drep_distributions` |
| `ada-pot:v1` | Epoch boundary | `ada_pots` |
| `governance-proposal-status:v1` | Epoch boundary | `governance_proposal_statuses` |

## Physical history tables

### `chain_transaction`

One row per transaction. Logical row key: `tx_hash`.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `tx_hash` | `BLOB` | No | Cardano transaction hash. |
| `block_hash` | `BLOB` | No | Hash of the block containing the transaction. |
| `block_number` | `BIGINT` | No | Number of the block containing the transaction. |
| `slot` | `BIGINT` | No | Absolute Cardano slot of the containing block. |
| `epoch` | `BIGINT` | No | Epoch containing the transaction. |
| `block_time` | `BIGINT` | No | Containing block timestamp as Unix seconds. |
| `tx_index` | `INTEGER` | No | Transaction position within the block. |
| `valid` | `BOOLEAN` | No | Whether the transaction passed phase-two/script validation. |
| `fee` | `BIGINT` | Yes | Transaction fee in lovelace when available. |
| `archive_job_id` | `UUID` | No | Projection job that archived the transaction. |

### `account_events`

One row per stake-account, delegation, withdrawal, pool, or governance account event. Logical row
key: `stake_credential`, `slot`, `tx_index`, `event_index`, `event_type`, `tx_hash`.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `stake_credential` | `BLOB` | No | Raw 28-byte stake credential affected by the event. |
| `stake_credential_type` | `VARCHAR` | No | Stake credential type, normally `key` or `script`. |
| `stake_address` | `VARCHAR` | No | Bech32 stake address derived from the credential and network. |
| `event_type` | `VARCHAR` | No | Stable event classification, such as registration, delegation, or withdrawal. |
| `tx_hash` | `BLOB` | No | Hash of the transaction containing the event. |
| `block_hash` | `BLOB` | No | Hash of the block containing the transaction. |
| `block_number` | `BIGINT` | No | Number of the block containing the transaction. |
| `slot` | `BIGINT` | No | Slot of the block containing the transaction. |
| `epoch` | `BIGINT` | No | Epoch containing the transaction. |
| `block_time` | `BIGINT` | No | Containing block timestamp as Unix seconds. |
| `tx_index` | `INTEGER` | No | Transaction position within the block. |
| `event_index` | `BIGINT` | No | Stable event ordinal within the transaction. |
| `pool_hash` | `BLOB` | Yes | Pool credential when the event concerns a stake pool. |
| `drep_type` | `VARCHAR` | Yes | DRep category when the event concerns governance delegation. |
| `drep_credential` | `BLOB` | Yes | Raw DRep credential when one applies. |
| `amount` | `BIGINT` | Yes | Event amount in lovelace when the event carries an amount. |
| `archive_job_id` | `UUID` | No | Projection job that archived the event. |

### `address_transactions`

One participation summary per subject and transaction. Logical row key: `subject_type`,
`subject_key`, `tx_hash`.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `subject_type` | `VARCHAR` | No | Subject scope: `address`, `payment_credential`, or `stake_credential`. |
| `subject_key` | `BLOB` | No | Blake2b-256 address key or raw 28-byte payment/stake credential, depending on `subject_type`. |
| `address` | `VARCHAR` | Yes | Exact display address for an `address` subject; null for credential subjects. |
| `stake_address` | `VARCHAR` | Yes | Bech32 stake address for a `stake_credential` subject. |
| `tx_hash` | `BLOB` | No | Hash of the participating transaction. |
| `block_hash` | `BLOB` | No | Hash of the block containing the transaction. |
| `block_number` | `BIGINT` | No | Number of the block containing the transaction. |
| `slot` | `BIGINT` | No | Slot of the block containing the transaction. |
| `epoch` | `BIGINT` | No | Epoch containing the transaction. |
| `block_time` | `BIGINT` | No | Containing block timestamp as Unix seconds. |
| `tx_index` | `INTEGER` | No | Transaction position within the block. |
| `input_count` | `INTEGER` | No | Number of regular inputs belonging to this subject. |
| `output_count` | `INTEGER` | No | Number of regular outputs belonging to this subject. |
| `collateral_input_count` | `INTEGER` | No | Number of collateral inputs belonging to this subject. |
| `collateral_return_count` | `INTEGER` | No | Number of collateral-return outputs belonging to this subject. |
| `archive_job_id` | `UUID` | No | Projection job that archived the participation summary. |

For `subject_type = 'address'`, `subject_key` is
`Blake2b-256(canonical_address_bytes)`. For credential subjects, `subject_key` is the raw
credential. The four count columns count UTxO occurrences, not lovelace or native-asset amounts.
Reference inputs are not included in those counts.

### `transaction_outputs`

One row per transaction output. Logical row key: `tx_hash`, `output_index`.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `tx_hash` | `BLOB` | No | Hash of the transaction that created the output. |
| `output_index` | `INTEGER` | No | Output index within the creating transaction. |
| `tx_index` | `INTEGER` | No | Creating transaction's position within its block. |
| `origin_type` | `VARCHAR` | No | Output provenance, such as a normal transaction or genesis/bootstrap distribution. |
| `address` | `VARCHAR` | No | Destination address in canonical display form. |
| `network_id` | `INTEGER` | Yes | Decoded address network identifier when the address form carries one. |
| `address_type` | `VARCHAR` | No | Decoded Cardano address category. |
| `payment_credential_type` | `VARCHAR` | Yes | Payment credential type, normally `key` or `script`. |
| `payment_credential` | `BLOB` | Yes | Raw payment credential when present. |
| `stake_address` | `VARCHAR` | Yes | Bech32 stake address derived from the stake credential when present. |
| `stake_credential_type` | `VARCHAR` | Yes | Stake credential type, normally `key` or `script`. |
| `stake_credential` | `BLOB` | Yes | Raw stake credential when present. |
| `lovelace` | `BIGINT` | No | Base-currency amount held by the output. |
| `datum_kind` | `VARCHAR` | No | Datum representation, such as none, hash-only, or inline. |
| `datum_hash` | `BLOB` | Yes | Datum hash when one is attached to the output. |
| `inline_datum_cbor` | `BLOB` | Yes | Raw inline-datum CBOR when present. |
| `reference_script_hash` | `BLOB` | Yes | Hash of the attached reference script when present. |
| `reference_script_type` | `VARCHAR` | Yes | Language/type of the attached reference script. |
| `reference_script_cbor` | `BLOB` | Yes | Raw reference-script CBOR when present. |
| `is_collateral_return` | `BOOLEAN` | No | Whether the output is a collateral-return output. |
| `block_hash` | `BLOB` | Yes | Hash of the creating block; null for genesis/bootstrap outputs. |
| `block_number` | `BIGINT` | Yes | Number of the creating block; null for genesis/bootstrap outputs. |
| `slot` | `BIGINT` | Yes | Creating slot; null for genesis/bootstrap outputs. |
| `epoch` | `BIGINT` | Yes | Creating epoch; null for genesis/bootstrap outputs. |
| `block_time` | `BIGINT` | Yes | Creating block timestamp; null for genesis/bootstrap outputs. |
| `archive_job_id` | `UUID` | No | Projection job that archived the output. |

### `transaction_output_assets`

One row per native asset held by an output. Logical row key: `tx_hash`, `output_index`, `policy_id`,
`asset_name`.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `tx_hash` | `BLOB` | No | Hash of the transaction that created the output. |
| `output_index` | `INTEGER` | No | Output index within the creating transaction. |
| `policy_id` | `BLOB` | No | Native-asset policy ID. |
| `asset_name` | `BLOB` | No | Raw native-asset name bytes. |
| `quantity` | `DECIMAL(38,0)` | No | Quantity of the native asset held by the output. |
| `block_number` | `BIGINT` | Yes | Number of the creating block; null for genesis/bootstrap outputs. |
| `slot` | `BIGINT` | Yes | Creating slot; null for genesis/bootstrap outputs. |
| `epoch` | `BIGINT` | Yes | Creating epoch; null for genesis/bootstrap outputs. |
| `archive_job_id` | `UUID` | No | Projection job that archived the asset row. |

### `transaction_inputs`

One row per regular, reference, or collateral input. Logical row key: `spending_tx_hash`,
`input_role`, `input_index`.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `spending_tx_hash` | `BLOB` | No | Hash of the transaction containing the input. |
| `spending_tx_index` | `INTEGER` | No | Spending transaction's position within its block. |
| `input_index` | `INTEGER` | No | Position within the role-specific input collection. |
| `input_role` | `VARCHAR` | No | Input classification, such as regular, reference, or collateral. |
| `referenced_tx_hash` | `BLOB` | No | Hash of the transaction that created the referenced output. |
| `referenced_output_index` | `INTEGER` | No | Index of the referenced output. |
| `consumes_output` | `BOOLEAN` | No | Whether the input consumes the output; false for reference inputs. |
| `block_hash` | `BLOB` | No | Hash of the block containing the spending transaction. |
| `block_number` | `BIGINT` | No | Number of the block containing the spending transaction. |
| `slot` | `BIGINT` | No | Slot of the block containing the spending transaction. |
| `epoch` | `BIGINT` | No | Epoch containing the spending transaction. |
| `block_time` | `BIGINT` | No | Spending block timestamp as Unix seconds. |
| `archive_job_id` | `UUID` | No | Projection job that archived the input. |

### `transaction_datums`

One row per transaction witness datum. Logical row key: `tx_hash`, `datum_hash`.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `tx_hash` | `BLOB` | No | Hash of the transaction carrying the datum. |
| `tx_index` | `INTEGER` | No | Transaction position within the block. |
| `datum_hash` | `BLOB` | No | Hash of the datum. |
| `datum_cbor` | `BLOB` | No | Raw datum CBOR. |
| `block_hash` | `BLOB` | No | Hash of the block containing the transaction. |
| `block_number` | `BIGINT` | No | Number of the block containing the transaction. |
| `slot` | `BIGINT` | No | Slot of the block containing the transaction. |
| `epoch` | `BIGINT` | No | Epoch containing the transaction. |
| `block_time` | `BIGINT` | No | Containing block timestamp as Unix seconds. |
| `archive_job_id` | `UUID` | No | Projection job that archived the datum. |

### `transaction_redeemers`

One row per transaction script redeemer. Logical row key: `tx_hash`, `purpose`, `redeemer_index`.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `tx_hash` | `BLOB` | No | Hash of the transaction carrying the redeemer. |
| `tx_index` | `INTEGER` | No | Transaction position within the block. |
| `purpose` | `VARCHAR` | No | Redeemer purpose, such as spend, mint, certificate, or withdrawal. |
| `redeemer_index` | `INTEGER` | No | Redeemer index within its purpose. |
| `redeemer_cbor` | `BLOB` | No | Raw redeemer CBOR. |
| `redeemer_data_hash` | `BLOB` | Yes | Hash of the redeemer data when available. |
| `execution_mem` | `DECIMAL(38,0)` | No | Memory execution units. |
| `execution_steps` | `DECIMAL(38,0)` | No | CPU execution steps. |
| `block_hash` | `BLOB` | No | Hash of the block containing the transaction. |
| `block_number` | `BIGINT` | No | Number of the block containing the transaction. |
| `slot` | `BIGINT` | No | Slot of the block containing the transaction. |
| `epoch` | `BIGINT` | No | Epoch containing the transaction. |
| `block_time` | `BIGINT` | No | Containing block timestamp as Unix seconds. |
| `archive_job_id` | `UUID` | No | Projection job that archived the redeemer. |

### `rewards`

One row per reward component. Logical row key: `stake_credential`, `epoch`, `reward_type`,
`source_id`.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `stake_credential` | `BLOB` | No | Raw 28-byte stake credential that earned the reward. |
| `stake_credential_type` | `VARCHAR` | No | Stake credential type, normally `key` or `script`. |
| `stake_address` | `VARCHAR` | No | Bech32 reward/stake address. |
| `pool_hash` | `BLOB` | Yes | Pool responsible for the reward when applicable. |
| `reward_type` | `VARCHAR` | No | Reward classification, such as leader, member, MIR, or refund. |
| `epoch` | `BIGINT` | No | Epoch in which the reward was earned. |
| `spendable_epoch` | `BIGINT` | No | Epoch in which the reward became spendable. |
| `amount` | `BIGINT` | No | Reward amount in lovelace. |
| `source_id` | `VARCHAR` | No | Stable identity distinguishing the source of otherwise similar reward components. |
| `boundary_block_hash` | `BLOB` | No | Canonical boundary block used to capture the reward. |
| `boundary_block_number` | `BIGINT` | No | Number of the canonical boundary block. |
| `boundary_slot` | `BIGINT` | No | Slot of the canonical boundary block. |
| `boundary_block_time` | `BIGINT` | No | Canonical boundary block timestamp as Unix seconds. |
| `archive_job_id` | `UUID` | No | Epoch projection job that archived the reward. |

### `epoch_stakes`

One active-stake snapshot row per epoch and stake credential. Logical row key: `epoch`,
`stake_credential`.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `epoch` | `BIGINT` | No | Semantic epoch represented by the stake snapshot. |
| `stake_credential_type` | `VARCHAR` | No | Stake credential type, normally `key` or `script`. |
| `stake_credential` | `BLOB` | No | Raw 28-byte stake credential. |
| `stake_address` | `VARCHAR` | No | Bech32 stake address. |
| `pool_hash` | `BLOB` | Yes | Delegated pool credential when the stake is delegated. |
| `amount` | `BIGINT` | No | Active stake amount in lovelace. |
| `boundary_block_hash` | `BLOB` | No | Canonical boundary block for the snapshot. |
| `boundary_block_number` | `BIGINT` | No | Number of the canonical boundary block. |
| `boundary_slot` | `BIGINT` | No | Slot of the canonical boundary block. |
| `boundary_block_time` | `BIGINT` | No | Canonical boundary block timestamp as Unix seconds. |
| `source_state_version` | `VARCHAR` | No | Ledger-state representation/version used to produce the snapshot. |
| `archive_job_id` | `UUID` | No | Epoch projection job that archived the snapshot. |

### `drep_distributions`

One DRep voting-power snapshot row per epoch, DRep type, and credential. Logical row key: `epoch`,
`drep_type`, `drep_credential`.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `epoch` | `BIGINT` | No | Semantic epoch represented by the DRep snapshot. |
| `drep_type` | `VARCHAR` | No | DRep category, including credential-backed and special DRep types. |
| `drep_credential` | `BLOB` | Yes | Raw DRep credential; null for special DRep identities. |
| `amount` | `BIGINT` | No | Voting power delegated to the DRep in lovelace. |
| `stored_expiry` | `BIGINT` | Yes | Expiry epoch stored in DRep ledger state. |
| `dormant_epochs` | `BIGINT` | No | Governance dormant-epoch adjustment at capture time. |
| `effective_expiry` | `BIGINT` | Yes | Expiry epoch after applying the dormant-epoch adjustment. |
| `active` | `BOOLEAN` | No | Whether the DRep is active for the snapshot epoch. |
| `boundary_block_hash` | `BLOB` | No | Canonical boundary block for the snapshot. |
| `boundary_block_number` | `BIGINT` | No | Number of the canonical boundary block. |
| `boundary_slot` | `BIGINT` | No | Slot of the canonical boundary block. |
| `boundary_block_time` | `BIGINT` | No | Canonical boundary block timestamp as Unix seconds. |
| `source_state_version` | `VARCHAR` | No | Ledger-state representation/version used to produce the snapshot. |
| `archive_job_id` | `UUID` | No | Epoch projection job that archived the snapshot. |

### `ada_pots`

One network Ada-pot snapshot per epoch. Logical row key: `epoch`.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `epoch` | `BIGINT` | No | Semantic epoch represented by the pot snapshot. |
| `treasury` | `BIGINT` | No | Treasury balance in lovelace. |
| `reserves` | `BIGINT` | No | Reserves balance in lovelace. |
| `deposits` | `BIGINT` | No | Deposit pot balance in lovelace. |
| `fees` | `BIGINT` | No | Fee pot balance in lovelace. |
| `distributed` | `BIGINT` | No | Reward amount distributed at the boundary. |
| `undistributed` | `BIGINT` | No | Calculated reward amount not distributed. |
| `rewards_pot` | `BIGINT` | No | Total calculated rewards pot. |
| `pool_rewards_pot` | `BIGINT` | No | Portion of the rewards pot allocated through stake pools. |
| `boundary_block_hash` | `BLOB` | No | Canonical boundary block for the snapshot. |
| `boundary_block_number` | `BIGINT` | No | Number of the canonical boundary block. |
| `boundary_slot` | `BIGINT` | No | Slot of the canonical boundary block. |
| `boundary_block_time` | `BIGINT` | No | Canonical boundary block timestamp as Unix seconds. |
| `source_state_version` | `VARCHAR` | No | Ledger-state representation/version used to produce the snapshot. |
| `archive_job_id` | `UUID` | No | Epoch projection job that archived the snapshot. |

### `governance_proposal_statuses`

One governance-action status observation per epoch and phase. Logical row key: `epoch`, `tx_hash`,
`governance_action_index`, `observation_phase`.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `epoch` | `BIGINT` | No | Epoch in which the status was observed. |
| `tx_hash` | `BLOB` | No | Hash of the transaction that submitted the governance action. |
| `governance_action_index` | `INTEGER` | No | Governance-action index within the submitting transaction. |
| `action_type` | `VARCHAR` | No | Governance-action category. |
| `observation_phase` | `VARCHAR` | No | Processing phase in which the status was observed. |
| `status_code` | `VARCHAR` | No | Status or decision recorded during that phase. |
| `decision_reason` | `VARCHAR` | Yes | Additional reason for the recorded status when available. |
| `deposit` | `BIGINT` | No | Governance-action deposit in lovelace. |
| `return_address` | `BLOB` | No | Raw reward address designated to receive the returned deposit. |
| `submitted_epoch` | `BIGINT` | No | Epoch in which the governance action was submitted. |
| `expires_after_epoch` | `BIGINT` | No | Epoch after which the governance action expires. |
| `boundary_block_hash` | `BLOB` | No | Canonical boundary block for the observation. |
| `boundary_block_number` | `BIGINT` | No | Number of the canonical boundary block. |
| `boundary_slot` | `BIGINT` | No | Slot of the canonical boundary block. |
| `boundary_block_time` | `BIGINT` | No | Canonical boundary block timestamp as Unix seconds. |
| `source_state_version` | `VARCHAR` | No | Governance-state representation/version used for the observation. |
| `archive_job_id` | `UUID` | No | Epoch projection job that archived the observation. |

## Derived read-model views

Views do not own data or projection receipts. They are stable query shapes derived from the
physical UTxO-history tables. DuckDB's `information_schema` conservatively reports every view
column as nullable; the `Nullable` values below describe the logical nullability inherited from
the source expressions. Query consumers should still handle nulls defensively at a view boundary.

### `transactions`

Compatibility alias for `chain_transaction`.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `tx_hash` | `BLOB` | No | Cardano transaction hash. |
| `block_hash` | `BLOB` | No | Hash of the block containing the transaction. |
| `block_number` | `BIGINT` | No | Number of the block containing the transaction. |
| `slot` | `BIGINT` | No | Absolute Cardano slot of the containing block. |
| `epoch` | `BIGINT` | No | Epoch containing the transaction. |
| `block_time` | `BIGINT` | No | Containing block timestamp as Unix seconds. |
| `tx_index` | `INTEGER` | No | Transaction position within the block. |
| `valid` | `BOOLEAN` | No | Whether the transaction passed phase-two/script validation. |
| `fee` | `BIGINT` | Yes | Transaction fee in lovelace when available. |
| `archive_job_id` | `UUID` | No | Projection job that archived the transaction. |

### `transaction_output_amounts`

Uniform amount stream combining the lovelace and native assets held by every output.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `tx_hash` | `BLOB` | No | Hash of the transaction that created the output. |
| `output_index` | `INTEGER` | No | Output index within the creating transaction. |
| `policy_id` | `BLOB` | No | Empty bytes for lovelace; native-asset policy ID otherwise. |
| `asset_name` | `BLOB` | No | Empty bytes for lovelace; raw native-asset name otherwise. |
| `quantity` | `DECIMAL(38,0)` | No | Lovelace or native-asset quantity. |
| `is_lovelace` | `BOOLEAN` | No | Whether the row represents the output's lovelace amount. |

### `output_lifecycle`

Every projected output joined to the regular or collateral input that consumes it. The view has
the complete `transaction_outputs` shape plus three spending columns.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `tx_hash` | `BLOB` | No | Hash of the transaction that created the output. |
| `output_index` | `INTEGER` | No | Output index within the creating transaction. |
| `tx_index` | `INTEGER` | No | Creating transaction's position within its block. |
| `origin_type` | `VARCHAR` | No | Output provenance, such as a normal transaction or genesis/bootstrap distribution. |
| `address` | `VARCHAR` | No | Destination address in canonical display form. |
| `network_id` | `INTEGER` | Yes | Decoded address network identifier when available. |
| `address_type` | `VARCHAR` | No | Decoded Cardano address category. |
| `payment_credential_type` | `VARCHAR` | Yes | Payment credential type, normally `key` or `script`. |
| `payment_credential` | `BLOB` | Yes | Raw payment credential when present. |
| `stake_address` | `VARCHAR` | Yes | Bech32 stake address when present. |
| `stake_credential_type` | `VARCHAR` | Yes | Stake credential type, normally `key` or `script`. |
| `stake_credential` | `BLOB` | Yes | Raw stake credential when present. |
| `lovelace` | `BIGINT` | No | Base-currency amount held by the output. |
| `datum_kind` | `VARCHAR` | No | Datum representation, such as none, hash-only, or inline. |
| `datum_hash` | `BLOB` | Yes | Datum hash when one is attached to the output. |
| `inline_datum_cbor` | `BLOB` | Yes | Raw inline-datum CBOR when present. |
| `reference_script_hash` | `BLOB` | Yes | Hash of the attached reference script when present. |
| `reference_script_type` | `VARCHAR` | Yes | Language/type of the attached reference script. |
| `reference_script_cbor` | `BLOB` | Yes | Raw reference-script CBOR when present. |
| `is_collateral_return` | `BOOLEAN` | No | Whether the output is a collateral-return output. |
| `block_hash` | `BLOB` | Yes | Hash of the creating block; null for genesis/bootstrap outputs. |
| `block_number` | `BIGINT` | Yes | Number of the creating block; null for genesis/bootstrap outputs. |
| `slot` | `BIGINT` | Yes | Creating slot; null for genesis/bootstrap outputs. |
| `epoch` | `BIGINT` | Yes | Creating epoch; null for genesis/bootstrap outputs. |
| `block_time` | `BIGINT` | Yes | Creating block timestamp; null for genesis/bootstrap outputs. |
| `archive_job_id` | `UUID` | No | Projection job that archived the output. |
| `spending_tx_hash` | `BLOB` | Yes | Hash of the transaction that consumed the output; null while unspent. |
| `spent_block_number` | `BIGINT` | Yes | Number of the block where the output was consumed. |
| `spent_slot` | `BIGINT` | Yes | Slot where the output was consumed. |

### `unspent_outputs`

The current UTxO set at the archive's committed coordinate. This is `output_lifecycle` filtered
to rows whose `spending_tx_hash` is null.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `tx_hash` | `BLOB` | No | Hash of the transaction that created the unspent output. |
| `output_index` | `INTEGER` | No | Output index within the creating transaction. |
| `tx_index` | `INTEGER` | No | Creating transaction's position within its block. |
| `origin_type` | `VARCHAR` | No | Output provenance, such as a normal transaction or genesis/bootstrap distribution. |
| `address` | `VARCHAR` | No | Destination address in canonical display form. |
| `network_id` | `INTEGER` | Yes | Decoded address network identifier when available. |
| `address_type` | `VARCHAR` | No | Decoded Cardano address category. |
| `payment_credential_type` | `VARCHAR` | Yes | Payment credential type, normally `key` or `script`. |
| `payment_credential` | `BLOB` | Yes | Raw payment credential when present. |
| `stake_address` | `VARCHAR` | Yes | Bech32 stake address when present. |
| `stake_credential_type` | `VARCHAR` | Yes | Stake credential type, normally `key` or `script`. |
| `stake_credential` | `BLOB` | Yes | Raw stake credential when present. |
| `lovelace` | `BIGINT` | No | Base-currency amount held by the unspent output. |
| `datum_kind` | `VARCHAR` | No | Datum representation, such as none, hash-only, or inline. |
| `datum_hash` | `BLOB` | Yes | Datum hash when one is attached to the output. |
| `inline_datum_cbor` | `BLOB` | Yes | Raw inline-datum CBOR when present. |
| `reference_script_hash` | `BLOB` | Yes | Hash of the attached reference script when present. |
| `reference_script_type` | `VARCHAR` | Yes | Language/type of the attached reference script. |
| `reference_script_cbor` | `BLOB` | Yes | Raw reference-script CBOR when present. |
| `is_collateral_return` | `BOOLEAN` | No | Whether the output is a collateral-return output. |
| `block_hash` | `BLOB` | Yes | Hash of the creating block; null for genesis/bootstrap outputs. |
| `block_number` | `BIGINT` | Yes | Number of the creating block; null for genesis/bootstrap outputs. |
| `slot` | `BIGINT` | Yes | Creating slot; null for genesis/bootstrap outputs. |
| `epoch` | `BIGINT` | Yes | Creating epoch; null for genesis/bootstrap outputs. |
| `block_time` | `BIGINT` | Yes | Creating block timestamp; null for genesis/bootstrap outputs. |
| `archive_job_id` | `UUID` | No | Projection job that archived the output. |
| `spending_tx_hash` | `BLOB` | Yes | Always null in this view because the output is unspent. |
| `spent_block_number` | `BIGINT` | Yes | Always null in this view because the output is unspent. |
| `spent_slot` | `BIGINT` | Yes | Always null in this view because the output is unspent. |

The view is current only through the projection sink coordinate, which may trail the chain tip
while a backlog is draining.

### `address_utxo_amounts`

Every output flattened into one lovelace or native-asset row per amount.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `address` | `VARCHAR` | No | Address that received the output. |
| `stake_address` | `VARCHAR` | Yes | Derived Bech32 stake address when present. |
| `payment_credential` | `BLOB` | Yes | Raw payment credential when present. |
| `stake_credential` | `BLOB` | Yes | Raw stake credential when present. |
| `tx_hash` | `BLOB` | No | Hash of the transaction that created the output. |
| `output_index` | `INTEGER` | No | Output index within the creating transaction. |
| `policy_id` | `BLOB` | No | Empty bytes for lovelace; native-asset policy ID otherwise. |
| `asset_name` | `BLOB` | No | Empty bytes for lovelace; raw native-asset name otherwise. |
| `quantity` | `DECIMAL(38,0)` | No | Lovelace or native-asset quantity. |
| `is_lovelace` | `BOOLEAN` | No | Whether the row represents lovelace. |
| `block_number` | `BIGINT` | Yes | Number of the block that created the output. |
| `slot` | `BIGINT` | Yes | Slot that created the output. |
| `epoch` | `BIGINT` | Yes | Epoch that created the output. |

### `address_asset_flow`

Received and spent lovelace/native-asset movements by address.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `address` | `VARCHAR` | No | Address receiving or spending the amount. |
| `stake_address` | `VARCHAR` | Yes | Derived Bech32 stake address when present. |
| `payment_credential` | `BLOB` | Yes | Raw payment credential when present. |
| `stake_credential` | `BLOB` | Yes | Raw stake credential when present. |
| `tx_hash` | `BLOB` | No | Creating transaction for `received`; spending transaction for `spent`. |
| `output_index` | `INTEGER` | No | Created output index for `received`; referenced output index for `spent`. |
| `policy_id` | `BLOB` | No | Empty bytes for lovelace; native-asset policy ID otherwise. |
| `asset_name` | `BLOB` | No | Empty bytes for lovelace; raw native-asset name otherwise. |
| `quantity` | `DECIMAL(38,0)` | No | Amount received or spent. |
| `is_lovelace` | `BOOLEAN` | No | Whether the movement represents lovelace. |
| `block_number` | `BIGINT` | No | Block where the receive or spend movement occurred. |
| `slot` | `BIGINT` | No | Slot where the receive or spend movement occurred. |
| `epoch` | `BIGINT` | No | Epoch where the receive or spend movement occurred. |
| `direction` | `VARCHAR` | No | Movement direction: `received` or `spent`. |

## Projection identity, receipt, and coverage tables

These relations are part of the projection protocol. Applications may query them for diagnostics,
but must not mutate them directly.

### `archive_identity`

One row identifying the archive and preventing it from being opened against an incompatible
network or schema.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `archive_id` | `UUID` | No | Unique archive identity. |
| `engine` | `VARCHAR` | No | Archive engine identifier, normally `ducklake`. |
| `schema_version` | `INTEGER` | No | Overall archive schema version. |
| `network_magic` | `INTEGER` | No | Cardano network magic. |
| `genesis_hash` | `VARCHAR` | No | Network genesis identity. |
| `created_at` | `TIMESTAMP` | No | Time the archive identity was created. |

### `projection_identity`

One row persisting the installed block-projection identity.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `fingerprint` | `VARCHAR` | No | Stable fingerprint covering network, sink version, and selected block sections. |
| `installed_at` | `TIMESTAMP` | No | Time the projection identity was installed. |

### `projection_receipts`

Exactly-once commit receipt per projected block batch.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `first_block` | `BIGINT` | No | First block in the committed inclusive range. |
| `last_block` | `BIGINT` | No | Last block in the committed inclusive range. |
| `block_count` | `BIGINT` | No | Number of blocks represented by the receipt. |
| `identity_fingerprint` | `VARCHAR` | No | Projection identity that produced the batch. |
| `first_envelope_id` | `VARCHAR` | No | Identity of the first ordered outbox envelope. |
| `last_envelope_id` | `VARCHAR` | No | Identity of the last ordered outbox envelope. |
| `ordered_digest` | `VARCHAR` | No | Digest proving the ordered contents of the batch. |
| `row_counts` | `VARCHAR` | No | Encoded per-table row counts committed by the batch. |
| `committed_at` | `TIMESTAMP` | No | Time the batch and receipt committed atomically. |
| `last_slot` | `BIGINT` | Yes | Canonical slot at the end of the batch; null only for legacy preview receipts. |
| `last_block_hash` | `BLOB` | Yes | Canonical block hash at the end of the batch; null only for legacy preview receipts. |

### `projection_genesis`

At most one exactly-once receipt for projected genesis/bootstrap UTxOs.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `identity` | `VARCHAR` | No | Genesis projection identity. |
| `row_digest` | `VARCHAR` | No | Digest of the projected genesis rows. |
| `row_count` | `BIGINT` | No | Number of projected genesis rows. |
| `total_lovelace` | `VARCHAR` | No | Total projected genesis lovelace as arbitrary-precision text. |
| `committed_at` | `TIMESTAMP` | No | Time the genesis rows and receipt committed atomically. |

### `projection_artifact_identity`

One row persisting the selected epoch-artifact contract set.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `wire_form` | `VARCHAR` | No | Versioned serialized identity of all selected epoch-artifact contracts. |
| `installed_at` | `TIMESTAMP` | No | Time the artifact identity was installed or updated. |

### `projection_artifact_enrollment`

One row per selected epoch dataset recording when honest coverage begins.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `dataset` | `VARCHAR` | No | Epoch dataset identifier, such as `REWARD` or `EPOCH_STAKE`. |
| `projected_from_epoch` | `INTEGER` | Yes | First semantic epoch required for this archive; null when not applicable. |
| `origin` | `VARCHAR` | No | Enrollment origin, such as `FRESH` or `PROSPECTIVE_JOIN`. |
| `installed_at` | `TIMESTAMP` | No | Time the dataset enrollment was installed. |

### `projection_epoch_coverage`

Positive per-dataset, per-epoch completion or failure evidence.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `dataset` | `VARCHAR` | No | Epoch dataset identifier. |
| `semantic_epoch` | `INTEGER` | No | Dataset epoch represented by the outcome. |
| `boundary_block_number` | `BIGINT` | No | Canonical boundary block number associated with the outcome. |
| `boundary_slot` | `BIGINT` | No | Canonical boundary slot associated with the outcome. |
| `boundary_hash` | `BLOB` | No | Canonical boundary block hash associated with the outcome. |
| `outcome` | `VARCHAR` | No | Coverage result, such as `COMPLETE` or a recorded gap/failure outcome. |
| `row_count` | `BIGINT` | Yes | Number of dataset rows produced; zero is a valid complete result. |
| `content_digest` | `VARCHAR` | Yes | Digest of the emitted artifact contents. |
| `failure_class` | `VARCHAR` | Yes | Classified failure type when capture did not complete. |
| `failure_detail` | `VARCHAR` | Yes | Additional failure diagnostic. |
| `recorded_at` | `TIMESTAMP` | No | Time the outcome was persisted. |

### `projection_epoch_gap_interval`

Compressed representation of one or more missing epoch-artifact outcomes.

| Column | Type | Nullable | Description |
| --- | --- | --- | --- |
| `dataset` | `VARCHAR` | No | Epoch dataset containing the gap. |
| `from_epoch` | `INTEGER` | No | First missing semantic epoch in the interval. |
| `through_epoch` | `INTEGER` | No | Last missing semantic epoch currently represented. |
| `from_slot` | `BIGINT` | No | Canonical boundary slot for the first missing epoch. |
| `from_hash` | `BLOB` | No | Canonical boundary hash for the first missing epoch. |
| `through_slot` | `BIGINT` | No | Canonical boundary slot for the last missing epoch. |
| `through_hash` | `BLOB` | No | Canonical boundary hash for the last missing epoch. |
| `is_open` | `BOOLEAN` | No | Whether subsequent failures may extend the interval. |
| `caused_by_epoch` | `INTEGER` | No | Epoch whose failure initiated or propagated the interval. |
| `failure_class` | `VARCHAR` | No | Classified cause of the gap. |
| `recorded_at` | `TIMESTAMP` | No | Time the interval was persisted. |

## Querying the schema

Attach a running Yano archive read-only so the projection remains the only writer:

```sql
ATTACH
  'ducklake:sqlite:/path/to/history/ducklake-catalog.sqlite'
AS history_lake (
  DATA_PATH '/path/to/history/ducklake-data',
  READ_ONLY
);
```

List relations and inspect a relation's current physical schema:

```sql
SHOW TABLES FROM history_lake;
DESCRIBE history_lake.rewards;
```

Use epoch or block-range predicates for large queries, and detach after an interactive session to
release catalog and filesystem resources promptly:

```sql
DETACH history_lake;
```
