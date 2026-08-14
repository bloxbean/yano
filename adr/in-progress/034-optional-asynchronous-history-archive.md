# ADR-034: Optional Asynchronous History Layer with DuckLake or SQLite

## Status

Accepted

## Date

2026-08-14

## Context

Yano is primarily a node and current-ledger-state implementation. Its core sync
path must persist the canonical chain and update authoritative current state,
including the current UTXO set, stake/account state, protocol parameters, and
the bounded rollback state required by those stores.

Historical wallet and analytics APIs introduce a different workload:

- address transaction history can create several query rows per transaction;
- transaction-location history creates one durable row per transaction;
- account events create rows for withdrawals, registrations, delegations, MIRs,
  and future event types;
- reward history can create more than one million rows at a mainnet epoch
  boundary;
- users may enable history after a deployment has already synchronized;
- historical data is normally immutable, whereas the most recent chain remains
  rollbackable.

The account-history foundation already on `main` writes withdrawal,
delegation, registration, and MIR rows to RocksDB from block-apply events. PR
#58 (`feat/node-wallet-apis`) extends that model with address transaction rows,
reward rows, a transaction-status endpoint, and wallet-facing endpoints. The PR
does not add a stored transaction-location index; status is inferred from
retained UTXO outputs.

Keeping unlimited history in the block-apply path has several problems:

1. Core synchronization waits for optional index writes and their RocksDB write
   amplification.
2. Initial synchronization writes old rows into RocksDB even if they will later
   be moved to an immutable archive. Reading, converting, rewriting, and
   deleting those rows can be slower than retaining them.
3. Address input resolution depends on the live UTXO store and its spent-output
   retention. That can silently omit sender history during replay, asynchronous
   apply, or after spent outputs have been pruned.
4. Unlimited RocksDB history and credential-first key layouts make rollback
   increasingly unsafe. The existing `rollbackToSlot` runs a full history-CF
   slot scan unconditionally after applying its delta journal; it is not merely
   a rare fallback. With PR #58's non-pruned address/reward rows, every rollback
   scans an unbounded CF.
5. History retention, rollback-journal retention, current-state retention, and
   block-body retention currently have overlapping configuration meanings.
6. PR #58 buffers a whole epoch of reward history in heap and flushes it in an
   unchunked RocksDB batch without exact reward undo entries. Reward-scale
   batches add substantial heap/native-memory pressure even though reward
   history is not required for ledger correctness or basic wallet operation.

Moving rows from RocksDB to an archive only when pruning does not solve the
sync cost. The rows have already been indexed on the hot path. To isolate core
sync, history must be a separately advancing projection over durable canonical
blocks and durable epoch outputs.

Yano already has an `epoch-export` module and
`ParquetEpochSnapshotExporter`. It uses DuckDB JDBC to write Hive-partitioned
Parquet files for epoch stake, DRep distribution, Ada pots, and proposal
statuses. Yaci Store also has a DuckLake analytics writer, catalog initializer,
and shared DuckDB connection helper. Those implementations provide useful
schema, projection, verification, and operational experience, but the current
export-oriented interface is not a sufficient archive storage contract. Yano's
exporter opens a new `jdbc:duckdb:` in-memory database for each export and does
not configure DuckDB memory, threads, or temporary storage. These `COPY`
operations run synchronously inside epoch-boundary processing, some callers
construct complete in-heap row lists, failures have no durable retry/coverage
record, and rollback deletes raw epoch directories directly.

Yano and this exporter are still preview features. This ADR therefore replaces,
rather than preserves, the old exporter architecture. The useful dataset
semantics and verification fixtures are migrated into the epoch-projection
packages in `archive-core`, but the `epoch-export` module,
`EpochSnapshotExporter` callback SPI,
ServiceLoader provider, direct Parquet writer, and legacy rollback-directory
cleanup are removed when the replacement archive path is introduced.

Directly managed Parquet would require Yano to implement its own catalog,
snapshot generation, file-visibility protocol, schema metadata, active-reader
protection, compaction, orphan cleanup, and time travel. DuckLake provides
these facilities while retaining Parquet as the data format. It supports
persistent SQL tables/views and ACID snapshots, and can store its metadata in a
DuckDB, SQLite, or PostgreSQL catalog database.

This ADR distinguishes two uses of SQLite that must not be conflated:

1. **DuckLake with a SQLite catalog:** DuckLake owns the SQLite catalog schema;
   historical rows remain in DuckLake-managed Parquet files and queries still
   execute through DuckDB.
2. **Standalone SQLite archive backend:** Yano owns ordinary SQLite history
   tables, indexes, views, and migrations; no Parquet or DuckLake is involved.

Both archive backends are part of the initial implementation scope. DuckLake
with a SQLite catalog is the default. Standalone SQLite is intended for
devnets, smaller networks, embedded wallet deployments, and operators who
prefer one relational file and indexed point lookups over columnar analytics.

DuckDB's default `memory_limit` is 80% of physical RAM. The setting primarily
limits DuckDB's buffer manager; some allocations can exist outside it. An
in-process node must therefore set an explicit conservative limit and also
bound concurrency and thread count. Relying on the driver default risks the
history/analytics component competing with the JVM, RocksDB block cache, and
the operating system for most of the host's memory.

ADR-018 proposed Parquet reward export, but retained tx/certificate history in
the synchronous RocksDB event path. This ADR generalizes that archive direction
into a pluggable backend for all optional historical datasets and
supersedes ADR-018's Phase 4 storage, rollback, replay, and retention decisions.
ADR-018 remains authoritative for REST compatibility and DTO behavior unless
this ADR says otherwise.

## Decision

Introduce an optional, asynchronous historical-data subsystem with two storage
zones:

```text
canonical block store / durable epoch sources
                    |
                    v
          independent history worker
                    |
          +---------+------------------+
          |                            |
          v                            v
 recent mutable history         finalized archive
 RocksDB + exact undo       DuckLake/Parquet or SQLite
                    \            /
                     \          /
                    unified query provider
```

The core node reaches and maintains chain tip without waiting for historical
projection. Each dataset has independent durable progress/coverage, readiness,
health, and lag metrics. A history failure makes only the affected history
dataset/endpoints unavailable or stale; it does not make authoritative chain
state unhealthy or stop unaffected datasets.

History is disabled by default. When disabled:

- no history worker is started;
- no DuckLake catalog, Parquet files, SQLite history database, Flyway migration,
  or new history RocksDB column families are created/used;
  legacy history CFs may still be opened when required to open an existing
  RocksDB safely, but remain idle;
- no DuckDB or SQLite history connection is opened and no archive driver is
  initialized;
- block apply does not derive or enqueue per-address, per-account, per-tx, or
  per-reward history rows;
- current account/reward state continues to operate normally.

The initial datasets are:

1. `account_event`
2. `address_transaction`
3. `transaction`
4. `reward`
5. optional `utxo_history`, emitted atomically into `address`,
   `transaction_output`, `transaction_output_asset`, `transaction_input`, and
   optional content-addressed datum/script payload tables
6. `epoch_stake`
7. `drep_distribution`
8. `ada_pot`
9. `governance_proposal_status`

`utxo_history` is disabled independently by default because complete output,
asset, input, datum, and script history is materially larger than the wallet
transaction index. When enabled, it archives all outputs and input references,
including later spend facts; it is not a second authoritative current-UTXO
store. Epoch datasets replace the preview synchronous exporter and can be
enabled independently from wallet-oriented datasets. The archive layer remains
generic so additional governance lifecycle events, pool history, or analytics
can implement the same dataset, commit, retention, and query contracts.

## Archive Backend Decision

Yano implements one backend-neutral history contract with two production
implementations in this ADR:

| Archive engine | Metadata/catalog | Historical rows | Intended profile |
|---|---|---|---|
| `ducklake` (default) | SQLite catalog owned by DuckLake | DuckLake-managed Parquet | full history, analytics, large networks, concurrent local API/reader access |
| `sqlite` | standalone SQLite database owned by Yano | ordinary SQLite tables and indexes | devnet, smaller networks, embedded deployments, index-heavy wallet queries |

The choice is made before an archive is initialized and is recorded with the
network/genesis and archive schema identity. Changing the configured engine for
an existing archive requires an explicit verified migration or a rebuild from
canonical sources; Yano never silently opens one backend's files as another.

DuckLake's catalog database is a separate choice from the archive engine. The
initial implementation supports `sqlite` as the DuckLake catalog and makes it
the default. A future PostgreSQL catalog can implement the same DuckLake adapter
without changing dataset or API contracts. A DuckDB-file catalog is useful for
single-client tests and tools, but is not the production default because it
prevents an independent local DuckDB client from opening the same catalog while
Yano owns it. SQLite catalog locking/retry behavior must still be bounded so a
slow external client cannot block the history writer indefinitely.

Serving APIs from the same Yano process does not by itself require a SQLite
catalog: DuckLake queries still run through DuckDB in either case. SQLite is the
default catalog because it removes the DuckDB-file catalog's single-client
restriction and permits the managed history writer, bounded API readers, and an
operator's local read-only client to coexist. The history API never queries the
SQLite catalog tables directly; it attaches DuckLake through DuckDB and queries
the stable DuckLake views.

DuckLake owns and migrates the schema of its SQLite catalog. Flyway must never
run against that file. The standalone SQLite backend uses a different database
file and its Yano-owned schema is managed exclusively by Flyway, as described
below. Configuration rejects the same resolved path being used for both roles.

## Non-Goals

This ADR does not:

- replace RocksDB for authoritative current ledger state;
- require reward history for a wallet profile;
- make either archive backend part of consensus or ledger correctness;
- guarantee complete history after source block bodies have already been
  pruned;
- provide arbitrary SQL over untrusted user input;
- make normal rollback mutate finalized archive rows one by one;
- retain core UTXO, account-state, or consensus rollback journals forever when
  archive retention is unlimited.

## Core Sync Boundary

Core block apply remains responsible only for canonical chain persistence and
authoritative/rollback-required state. It may publish a lightweight wake-up
signal after a block is durable, but history correctness must not depend on
delivery or ordering of an in-memory event.

The history worker reads blocks by canonical block number from a durable
`ChainBlockReader`-style interface. Before committing work it verifies both:

```text
block.parentHash == historyCursor.blockHash
canonicalHash(block.number) == block.hash
```

An event is only a latency optimization that wakes the worker. On startup,
after a dropped event, and after reconnect, the worker advances by comparing
its durable cursor with canonical storage.

The core commit must never wait for:

- DuckDB startup or a DuckDB query;
- DuckLake/Parquet generation, SQLite writes, Flyway, archive compaction, or
  catalog work;
- history RocksDB writes;
- archive retention or compaction;
- a history REST query.

## Core-Path Non-Regression Contract

This design is accepted only if it preserves current core sync, ledger-state,
rollback, restart, and pruning behavior when history is disabled. History is an
optional downstream consumer, not a new prerequisite of the node kernel.

The implementation must preserve these invariants:

1. **Disabled means absent.** With `yano.history.enabled=false`, Yano does not
   load the DuckDB or SQLite archive driver, run Flyway, create history executors/new column
   families/files, acquire block-body retention leases, subscribe a history
   block listener, or add per-block allocations and writes. Existing legacy
   history CF descriptors may be opened solely because RocksDB requires all
   existing CFs to be opened; they receive no history work. The removed preview
   snapshot-export path is not a separately enabled exception. Configuration
   resolution may read the history flag, but the steady-state block path has no history branch beyond an
   already-existing subsystem/no-op boundary.
2. **Core commit does not enqueue history rows.** Block apply may publish the
   existing post-commit notification or advance canonical storage, but it does
   not build address/reward/account row collections, wait on a queue, or perform
   backpressure on behalf of history.
3. **History cannot veto canonical progress.** DuckDB/DuckLake/SQLite failure,
   archive disk full, corrupt history metadata, worker lag, query overload, or
   a disabled dataset cannot fail block application, authoritative ledger
   rollback, node startup, or core snapshot creation. The history subsystem
   degrades its own health/readiness instead. The sole startup exception is an
   explicitly enabled archive whose own schema/configuration validation fails;
   operators can disable history and start the core node without repair.
4. **No shared mutable connection or batch.** History never writes through the
   core block-apply `WriteBatch` or core RocksDB database, never holds a core
   RocksDB handle/read gate while decoding or accessing an archive backend, and
   never shares a thread/executor needed by chain sync, ledger apply, peer
   handling, or block production.
5. **Authoritative stores retain existing semantics.** Current UTXO, account
   rewards/balances, governance, epoch parameters, and their rollback journals
   remain correct and available with every history dataset disabled.
6. **Core pruning remains correct.** A retention lease can postpone block-body
   pruning only while an enabled worker needs those bodies. It cannot change
   UTXO/account-state retention, delete core data, or reduce a configured core
   rollback window. Removing/disabling history releases its lease safely.
7. **Lifecycle isolation is bounded.** Node shutdown or snapshot restore asks
   history to quiesce for a bounded duration. If it cannot quiesce, Yano closes
   or marks history unavailable according to lifecycle policy without allowing
   stale history handles to corrupt the restored node. Normal startup does not
   wait for history catch-up.

Before merge, run the same core sync/rollback/restart test matrix twice: once
from an unmodified `main` baseline and once with the ADR implementation but
history disabled. Compare at least:

- final canonical tip/hash and ledger-state hashes/invariants;
- epoch transitions, rewards/current account state, and governance results;
- rollback and replay results;
- graceful restart and kill-9 recovery results;
- block-body and core-state pruning behavior;
- sync wall time, blocks/second, JVM heap, native RSS, RocksDB writes/compaction,
  and CPU.

No correctness difference is permitted. Performance changes when history is
disabled must remain within a predeclared noise budget established by repeated
baseline runs; a statistically meaningful regression blocks rollout. History
enabled is benchmarked separately and must demonstrate that core tip progress
continues independently even when the worker is deliberately slow or failed.

Rollout is feature-gated. The initial release keeps history disabled in all
existing profiles, including the wallet profile, until the disabled-path
regression suite and enabled-path soak tests pass. Datasets are then enabled
individually rather than turning on address and reward history together.

## Worker Modes

Each enabled dataset can have two independently checkpointed processing tracks.
The scheduler shares block decoding and I/O where useful, but one dataset's
backfill or failure does not move another dataset's cursor.

### Bulk catch-up

Blocks at or below the resolved immutable/archive boundary are processed in
large bounded ranges. Derived rows are streamed directly into the selected
archive backend and committed as one idempotent archive job. DuckLake writes
Parquet through its catalog transaction; standalone SQLite uses a bounded SQL
transaction. These rows are never first written as query history in RocksDB and
do not receive routine per-block undo journals.

This still requires parsing historical blocks and deriving rows; it removes
that work from core sync and avoids millions of individual permanent RocksDB
keys. Full history completion can lag the node tip substantially.

Bulk catch-up is lower priority than core synchronization. By default it does
not run while the core node is materially behind its upstream/network tip, and
it yields between bounded batches when core lag, apply queue depth, disk
latency, CPU pressure, or core RocksDB write/compaction pressure crosses a
configured threshold. It never holds a shared semaphore, executor, or I/O lock
needed by core apply. Operators may tune throughput, but cannot configure bulk
work to execute synchronously in block apply.

### Near-tip projection

Blocks above the archive boundary are mutable. The worker writes their history
rows to hot RocksDB, together with an exact block-keyed undo journal and its
cursor, in one RocksDB batch. Once a range crosses the archive-finality boundary
and is committed to the selected archive backend, the corresponding hot rows
and undo entries may be deleted.

Except for archive-only datasets such as rewards and epoch snapshots, or a
dataset explicitly configured with `live-enabled=false`, near-tip projection
starts immediately at an activation anchor while historical backfill proceeds
from the configured start in parallel. This prevents newly enabled wallet
endpoints from waiting weeks for mainnet backfill. The live and backfill tracks
publish explicit,
non-overlapping coverage ranges; the gap between them is incomplete, not
silently empty. When backfill reaches the activation anchor, the worker
validates the common block/projection version and merges coverage.

`transaction` and `account_event` need no outpoint resolver and can
always use this dual-track mode. `utxo_history` is also resolver-independent and
can dual-track: outputs are self-contained, and inputs record declared
references plus validity-derived consumption without resolving the referenced
output during projection. A query joining across an uncovered track gap remains
explicitly incomplete. `address_transaction` uses a separately seeded live
resolver as described below. A block is classified using the canonical tip and
archive boundary at commit time, not only when the batch started.

## Canonical and Archive Boundaries

Safety settings use **blocks**, not slots or epochs. Cardano's security
parameter `k` is a block-count parameter.

Defaults and validation read `k` from the parsed Shelley genesis owned by
`NetworkGenesisConfig.getSecurityParam()` (or an equivalent immutable genesis
value passed into the history configuration resolver):

```text
rollbackRetentionBlocks = 2 * k
archiveFinalityBlocks    = 2 * k
```

For mainnet/preprod (`k=2160`) the default is 4320 blocks. A devnet with
`k=100` defaults to 200 blocks. Preview/Sanchonet values follow their own
genesis.

Operators may configure larger or smaller-than-default values, but startup
validation is strict:

```text
rollbackRetentionBlocks >= k
archiveFinalityBlocks >= rollbackRetentionBlocks
archiveFinalityBlocks >= k
```

Values below `k` are configuration errors. There is no unsafe override.

The history subsystem must not derive this value from
`EpochParamProvider.getSecurityParam()`: that interface has a mainnet-oriented
default of 2160 and a devnet/test provider that does not override it would
silently validate against the wrong network. `RollbackRetentionPlanner` does not
currently receive `k`; adding a genesis-derived history planner input is an
explicit implementation task.

When Yano exposes a stronger canonical immutable-point abstraction, the worker
should use that point and apply the configured value as a minimum/conservative
fallback. Until then, the archive boundary is derived by block number from the
canonical tip.

Existing `yano.account-history.rollback-safety-slots` must not be reused for the
new subsystem. It has the wrong unit. It will be deprecated when the old
synchronous history implementation is removed.

## Per-Dataset Progress, Coverage, and Checkpoints

Every dataset has independent durable progress. A block-derived dataset can
have both a live cursor and a backfill cursor; an epoch-derived/archive-only
dataset has its own job/watermark. Shared worker metadata is not a substitute
for dataset progress.

Each cursor contains at least:

```text
network/genesis identity
block number
slot
block hash
parent hash
era
dataset name and schema/projection version
track type and activation anchor, when applicable
last committed archive generation
archive backend identity and commit receipt
worker software/projection version
```

For an epoch-derived dataset, the corresponding progress record replaces the
block-by-block fields with epoch number/range, canonical boundary block
hash/number/slot, source-state version, and deterministic epoch job ID.

Each block cursor advances for every block processed by that dataset/track,
including empty blocks and blocks that emit no rows. Cursor advancement and
near-tip hot writes are atomic. An epoch watermark advances for every eligible
source epoch, including one that emits no rows, only after its archive
transaction and receipt are durable. For a bulk block range, the backfill
cursor likewise advances only after the dataset's archive transaction and
commit receipt are durable. Dataset coverage is represented as explicit
complete block or epoch ranges, so separate live/backfill ranges and any gap
are visible to queries.

Periodic checkpoints contain the worker's independent input-resolution state
and the last canonical anchor. They accelerate restart/rebuild but are derived
data, not a substitute for source blocks.

Changing a dataset's projection semantics increments its projection/schema
version. A worker must not silently continue an old cursor using new semantics;
it either runs a declared migration or rebuilds the affected dataset/range.

## Independent Address Input Resolution

Address transaction history must not resolve inputs through the live UTXO
store. The worker maintains a private, durable outpoint-resolution state while
replaying blocks sequentially:

```text
valid output       -> add outpoint -> address/scope metadata
valid input        -> resolve sender, emit input role, remove outpoint
invalid collateral -> resolve collateral input, emit role, remove outpoint
collateral return  -> add returned outpoint and emit output role
```

This state can use a private RocksDB column family or database because it is a
mutable projection aid, not historical query storage. During bulk replay it is
checkpointed in bounded intervals. Near tip, its inserts/deletes are included
in the same block undo journal as history rows.

Before replaying the first block, the backfill resolver is seeded from every
genesis UTXO source supported by the ledger: Byron genesis/AVVM balances and
Shelley `initialFunds` (including custom/devnet genesis). Seeded outpoints use
the same canonical identifiers as core ledger bootstrap. The seed set and
genesis identity are checkpointed and tested before cursor advancement;
otherwise the first spend of a genesis output would trigger the required fatal
unresolved-input behavior.

For dual-track enable-later operation, the live resolver is seeded from a
consistent, detached snapshot of the canonical current UTXO set at the
activation anchor, then observes blocks strictly after that anchor. Creating
that source snapshot must hold the core lifecycle gate only long enough to bind
the anchor and create an immutable RocksDB checkpoint/export; loading millions
of UTXOs into the history resolver proceeds from the detached copy without a
live core handle. The activation block hash and resulting resolver checkpoint
are committed together before live projection begins. Backfill uses its
separate genesis-seeded resolver. When it reaches the activation anchor,
resolver state hashes are compared before the tracks merge; a mismatch makes
address history unhealthy and requires rebuild rather than silently joining
inconsistent state.

The resolver must implement era and validity semantics explicitly:

- phase-1-invalid transactions are not on-chain transactions and emit no rows;
- phase-2-invalid transactions consume collateral, may create a collateral
  return, and are recorded as invalid in `transaction`;
- ordinary inputs and outputs of phase-2-invalid transactions are not applied;
- duplicate references, malformed blocks, or unresolved inputs are fatal to
  the affected history range rather than silently skipped;
- Byron and Shelley-family address forms are normalized without losing the raw
  address identity;
- the worker does not advance its cursor past an unresolved required input.

An unresolved input means that source data or the resolver checkpoint is
incomplete/corrupt. The worker marks the range unhealthy and reports the exact
block/transaction/input. Operator choices are restore a checkpoint, restore
missing blocks, or rebuild. Silent receiver-only history is not allowed.

## Dataset Model

Every dataset has a common descriptor plus a source-specific projection
contract. A block-only `derive(BlockContext, ...)` method cannot model
epoch-derived data:

```java
interface ArchiveDataset<S extends DatasetSourceContext> {
    String name();
    int schemaVersion();
    SourceKind sourceKind();
    void derive(S source, ProjectionContext projection, RowSink sink);
    PrimaryKey primaryKey(HistoryRow row);
    PartitionPlan partitionPlan(HistoryRange range);
    RetentionPolicy retentionPolicy();
}

interface BlockHistoryDataset extends ArchiveDataset<BlockSourceContext> {}
interface EpochArchiveDataset extends ArchiveDataset<EpochSourceContext> {}
```

`BlockSourceContext` supplies a canonical block/range and resolver view.
`EpochSourceContext` supplies the committed epoch source version, boundary
block/hash/slot, and a bounded streaming reader for the durable epoch result.
The generic worker dispatches by `sourceKind`; it does not manufacture a block
context for an epoch calculation.

Implementations share worker orchestration and resource management but own
their row schema and query adapter. One logical dataset may emit multiple
physical tables in the same archive transaction; `utxo_history` uses this to
keep outputs, assets, inputs, address dictionary entries, and optional payloads
at one consistent coverage watermark.

### Relational and columnar schema principles

Physical archive tables are narrow, typed, and analytics-friendly. JSON/JSONB
is not used for amounts, inputs, outputs, credentials, or other fields that have
a stable relational shape. Large or genuinely semi-structured payloads are
stored separately and content-addressed where possible. Stable views provide
convenient denormalized query shapes without making duplicated wide rows the
durability format.

Every chain fact/event table contains a canonical coordinate tuple sufficient
for filtering, stable ordering, and provenance:

```text
block_number, slot, epoch, block_time
tx_hash and tx_index, when transaction-scoped
```

Explicit genesis/initial-funds output rows are the only exception: they carry
genesis identity and an origin type instead of invented block/slot values.

Root facts such as `transaction`, `account_event`, `reward`, and
`transaction_output` also store `block_hash`. Narrow child facts such as
`transaction_output_asset` repeat the inexpensive `block_number`, `slot`, and
`epoch` columns for Parquet pruning and self-contained time filtering, but do
not repeat wide address, datum, script, or block-hash values. The address
dimension records first-seen coordinates under the merge rules below rather
than pretending to be an event. Content-addressed payload tables are object
storage keyed by content hash and do not claim canonical first-seen semantics.

Physical types avoid presentation-driven duplication:

- transaction/block/datum/script hashes, credentials, policy IDs, asset names,
  raw addresses, and subject keys are binary, not hex text;
- `block_time` is stored as an unambiguous epoch value and exposed as a timestamp
  by stable views;
- lovelace is a lossless signed 64-bit integral value;
- native-asset quantities use `DECIMAL(38,0)` in DuckLake and a canonical exact
  decimal representation in SQLite, never floating point; repositories return
  `BigInteger` and REST returns strings;
- derived display values such as asset `unit`, address bech32/base58 text, and
  hex hashes may be exposed by views but are not repeated in every fact row.

This is deliberately inspired by Yaci Store's block/slot/epoch conventions and
flattened Parquet analytics, but does not copy its JSON transaction arrays,
`owner_addr`/`owner_addr_full` split, or its wide one-row-per-amount UTXO
layout. In a directional measurement over one existing mainnet Yaci Store
DuckLake file, 138,849 outputs expanded to 651,243 flattened amount rows (4.69
rows/output). Splitting output facts from narrow native-asset rows reduced the
sample from 16.12 MB to 12.20 MB (about 24%) under a comparable Zstandard
rewrite. Phase 0 repeats this over representative epochs/backends before final
file and index choices; the measurement justifies the normalized default, not
a fixed size guarantee.

### Address identity and credential queries

`address` stores one row per canonical raw address:

```text
address_key, raw_address, display_address, network_id, address_type,
payment_credential_type, payment_credential,
stake_reference_type, stake_credential_type, stake_credential,
pointer_slot, pointer_tx_index, pointer_cert_index,
first_seen_block_number, first_seen_slot, first_seen_epoch
```

`address_key` is a versioned deterministic fixed-width digest of canonical raw
address bytes (initially BLAKE2b-256). An API decodes the supplied bech32/base58
address, computes the same key, and binds it as a binary equality predicate.
Archive initialization records the key algorithm/version. Any observed digest
collision with different raw bytes is a fatal history-integrity error rather
than an alias.

`address` is a derived, serialized-upsert dimension even though the fact files
it serves are immutable. Its canonical `first_seen_*` value is the minimum
coordinate across currently valid complete coverage, not whichever of live or
backfill discovered the address first. When backfill later supplies an earlier
coordinate, the track-merge transaction updates/supersedes the dimension row.
If invalidation removes the range containing the recorded minimum, the same
backend transaction recomputes the minimum from surviving referencing facts or
removes the dimension row when none remain. Readers never use first-seen from
an incomplete track as a claim about uncovered history.

`datum` and `script` payloads are different: their hash key makes reinsertion
idempotent, and canonical outputs—not payload presence—establish use. Range
invalidation may leave an unreferenced payload object for later bounded garbage
collection; it cannot make wallet/history results canonical by itself.

Address-facing fact tables carry `address_key`; tables that must support direct
credential lookup, especially `address_transaction` and `transaction_output`,
also carry binary payment/stake credentials. Stake-credential lookup is a
first-class correctness and performance requirement, not a best-effort join.
Base addresses expose their stake credential directly. Pointer addresses always
retain their raw pointer coordinates. Whether an era treats the pointer as an
effective stake reference, and which canonical certificate can resolve it, is
implemented only after fixtures are verified against the pinned Haskell
`cardano-ledger` implementation for that era, including Conway and later
changes. The worker maintains a registration projection only for semantics the
ledger actually honors; it does not assume that decoding a pointer makes it
stake-active. Enterprise and Byron addresses legitimately have no stake
credential. Query semantics distinguish “no stake reference,” “pointer not
effective in this era,” and “effective pointer present but unresolved,” and
never manufacture a credential. Any required pointer mapping/checkpoint
mutations use the same exact near-tip undo rules as input resolution.
SQLite creates query-driven composite indexes such as:

```text
(address_key, block_number, tx_hash, output_index)
(stake_credential, block_number, tx_hash, output_index)
```

DuckLake uses fixed-width binary predicates, sorted row groups, and zone-map
statistics. If benchmarked address/stake point-range queries still fan out too
broadly, the implementation adds a narrow rebuildable
`output_subject` accelerator containing only subject type/key, outpoint, and
block/slot coordinates. Parquet Bloom filters are a benchmark-gated bonus only
when file metadata proves that the selected encoding emitted them and measured
size/latency improves; sorted columns do not require them. The accelerator does
not duplicate full output or asset payloads.

### Account events

`account_event` includes at least withdrawals, stake registration and
deregistration, pool delegation, DRep delegation where exposed, and MIR events.
Future Conway account-affecting events can be added with a versioned event type.

The logical primary key includes stake credential, slot, transaction index,
event index, event type, and transaction hash. Event indexes must not use a
fixed-width sequence that can wrap within an epoch.

### Address transactions

`address_transaction` represents transaction participation by exact address and
the supported payment/stake credential scopes. A row is keyed by
`subject_type`, binary `subject_key`, and transaction hash, with canonical block
coordinates and transaction index. `subject_type` distinguishes exact address,
payment credential, and stake credential; exact-address keys use `address_key`
and credential keys use the raw 28-byte credential.

Roles distinguish ordinary input, ordinary output, collateral input, and
collateral return. Multiple occurrences for the same subject and transaction
are aggregated into declared role flags/counts in one row; REST transaction
lists must not return accidental duplicates. Asset deltas are not embedded as
JSON or repeated in this table. An optional analytics view derives per-address
asset flows from normalized inputs/outputs when `utxo_history` is enabled.

### Transactions

`transaction` stores one row per on-chain transaction. It expands the earlier
`transaction_location` concept rather than maintaining two overlapping tables:

```text
tx hash, block hash, block number, slot, epoch, block time,
transaction index, validity, fee
```

`fee` is the actual ledger fee when it is available without consulting mutable
core state: the declared fee for a valid transaction and `totalCollateral` for
a phase-2-invalid Babbage-or-later transaction. It is null for Byron and for an
invalid transaction in an era whose collateral fee would require resolving
historical inputs. The archive never labels the invalid transaction's declared
fee as the collateral actually collected.

Any additional scalar transaction fields require the Phase 0 schema decision
to name their ledger meaning, type, historical availability, and query/API use;
“other selected facts” is not an implementation contract.

The logical dataset/API name is `transaction`; its physical table is
`chain_transaction` in both DuckLake and standalone SQLite so neither backend
must quote the SQL keyword `TRANSACTION`. Both expose the stable
`transactions` convenience view.

It is the durable source for `/txs/{hash}/status`. It does not depend on UTXO
retention or on whether a transaction created an output. Phase-2-invalid
transactions are present with `valid=false`. Inputs, outputs, assets, required
signers, collateral, and reference inputs are not stored as JSON arrays in this
row; enabled normalized child tables represent them.

The DuckLake backend queries a catalog table or stable view with predicate
pushdown; callers never enumerate Parquet paths. Because transaction hashes are
uniformly distributed, epoch partitioning and min/max metadata alone provide
little file pruning. DuckLake does not provide secondary indexes. The writer
therefore maintains a compact derived transaction locator mapping tx hash to an
archive range/snapshot pruning key when the `transaction` dataset uses
DuckLake. Point lookup uses the locator first and then verifies the hash in the
DuckLake table. The locator is rebuildable from committed transaction rows and
is not authoritative ledger state. Phase 0 benchmarks choose its physical
representation and rebuild/checkpoint strategy. Bloom filters may reduce scans
only if the actual Parquet metadata proves they were emitted without
counterproductive dictionary encoding and benchmarks show a net benefit; they
are not the primary point-lookup plan.

The standalone SQLite backend creates a B-tree index on transaction hash and
uses it for this lookup. API behavior is identical across backends even though
the physical query plans differ.

### Optional UTXO history

`utxo_history` is one logical dataset/coverage unit with normalized physical
tables. Its output, asset, input, address, and enabled payload mutations for a
canonical range commit atomically.

`transaction_output` contains exactly one row per output:

```text
tx_hash, output_index, tx_index,
origin_type,
address_key, payment_credential, stake_credential,
lovelace,
datum_kind, datum_hash, reference_script_hash,
is_collateral_return,
block_hash, block_number, slot, epoch, block_time,
archive_job_id
```

It stores output-level data only. Datum and reference-script hashes remain even
when payload archival is disabled. When payload archival is enabled, canonical
datum/script CBOR is deduplicated into content-addressed `datum` and `script`
tables keyed by hash; it is not repeated per output or asset.

Before the first block, the dataset writes the same Byron genesis/AVVM and
Shelley `initialFunds` outpoint seed used by the independent resolver.
`origin_type` distinguishes ordinary chain output, Byron genesis/AVVM, and
Shelley initial-funds rows. Genesis rows use the canonical outpoint identifier
understood by the ledger/resolver, have no parent `transaction` row, and carry
genesis identity rather than invented block coordinates. Views order them
before block zero and permit later `transaction_input` rows to join their
spends normally.

`transaction_output_asset` contains exactly one row per **native asset** in an
output:

```text
tx_hash, output_index,
policy_id, asset_name, quantity,
block_number, slot, epoch,
archive_job_id
```

ADA is not stored as an asset child: `lovelace` on `transaction_output` is its
single physical representation. `unit` is not stored because it is derivable
from policy ID plus asset name; this avoids storing the combined identifier as
well as both components.

`transaction_input` contains transaction-declared input/reference facts:

```text
spending_tx_hash, spending_tx_index, input_index, input_role,
referenced_tx_hash, referenced_output_index, consumes_output,
block_hash, block_number, slot, epoch, block_time,
archive_job_id
```

`input_role` distinguishes ordinary, collateral, and reference input. The
`consumes_output` flag reflects ledger application: ordinary inputs of valid
transactions and collateral inputs of phase-2-invalid transactions consume;
reference inputs and unapplied ordinary inputs do not. A canonical output has
at most one consuming row. Input rows do not duplicate the referenced output's
address, credentials, lovelace, assets, datum, or script; queries join by the
referenced outpoint.

Backend-neutral stable views provide the convenient merged shapes:

- `transaction_output_amounts` emits one row for lovelace plus one row per
  native asset, deriving `unit` and joining output-level address/datum fields;
  its lovelace row uses `unit='lovelace'` with null policy/asset name, while a
  native unit is the canonical policy-ID-plus-asset-name representation;
- `output_lifecycle` left-joins each output to its consuming input and exposes
  created/spent coordinates and `is_unspent_at_archive_watermark`;
- `unspent_outputs` filters the lifecycle view at the finalized archive
  watermark; Yano's live API additionally merges near-tip RocksDB changes;
- `address_utxo_amounts` joins the address dimension and flattened amount view
  for Yaci Store-style analytics;
- `address_asset_flow` can join applied inputs and outputs to derive per-address
  or per-stake-credential asset flows.

For point-in-time analytics at block `N`, an output is unspent when its creation
block is at or before `N` and it has no consuming input at or before `N`. A
global current/unspent boolean is not stored on every asset row because it would
require rewriting immutable creation partitions when the output is later spent.

The principal SQLite keys/indexes are limited to documented query paths:

```text
transaction_output:       PK(tx_hash, output_index)
                          INDEX(address_key, block_number, tx_hash, output_index)
                          INDEX(stake_credential, block_number, tx_hash, output_index)
transaction_output_asset: PK(tx_hash, output_index, policy_id, asset_name)
                          INDEX(policy_id, asset_name, block_number)
transaction_input:        PK(spending_tx_hash, input_role, input_index)
                          UNIQUE(referenced_tx_hash, referenced_output_index)
                            WHERE consumes_output = true
                          INDEX(referenced_tx_hash, referenced_output_index)
```

The DuckLake tables use the same logical keys but cannot enforce them; the
worker validates range uniqueness before commit and the conformance suite
checks it after commit. DuckLake partitioning remains time/range oriented;
files are sorted to cluster dominant address/stake/outpoint predicates without
creating a partition per address or asset.

### Rewards

`reward` is independently optional even when other history is enabled. It
records the complete supported reward/event classification, credential, pool
when applicable, earned/spendable epoch semantics, amount, and source identity.

It must include all paths that affect user-visible reward history, including
ordinary pool/member/leader rewards and applicable proposal-deposit refunds,
treasury withdrawals, reserve/treasury/MIR-style credits, according to ledger
semantics and API scope. Current withdrawable reward balance remains in the
authoritative account state and does not depend on this dataset.

Reward history is **archive-only**. It is never written to hot history RocksDB
and never creates an epoch-scale history undo journal. At epoch calculation,
authoritative current reward state commits as it does today and a small durable,
restartable reward-export job references/retains its source. After the boundary
block passes `archiveFinalityBlocks`, the job streams bounded batches directly
to the selected archive backend and commits one archive job. This intentionally
allows roughly one archive-finality window of reward-history latency.

A rollback before reward export finality cancels/recomputes the job. An
exceptional rollback after archive commit invalidates and rebuilds the affected
reward partition/range. A large in-memory callback followed by a single
unbounded JDBC or RocksDB batch is not an acceptable durability boundary.

### Epoch stake

`epoch_stake` records the committed delegation snapshot grain of one row per
epoch and stake credential:

```text
epoch, stake_credential_type, stake_credential,
pool_hash, amount,
boundary_block_hash, boundary_block_number, boundary_slot, boundary_block_time,
source_state_version, archive_job_id
```

Stake/reward addresses and pool IDs are derived presentation values in stable
views. The row is sourced from the committed epoch snapshot, not reconstructed
later from mutable current delegations or current UTXOs.

### DRep distribution

`drep_distribution` records one row per epoch and DRep subject, including
virtual abstain/no-confidence subjects:

```text
epoch, drep_type, drep_credential,
amount, stored_expiry, dormant_epochs, effective_expiry, active,
boundary_block_hash, boundary_block_number, boundary_slot, boundary_block_time,
source_state_version, archive_job_id
```

The expiry/activity fields represent the exact boundary calculation. They are
durably staged with that result and are not recomputed from later DRep state.

### Ada pots

`ada_pot` records exactly one finalized row per epoch:

```text
epoch, treasury, reserves, deposits, fees,
distributed, undistributed, rewards_pot, pool_rewards_pot,
boundary_block_hash, boundary_block_number, boundary_slot, boundary_block_time,
source_state_version, archive_job_id
```

Every quantity is lossless. The row is read only after the reward and
governance adjustments for the boundary are durably committed.

### Governance proposal status

`governance_proposal_status` records the ratification evaluation grain of one
row per boundary epoch and governance action:

```text
epoch, tx_hash, governance_action_index, action_type,
observation_phase, status_code, decision_reason,
deposit, return_address, submitted_epoch, expires_after_epoch,
boundary_block_hash, boundary_block_number, boundary_slot, boundary_block_time,
source_state_version, archive_job_id
```

`return_address` stores canonical raw address bytes; its display form and the
action ID are derived by stable views. Status vocabulary is versioned data, not
a closed Java/SQL enum frozen by this ADR. The current `RatificationEngine`
evaluation produces at least `active`, `ratified`, and `expired`; a delayed
decision currently remains an evaluation outcome with a reason such as
`delayed_by_prior_action`, while enactment and drop occur in a different
boundary phase. `observation_phase` and `decision_reason` preserve those
distinctions rather than overloading one status value. Phase 0 verifies the
complete vocabulary and transitions against the pinned Haskell ledger, Yano's
engine, and the repository Issue #47 regression case before freezing schema/API
mappings. If enacted/dropped lifecycle observations are in scope, their durable
Phase-1
source is captured explicitly; they are never inferred from pending keys or
collapsed into ratification status. The same action can legitimately have rows
in multiple epochs.

The four epoch datasets are independently selectable. They are archive-only by
default: they do not create near-tip history rows or block-level undo journals.
Their coverage uses epoch ranges plus the canonical boundary block anchor. A
rollback that crosses a boundary invalidates/rebuilds the affected epoch rows
using the epoch-source rules below.

## Module Layout and Dependency Boundaries

Archive production code is grouped under one top-level repository folder:

```text
archive-modules/
├── archive-api/
├── archive-core/
├── archive-store-ducklake/
└── archive-store-sqlite/
```

The corresponding Gradle paths are `:archive-modules:archive-api`,
`:archive-modules:archive-core`, `:archive-modules:archive-store-ducklake`, and
`:archive-modules:archive-store-sqlite`. The `archive-modules` parent is an
organizational/Gradle grouping, not a published runtime artifact.

The four modules are deliberately coarse-grained:

- `archive-api` contains backend-neutral dataset identifiers, typed row/source
  and repository contracts, archive jobs, coverage, cursors, commit receipts,
  and the store-provider SPI. It has no RocksDB, DuckDB, DuckLake, SQLite,
  Flyway, ledger-state, runtime, or REST dependency.
- `archive-core` contains worker scheduling, all block/history and
  epoch-derived dataset projections, per-dataset progress, finality,
  idempotency, source leases, hot promotion, rollback/invalidation, retention,
  and query composition. History and epoch projections use separate packages,
  not separate Gradle modules.
- `archive-store-ducklake` contains the DuckLake backend and its bounded DuckDB
  manager, packaged extension loading, SQLite catalog attachment, Parquet
  layout, snapshots, compaction, and typed repositories.
- `archive-store-sqlite` contains the standalone SQLite backend, Flyway
  migrations, WAL lifecycle, indexes, maintenance, and typed repositories. It
  has no DuckDB dependency.

Thin assembly, configuration binding, lifecycle, health/metrics registration,
and canonical-source adapters remain in Yano's existing `runtime` module. A
separate `archive-runtime-integration` artifact is unnecessary. Backend
conformance and dataset fixtures use Gradle `java-test-fixtures` from
`archive-api`/`archive-core`, consumed by both store modules; they do not need a
published `archive-testkit` module.

Dependencies flow inward toward `archive-api`. Dataset projections never
import a concrete backend, ledger-state never imports DuckDB/SQLite/Flyway, and
store modules never call runtime or REST resources. The application can select
one store implementation at runtime; disabled archive/history initializes no
driver, Flyway migration, worker, connection, hot-history store, or projection.
This four-module boundary is the initial design. A package is extracted later
only when there is a second production consumer or a demonstrated independent
dependency/release boundary.

## Archive Backend Contract

Dataset projection code does not receive a JDBC connection, DuckDB SQL string,
SQLite table name, or filesystem path. It emits versioned typed rows into a
backend-neutral contract. Conceptually:

```java
interface HistoryArchiveBackend extends AutoCloseable {
    ArchiveCapabilities capabilities();
    ArchiveCommit beginCommit(ArchiveBatchMetadata metadata);
    ArchiveReadSnapshot openReadSnapshot();
    DatasetCoverage coverage(ArchiveDatasetId dataset);
    void invalidate(ArchiveDatasetId dataset, ArchiveRange range);
    void applyRetention(ArchiveDatasetId dataset, RetentionCutoff cutoff);
    void maintain(MaintenanceBudget budget);
    ArchiveHealth health();
}

interface ArchiveCommit extends AutoCloseable {
    ArchiveRowSink openSink(ArchiveDatasetId dataset, int schemaVersion);
    ArchiveCommitReceipt commit();
    void abort();
}
```

`ArchiveRange` is source-aware: it is a canonical block range for block-derived
data or an epoch range with canonical boundary anchors for epoch-derived data.

Query code uses typed repositories such as
`AccountEventHistoryRepository`, `AddressTransactionHistoryRepository`,
`TransactionHistoryRepository`, `UtxoHistoryRepository`, and
`RewardHistoryRepository`, plus epoch-stake, DRep-distribution, Ada-pot, and
governance-proposal-status repositories. It does
not expose arbitrary backend SQL. Capability metadata records material backend
differences such as time travel, secondary indexes, concurrent external
readers, and maintenance operations; common orchestration does not pretend the
two engines have identical physical behavior.

Both implementations must preserve the same logical schema, canonical range,
stable ordering, pagination, coverage, and incomplete/unhealthy semantics.
Binary hashes and credentials are stored losslessly. Amounts use a lossless
backend mapping and REST always emits quantity strings. Every archived row can
be traced to its dataset schema/projection version, canonical range, and
archive job ID.

Network magic alone is not sufficient identity for custom/dev networks. Each
archive records the genesis hashes and network magic and refuses to open a
mismatched archive.

## DuckLake Backend

`DuckLakeHistoryArchiveBackend` is the default. It uses:

```text
history/
  network=<genesis-hash>/
    ducklake-catalog.sqlite
    ducklake-data/
    tmp/
```

The SQLite file is a **DuckLake catalog**, not a history row database. DuckLake
owns its tables and schema evolution. Historical rows live in
DuckLake-managed, immutable Parquet files below `ducklake-data`; Yano never
constructs or persists raw Parquet filenames as query metadata.

Yano attaches it using the equivalent of:

```sql
ATTACH 'ducklake:sqlite:/absolute/path/ducklake-catalog.sqlite'
    AS history_lake
    (DATA_PATH '/absolute/path/ducklake-data');
```

The initial archive uses stable, unversioned physical table names. Schema and
projection versions live in archive metadata and migration history rather than
being encoded in every table name. Operator-facing convenience views also have
stable names:

```text
account_event             -> account_events
address_transaction       -> address_transactions
chain_transaction         -> transactions
reward                    -> rewards
epoch_stake               -> epoch_stakes
drep_distribution         -> drep_distributions
ada_pot                   -> ada_pots
governance_proposal_status -> governance_proposal_statuses
address                   -> addresses
transaction_output        -> transaction_outputs
transaction_output_asset  -> transaction_output_assets
transaction_input         -> transaction_inputs
datum / script            -> datums / scripts (when payloads are enabled)
archive_commit            -> archive_commits
archive_coverage          -> archive_coverage

transaction_output_amounts, output_lifecycle, unspent_outputs,
address_utxo_amounts, address_asset_flow -> stable analytics views
```

Views are persisted in DuckLake metadata. They expose the finalized archive and
its cold watermark only; they do not pretend that near-tip RocksDB rows are
part of DuckLake. Wallet APIs merge hot and cold through the typed repositories.

Epoch is the default coarse partition. Additional credential/hash bucketing is
introduced only when measurements show that it improves pruning without a
small-file explosion. The bounded-memory default targets approximately 32 MB
files and 100,000 rows per row group, then tunes from benchmark data. Larger
targets are appropriate only when the operator raises the bulk DuckDB memory
budget as well. Rows are
sorted for dominant predicates and stable ordering.

Set DuckLake data inlining to `0` for this archive. Even small history batches
must go to Parquet rather than growing the SQLite metadata catalog with inline
row payloads. Compression, partition, and row-group options are set and
verified at table/catalog initialization.

One Yano writer owns the DuckLake. Bounded Yano API readers and optional local
external readers may attach through the SQLite catalog. SQLite catalog lock
retries have explicit attempt/time limits; lock exhaustion marks the archive
request/job unavailable and never propagates backpressure into core sync.

DuckLake snapshots replace the custom Parquet manifest generation. A query
captures a DuckLake snapshot/version at request start. DuckLake catalog
transactions control which data/delete files are visible, and DuckLake's
snapshot expiration, file rewrite/merge, old-file cleanup, and orphan cleanup
are used through bounded background maintenance jobs. Yano still owns the
chain-specific archive commit/coverage rows and validates canonical anchors.

## Standalone SQLite Backend and Flyway

`SqliteHistoryArchiveBackend` is also delivered by this ADR, not deferred as a
future option. It stores finalized rows in ordinary SQLite tables and does not
initialize DuckDB, DuckLake, or Parquet. Its database is separate from both hot-history RocksDB and any
DuckLake SQLite catalog:

```text
history/
  network=<genesis-hash>/
    history.sqlite
```

The standalone SQLite schema is managed exclusively by Flyway using versioned
SQL migrations in a dedicated location such as
`classpath:db/migration/history-sqlite`. The implementation includes Flyway,
the Xerial SQLite JDBC driver, and the required Flyway SQLite database support.
On enabled-backend startup, before the worker or history API accepts work, Yano
runs `validate` and `migrate` programmatically against this file.

The archive path is a Yano-owned, dedicated SQLite database file; it must not
point at a host application's database. This remains true when Yano is embedded
as a Java library. Yano scans only its archive migration location and records
those migrations in the module-specific `yano_archive_schema_history` table,
so a host application's `V1`, `V10`, or other Flyway versions cannot collide
with archive versions. Yaci Store-style reserved version ranges are unnecessary
here because its modules intentionally share a database and migration history,
whereas this backend does not.

Flyway rules are strict:

- `baselineOnMigrate=false`; an unknown non-empty database is not silently
  adopted;
- `clean` is disabled in production;
- the unreleased initial schema is consolidated in `V1__archive_schema.sql`;
  after the first release, applied migrations are immutable and changes roll
  forward through `V2`, `V3`, and later versions;
- migration checksums are validated and failed/unknown migrations make only
  the history subsystem unavailable;
- exactly one process may migrate or write the database; concurrent migration
  is not attempted because SQLite does not provide Flyway's usual migration
  locking semantics;
- migrations do not contain explicit `BEGIN`/`COMMIT`, because Flyway owns the
  migration transaction and SQLite does not support nested transactions;
- destructive or long data rewrites use an explicitly tested restartable
  migration/rebuild plan rather than an unbounded startup migration;
- the `yano_archive_schema_history` table, application tables, indexes,
  triggers, and views are backed up together.

The initial schema uses `chain_transaction` for the logical transaction
dataset, matching DuckLake. It also includes stable unversioned address, output,
asset, input, optional datum/script, account-event, address-transaction,
reward, epoch-stake, DRep-distribution, Ada-pot, governance-proposal-status,
`archive_commit`, and `archive_coverage` tables, plus convenience views
equivalent to the DuckLake views. Flyway versions the schema through
`yano_archive_schema_history`; table names do not repeat that version. It uses
declared primary/unique keys and B-tree indexes only for documented query
predicates, including transaction hash, outpoint, address/credential plus
ordering key, asset identity, epoch, canonical block range, and archive job ID.
Every fact table includes `archive_job_id` so a committed range can be
verified, invalidated, or rebuilt deterministically.

SQLite operates with one serialized archive writer, bounded read connections,
`foreign_keys=ON`, `journal_mode=WAL`, a finite `busy_timeout`, and an explicit
durability setting (default `synchronous=FULL`, benchmarkable to `NORMAL` only
through an operator setting with documented durability tradeoff). WAL
checkpointing, `PRAGMA integrity_check`, `ANALYZE`, free-page monitoring, and
optional `VACUUM` are maintenance jobs with resource/deadline guards; they do
not run in block apply or synchronously in an API request. Long-lived read
transactions are prevented by query deadlines and value-based pagination.

Until Phase 0 establishes standalone SQLite's practical full-mainnet limits,
selecting it for a known public Cardano mainnet genesis identity logs a clear
non-blocking startup warning about expected scale and points to DuckLake as the
large-history default. The warning is based on verified genesis identity, not
network magic alone, because custom networks can reuse magic values. It does
not reject SQLite or appear merely because a dataset has a large configured
retention; operators may still intentionally choose it.

## Archive Input and Atomic Commit

Archive input is explicit:

- bulk backfill derives rows from canonical block bodies and holds a block-body
  retention lease until commit;
- near-tip promotion copies already-derived hot RocksDB rows from a pinned
  RocksDB snapshot after checking their per-block canonical anchors; it does not
  parse the same blocks a second time and therefore needs no routine block-body
  lease;
- archive-only epoch datasets such as rewards stream from their durable epoch
  source and hold that source's retention lease until commit.

Copying hot rows preserves the exact projection that queries already observed
and avoids duplicate parsing/resolver work. Canonical block bodies remain the
rebuild source for exceptional invalidation when they are available.

Every archive range has a deterministic job ID derived from at least network,
dataset, start/end canonical hashes, and projection version. Commit proceeds:

1. Select a canonical finalized range, pin the input snapshot, and acquire its
   source-retention lease when required.
2. Derive/stream bounded rows while checking row schema, primary-key rules, row
   count, and range anchors.
3. Recheck canonical start/end anchors immediately before archive commit.
4. In one backend transaction, append all rows and an `archive_commit` record
   containing the job ID, canonical range/hashes, projection version, row count,
   and checksum/digest metadata.
5. Commit and obtain an `ArchiveCommitReceipt`: DuckLake snapshot ID or SQLite
   monotonically increasing archive generation.
6. Persist the receipt, coverage, and backfill/archive cursor in history
   RocksDB.
7. Release source retention and idempotently prune promoted hot rows/old undo
   records when safe.

DuckLake does not enforce primary/unique keys on lake tables. Its writer is
serialized, checks `archive_commit` for the deterministic job ID, and uses
`MERGE`/verified retry semantics. All data rows carry the same job ID so a
duplicate or partial semantic commit can be detected and invalidated. SQLite
enforces the job ID and logical row keys with unique constraints in the same
transaction.

The archive transaction and history RocksDB cursor cannot share an atomic
commit. Recovery therefore handles every boundary:

| State | Recovery |
|---|---|
| failure before archive commit | backend transaction is invisible/rolled back; retry same job ID |
| archive commit exists, RocksDB receipt/cursor absent | verify job ID, anchors, row count and digest; record receipt and advance without reinserting |
| RocksDB receipt exists, hot cleanup incomplete | repeat idempotent cleanup |
| DuckLake unreferenced physical file after crash | DuckLake orphan cleanup after grace period |
| catalog/database or committed data corrupt | hide affected coverage, mark backend unhealthy, restore or rebuild |
| retry conflicts with different metadata for same job ID | fatal history-integrity error; never treat as success |

No Yano `PREPARED` Parquet manifest or file-renaming protocol is retained.
DuckLake owns logical file visibility; SQLite owns transactional page
visibility. Yano's receipt and coverage records map those backend commits to
canonical chain ranges.

## Near-Tip RocksDB and Undo Journal

Hot RocksDB is a dedicated database under the history directory, not column
families in the core chain-state RocksDB. It contains only the mutable/recent
portion of enabled history, projection metadata, undo, and private resolution
state. It is written by the history worker, not the core block listener. This
keeps history compaction, write stalls, block cache, checkpoints, and pinned
query snapshots isolated from core state.

The worker reads/copies a canonical block and its hash metadata while holding a
short core read/lifecycle gate, then releases all core handles before decoding,
deriving, writing hot history, or invoking an archive backend. A long archive
job or history query therefore cannot delay core RocksDB close/reopen. The live
resolver's one-time activation snapshot is an explicitly observable exceptional
read and must also avoid sharing a mutable core batch.

For every near-tip block, one atomic batch contains:

- all inserted history keys for every enabled hot dataset;
- any replaced values and their previous values;
- resolver outpoints inserted and removed, including removed values;
- the block number/hash/parent/slot checkpoint, even if no rows were emitted;
- the updated per-dataset live cursors.

The undo journal covers:

- address transactions;
- account events;
- transactions;
- enabled UTXO-history output, asset, input/spend, address-dimension, and
  payload-reference mutations;
- private input-resolution state.

Rewards are archive-only and are deliberately absent from hot RocksDB and this
undo journal.

Routine rollback deletes/restores exact keys from this journal. It does not scan
the full history column family by slot. A full scan is an offline repair tool,
not a production rollback mechanism.

Undo entries are deleted only when all of the following hold:

- their blocks are below the supported rollback boundary;
- any retained history rows have been durably committed to a visible archive
  backend transaction, or the configured history retention has intentionally
  expired;
- the hot-to-cold cleanup is itself checkpointed and restartable.

Unlimited archive retention does not imply unlimited undo retention.

## Rollback Handling

Rollback events are hints. Peer reconnects and non-real rollback notifications
do not directly mutate history. The worker establishes rollback from canonical
parent/hash mismatch.

### Worker has not reached the rollback point

For any dataset/track that has not reached the rollback point, no history undo
is needed. Before its next commit it rereads the canonical hash for the range
and processes the new canonical branch.

### Rollback is inside the hot window

The worker:

1. pauses new projection commits;
2. finds the common canonical block;
3. applies exact undo records in reverse block order;
4. restores private input-resolution state;
5. resets affected per-dataset live cursors to the common block;
6. replays the new branch;
7. resumes archive promotion after canonical checks pass.

Rollback is idempotent. Repeating the same target performs no additional
deletion after the cursor reaches it.

### Rollback crosses the finalized archive

This is exceptional under the strict finality configuration, but can occur
during devnet time travel, snapshot restore, corruption repair, or a deeper
than-supported network rollback.

The system creates a new backend transaction; it never moves the global archive
to an old generation because that could revert unrelated datasets committed in
later snapshots. It:

1. marks affected archive commits/ranges invalid and deletes or supersedes their
   logical rows in one new DuckLake snapshot or SQLite transaction;
2. makes the invalidated range unavailable to queries immediately;
3. resets the affected dataset progress/resolver to the latest valid checkpoint
   before the affected range;
4. rebuilds replacement rows from retained canonical source blocks or the
   dataset's durable epoch source;
5. commits the replacement archive job before physical DuckLake old-file
   cleanup or SQLite vacuum can reclaim invalid data.

If the required block or epoch rebuild source is unavailable, the affected
history remains explicitly incomplete. The node does not silently serve
orphaned history.

### Rollback precedes a live activation anchor

This case is distinct for a resolver-dependent live track. Its exact undo and
detached UTXO base begin at the activation anchor; they cannot prove state
before that anchor. If the common canonical block is earlier than the anchor,
the anchor and resolver checkpoint are orphaned even when some later blocks
again appear on the replacement branch.

The worker stops that live track, invalidates its hot and already-promoted
coverage after the common block, discards the orphaned resolver base/checkpoint,
and clears the old live cursor. It then binds a new canonical activation anchor,
creates a new detached current-UTXO snapshot, records the anchor/hash and
resolver checkpoint atomically, and starts a new live coverage range. If the
genesis backfill resolver has already reached a suitable canonical point, it
may instead be checkpointed/replayed to seed the new anchor after state-hash
verification. The old and new live ranges are never merged merely because their
block numbers overlap.

Until re-activation completes, resolver-dependent endpoints report the affected
live range unavailable/incomplete. Address-independent datasets reset/replay
from canonical blocks and do not unnecessarily create a UTXO base. Devnet
time-travel and snapshot-restore tests exercise this path explicitly.

## Block-Body Retention and Enabling History Later

Complete historical replay requires complete source data. The history worker
cannot reconstruct sender addresses, transactions, or account events
from current UTXO/account state alone.

The block pruner therefore participates in a consumer-low-watermark contract.
While history is enabled, it must not prune a block body still required by:

- any dataset backfill cursor/coverage gap;
- an in-progress archive job;
- the newest valid resolver checkpoint;
- a pending rebuild/invalidation job.

History lag does not block core block application, but it can delay block-body
pruning and increase disk use. This condition is observable and alertable.

When history is enabled after deployment:

- `full-required` starts backfill at genesis and requires every source body;
- `earliest-available` starts at the earliest retained body and publishes that
  lower coverage bound;
- `tip` creates only the live activation anchor and makes no claim about earlier
  history;
- unless `live-enabled=false`, live projection starts at activation concurrently
  with `full-required` or `earliest-available` backfill, as allowed by each
  dataset;
- if all required bodies remain (the current default block-body prune depth is
  disabled), a full backfill can replay from genesis;
- if earlier bodies were pruned, `full-required` mode fails clearly with the
  earliest missing block;
- restoring blocks or resynchronizing is required to obtain complete history.

A failed backfill track does not discard an independently verified live range.
Queries wholly inside that range may be served with its explicit coverage
metadata; queries crossing the gap are incomplete/unavailable according to the
API contract. This preserves new wallet activity without falsely claiming full
history.

The worker must never advance across a missing body and claim complete
coverage.

Core block-body retention, core UTXO spent-output retention, history retention,
and history undo retention remain separate policies. Setting history archive
retention to unlimited does not disable pruning of spent UTXOs or other core
rollback data.

## Query Model

History providers merge two sources:

```text
committed cold range -> selected archive backend read snapshot
recent mutable range -> RocksDB prefix/range query
```

At request start the provider captures:

- backend identity and archive snapshot/generation (an explicit DuckLake
  snapshot ID or a standalone SQLite read transaction plus committed
  generation);
- archive boundary;
- a RocksDB snapshot/sequence that pins the hot rows for the request lifetime;
- per-dataset live/backfill cursors and complete coverage ranges;
- dataset coverage and health.

All cold subqueries for one request use the same logical backend snapshot. For
standalone SQLite they use one bounded read transaction pinned to one WAL
snapshot. For DuckLake with a SQLite catalog, they explicitly attach/read at
the captured DuckLake `SNAPSHOT_VERSION`; they do not assume that holding a
long SQLite-catalog read transaction is compatible with DuckLake's documented
per-query attach/detach and write-lock retry model. Managed connections may be
released between subqueries only if each reattach is proven to select the same
snapshot ID. A request-scoped active-snapshot lease prevents Yano maintenance
from expiring that snapshot. A pool must never route subqueries across
independently advancing snapshot versions.

It queries each non-overlapping range and merges by a stable dataset key. A
promotion concurrent with a request cannot cause duplicates or omissions
because the request continues using its captured archive snapshot/boundary and
pinned RocksDB snapshot even if promotion deletes newer-version hot keys.
Snapshot leases/transactions are released in `finally`/request-close paths;
query deadlines bound how long they can retain old DuckLake files, RocksDB
versions, or SQLite WAL pages and delay maintenance. Phase 0 must prove that
explicit snapshot-version reads, concurrent commits, attach/detach, catalog
retry limits, cancellation, and maintenance compose without a reader exhausting
the writer's bounded retry budget. If the pinned DuckLake/SQLite combination
cannot meet that contract, the query strategy or production catalog choice
must change before release; the implementation must not weaken snapshot
consistency silently.

Pagination cursors are value-based, not offset-only, and include enough stable
ordering fields to resume across hot/cold boundaries. Examples include slot,
transaction index, event index, and a primary-key tie-breaker. Ascending and
descending default behavior is defined once in the provider and shared by
related endpoints.

A cursor also binds dataset/projection version, filter digest, ordering,
archive boundary, and canonical coverage revision. Connections are not held
between HTTP requests. On resume, the provider may use a retained DuckLake
snapshot, or safely continue against a newer backend generation when intervening
commits do not overlap the remaining logical range. If retention, invalidation,
schema migration, or deep rollback changed the relevant range and the backend
cannot reproduce the cursor snapshot (notably standalone SQLite), the API
returns an explicit stale/expired-cursor response; it never silently mixes two
histories.

Queries never interpolate untrusted SQL identifiers or paths. Dataset
tables/views are fixed by backend code and user filters are bound parameters.
Query duration, concurrent backend reads, result rows, scanned bytes where
observable, and page size are bounded. APIs do not accept raw SQL and never
open request-supplied DuckLake, Parquet, or SQLite paths.

When a dataset is disabled, unhealthy, rebuilding, or incomplete for the
requested range, endpoints return an explicit unavailable/incomplete response
rather than an empty list that implies no history. A history status endpoint
and metrics expose at least:

```text
core tip
history tip and lag
archive watermark/generation
archive engine, catalog type, schema/Flyway version, and backend snapshot
earliest and latest complete block or epoch per dataset
worker state and last error
pending-job bytes and archive/catalog bytes
hot rows and undo depth
oldest retained source block
DuckDB memory/thread/temp settings when DuckLake is selected
SQLite WAL/checkpoint/busy state when standalone SQLite is selected
```

## Retention

Retention is configured independently per dataset. `0` means unlimited archive
retention for that dataset, not unlimited rollback data.

History retention operates only on finalized archive partitions/ranges and
commits a new backend generation. It never changes core pruning.

For DuckLake, retention logically deletes/supersedes the expired dataset range
in a new snapshot. Snapshot expiration is catalog-wide rather than per table,
so per-dataset logical retention and global physical snapshot retention are
different controls. Old files remain available to captured snapshots until the
configured global snapshot/grace window passes; bounded background expiration
and old-file cleanup reclaim them afterward. If datasets require materially
different physical recovery windows, separate DuckLakes may be configured by
retention class rather than pretending catalog-wide expiration is per-dataset.

For standalone SQLite, retention deletes the expired range and its coverage in
one transaction. Existing read transactions continue against their WAL
snapshot. Checkpoint/free-page reuse happens asynchronously; `VACUUM` is an
explicit bounded maintenance action and is not required for logical deletion to
take effect.

Hot history is retained until it is both outside the mutable window and either
archived or intentionally expired. If archive export is failing, pruning must
not delete the only complete copy merely to maintain the desired hot size.

Suggested independent policies are:

- account events: usually unlimited or operator-selected epochs;
- address transactions: usually unlimited for a full wallet index;
- transactions: unlimited when historical tx status is promised;
- UTXO history: independently optional and commonly bounded unless full output,
  asset, and spend analytics are required; all physical child tables share one
  logical retention/coverage policy;
- rewards: optional, independently unlimited or bounded;
- epoch stake: optional, normally unlimited for longitudinal stake analytics;
- DRep distribution: optional, normally unlimited for governance analytics;
- Ada pots: optional and cheap enough to retain indefinitely in most profiles;
- governance proposal status: optional, unlimited or operator-selected epochs.

## DuckDB Resource Management

Pin `org.duckdb:duckdb_jdbc:1.5.5.0` and the compatible DuckLake 1.0 extension.
DuckDB 1.5.5 is the selected repository baseline; builds and deployment images
must not resolve a different JDBC or extension version implicitly. Upgrade
tests cover an archive created by the previously tested DuckLake version before
automatic catalog migration is allowed in production.

Create a reusable `DuckDbManager` (name illustrative) inside
`archive-store-ducklake`. It owns every DuckDB connection used by the new
DuckLake archive backend. The current per-export
`DriverManager.getConnection("jdbc:duckdb:")` pattern disappears with the old
epoch exporter and is replaced by managed database/connection lifecycle.

The manager owns:

- driver/database initialization;
- a bounded set of workload-scoped database contexts and duplicated JDBC
  connections when safe, governed by one aggregate memory budget;
- writer serialization and bounded reader concurrency;
- applying and verifying settings on every database instance/connection;
- prepared statements/appenders and Arrow integration where appropriate;
- cancellation, shutdown, and leaked-connection detection;
- normalized trusted path quoting, DuckLake SQLite-catalog attach/detach, and
  snapshot pinning;
- loading only the packaged, signed, version-matched `ducklake` and `sqlite`
  extensions without a runtime network install;
- metrics for active queries, native failures, spill, and duration.

Defaults prioritize node safety over maximum analytics throughput. Bulk
catch-up and steady-state/query work have separate profiles because dedicated
indexer deployments may give backfill more memory/threads without changing the
near-tip footprint. A manager-wide budget bounds their aggregate configured
DuckDB buffer-manager limits; jobs are queued if starting another context would
exceed it.

```yaml
yano:
  history:
    duckdb:
      max-total-memory: 256MB
      max-concurrent-queries: 2
      temp-directory: "${yano.history.dir}/tmp"
      max-temp-directory-size: 2GB
      steady-state:
        reserved-memory: 128MB
        memory-limit: 128MB
        threads: 1
      bulk-catch-up:
        memory-limit: 128MB
        threads: 1
        max-concurrent-jobs: 1
```

The default aggregate budget is `256MB`, split into a reserved `128MB`
steady/query context and at most `128MB` for bulk catch-up, instead of DuckDB's
80%-of-system-RAM default. Bulk work cannot consume the steady reservation, so
a cold wallet query does not wait behind an entire bulk batch merely because
backfill is active. When no query is running the reservation is still a budget
boundary, not preallocated RSS. Both contexts spill within the configured temp
cap. Phase 0 measures whether 128MB is sufficient for the supported cold-query
service objective; operators can explicitly raise the aggregate and bulk
budgets on a dedicated analytics machine without removing a configured steady
reservation.

Configured values are parsed and validated by Yano before they are sent to
DuckDB. Empty, negative, percentage, aggregate-inconsistent, and
unrecognized-unit values fail startup when history is enabled. After
connection creation the manager verifies effective values using
`current_setting(...)` and logs them once.

`memory_limit` is not treated as a hard process RSS limit because DuckDB has
allocations outside its buffer manager and the JDBC driver is native code.
Consequently:

- thread count and query concurrency are also capped;
- history batches and Java collections are bounded;
- reward rows are streamed rather than accumulated for a whole epoch;
- temporary disk has a separate cap and free-space guard;
- out-of-memory/native errors fail the history job and close/recreate the
  manager; they do not terminate core sync where recovery is possible.

Epoch-derived archive datasets use this manager through the DuckLake store SPI;
they never call it from epoch-boundary code. The standalone SQLite backend does
not load it.

### Standalone SQLite resource management

The standalone SQLite backend has its own manager and pool; it does not start
`DuckDbManager`. It serializes Flyway migration and all writes through one
writer connection/executor, bounds reader connections and `busy_timeout`, and
reports WAL size, checkpoint latency, busy/locked failures, database size, and
integrity-check results. History row batches, prepared statements, and query
results remain bounded exactly as for DuckLake.

Flyway migration happens before opening the normal writer/read pool and never
runs concurrently with the worker or API. A migration failure closes the SQLite
data source and marks the selected history backend unavailable. It cannot leave
a partially initialized API serving an older schema.

## Configuration

The target shape is illustrative; final property names follow Yano's config
binding conventions:

```yaml
yano:
  history:
    enabled: false
    dir: ./history
    start-mode: full-required       # full-required | earliest-available | tip
    live-enabled: true              # run near-tip track during backfill

    worker:
      poll-interval-millis: 1000
      max-blocks-per-batch: 1000
      max-rows-per-batch: 250000
      bulk-pause-core-lag-blocks: 100

    rollback:
      retention-blocks: auto        # auto = 2 * genesis k

    maintenance:
      interval-seconds: 300         # optional worker only; never core apply
      time-limit-seconds: 5         # hard per-run query deadline
      max-bytes-to-rewrite: 512MB   # bounds each compaction pass

    archive:
      engine: ducklake                  # ducklake | sqlite
      finality-blocks: auto          # auto = 2 * genesis k

      ducklake:
        catalog:
          type: sqlite                  # sqlite is the initial/default catalog
          path: ./history/ducklake-catalog.sqlite
          busy-timeout-millis: 5000
          max-retries: 10
        data-path: ./history/ducklake-data
        target-file-size: 32MB
        row-group-size: 100000
        data-inlining-row-limit: 0
        snapshot-retention-hours: 168
        cleanup-grace-hours: 24

      sqlite:
        path: ./history/history.sqlite
        busy-timeout-millis: 5000
        synchronous: FULL               # FULL | NORMAL
        max-read-connections: 2
        wal-auto-checkpoint-pages: 1000
        flyway-locations:
          - classpath:db/migration/history-sqlite

    datasets:
      account-events:
        enabled: true
        start-mode: full-required    # defaults to history.start-mode
        retention-epochs: 0
      address-transactions:
        enabled: false
        start-mode: full-required
        retention-epochs: 0
      transactions:
        enabled: false
        start-mode: full-required
        retention-epochs: 0
      utxo-history:
        enabled: false
        start-mode: full-required
        retention-epochs: 0
        payloads:
          datums: false
          scripts: false
      rewards:
        enabled: false
        start-mode: earliest-available
        retention-epochs: 0
      epoch-stake:
        enabled: false
        start-mode: earliest-available
        retention-epochs: 0
      drep-distribution:
        enabled: false
        start-mode: earliest-available
        retention-epochs: 0
      ada-pots:
        enabled: false
        start-mode: earliest-available
        retention-epochs: 0
      governance-proposal-status:
        enabled: false
        start-mode: earliest-available
        retention-epochs: 0

    duckdb:
      max-total-memory: 256MB
      max-concurrent-queries: 2
      temp-directory: ./history/tmp
      max-temp-directory-size: 2GB
      steady-state:
        reserved-memory: 128MB
        memory-limit: 128MB
        threads: 1
      bulk-catch-up:
        memory-limit: 128MB
        threads: 1
        max-concurrent-jobs: 1
```

Top-level `start-mode` and `live-enabled` are dataset defaults. Any
block-derived dataset may override them; `reward` ignores `live-enabled`
because it is archive-only. The four epoch snapshot datasets are also
archive-only and do not create a live activation track. For an epoch-derived
dataset, `full-required` requires a complete durable source from its first
ledger-applicable epoch, `earliest-available` publishes the earliest retained
source epoch as its lower coverage bound, and `tip` begins with the next
completed eligible epoch at/after enablement. Missing earlier epoch sources are
never represented as empty epochs.

The DuckDB JDBC/DuckLake versions are build-pinned constants and diagnostics
output, not operator inputs. Status and startup logs report the effective
driver/extension versions; configuration cannot request a version that differs
from the packaged artifacts.

Additional validation includes:

- the selected engine is supported and its files belong to the configured
  network/genesis identity;
- the DuckLake catalog, DuckLake data path, standalone SQLite path, hot-history
  RocksDB, core RocksDB, and temp directory resolve to non-overlapping paths;
- output and temp directories are not the core RocksDB directory;
- DuckLake target file/row-group/snapshot settings are positive and bounded;
- DuckLake catalog type is `sqlite`, its extension versions match DuckDB
  `1.5.5`, and runtime extension auto-install/network download is disabled;
- standalone SQLite pragmas, reader count, timeout, and Flyway location are
  valid before migration begins;
- worker limits prevent unbounded lists/batches;
- enabled datasets have compatible source retention;
- enabled epoch datasets have a durable/restartable source for every claimed
  epoch and a source lease that outlives their pending job;
- `utxo-history.enabled=true` requires `transactions.enabled=true`, and
  transaction retention/coverage must be a superset of retained UTXO history;
- datum/script payload switches require UTXO history and never remove their
  hashes from output rows when payload bodies are disabled;
- archive identity matches configured genesis;
- only one writer owns a history directory;
- read-only secondary processes cannot become archive writers.

The existing `account-history.retention-epochs` does not become an umbrella for
all datasets. During migration it can map only to legacy account-event behavior
with a deprecation warning. New deployments use per-dataset retention.

The preview-only `yano.snapshot-export.*` configuration family is removed
rather than supported through a compatibility adapter. Equivalent functionality
is selected through the new epoch datasets and chosen archive backend. Startup
fails on an explicitly supplied removed key with a migration message; it does
not silently ignore a requested export. Existing raw `epoch=N` Parquet
directories are left untouched, are not treated as committed archive coverage,
and may be removed manually after the new archive is rebuilt and verified.

## Existing Account-History Data Migration

There is no automatic row-level migration from the legacy `account_history` CF
on `main` or from PR #58's extended rows. Those rows may be incomplete under the
old replay/UTXO-retention rules, have different rollback evidence, and do not
provide a trustworthy source for declaring archive coverage.

On upgrade:

1. the new subsystem uses its dedicated hot RocksDB database and ignores legacy
   core-DB rows as archive input;
2. existing legacy CFs are preserved and never deleted automatically;
3. every enabled new dataset rebuilds from canonical retained block/epoch
   sources according to its `start-mode`;
4. during a bounded compatibility release, legacy endpoints may remain behind
   an explicit legacy provider flag, but their coverage is not merged with the
   new provider;
5. after the new dataset has verified complete coverage, an explicit offline
   cleanup/upgrade command may drop legacy CF contents with operator approval.

If required bodies have already been pruned, the new dataset follows the normal
`full-required` failure or explicitly partial coverage rules. Copying legacy
rows is not used to conceal the gap.

## Switching Archive Backends

The SPI makes DuckLake and SQLite selectable; it does not make changing an
existing archive a live configuration flip. A mismatched configured engine and
recorded archive identity fails the history subsystem closed.

The implementation supplies an offline/restartable migration command using the
typed repositories and row sinks:

1. stop the history worker and new history API requests at a safe commit
   boundary while core sync may continue;
2. record the source backend generation, per-dataset coverage, projection
   versions, and canonical anchors;
3. create a new target archive at a different path (including Flyway migration
   when SQLite is the target);
4. stream committed logical rows in bounded canonical ranges, assigning
   deterministic migration job IDs;
5. compare per-range counts, ordered digests, primary-key uniqueness, and
   coverage against the captured source;
6. catch up any newly finalized range from canonical source/hot promotion;
7. atomically change only the history control-store backend identity/receipt,
   reopen the typed repositories, and resume history;
8. retain the source read-only until operator verification and explicit cleanup.

If canonical block/epoch sources are complete, operators may choose a clean
rebuild instead. If those sources have been pruned, verified backend-to-backend
migration is the only way to preserve the older coverage. Yano never merges two
backends opportunistically in a normal API request and never deletes the source
archive automatically after cutover.

## JVM and GraalVM Native-Image Support

The initial DuckLake and standalone SQLite implementations are supported only
in the JVM distribution. DuckDB JDBC and the Xerial SQLite JDBC driver package
native code; Yano's GraalVM native-image packaging/loading behavior must be
proven separately for each selected backend.

In a native-image process, enabling the DuckLake or standalone SQLite archive
backend is a startup configuration error with a clear backend-specific message;
it must not fall through to a late native link/load error. Disabled native
builds must not initialize either driver or Flyway. Native support is enabled
per backend only after CI builds and runs the supported OS/architecture matrix,
verifies native library extraction/loading and shutdown, and passes the same
crash, migration, memory, query, and history correctness tests. This limitation
is documented in the native distribution README and configuration reference.

## Epoch-Derived Data and Replacement of the Preview Exporter

Not every future dataset is cheaply re-derived from a block body alone. DRep
distribution, stake snapshots, Ada pots, proposal status, and rewards are
produced by epoch-boundary calculations.

The generic layer supports two durable source types:

1. block-derived datasets read canonical retained block bodies;
2. epoch-derived datasets read a durable epoch snapshot/result or a restartable
   epoch job keyed by epoch and source-state version.

Epoch processing must not call DuckDB synchronously. After authoritative epoch
state commits, it records a small durable export job/reference. The background
worker reads the corresponding state snapshot and writes the selected archive
backend. If a source snapshot would otherwise be pruned, the pending job holds
a retention lease.

If the current implementation has only an ephemeral in-memory result, that
dataset is not declared restart-safe until a durable source or deterministic
recomputation path exists. Dropping a completion stage or publishing a lossy
event is not a durability mechanism.

The initial epoch dataset migration is:

| Dataset | Durable source used by `archive-core` |
|---|---|
| `epoch_stake` | Stream the committed epoch delegation snapshot by epoch. Its existing pruning is held by a pending archive-source lease. Do not construct a second complete Java list. |
| `drep_distribution` | Stream the committed distribution and persist the exact boundary-only virtual-DRep and expiry/activity fields needed by the archive projection. Reading mutable latest DRep state later is not an equivalent snapshot. |
| `ada_pot` | Read the committed per-epoch AdaPot record after governance adjustment. |
| `governance_proposal_status` | Persist the exact Phase 0-approved observation phase, ratification outcome, decision reason, and any in-scope enactment/drop lifecycle rows as bounded/restartable source staging. The current result/reason data is ephemeral and pending enactment/drop keys do not represent the complete observation. |
| `reward` | Use the archive-only durable reward job/source described above; never an epoch-sized callback or hot-history batch. |

Every job includes network/genesis identity, epoch, canonical boundary block
hash/slot, dataset and projection version, source-state version, and a
deterministic job ID. A job is discoverable after restart by comparing committed
epoch state with the dataset cursor, so losing an in-memory wake-up cannot lose
the archive range. Normal export waits for archive finality; rollback before
then cancels or replaces the pending job. Exceptional rollback of an already
archived epoch uses backend invalidation/rebuild rather than deleting files by
directory name.

There is no final compatibility adapter. Implementation removes:

- the `epoch-export` Gradle module and application dependency;
- `EpochSnapshotExporter` and its `NOOP`/record types from `ledger-state`;
- its ServiceLoader and native-image descriptors;
- exporter fields, setters, full-list construction, and synchronous callbacks
  in account, governance, and epoch-boundary processing;
- `wireSnapshotExporter`, direct exported-directory rollback cleanup, and the
  preview `yano.snapshot-export.*` property keys.

Dataset records, address/identifier derivation, schemas, comparison fixtures,
and verification scripts may be moved into `archive-core` tests/projections.
The raw `ParquetEpochSnapshotExporter` implementation and its per-call DuckDB
connections are not reused.

## Failure Isolation and Backpressure

History failures are classified and observable:

- transient I/O/DuckDB/DuckLake/SQLite lock failure: retry with bounded
  exponential backoff and a hard attempt/deadline limit;
- disk full/free-space threshold: pause export and queries that require new
  files; keep core sync running and alert;
- missing block body: stop at the gap and mark coverage incomplete;
- canonical mismatch: discard/reconcile uncommitted work and rollback hot state;
- corrupt committed DuckLake file or SQLite page/table: hide affected coverage,
  restore or rebuild;
- schema/projection mismatch: require migration or rebuild;
- Flyway validation/migration failure: do not open the standalone SQLite API or
  worker; preserve the database for operator repair;
- unresolved address input: stop the affected dataset/range, never skip;
- DuckLake catalog corruption: open no unverified snapshot; restore catalog and
  data consistently or rebuild;
- repeated native crash: circuit-break the selected archive backend while
  leaving core state available.

Queues, pending batches, DuckLake temporary data, and SQLite transactions are
bounded. If the worker cannot keep up, its lag and source retention grow; it
does not push blocking work back into block apply. Operators may disable a
high-volume dataset, increase resources, or accept additional block-body disk
use.

## Shutdown, Restart, and Snapshot Restore

Graceful shutdown stops new history queries/jobs, waits a bounded time for a
safe batch boundary, persists cursors/commit receipts, and closes the selected
archive manager before history RocksDB. An interrupted batch is recovered
through backend transactions and deterministic archive job IDs.

Kill-9 recovery tests must cover every archive commit step and every hot
RocksDB batch boundary. At restart, the worker validates the canonical hash at
its cursor before processing more blocks.

Core snapshot restore and devnet time travel treat history as derived external
state:

- stop new core block reads and let any bounded in-flight copy finish before
  core RocksDB handle replacement; archive and history-DB work need not hold
  the core restore gate;
- keep/reopen the separate hot history/resolver database, then reconcile it
  against the restored canonical chain;
- compare all cursors and archive range anchors with restored canonical chain;
- exact-undo the hot window where evidence exists;
- invalidate and rebuild archive ranges when restore crosses the archive
  boundary;
- never delete DuckLake files or SQLite rows solely by numeric epoch without
  consulting committed archive jobs and canonical range anchors.

A restore failure leaves history unavailable and does not serve mixed
pre/post-restore data.

## Schema Evolution and Compatibility

Logical schemas are versioned per dataset and stored in archive commit/coverage
metadata. Initial physical tables and public/convenience views have stable
unversioned names. A suffix such as `_v1` is not part of the normal naming
convention and is not added merely because this is the first schema revision.

For DuckLake, additive compatible changes use transactional schema evolution on
the existing table. For standalone SQLite, every DDL/index/view change is a
reviewed Flyway migration; a normal additive change adds a column to the
existing table with a nullable value or deterministic default. Adding a column
also defines its historical semantics: either older rows legitimately remain null,
the value is deterministically backfilled by a bounded background job, or
coverage metadata states the first projection version/range for which it is
complete. An API must not imply that a newly added field is historically
complete when it is not.

An incompatible change—such as changing row grain, primary-key identity,
canonical ordering, amount representation, partitioning, or the meaning of
existing rows—uses an explicit rebuild/cutover. A temporary versioned or shadow
table may be created for that migration, but it is an implementation detail,
not the initial or permanent public table naming scheme. The worker builds it
in bounded ranges, validates keys/counts/digests and coverage, then atomically
switches the stable repository/view contract. Old DuckLake snapshots or old
SQLite tables are retained only for the configured query-drain/recovery window
and removed by explicit post-cutover maintenance.

Large SQLite history backfills or projection rebuilds are background jobs, not
implicit Flyway DML over an unbounded table at startup. Flyway creates the new
schema objects and records the migration; the archive worker performs bounded
data movement before the final validated cutover.

Readers declare the logical schema versions they support. A backend with an
unknown newer schema or Flyway version is not silently interpreted.

REST compatibility is separate from physical schemas. DTOs continue to encode
large quantities as strings, normalize addresses/IDs, and keep endpoint
pagination semantics stable even when the underlying archive schema evolves.

## Security and Operational Boundaries

- History directories are local trusted operator paths, not request-controlled
  paths.
- SQL templates and identifiers are fixed by dataset code; request values use
  parameters.
- DuckDB extension auto-install/network download and unsigned/community
  extensions are disabled. Version-matched signed DuckLake and SQLite
  extensions are packaged and loaded explicitly.
- External DuckLake readers are read-only and operator-controlled. A history
  API cannot expose arbitrary SQL or filesystem Parquet scans.
- Checksums detect accidental corruption; they are not a substitute for
  trusted storage permissions.
- DuckLake backups include a mutually consistent catalog snapshot and all data
  files needed by retained snapshots. Standalone SQLite backups use the SQLite
  online-backup mechanism or a coordinated checkpoint/copy that includes the
  required database/WAL state. Copying only one DuckLake component or a live
  SQLite main file without its required WAL can produce an unusable archive.

## Implementation Baseline and Branch Strategy

Implement this ADR on a clean feature branch from the latest `main`, then port
only reviewed pieces from PR #58. PR #58 is a single large wallet-serving commit
that combines storage, REST, SSE, and transaction-status concerns. Removing its
hot history additions while preserving unrelated endpoint changes is more
error-prone than selective porting.

The clean branch still contains the account-event `AccountHistoryStore`
foundation already present on `main`. Migrate that foundation to the worker in
phases; do not claim complete hot-path isolation while its block listener still
writes history synchronously.

The clean branch initially also contains the preview `epoch-export` module.
It may coexist temporarily on the implementation branch while the durable
sources, both backends, worker, and replacement epoch projections are being
built, but one configuration must never write an epoch through both paths.
Remove it before the replacement series is merged, only after at least one
complete epoch is exported and queried through each required backend and the
replacement conformance tests pass. Thus no merged/released revision has a
capability gap or two public export paths. Because the feature is preview-only,
no deprecated callback adapter or legacy configuration mode is carried forward.

Candidate pieces to port after their backing providers exist:

- address normalization/key utilities after simplifying their parse contract;
- REST DTOs/resources with corrected pagination and string quantities;
- transaction status API after `transaction` is durable;
- SSE fan-out separately. PR #58 subscribes the fan-out at priority 0 while
  UTXO/account/history stores apply at priorities 100-112, so it can announce a
  block before wallet-query state reflects that block. The port must publish
  only after the relevant core stores commit, filter non-real reconnect
  rollbacks, and handle send completion/dead clients.

Do not port as the new storage foundation:

- unlimited address/reward rows in the existing `account_history` CF;
- current-UTXO-dependent sender replay;
- reward history written as a single epoch-scale RocksDB batch;
- slot-scan rollback fallback;
- tx status inferred from retained UTXO outputs.

The ADR should be committed independently so it can be cherry-picked to the
clean implementation branch before code changes begin.

## Implementation Plan

### Phase 0: Measurements and contracts

1. Add mainnet/preprod fixture counts and benchmarks for row cardinality,
   DuckLake/Parquet and SQLite size, point queries, range queries, and bulk
   ingestion throughput.
2. Benchmark normalized output-plus-native-asset tables against a wide
   one-row-per-amount layout across representative ADA-only, multi-asset,
   datum-heavy, and script-heavy epochs in both backends.
3. Define every dataset schema, stable primary key, coverage semantics, API
   pagination cursor, and the exact allowlist/meaning of any transaction scalar
   fields beyond hash, canonical coordinates, validity, and fee.
4. Define the backend SPI, deterministic archive job/receipt protocol, and
   shared conformance test fixtures executed against DuckLake and SQLite.
5. Add block-reader canonical-hash and earliest-retained-body capabilities.
6. Add security-parameter-based validation using genesis `k`.
7. Benchmark an accelerator-first DuckLake tx-hash lookup plan and equivalent
   SQLite indexed plan. Inspect actual Parquet metadata/probes to measure Bloom
   filters only as an optional encoding-dependent optimization; do not force
   high-cardinality dictionary encoding merely to obtain a filter.
8. Verify DuckLake explicit `SNAPSHOT_VERSION` reads with a SQLite catalog
   across multiple request subqueries, per-query attach/detach, concurrent
   commits, bounded lock retries, cancellation, active-reader snapshot
   retention, and maintenance.
9. Verify pointer-address stake-reference behavior per supported era against
   the pinned Haskell `cardano-ledger` source and fixtures before defining the
   registration projection or credential-query expectations.
10. Resolve the governance proposal observation phases, status codes, delayed
    decision reasons, and enacted/dropped lifecycle scope against Yano's
    `RatificationEngine`, pinned Haskell ledger behavior, and repository Issue
    #47's regression fixture before freezing tables, views, or API values.
11. Establish repeated core-sync non-regression baselines before changing the
   history implementation.

### Phase 1: Shared archive infrastructure

1. Create `archive-modules/` with `archive-api`, `archive-core`,
   `archive-store-ducklake`, and `archive-store-sqlite`; register their nested
   Gradle project paths and enforce the dependency direction above.
2. Upgrade and pin DuckDB JDBC `1.5.5.0`; package the compatible signed
   DuckLake and SQLite extensions for supported platforms.
3. Implement the bounded `DuckDbManager` inside `archive-store-ducklake`,
   reusing applicable Yaci Store DuckLake connection/catalog patterns rather
   than retaining `epoch-export` as a shared utility module.
4. Enforce a default aggregate `256MB` DuckDB budget with a `128MB`
   steady/query reservation and `128MB` bulk limit, bounded queries, and
   managed temp storage.
5. Add the backend SPI, logical schemas/views, commit receipts, archive
   identity, typed repositories, and conformance test fixtures shared through
   Gradle `java-test-fixtures`.
6. Define the durable epoch-source/job interfaces and bounded staging formats;
   do not remove the preview exporter until the Phase 5 replacement is
   end-to-end operational.

### Phase 2: DuckLake backend

1. Implement DuckLake with its SQLite catalog and separate Parquet data path.
2. Set up stable unversioned tables/views, schema/projection version metadata,
   partitioning, compression, zero data inlining, snapshot capture, idempotent
   job commits, and canonical coverage metadata.
3. Implement bounded snapshot expiration, file merge/rewrite, old-file cleanup,
   orphan cleanup, integrity/backup procedures, and catalog lock retry limits.
4. Test concurrent Yano writer/API readers and an independent read-only local
   DuckDB client without core-path backpressure.

### Phase 3: Standalone SQLite backend with Flyway

1. Add the Xerial SQLite JDBC driver, Flyway SQLite support, the dedicated
   migration location, and programmatic validate/migrate lifecycle.
2. Use Flyway to create stable unversioned dataset tables, indexes, convenience
   views, `archive_commit`, `archive_coverage`, and genesis/backend identity;
   keep schema versions in Flyway/archive metadata rather than table names.
3. Implement WAL/single-writer/bounded-reader configuration, transactional
   idempotent commits, retention, checkpoints, integrity checks, backup, and
   maintenance.
4. Run the same backend conformance suite and logical API fixtures as DuckLake.
   Both backends are required for completion of this ADR's initial storage
   layer; SQLite is not a post-release placeholder.

### Phase 4: Worker and source retention

1. Add optional history subsystem lifecycle, per-dataset live/backfill progress,
   explicit coverage, health/readiness, and lag metrics.
2. Add canonical block-range reader and block-body retention leases.
3. Implement a dedicated hot-history RocksDB, bulk direct-to-selected-backend,
   concurrent near-tip projection, and pinned hot-row promotion/query snapshots.
4. Add exact per-block undo and eliminate full-slot-scan rollback from the new
   path.

### Phase 5: Epoch datasets and preview-exporter replacement

1. Implement durable/restartable sources and selected-backend projections for
   `epoch_stake`, `drep_distribution`, `ada_pot`, and
   `governance_proposal_status`, including their tables/views, per-dataset
   configuration, coverage, retention, invalidation, and query repositories.
2. Stream epoch-stake and DRep rows in bounded batches; persist the exact
   ephemeral boundary fields identified in the source-migration table. Expose
   governance decision reasons as structured durable result data; never parse
   log messages to construct archive rows.
3. Verify at least one complete epoch through DuckLake and standalone SQLite,
   including restart, duplicate job, rollback/invalidation, source lease, and
   API/repository reads.
4. Only then remove the `epoch-export` project from `settings.gradle` and the
   application dependency, delete `EpochSnapshotExporter`, its ServiceLoader
   and native-image descriptors, remove runtime/caller wiring and raw-directory
   rollback cleanup, and reject the removed `yano.snapshot-export.*` keys with
   the documented migration message.

### Phase 6: Low-volume block datasets

1. Move existing account events from synchronous block apply to the worker.
2. Add `transaction`, including phase-2-invalid validity and only the scalar
   analytics fields approved in Phase 0.
3. Implement hot/cold merged providers and transaction status.

### Phase 7: Address transaction history

1. Add the canonical address dictionary/key codec and collision checks before
   any `address_transaction` row can use `address_key`.
2. Add private sequential outpoint resolver and checkpoints.
3. Cover ordinary input/output and phase-2 collateral semantics.
4. Add exact-address and credential-scope query plans for both archive backends.
5. Test deep replay after core spent-UTXO pruning.

### Phase 8: Optional normalized UTXO history

1. Reuse the canonical address dictionary/key codec established in Phase 7.
2. Add `transaction_output`, `transaction_output_asset`, and
   `transaction_input` with atomic group coverage and phase-2 validity,
   collateral, reference-input, and spend semantics.
3. Add optional content-addressed datum/script payload tables while always
   retaining their hashes on outputs.
4. Add merged amount, lifecycle, unspent, address-UTXO, and asset-flow views;
   verify exact-address, payment-credential, stake-credential, asset, outpoint,
   and point-in-time spent/unspent query plans in both backends.
5. Keep the dataset disabled by default until storage/cardinality, backfill,
   promotion, rollback, and API-query benchmarks pass.

### Phase 9: Optional reward history

1. Establish a durable/restartable epoch reward source.
2. Keep reward history archive-only and stream complete classifications into
   bounded backend batches after the finality boundary.
3. Add invalidation/rebuild for epoch rollback and snapshot restore.
4. Enable only after memory, sync-lag, and query benchmarks pass.

### Phase 10: API and PR #58 porting

1. Port wallet APIs against the new providers.
2. Expose history status/coverage and consistent incomplete behavior.
3. Port and independently correct SSE behavior.
4. Add explicit legacy cleanup tooling; remove/deprecate legacy configuration
   and synchronous indexes after migration compatibility is no longer required.

## Testing Requirements

At minimum, with all logical dataset/API fixtures parameterized across both
archive engines where applicable:

- history disabled has zero DuckDB/DuckLake/SQLite/Flyway/archive-write
  activity and no separately enabled preview snapshot-export path exists;
- DuckDB JDBC reports `1.5.5` and only the packaged compatible DuckLake/SQLite
  extensions load; offline startup does not attempt extension download;
- DuckLake uses a SQLite catalog separate from its Parquet data path, Flyway
  never touches that catalog, and catalog lock timeout/retry cannot block core
  sync;
- standalone SQLite initializes and upgrades from every supported Flyway schema
  version; checksum mismatch, unknown non-empty schema, failed migration, and
  concurrent migration all fail history closed without affecting core startup;
- standalone SQLite applies and verifies WAL, foreign-key, busy-timeout, and
  durability settings and creates all expected indexes/views;
- selected-backend path identity rejects DuckLake catalog/data, standalone
  SQLite, hot history, temp, and core database overlap;
- core sync reaches tip while a deliberately slow/failing history worker lags;
- bulk backfill remains paused/yields during core catch-up and induced core
  RocksDB/CPU/disk pressure;
- native-image startup rejects enabled DuckLake/SQLite archive backends clearly
  and the disabled native path initializes neither driver nor Flyway;
- enable-later full replay succeeds when bodies are retained;
- enable-later full replay fails explicitly at the first pruned body;
- `tip`, `earliest-available`, and `full-required` produce the documented
  per-dataset coverage ranges;
- archive-only epoch start modes use eligible epoch-source bounds rather than
  block live anchors and never represent unavailable earlier sources as empty;
- live projection serves post-activation transactions while an independent
  historical backfill is still running;
- `utxo_history` dual-tracks without a seeded UTXO resolver, while joins across
  its uncovered live/backfill gap report incomplete rather than fabricating
  output details;
- bulk finalized rows never transit the history RocksDB query CF;
- hot history, undo, resolver, and pinned query snapshots use the dedicated
  history RocksDB and create no core RocksDB writes/compaction stalls;
- near-tip promotion copies pinned hot rows and does not require routine block
  re-derivation;
- receiver and sender history match after core spent-output pruning;
- genesis/AVVM and Shelley `initialFunds` spends resolve after resolver seeding;
- live resolver activation from a current-UTXO snapshot merges with the
  genesis backfill only when resolver state hashes agree;
- live resolver seeding loads from a detached checkpoint and does not hold a
  core DB handle throughout the UTXO scan or block core snapshot restore;
- rollback below a resolver-dependent live activation anchor discards the
  orphaned base/checkpoint and old live coverage, creates and verifies a new
  canonical anchor, and leaves the affected range explicitly unavailable until
  re-activation completes;
- phase-2-invalid collateral input/return and tx validity are indexed;
- both backends physically store the logical `transaction` dataset in
  `chain_transaction` and expose the same `transactions` view;
- with UTXO history disabled, no output/asset/input/address/payload row or hot
  key is produced;
- canonical address-key encoding round-trips Shelley and Byron forms, treats
  different textual encodings of identical bytes consistently, and fails on a
  synthetic digest collision with different raw bytes;
- base, enterprise, pointer, and Byron address credential semantics match
  pinned Haskell `cardano-ledger` fixtures for every supported era; pointer
  coordinates remain available even where the era does not treat them as an
  effective stake reference, and any required mapping rolls back exactly;
- Byron genesis/AVVM and Shelley `initialFunds` appear once as typed genesis
  outputs and later spends join them through the same canonical outpoint IDs as
  the resolver, without an invented parent transaction;
- every output produces exactly one `transaction_output` row; ADA-only outputs
  produce no asset child and every native asset produces exactly one
  `transaction_output_asset` row;
- the merged amount view produces one lovelace row plus all native-asset rows
  with the same result, ordering, and exact quantities on DuckLake and SQLite;
- no output-level address, credential, datum/script payload, or block hash is
  duplicated into native-asset physical rows;
- address, payment-credential, and stake-credential output/history queries are
  complete across hot/cold promotion, use the expected SQLite composite indexes,
  and meet the benchmarked DuckLake row-group/accelerator scan bound;
- live-first then backfill-earlier address discovery converges to the minimum
  canonical `first_seen_*`; invalidating that minimum recomputes or removes the
  dimension row, while unreferenced content-addressed payloads are harmless and
  eventually garbage-collected;
- transaction inputs distinguish ordinary, collateral, and reference roles;
  `consumes_output` matches valid and phase-2-invalid ledger application and at
  most one canonical consuming input exists per outpoint;
- output-lifecycle and point-in-time spent/unspent queries match authoritative
  UTXO fixtures before, at, and after a spend, including collateral return;
- disabling datum/script payloads retains their hashes but writes no payload
  bodies; enabling them deduplicates identical CBOR by content hash;
- one UTXO archive job promotes or invalidates output, asset, input, address,
  and enabled payload rows at a single coverage boundary in either backend;
- retaining/pruning UTXO history never leaves a view claiming complete coverage
  over missing parent/child ranges, and transaction coverage remains a superset;
- unresolved inputs stop cursor advancement;
- empty blocks advance cursor and remain rollback-detectable;
- rollback before/at/after each dataset live tip is idempotent;
- reconnect/non-real rollback does not remove history;
- every hot dataset and resolver mutation is exactly undone;
- normal rollback performs no full history scan;
- rollback crossing either finalized backend invalidates before queries can
  observe orphan rows;
- archive commit crash tests cover each numbered commit step for DuckLake and
  SQLite, especially archive commit before RocksDB cursor receipt;
- a committed missing/corrupt DuckLake file or corrupt SQLite archive is hidden
  and recoverable/rebuildable;
- query during hot-to-cold promotion has no gaps/duplicates;
- a query whose captured DuckLake snapshot or SQLite read transaction predates
  promotion still reads deleted hot keys through its pinned RocksDB snapshot,
  and always releases both snapshots/transactions;
- ascending/descending pagination crosses the archive boundary stably;
- pagination resumes across non-overlapping archive commits and returns an
  explicit expired cursor when retention/invalidation removes the required
  DuckLake snapshot or SQLite range;
- per-dataset retention does not alter core UTXO/account pruning;
- undo is retained while archive commit fails and removed after safe promotion;
- body pruner respects worker/job/checkpoint low watermarks;
- kill-9 and graceful restart resume every dataset/track from the correct
  cursor/watermark;
- snapshot restore inside and across archive boundary is consistent;
- archive cannot open under the wrong genesis identity;
- schema upgrade/rebuild does not mix incompatible rows;
- legacy rows are neither trusted nor deleted automatically; source rebuild and
  explicit cleanup follow the migration contract;
- DuckLake transaction lookup uses and verifies its derived locator before
  probing the table; any Bloom filter is accepted only when actual file metadata
  and benchmarks prove a net benefit, while SQLite uses and verifies its
  transaction-hash index;
- DuckDB effective aggregate budget is `256MB` by default, steady/query work
  retains a `128MB` reservation during a `128MB` bulk backfill, and both are
  configurable without allowing bulk work to consume the steady reservation;
- invalid DuckDB memory/thread/temp settings fail startup when enabled;
- stress tests show bounded Java heap, native RSS, query concurrency, DuckLake
  temporary/files, SQLite WAL size, and reward batch size;
- reward history creates no hot rows or undo entries and is visible only after
  its finalized backend commit;
- each epoch dataset can be enabled independently and produces identical
  logical rows, epoch coverage, boundary anchors, and invalidation behavior on
  DuckLake and SQLite; exporter removal tests prove there is neither a missing
  replacement nor a second public export path;
- governance proposal fixtures preserve observation phase, outcome, and delayed
  reason separately and cover every Phase 0-approved ratification and
  enactment/drop lifecycle value without an unknown-to-active fallback;
- one request's DuckLake cold subqueries all use the same explicit snapshot ID
  across per-query attach/detach while concurrent writes and bounded retries
  proceed; cancellation releases its active-snapshot lease;
- DuckLake maintenance does not expire active snapshots; standalone SQLite
  checkpoint/vacuum does not violate active query or commit semantics;
- backup/restore tests keep DuckLake catalog plus data files consistent and
  restore standalone SQLite from a coordinated backup;
- DuckLake-to-SQLite and SQLite-to-DuckLake offline migration preserve
  per-dataset rows, ordering, digests, coverage, and canonical anchors; crash
  before cutover leaves the source active and target restartable;
- disk-full, DuckDB OOM, SQLite busy/locked, Flyway failure, and backend
  corruption isolate history from core sync;
- standalone SQLite on the known public mainnet genesis emits the non-blocking
  scale warning, while a custom network sharing only its magic does not;
- REST quantities remain lossless strings above JavaScript's safe integer range.

## Consequences

Positive:

- optional history no longer determines core sync throughput or correctness;
- old history is written directly to the selected finalized archive rather than
  churned through RocksDB;
- enabling history later is deterministic when source blocks exist;
- sender history no longer depends on live spent-UTXO retention;
- rollback cost is bounded to an exact recent journal;
- DuckLake provides cataloged tables/views, ACID snapshots, schema evolution,
  and managed Parquet lifecycle instead of a custom file manifest;
- standalone SQLite provides a one-file, indexed alternative with Flyway-owned
  schema evolution;
- epoch stake, DRep distribution, Ada pots, and governance proposal status use
  the same durable asynchronous coverage/query model instead of synchronous raw
  Parquet callbacks;
- the backend-neutral layer can host more historical/analytics datasets
  consistently;
- normalized output/asset/input tables support address, stake credential,
  asset, outpoint, lifecycle, and point-in-time UTXO analytics without JSON
  parsing, while stable views preserve a convenient flattened amount shape;
- output-level datum, script, address, and block data are not multiplied by the
  number of assets in an output;
- DuckDB resource use is explicit and conservative by default;
- reward history can remain independently disabled for basic wallets.

Tradeoffs:

- history availability lags core chain availability;
- epoch-derived archive rows appear only after their durable source and archive
  finality boundary, unlike the preview exporter's immediate boundary file;
- full history still requires reading and interpreting every source block;
- a private resolver duplicates the unspent outpoint mapping needed for
  independent replay;
- lagging history can retain block bodies longer and increase disk usage;
- hot/cold query merging and backend commit lifecycle are more complex than one
  RocksDB CF;
- two archive implementations require a conformance suite and twice the
  migration/maintenance/backup coverage;
- DuckLake's catalog snapshot expiration is global, while logical retention is
  per dataset;
- SQLite can use substantially more storage and write amplification than
  Parquet at full-mainnet scale and has one writer;
- normalized UTXO analytics require joins; stable views hide common joins but
  complex ad-hoc queries still need awareness of output, asset, and spend grain;
- enabling complete UTXO history substantially increases archive size and
  backfill work even with normalized storage, so it remains independently
  optional;
- Parquet is inefficient for tiny immediate writes, hence the mutable hot zone;
- very deep rollback or devnet restore can require partition rebuild rather
  than instant undo;
- the default aggregate 256MB DuckDB budget, 128MB steady reservation, 128MB
  bulk limit, and one-thread profiles favor isolation over maximum export
  speed; dedicated deployments may need tuning.

## Rejected Alternatives

### Keep unlimited history in RocksDB

Rejected because it couples optional write amplification and unbounded storage
to sync, and makes large-scale rollback/maintenance scans risky.

### Write to RocksDB first, move to Parquet during pruning

Rejected as the primary architecture because it does not improve initial sync
and adds conversion/read/delete work. It may be used only for the bounded
near-tip mutable zone.

### Write every new block directly to Parquet

Rejected because Parquet is immutable/batch-oriented and recent blocks can
rollback. It would produce small files and expensive rewrites.

### Make standalone SQLite the only archive backend

Rejected because billion-row B-tree/index maintenance and write amplification
are less suitable than columnar immutable files for a full-mainnet analytics
workload. SQLite remains a first-class selectable backend, not merely a
manifest or accelerator, while DuckLake is the default for large/unlimited
history.

### Manage raw Parquet and a custom Yano manifest

Rejected because it duplicates catalog transactions, snapshot visibility,
schema/view metadata, active-reader protection, compaction, snapshot expiry,
and orphan cleanup already provided by DuckLake.

### Store one wide output row per amount/asset

Rejected as the physical model because it repeats address, credential, datum,
script, transaction, block, and spend-independent output data for ADA and every
native asset. Parquet compresses repetition but SQLite does not obtain the same
benefit, and datum/script payload duplication is especially wasteful. The
normalized output-plus-native-asset model is authoritative; a stable view
provides the wide one-row-per-amount interface when convenient.

### Store transaction inputs/outputs or amounts as JSON

Rejected for fields with a stable relational grain because it prevents normal
column statistics/indexes, complicates exact asset and credential predicates,
and makes cross-backend behavior less predictable. JSON remains appropriate
only for genuinely semi-structured optional metadata after a separate schema
decision.

### Keep permanent undo journals for unlimited history

Rejected because exact undo is required only for the supported mutable window.
Finalized archive ranges use a new backend invalidation transaction and rebuild
for exceptional deep rollback.

### Use an in-memory event stream as the history source

Rejected because events can be missed across crash, startup ordering, or
reconnect. Durable canonical blocks/epoch results plus a cursor are required.

### Permit rollback retention below `k`

Rejected because it permits the node to discard undo/archive safety while the
chain is still within the network security bound. There is no unsafe escape
hatch.

## Open Measurement Questions

The architecture is decided, but these values require benchmarks before being
treated as stable defaults:

- optimal Parquet target size and row-group size for wallet point/range queries;
- optimal physical representation, size, and rebuild/checkpoint strategy for
  the required DuckLake transaction locator;
- whether address/stake queries need the narrow `output_subject` accelerator;
- optimal DuckLake sorting/zone-map layout and whether optional Bloom filters
  on any measured low/moderate-cardinality predicate justify their size without
  forced dictionary encoding;
- full-mainnet normalized UTXO/archive size with datum/script payloads disabled
  and enabled, compared with the wide flattened Yaci Store layout;
- maximum useful history query concurrency under the default aggregate 256MB
  DuckDB budget;
- bulk-catch-up memory/thread settings needed for an acceptable mainnet
  backfill time without core-path pressure;
- safe worker batch sizes for mainnet reward epochs;
- practical upper scale, index set, WAL checkpoint cadence, and vacuum policy
  for standalone SQLite;
- whether multiple DuckLakes are justified for materially different physical
  snapshot-retention classes;
- acceptable source block-body growth at expected worker lag.

## References

- `epoch-export/.../ParquetEpochSnapshotExporter.java` — preview DuckDB/Parquet
  exporter whose projection/schema lessons are retained but whose module and
  direct writer are removed by this ADR.
- `ledger-state/.../export/EpochSnapshotExporter.java` — preview synchronous
  callback SPI removed by this ADR after durable epoch archive sources exist.
- `ledger-state/.../AccountHistoryStore.java` — existing synchronous history,
  undo, replay, and pruning implementation.
- `runtime/.../chain/BlockPruner.java` — current block-body pruning path that
  needs a consumer low watermark.
- ADR-018 — REST compatibility and earlier account/reward history plan; storage
  portions superseded by this ADR.
- ADR-021 — snapshot restore and DB lifecycle considerations.
- ADR-027 — first-class Parquet/DuckDB analytics direction.
- Yaci Store `analytics-store/.../StorageWriter.java`,
  `DuckLakeWriterService.java`, `DuckLakeCatalogInitializer.java`, and
  `DuckDbConnectionHelper.java` — existing DuckLake patterns to reuse/refine.
- Yaci Store `stores/utxo/.../V0_200_1__init.sql`,
  `V0_1500_2__create_address_utxo_flattened_view.sql`,
  `TransactionOutputsExporter.java`, `SpentOutputsExporter.java`, and
  `TransactionExporter.java` — source schema and flattened analytics patterns
  used as references, with the normalization changes recorded here.
- [DuckDB 1.5.5 release](https://duckdb.org/2026/07/22/announcing-duckdb-155)
  — selected DuckDB/JDBC baseline.
- [DuckLake catalog selection](https://ducklake.select/docs/stable/duckdb/usage/choosing_a_catalog_database)
  — DuckDB, SQLite, and PostgreSQL catalog concurrency characteristics,
  including SQLite's per-query attach/detach and retry model.
- [DuckLake connecting](https://ducklake.select/docs/stable/duckdb/usage/connecting)
  — explicit snapshot-version attachment and read-only connection parameters.
- [DuckLake views](https://ducklake.select/docs/stable/duckdb/advanced_features/views)
  and [transactions](https://ducklake.select/docs/stable/duckdb/advanced_features/transactions)
  — persistent views and snapshot/transaction semantics.
- [DuckLake maintenance](https://ducklake.select/docs/stable/duckdb/maintenance/recommended_maintenance)
  — snapshot/file cleanup is explicit rather than automatic.
- [DuckLake unsupported features](https://ducklake.select/docs/stable/duckdb/unsupported_features)
  — no secondary indexes or enforced primary/unique constraints on lake tables.
- [Flyway SQLite support](https://documentation.red-gate.com/flyway/reference/database-driver-reference/sqlite)
  — Xerial driver, migration syntax, and SQLite migration-locking limitations.
- [DuckDB configuration reference](https://duckdb.org/docs/stable/configuration/overview)
  — `memory_limit`, threads, temporary storage, and effective-setting queries.
- [DuckDB JDBC client](https://duckdb.org/docs/current/clients/java) — JDBC
  connection configuration and duplicated connections.
- [DuckDB Parquet Bloom filters](https://duckdb.org/2025/03/07/parquet-bloom-filters-in-duckdb)
  — encoding-dependent Bloom-filter creation (dictionary-encoded chunks),
  metadata/probe support, and the dictionary-size tradeoff.
- [DuckDB memory management](https://duckdb.org/2024/07/09/memory-management)
  — buffer-manager limit, spill directory, and default memory behavior.
- [DuckDB out-of-memory guidance](https://duckdb.org/docs/current/guides/performance/oom)
  — limits outside the buffer manager and related thread/memory controls.
- [Haskell `cardano-ledger`](https://github.com/IntersectMBO/cardano-ledger)
  — source of truth for era-specific pointer-address and stake semantics.
