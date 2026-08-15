# ADR-036: Pluggable Hot-History Store with an Append-Only SQLite Option

## Status

Proposed

## Date

2026-08-15

## Context

[ADR-034](034-optional-asynchronous-history-archive.md) separates optional
history projection from authoritative core ledger processing. Its current hot
layer is a dedicated RocksDB instance. One default column family multiplexes
recent fact rows, resolver state, rollback undo, block checkpoints, projection
progress, archive receipts, block-body leases, and durable block-body
requirements through binary key prefixes.

The RocksDB implementation is correct and isolated from core state, but its
storage contract is expressed as opaque key/value mutations. Recent archive
facts are encoded into values, subject queries scan a bounded prefix and filter
in Java, rollback depends on generic before-image records, and promotion must
decode those rows before appending them to DuckLake or standalone SQLite.

The standalone SQLite archive introduced by ADR-034 already demonstrates that
the logical archive schemas map naturally to relational tables, indexed point
lookups, transactional writes, Flyway migrations, and stable query views. A
bounded SQLite hot database may therefore make recent queries, rollback,
inspection, and promotion easier to reason about while retaining the existing
DuckLake or standalone SQLite durable archive choices.

The hot layer has three responsibilities that must move together:

1. recent, rollback-sensitive history facts;
2. independent resolver state used to attribute inputs and pointer addresses;
3. operational control state used for restart, pruning coordination, and
   idempotency.

Moving only fact rows to SQLite while leaving resolver/control state in
RocksDB would require an atomic commit across two databases and create more
failure states. This ADR considers only a complete hot-layer implementation.

The archive resolver is not the authoritative current UTXO store. Core RocksDB
continues to own current ledger UTXO and account state. The archive resolver is
an independent asynchronous projection whose narrow purpose is to map a
transaction input's referenced outpoint to the address and credentials of its
original output. This independence is required because an input does not carry
its source address, core may already have consumed or pruned the output before
the asynchronous history worker sees it, public UTXO history is optional, and
DuckLake point lookups must not enter the write path.

"Live" means near the local core tip, not necessarily near the public network
tip. During accelerated initial sync, blocks applied after the live activation
anchor also flow through the hot layer. The candidate SQLite implementation
must therefore be measured against full-sync ingestion, not only the public
network's steady one-block-per-slot-boundary workload.

## Decision

Introduce a backend-neutral `HotHistoryStore` contract and retain
`RocksDbHotHistoryStore` as the selected implementation while a complete
SQLite implementation is built and compared.

The SQLite implementation, `SqliteHotHistoryStore`, will replace the whole
dedicated hot-history RocksDB when selected. It will use a separate database:

```text
<history-directory>/hot-history.sqlite
```

It will not replace, read, or share a handle with authoritative core RocksDB.
It will remain independent of the durable archive engine:

| Hot store | Durable archive | Required test combination |
|---|---|---|
| RocksDB | DuckLake | yes; current compatibility baseline |
| RocksDB | SQLite | yes; current compatibility baseline |
| SQLite | DuckLake | yes; primary candidate |
| SQLite | SQLite | yes; embedded candidate |

History disabled must construct none of these resources and must add no work
to core block or epoch application.

### Backend-Neutral Contract

The public hot-store seam owns these capabilities:

- atomic application of a canonical block batch;
- backend-neutral, request-pinned read snapshots;
- exact block checkpoint and per-dataset/track progress;
- rollback, activation reset, and finalized cleanup;
- recent fact-row reads and promotion-range streaming;
- independent outpoint and pointer resolution;
- deterministic receipt persistence and lookup;
- block-body lease acquisition and durable low-watermark management; and
- lifecycle close and health failure reporting.

The contract must not expose a RocksDB handle, column-family descriptor,
iterator, snapshot, write batch, physical key prefix, or RocksDB exception.
Likewise, it must not expose a JDBC connection, SQL statement, row ID, or
SQLite transaction.

The first implementation step may preserve the current logical mutation model
behind the interface to provide a behavior-neutral seam. Before
`SqliteHotHistoryStore` is declared conformant, block application will use
semantic fact/resolver operations rather than requiring SQLite to emulate the
RocksDB binary key/value layout. A generic SQLite key/value table is not the
target architecture.

`HotHistorySnapshot` becomes a backend-neutral read contract. RocksDB pins a
native snapshot; SQLite pins a read transaction. Both must retain their view
until close and reject use after close.

### SQLite Database Ownership and Migration

The hot SQLite database is owned exclusively by Yano and uses a distinct
Flyway location, for example:

```text
db/migration/history-hot-sqlite/
```

Its migration history is private to `hot-history.sqlite` and cannot conflict
with an embedding application's datasource or with the standalone archive
SQLite database. The database uses WAL mode, foreign keys, bounded busy/query
timeouts, prepared statements, and explicit transactions. Durability defaults
to `FULL` until crash testing proves that another setting meets the same
contract.

Only one writer is admitted. Read sessions are bounded and time-limited so a
stuck request cannot grow WAL indefinitely or prevent clean shutdown.

The hot and durable SQLite stores remain separate files. Combining them would
couple near-tip latency, analytical queries, retention, backup, maintenance,
and migration lifecycles. Promotion across stores uses the same idempotent
receipt protocol as DuckLake rather than assuming a cross-file atomic commit.

### Append-Only Chain Facts

Chain-derived hot facts are append-only during normal forward processing. They
may be deleted only by canonical rollback, finalized promotion cleanup,
dataset reset, or configured retention. They are never updated in place to
represent later chain events.

The hot logical fact schemas match their ADR-034 durable counterparts and carry
an exact canonical coordinate:

```text
block_number, slot, block_hash
```

plus transaction/event ordering fields where applicable. The physical hot
schema may add `track` and hot-batch ownership columns that are not exposed by
the stable repository schema.

Recent fact families include enabled rows from:

- transaction;
- account event;
- address transaction;
- optional transaction output and output asset;
- optional transaction input;
- optional transaction datum and redeemer; and
- any future block-derived dataset that declares rollback and promotion
  semantics.

Epoch-derived archive rows retain the ADR-034 durable epoch-source staging and
finality process. They do not enter the block hot database merely to make the
storage engines symmetrical.

Rollback uses the exact common block number and verifies its block hash. Slot
is stored and queryable but is not the sole rollback identity. The basic fact
rollback is:

```sql
DELETE FROM <hot_fact>
WHERE track = :track
  AND block_number > :common_block;
```

If the supplied common point does not match the stored checkpoint, the store
must use the existing activation-reset/rebuild path rather than retaining a
same-height row from another fork.

### Append-Only Outpoint Resolver

The resolver follows the Yaci Store output/input lifecycle model: creation and
consumption are separate records. It does not update an output row with a
spent flag or spent coordinate.

`resolver_outputs` contains the minimum information needed to attribute a
future consuming input:

```text
track,
tx_hash, output_index,
address reference or normalized address ID,
payment credential type/hash,
stake address reference or normalized stake-address ID,
stake credential type/hash,
created_block_number, created_slot, created_block_hash,
source_kind (BLOCK or SEED)
```

It deliberately excludes lovelace, assets, datum and script payloads,
signatures, witnesses, and output CBOR. SQLite may normalize repeated address
and stake-address values behind private integer dimensions. Backend-private IDs
never cross `HotHistoryStore`.

`resolver_spends` records only canonical consumption:

```text
track,
referenced_tx_hash, referenced_output_index,
spending_tx_hash,
spending_block_number, spending_slot, spending_block_hash,
input_role (ordinary or collateral)
```

Reference inputs do not enter `resolver_spends`. A phase-2-invalid
transaction's consumed collateral does. A uniqueness constraint on
`(track, referenced_tx_hash, referenced_output_index)` makes a conflicting
double consumption fatal while identical deterministic replay is idempotent.

Resolution is a local indexed lookup and never falls through to DuckLake,
standalone archive SQLite, or mutable core state. Public UTXO history may be
disabled, tip-only, or independently retained without affecting address
history correctness.

The live resolver is seeded from the authoritative core UTXO snapshot at its
activation anchor. Seed rows have `source_kind=SEED` and are never exported as
historical transaction outputs. The backfill resolver is seeded from genesis
and advances sequentially. `track` is part of resolver identity until the
defined live/backfill merge retires one track.

Resolver retention is not simply the 4,320-block fact window:

```text
all currently unspent outputs for the track
+ outputs consumed inside the rollback window
```

When a consuming block becomes final and its derived archive facts are safely
committed, bounded maintenance deletes the internal resolver output; its spend
marker is deleted atomically or through `ON DELETE CASCADE`. An unspent output
is retained regardless of age. Core RocksDB remains authoritative for current
UTXO APIs; this narrow duplication is solely for independent history
resolution.

Rollback first deletes orphaned spend markers and then deletes outputs created
on the orphaned suffix in the same SQLite transaction. An older output spent
on the orphaned suffix therefore becomes resolvable again without an update or
before-image journal.

### Append-Only Pointer Resolution

Pre-Conway pointer resolution uses append-only certificate lifecycle facts:

- `pointer_registrations` maps the exact
  `(slot, transaction_index, certificate_index)` coordinate to a credential;
- `pointer_deregistrations` records credential deregistration with complete
  block and intra-block ordering.

Resolution finds the exact registration and verifies that no effective
deregistration invalidated it before the output coordinate. Re-registration
creates a new pointer coordinate; it does not reactivate an older invalidated
pointer. Same-block ordering is preserved. Era behavior must remain verified
against pinned `cardano-ledger` fixtures, including Conway's ineffective
pointer semantics.

The resolved address/credential is copied into `resolver_outputs` when the
output is created. A later spend therefore does not re-resolve the pointer.
Rollback deletes pointer lifecycle rows after the common block. Pointer data is
small and may be retained longer than ordinary fact rows when that is simpler
than proving it is no longer referenced.

### Mutable Operational State

Append-only is a rule for chain-derived facts and resolver lifecycle, not for
small operational singleton state. These tables may update bounded rows:

- `projection_progress`;
- `block_body_requirements`;
- renewable `block_body_leases`;
- store health and schema identity; and
- archive backend generation references.

Block checkpoints and archive receipts append and are pruned under their
existing safety rules. Maintaining one current cursor row is preferred to an
unbounded cursor event log.

### Atomic Block-Batch Commit

One SQLite transaction applies all enabled changes for a dataset batch:

```text
fact inserts
resolver output/spend or pointer lifecycle inserts
block checkpoints
projection progress
body requirement
receipt, when applicable
```

No cursor may advance without all required fact and resolver records. No
resolver record may become visible without its matching checkpoint. Dataset
derivation may remain parallel, but the hot backend retains one serialized
writer and bounded prepared-statement batches.

Rows created earlier in the same block or batch must be visible to later input
resolution through the current write transaction or an equivalent bounded
in-memory overlay. Resolution order remains canonical block, transaction, and
certificate order.

### Promotion and Cleanup

Promotion selects a complete finalized range from the hot relational facts,
streams it in canonical order into the selected ADR-034 durable backend, and
uses deterministic job identity:

1. pin and verify the canonical source range;
2. append the selected rows to DuckLake or archive SQLite;
3. commit durable rows, coverage, counts, and receipt;
4. verify the durable receipt; and
5. delete only the corresponding promoted hot fact rows.

Crash behavior is deterministic:

| Failure point | Recovery |
|---|---|
| before durable commit | hot rows remain; retry the same job |
| after durable commit, before hot cleanup | receipt makes replay idempotent; cleanup resumes |
| during hot cleanup transaction | SQLite rolls cleanup back or commits it atomically |
| after cleanup | durable receipt and coverage are authoritative |

Resolver cleanup is separate from fact promotion because old unspent outputs
must remain resolvable after their complete historical fact has moved cold.
No promotion or projection path performs a cold point lookup to decide whether
a row can be written.

### Query Consistency

A wallet history request pins one hot snapshot and one durable archive read
session, captures the ADR-034 common consistency watermark, restricts both
sources to that watermark, and then merges, orders, deduplicates, and paginates
rows. SQLite hot reads use indexed SQL rather than full Java-side prefix scans.

The selected hot implementation must provide the same omission-free promotion
race behavior as the RocksDB snapshot: a request that began before cleanup can
still see the old hot rows, while a later request sees the durable committed
copy.

### Configuration and Switching

The implementation will add an explicit hot-store selector separate from the
durable archive selector, with names finalized during implementation:

```yaml
yano:
  history:
    hot-store:
      engine: rocksdb        # rocksdb | sqlite
      sqlite:
        path: history/hot-history.sqlite
```

RocksDB remains the default until the acceptance gates pass. No automatic
online conversion between hot engines is required for this unreleased
feature. Switching engines requires the node to be stopped, the old hot store
to be retained for recovery or explicitly discarded, and the selected store
to be seeded/replayed from a valid activation source. Existing durable archive
coverage may be reused only after identity, projection-version, and canonical
boundary validation; otherwise the affected dataset rebuilds.

Paths for core RocksDB, RocksDB hot history, SQLite hot history, standalone
archive SQLite, DuckLake catalog, DuckLake data, and DuckDB temporary storage
must remain pairwise disjoint.

## Core Non-Regression Contract

This decision does not authorize archive writes on the core block-apply or
epoch-boundary critical path. In particular:

1. history disabled creates no SQLite/RocksDB hot store, archive backend,
   worker, lease, or staging resource;
2. core UTXO, account, reward, governance, protocol-parameter, and ledger state
   remain authoritative and unchanged;
3. a hot-store failure degrades optional history and must not stop core sync;
4. archive workers continue reading retained canonical bodies through the
   existing read-only source interface;
5. the history worker never queries DuckLake or archive SQLite to resolve an
   input during derivation; and
6. the SQLite hot implementation must not borrow an unrestricted amount of
   heap, native memory, file descriptors, or core database cache.

## Implementation Sequence

The implementation landed behind an explicit selector while RocksDB remains
the default. Phase acceptance is recorded by the shared semantic conformance
suite rather than by preserving the transitional byte-mutation API: logical
facts and typed resolver/pointer lifecycle operations now cross the seam, and
the RocksDB adapter alone owns its legacy key encoding.

### Phase 0: Contract and Baseline

- Record RocksDB hot ingestion, disk, memory, rollback, promotion, and hot-query
  baselines on fixed synthetic and real preprod ranges.
- Specify the semantic block-batch, snapshot, resolver, pointer, progress,
  lease, and requirement contracts.
- Add backend-neutral conformance fixtures, including seeded activation,
  same-block create/spend, phase-2 collateral, pointer registration/
  deregistration/re-registration, fork replacement, and promotion races.

### Phase 1: Behavior-Neutral Interface

- Introduce `HotHistoryStore` and a backend-neutral snapshot interface.
- Make workers, datasets, resolvers, source leases, promotion, query providers,
  and runtime integration depend on the interface.
- Keep `RocksDbHotHistoryStore` selected and retain its persisted format and
  behavior.
- Prove no enabled or disabled runtime behavior changed.

### Phase 2: Semantic Append Operations

- Replace storage-shaped resolver mutations at the interface boundary with
  typed output-created, output-consumed, pointer-registered, and
  pointer-deregistered operations.
- Pass logical `ArchiveRow` facts rather than RocksDB-encoded row values at the
  backend-neutral boundary.
- Adapt RocksDB internally to the semantic contract without changing its
  external results or persisted rollback guarantees.

### Phase 3: SQLite Hot Store

- Add the private Flyway schema and `SqliteHotHistoryStore`.
- Implement WAL, connection limits, deadlines, one writer, pinned reads,
  prepared-statement batches, exact rollback, activation reset, cleanup,
  receipts, leases, and body requirements.
- Run the same conformance suite against RocksDB and SQLite.

### Phase 4: Promotion, Queries, and Configuration

- Stream relational fact ranges into both durable backends.
- Add indexed SQLite hot query adapters and common-watermark merge tests.
- Add the selector, path validation, status/diagnostics, storage statistics,
  integrity check, backup rules, and operator documentation.

### Phase 5: Full Matrix and Default Decision

- Run all four hot/durable backend combinations.
- Run disabled/enabled core regression, full preprod sync, retained mainnet
  range, devnet time-travel, graceful restart, kill-9, disk-full, long-reader,
  and bounded-maintenance tests.
- Compare correctness and performance before deciding whether SQLite becomes
  the default. The existence of an implementation does not itself change the
  default.

Each phase must be independently buildable and reviewed. The RocksDB option is
not removed in this ADR.

## Verification and Acceptance Criteria

SQLite may become a supported hot engine only when:

- backend conformance passes identically for RocksDB and SQLite;
- address transactions contain complete input, output, collateral-input, and
  collateral-return attribution across Byron through Conway fixtures;
- enabling at tip resolves spends of pre-activation UTXOs from a core seed
  without exporting seed rows as historical facts;
- a still-unspent output older than the finality window remains resolvable;
- a recently consumed output is restored by rollback without an update or
  before-image journal;
- finalized resolver cleanup never removes a currently unspent output;
- fact rows, resolver events, checkpoints, progress, and receipts are atomic
  under injected failures and kill-9;
- promotion crash points produce neither omissions nor duplicate logical rows;
- hot database, WAL, temporary storage, readers, and prepared batches remain
  bounded;
- full-sync history throughput is measured and meets the ADR-034 core-isolation
  and operational catch-up targets;
- p50/p95/p99 recent wallet-query latency is no worse than the accepted
  RocksDB baseline;
- rollback of at least the configured default 4,320-block window and a rollback
  crossing the live activation anchor pass;
- standalone SQLite and DuckLake durable backends produce identical logical
  results from the same SQLite hot input; and
- a clean repository build and distribution smoke test pass with history both
  disabled and enabled.

Measurements must report blocks/second, rows/second, commit latency, query
latency, database/WAL size, bytes written, process RSS, Java heap, and CPU. Raw
SQLite insert throughput without indexes, resolver lookups, rollback, and
promotion is not an acceptance result.

## Consequences

The archive gains an explicit storage seam and a relational candidate for the
entire hot layer. The SQLite model makes chain-derived lifecycle visible,
supports indexed recent queries, avoids global cold lookups, and can simplify
promotion. It also duplicates a narrow portion of the current UTXO set for
independent address resolution; this is intentional and must be sized.

SQLite may impose more B-tree/index and WAL work than RocksDB during accelerated
sync. The design therefore preserves RocksDB and requires evidence rather than
assuming that steady network-tip load predicts full-sync performance.

Removing the generic undo journal does not remove rollback semantics. Those
semantics become explicit append-only creation/consumption and registration/
deregistration facts plus exact suffix deletion. Every future mutable resolver
must declare an equivalent rollback model before entering a SQLite block-batch
transaction.

## Rejected Alternatives

### SQLite facts with a RocksDB resolver/control store

Rejected because block application and rollback would span two independently
committed databases.

### Query hot facts and then the durable archive to resolve inputs

Rejected because public UTXO history is optional and DuckLake high-cardinality
point lookup across epoch partitions is unsuitable for the write path.

### Read the authoritative core UTXO store from the asynchronous worker

Rejected because core may consume or prune an output before history processes
the block, and it couples correctness to store/event ordering.

### Store a mutable `spent_block` on each resolver output

Rejected in favor of separate append-only output and consuming-input records.
Rollback then deletes the orphaned spend marker instead of restoring an
updated row.

### Reuse public UTXO-history tables as the only resolver

Rejected because those tables are independently optional and retained, their
facts are promoted after finality, and live tip activation needs non-exportable
seed rows.

### Use slot alone as rollback identity

Rejected because rollback is an exact canonical chain point. Block number,
hash, and parent continuity remain required; slot is retained for Cardano
queries and diagnostics.

### Emulate RocksDB prefixes in a generic SQLite key/value table

Rejected as the final design because it preserves opaque encoding, generic
before-image undo, prefix scans, and decode-heavy promotion without gaining the
relational benefits being evaluated.

### Put hot and durable SQLite data in one database

Rejected because near-tip writes would share locking, WAL, maintenance,
backup, query, and migration lifecycles with an unbounded analytical archive.
