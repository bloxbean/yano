# Historical archive

Enable Yano's DuckLake archive and query its history with DuckDB.

Canonical URL: https://getyano.dev/node/history/

Yano can project finalized chain history into **DuckLake** for analytics, reporting, and application-specific indexes. DuckLake keeps its metadata in SQLite and its table data in Parquet, so you can query the archive directly with DuckDB without adding another database service.

The archive is optional and runs outside authoritative block application. Yano first records a canonical projection outbox in RocksDB, then drains eligible data into DuckLake after the configured finality gate.

## Enable the archive at fresh sync

From a JVM distribution, start a new node with the `projection` profile:

```bash
./yano.sh start:preprod,projection
```

History is disabled by default and is not supported by the native distribution. Enable it with empty node storage so Yano can project from genesis. Turning it on for an already populated ordinary node does not backfill the missing past.

The `wallet` profile enables wallet indexes but does not enable the archive. Use `projection` when you need historical data.

Block sections cover transactions, UTxO history, account events, and address transactions. Section selection becomes part of the archive identity and cannot be changed retroactively on a populated archive. Epoch artifacts cover rewards, epoch stake, DRep distribution, Ada pots, and governance proposal status. Newly enabled epoch artifacts collect data prospectively from their enrollment point.

## Find the history files

The packaged profile writes to `./history` by default. Set `YANO_HISTORY_DIR` before starting Yano to use another location:

```bash
export YANO_HISTORY_DIR=/var/lib/yano/history
./yano.sh start:preprod,projection
```

The directory contains:

```text
history/
├── ducklake-catalog.sqlite
├── ducklake-catalog.sqlite.tx-locator.sqlite
├── ducklake-data/
└── tmp/
```

`ducklake-catalog.sqlite` holds DuckLake metadata, while `ducklake-data/` contains the managed Parquet data files. The transaction locator is a rebuildable Yano sidecar. It is not the history database.

## Connect with DuckDB

Install the [DuckDB CLI](https://duckdb.org/docs/stable/clients/cli/overview.html), start an in-memory session, and install the DuckLake and SQLite extensions. `INSTALL` downloads each extension once for the installed DuckDB version; later sessions only need `LOAD`.

```bash
duckdb
```

```sql
INSTALL ducklake;
INSTALL sqlite;
LOAD ducklake;
LOAD sqlite;
```

Attach the Yano archive in read-only mode. Replace both paths with absolute paths to the same history directory:

```sql
ATTACH
  'ducklake:sqlite:/var/lib/yano/history/ducklake-catalog.sqlite'
AS history_lake (
  DATA_PATH '/var/lib/yano/history/ducklake-data',
  DATA_INLINING_ROW_LIMIT 0,
  READ_ONLY
);
```

`READ_ONLY` keeps Yano as the only writer. A separate DuckDB process can query the archive while projection continues; a query may briefly wait if SQLite is committing catalog metadata.

Do not use `read_parquet('ducklake-data/**/*.parquet')`. DuckLake uses its catalog to select the files that belong to a snapshot and to exclude obsolete files. Query through `history_lake` instead.

## Discover tables and coverage

List the relations that are present and inspect their columns:

```sql
SHOW TABLES FROM history_lake;
DESCRIBE history_lake.transactions;
DESCRIBE history_lake.unspent_outputs;
```

The configured projection sections determine which data tables exist. Common physical tables and derived views include:

| Data                                 | Relation                       |
| ------------------------------------ | ------------------------------ |
| Transactions                         | `transactions`                 |
| Output creation and spending         | `output_lifecycle`             |
| Current unspent outputs              | `unspent_outputs`              |
| Address asset movement               | `address_asset_flow`           |
| Stake and delegation events          | `account_events`               |
| Address participation by transaction | `address_transactions`         |
| Epoch rewards                        | `rewards`                      |
| Epoch stake                          | `epoch_stakes`                 |
| DRep distribution                    | `drep_distributions`           |
| Ada pots                             | `ada_pots`                     |
| Governance proposal status           | `governance_proposal_statuses` |

Check the last committed block before treating query results as complete:

```sql
SELECT
  last_block,
  last_slot,
  lower(hex(last_block_hash)) AS last_block_hash,
  committed_at
FROM history_lake.projection_receipts
ORDER BY last_block DESC
LIMIT 1;
```

The archive normally trails the node tip because it projects only data eligible under its finality policy. Epoch datasets also have their own enrollment and coverage records. Inspect `projection_artifact_enrollment`, `projection_epoch_coverage`, and `projection_epoch_gap_interval` before assuming every historical epoch is present.

## Query chain history

Hashes and raw credentials are stored as blobs. Use `lower(hex(column))` for their familiar hexadecimal form. Block times are Unix timestamps in seconds, and monetary amounts are lovelace.

Find the latest archived transactions:

```sql
SELECT
  lower(hex(tx_hash)) AS tx_hash,
  block_number,
  slot,
  epoch,
  to_timestamp(block_time) AS block_time,
  valid,
  fee
FROM history_lake.transactions
ORDER BY block_number DESC, tx_index DESC
LIMIT 20;
```

Find unspent outputs at an address as of the attached archive snapshot:

```sql
SELECT
  lower(hex(tx_hash)) AS tx_hash,
  output_index,
  lovelace,
  block_number,
  slot
FROM history_lake.unspent_outputs
WHERE address = 'addr1...'
ORDER BY block_number DESC, output_index;
```

Follow received and spent assets for an address:

```sql
SELECT
  direction,
  lower(hex(tx_hash)) AS tx_hash,
  output_index,
  quantity,
  is_lovelace,
  lower(hex(policy_id)) AS policy_id,
  lower(hex(asset_name)) AS asset_name,
  block_number,
  slot
FROM history_lake.address_asset_flow
WHERE address = 'addr1...'
ORDER BY block_number, tx_hash, output_index;
```

Read reward history for a stake address when the `reward:v1` epoch artifact is enabled:

```sql
SELECT
  epoch,
  reward_type,
  amount,
  spendable_epoch,
  lower(hex(pool_hash)) AS pool_hash
FROM history_lake.rewards
WHERE stake_address = 'stake1...'
ORDER BY epoch DESC, reward_type;
```

Add `epoch`, `block_number`, or `slot` predicates to large queries. Historical tables with an `epoch` column are partitioned by epoch, so bounded queries scan less data.

## Pin a consistent snapshot

A normal read-only attachment follows the archive as Yano commits new projection batches. For a multi-query report that must use one immutable view, record the current snapshot and reattach at that version:

```sql
SELECT id FROM history_lake.current_snapshot();
-- Suppose the returned snapshot ID is 42.

DETACH history_lake;

ATTACH
  'ducklake:sqlite:/var/lib/yano/history/ducklake-catalog.sqlite'
AS history_lake (
  DATA_PATH '/var/lib/yano/history/ducklake-data',
  DATA_INLINING_ROW_LIMIT 0,
  SNAPSHOT_VERSION 42,
  READ_ONLY
);
```

Retained snapshots are finite; the packaged profile keeps them for 168 hours by default. Finish long-running analysis before its pinned snapshot is cleaned up, or increase `YANO_PROJECTION_SNAPSHOT_RETENTION_HOURS` before starting the node.

## Export query results

DuckDB can materialize a bounded result without modifying the Yano archive:

```sql
COPY (
  SELECT *
  FROM history_lake.transactions
  WHERE epoch BETWEEN 500 AND 510
)
TO 'transactions-500-510.parquet' (FORMAT PARQUET);
```

Use `FORMAT CSV, HEADER` instead when a downstream tool needs CSV. Detach when the session is complete:

```sql
DETACH history_lake;
```

For the complete relation and column contract, see the [DuckLake projection schema](https://github.com/bloxbean/yano/blob/main/docs/archive/DUCKLAKE_PROJECTION_SCHEMA.md). For archive selection, retention, maintenance, and disk thresholds, see the [configuration catalog](/reference/configuration-catalog/) and Yano's `/q/openapi-history` endpoint.

When moving or backing up an archive, keep the catalog and `ducklake-data/` together from the same consistent point. A catalog alone contains metadata, not the historical rows.
