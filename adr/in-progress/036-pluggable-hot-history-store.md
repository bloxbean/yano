# ADR-036: Pluggable Hot-History Store and a Single Sequential History Track

## Status

Proposed

## Date

2026-08-15

## Context

[ADR-034](034-optional-asynchronous-history-archive.md) keeps optional history
projection outside authoritative ledger processing. Final history is stored in
DuckLake or standalone SQLite; rollback-sensitive rows and the independent
input resolver are held in a bounded hot store.

The first implementation used a dedicated RocksDB hot store and two concurrent
block tracks:

- `BACKFILL` replayed retained blocks from the configured history start; and
- `LIVE` was seeded from core UTXO state and projected near-tip blocks while
  backfill was incomplete.

That made recently enabled wallet APIs available quickly, but duplicated
resolver state, projection progress, activation anchors, rollback handling,
and fact ownership. It also required a track merge whose failure modes were
disproportionate to an optional history subsystem. Wallet deployments can
provide uninterrupted service with normal infrastructure-level HA while a new
instance builds history.

The RocksDB hot-store contract is also expressed as opaque key/value
mutations. A relational SQLite hot store can make bounded facts, resolver
lifecycle, rollback, inspection, and promotion easier to reason about while
remaining independent of the selected durable archive backend.

The archive resolver is not the authoritative current UTXO store. Core RocksDB
continues to own ledger UTXO, account, reward, governance, and protocol state.
The resolver only maps a consuming input's outpoint to the address and
credentials of its original output. It must remain independent because inputs
do not contain source addresses and core may consume or prune an output before
an asynchronous history replay reaches the spend.

## Decision

### One Sequential Block-History Lifecycle

Every enabled block-derived dataset has one logical history lifecycle. The
address projection has one outpoint resolver identity; pointer state is
isolated by owning dataset. The durable lifecycle is:

```text
CATCHING_UP
  read canonical retained bodies sequentially
  derive and commit finalized archive rows directly
  advance the single resolver and catch-up cursor
        |
        | finalized archive frontier reached
        v
LIVE
  retain rollback-sensitive rows in the selected hot store
  continue the same resolver from the preceding block
  promote rows after archive finality
```

`CATCHING_UP` and `LIVE` never process the same dataset concurrently. The
transition persists the live cursor before deleting the obsolete catch-up
cursor. On restart, the presence of the live cursor selects `LIVE`; otherwise
the worker resumes `CATCHING_UP`. No UTXO snapshot is created at the transition
and no resolver merge occurs.

For `start-mode: full-required` and genesis-complete
`earliest-available`, the resolver is seeded from genesis. For
`start-mode: tip`, history is intentionally partial: the resolver is seeded
once from a consistent authoritative core UTXO snapshot and the dataset enters
`LIVE` at the next block. A tip seed is not an exported historical output.

History enabled later on a fully synced node remains unavailable while a
full-required catch-up is running. Wallet history endpoints return an explicit
building/incomplete response rather than mixing partial cold and near-tip
answers. Operators requiring continuous wallet availability use rolling or HA
deployment so an existing history-capable instance remains available while a
replacement builds.

Dataset projection may remain internally parallel and datasets may advance at
different speeds. Public multi-dataset reads are exposed only through the
common consistency watermark from ADR-034. Readiness requires every requested
dataset to cover that watermark; a faster dataset never makes an incomplete
cross-dataset answer appear complete.

Adding a new projection to an existing deployment follows its configured
start mode and may make endpoints requiring that projection unavailable until
it catches up. Side-by-side projection generations are a separate migration
feature and are not implemented by recreating the removed live/backfill split.

### Pluggable Complete Hot Store

Introduce a backend-neutral `HotHistoryStore`. The selected implementation
owns all three hot responsibilities together:

1. recent rollback-sensitive history facts;
2. independent outpoint and pointer resolver state; and
3. progress, checkpoints, receipts, source leases, and pruning requirements.

Moving only facts to SQLite while leaving resolver/control state in RocksDB is
rejected because block application and rollback would require atomic commits
across databases.

Supported combinations are:

| Hot store | Durable archive | Requirement |
|---|---|---|
| RocksDB | DuckLake | compatibility baseline |
| RocksDB | SQLite | compatibility baseline |
| SQLite | DuckLake | primary candidate |
| SQLite | SQLite | embedded candidate |

The SQLite hot database is private to Yano:

```text
<history-directory>/hot-history.sqlite
```

It has an isolated Flyway history, uses WAL, foreign keys, bounded timeouts,
prepared batches, explicit transactions, and one writer. It never shares a
datasource or migration namespace with an embedding application or the
standalone archive SQLite database.

The backend-neutral contract exposes semantic facts and resolver operations,
not RocksDB handles/prefixes or JDBC connections/statements. A hot read session
pins a RocksDB snapshot or SQLite read transaction until close.

### Append-Only Facts and Resolver

Normal forward processing appends chain facts. Rows may be deleted only by
rollback, finalized promotion cleanup, dataset reset, or configured retention.
Every fact carries:

```text
block_number, slot, block_hash
```

plus transaction/event ordering coordinates where applicable.

`resolver_outputs` contains only the information required to attribute a
future input:

```text
tx_hash, output_index,
address, payment credential,
stake address/credential,
created block/slot/hash,
source kind (BLOCK or SEED)
```

`resolver_spends` separately records canonical consumption:

```text
referenced tx hash/index,
spending tx hash,
spending block/slot/hash,
input role (ordinary or collateral)
```

There is no live/backfill discriminator in resolver identity. One deployment
has one resolver lifecycle for address history. Conflicting duplicate outputs
or spends are fatal; identical deterministic replay is idempotent. Reference
inputs are not spends. Phase-2-invalid collateral consumption is.

The resolver does not contain assets, values, datum/script payloads, witnesses,
or output CBOR and never falls through to DuckLake, archive SQLite, or mutable
core state during derivation.

Resolver retention is:

```text
all currently unspent resolver outputs
+ outputs consumed inside the rollback window
```

After a consuming block is outside the rollback window and its history is
durable, maintenance removes its resolver output and spend atomically. An
unspent output is retained regardless of age.

Rollback deletes orphaned spends first and outputs created on the orphaned
suffix second in the same transaction. An older output spent on that suffix
therefore becomes resolvable again without an update or generic before-image
journal.

Pre-Conway pointer resolution likewise stores append-only registration and
deregistration lifecycle rows with complete block and intra-block ordering.
The resolved credential is copied into a resolver output at creation time.
Era behavior remains pinned to `cardano-ledger` fixtures.

### Atomicity, Promotion, and Crash Recovery

One hot-store transaction applies a dataset block batch:

```text
facts
resolver output/spend and pointer lifecycle
block checkpoints
projection progress
body requirement/receipt changes when applicable
```

Rows created earlier in a batch are visible to later input resolution through
the write overlay or transaction. A cursor cannot advance without its facts,
resolver mutations, and checkpoint.

Finalized promotion uses deterministic job identity:

1. pin and verify a canonical hot range;
2. append it to DuckLake or archive SQLite;
3. commit durable rows, coverage, counts, and receipt;
4. verify the receipt; and
5. delete only the promoted hot rows.

Resolver cleanup is separate because old unspent outputs must remain
resolvable after their public facts move cold. No write path performs a cold
point lookup.

Crash recovery is deterministic:

| Failure point | Recovery |
|---|---|
| catch-up durable commit before cursor | receipt/coverage is verified and resolver progress is replayed |
| before durable promotion commit | hot rows remain and the same job retries |
| after durable commit before hot cleanup | receipt makes retry idempotent; cleanup resumes |
| during SQLite cleanup | the cleanup transaction commits or rolls back |
| during catch-up-to-live handoff | persisted live cursor wins; otherwise catch-up resumes |

### Rollback

Rollback uses exact block number and hash, not slot alone.

- In `LIVE`, hot facts, checkpoints, spends, created outputs, and pointer rows
  after the common point are deleted atomically and progress is rewound.
- In `CATCHING_UP`, a canonical mismatch invalidates affected durable jobs and
  rebuilds from the dataset activation point.
- A rollback deeper than retained hot checkpoints reactivates the dataset from
  a valid configured source. It never silently retains a resolver snapshot
  based on an orphaned block.

### Query Availability and Consistency

A request pins one hot snapshot and one durable read session, captures the
ADR-034 common watermark, restricts both sources to it, then merges, orders,
deduplicates, and paginates.

During `CATCHING_UP`, wallet endpoints requiring the dataset return
unavailable/incomplete. They do not serve an apparently current suffix without
the requested historical prefix. During `LIVE`, a request that starts before
promotion cleanup can still see the pinned hot rows; a later request sees the
durable committed copy.

### Configuration

The hot selector remains separate from the durable archive selector:

```yaml
yano:
  history:
    hot-store:
      engine: sqlite        # sqlite | rocksdb
      sqlite:
        path: history/hot-history.sqlite
```

No automatic online conversion is required for this unreleased feature.
Changing the hot engine requires a stopped node and a clean optional hot-store
rebuild. Durable coverage may be reused only after network identity,
projection-version, and canonical-boundary validation.

Core RocksDB, hot history, standalone archive SQLite, DuckLake catalog/data,
and DuckDB temporary paths must be pairwise disjoint.

## Core Non-Regression Contract

1. History disabled constructs no archive backend, hot store, worker, lease,
   or staging resource.
2. Core UTXO, account, reward, governance, protocol-parameter, and ledger
   state remain authoritative and unchanged.
3. Hot/archive failure degrades optional history and cannot stop core sync.
4. Workers read retained canonical bodies only through the read-only source
   interface and never write on the core block-apply path.
5. Derivation never performs DuckLake or archive-SQLite point lookup.
6. Memory, native memory, file descriptors, writer batches, and query sessions
   remain bounded.

## Implementation Sequence

### Phase 0: Contract and Baseline

- Capture RocksDB ingestion, storage, memory, rollback, promotion, and query
  baselines.
- Define semantic hot operations and shared conformance fixtures.

### Phase 1: Backend-Neutral Seam

- Make workers, resolvers, leases, queries, and promotion depend only on
  `HotHistoryStore`.
- Preserve RocksDB behavior behind the adapter.

### Phase 2: SQLite Hot Store

- Add its private Flyway schema, WAL/connection policy, semantic operations,
  exact rollback, promotion, receipts, leases, and requirements.
- Pass the same conformance suite for both hot engines.

### Phase 3: Single Sequential Track

- Give address history one resolver identity and isolate pointer state only by
  owning dataset.
- Run catch-up and live workers mutually exclusively.
- Persist a crash-safe catch-up-to-live handoff and delete obsolete track
  cursor/activation data.
- Remove resolver track columns/namespaces, dual resolver seeding, merge logic,
  and concurrent-suffix query behavior.
- Return explicit building/incomplete API status until handoff completes.

### Phase 4: Matrix and Default Decision

- Run all four hot/durable combinations.
- Run disabled/enabled regression, full preprod, retained mainnet, devnet deep
  rollback, graceful restart, kill-9, disk-full, and long-reader tests.
- Compare blocks/second, rows/second, commit/query latency, database/WAL size,
  bytes written, RSS, heap, native memory, and CPU.

## Acceptance Criteria

- both hot stores pass the same semantic conformance suite;
- exactly one resolver exists per address-history dataset;
- no catch-up and live worker for one dataset can run concurrently;
- a fresh full-required deployment does not seed a second current-tip UTXO
  copy;
- tip mode resolves pre-activation spends from one consistent core seed;
- catch-up-to-live handoff survives kill-9 at every write boundary;
- same-block create/spend, invalid collateral, Byron genesis, pointer lifecycle,
  and conflicting/idempotent duplicates are correct;
- unspent old outputs remain resolvable and finalized consumed pairs are
  removed;
- promotion and rollback create neither omissions nor logical duplicates;
- public history is explicitly unavailable until requested dataset coverage is
  complete;
- hot database, WAL, batches, and reads remain bounded; and
- a clean distribution works with history disabled and with every supported
  backend combination.

## Consequences

The sequential design deliberately trades immediate history availability after
enablement for substantially simpler storage and recovery. There is one
resolver, one canonical processing order, no merge, and no duplicate current
UTXO seed during full catch-up. Infrastructure HA supplies service continuity.

SQLite can add B-tree and WAL cost during accelerated sync, so RocksDB remains
available until measurements justify a default change. Explicit append-only
lifecycle rows replace generic undo for resolver state; rollback semantics are
not weakened.

## Rejected Alternatives

### Concurrent live and backfill tracks

Rejected because availability can be provided by HA, while duplicate resolver
state, activations, rollback paths, and merge recovery materially increase
correctness and storage risk.

### SQLite facts with a RocksDB resolver/control store

Rejected because a block commit and rollback would span two databases.

### Resolve inputs from the durable archive or authoritative core state

Rejected because cold point lookup is unsuitable for the write path and core
may consume/prune an output before asynchronous replay reaches it.

### Mutable spent fields or generic SQLite key/value emulation

Rejected in favor of explicit append-only output/spend lifecycle rows and
relational operations.

### Combine hot and durable SQLite in one file

Rejected because bounded near-tip writes must not share WAL, locks,
maintenance, backup, or migration lifecycle with an unbounded archive.
