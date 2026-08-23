# ADR-039: Canonical Projection Outbox for Fresh-Sync History

## Status

**Partially implemented — Phases 0–4b complete on `feat/adr-039-canonical-projection-outbox`
and validated to preprod tip with all four shipped block datasets; Phase 5 next, Phases 6–7 to
follow, Phase 8 deferred.** Reconciled with two source reviews.
This ADR describes a replacement architecture and must not be treated as an implementation
continuation of ADR-038.

The six decisions in "Review decision requested" are treated as **recorded** by the
instruction to implement this ADR end to end; the review closed with no open editorial
items. Implementation reports live in `adr/reports/adr-039-*`.

### Implementation state — 2026-08-20

| Phase | State |
|---|---|
| 0 boundary proofs, recovery classification | complete |
| 1 contracts, identity, fresh-sync guard | complete |
| 2 durable block outbox | complete |
| 3 transaction/UTXO slice + Byron carrier | complete (differential parity met) |
| 4 DuckLake primary sink | **complete** — full preprod sync to tip (1h07m, 5,076,688 blocks), restart and `kill -9` verified, near-tip batch policy and sink-owned maintenance live |
| 4b remaining shipped datasets | **complete, validated to preprod tip** on a fresh four-section sync (contributor lag 0, 0 drain failures, backlog pinned at the finality window). Archive logical size 4.50 GB against 3.16 GB with two sections — **1.42x** over the whole sync, which is the figure to size with; the `bytesPerBlock` status counter (4,637 vs 2,817) is backlog density at tip, not a sync average — all four datasets have contributors; account-event parity covered by seven certificate fixtures, address-transaction parity verified side by side against the live dataset on a real spend. Section cost measured at 234 B/participation, which materially increases outbox volume: the gate-4 bytes-per-block model was taken with two sections and must be re-derived before mainnet sizing |
| 5 epoch artifacts | **complete** — all five ship. EPOCH_STAKE by reference (IMMUTABLE_GENERATION), ADA_POT inline (ATOMIC_EVIDENCE), and REWARD / DREP_DISTRIBUTION / GOVERNANCE_PROPOSAL_STATUS as STAGED_FILE evidence, explicitly **not** claimed reconstructible. The staged mechanism was hardened rather than redesigned: fsync of file then directory, SHA-256 and size verified before a row is served, fail-closed on missing/truncated/corrupt. Full flow proven — staging capture → durable artifact → outbox reference → sink commit → receipt → acknowledgement → lease release → cleanup, with **zero staged files remaining at tip**. Genesis was found missing entirely by the Phase 7a oracle and is now captured as an explicit bootstrap with its own identity, receipt and completion marker |
| 6 operational/API cutover | **complete** — `/history/coverage` (identity, artifact contracts, committed range, honest "unknown not absent" note, tx-hash lookup declared `full-scan`), watermark answered by the projection's own consistency point, artifact/lease/health exposure. The read cutover was real work, not a no-op: lifecycle, archive identity and coverage all had to be cut from the legacy service, the last by falling back to `projection_receipts` when `archive_coverage` is empty |
| 7a remove block replay pipeline | **complete** — authorised by a differential oracle returning **zero differences** across all four block datasets through block 5,081,500 including Byron main blocks and EBBs. Removed workers, cursors, hot-history promotion, replay-only sources/prefetch/caches and their configuration: 37 files, +150/−6,899 lines; `HistoryArchiveService` 2,318 → ~640 lines. Removed configuration now **fails** rather than being ignored |
| 7b remove obsolete epoch staging | **complete, and deliberately small** — the epoch staging foundation is **not** obsolete: it is the projection's own write path for the three STAGED_FILE datasets. Only `BlockArchiveSource` and `UtxoPrefetchConfig` were genuinely obsoleted. One archival write path confirmed: canonical projection outbox → finality gate → primary sink |
| 8 standalone SQLite primary sink | **deferred — explicitly out of scope for this campaign.** The `ProjectionSink` / `ProjectionSinkProvider` boundary is retained so it stays buildable later; nothing in Phases 5-7 may remove the interfaces it needs |

One acceptance gate remains open: **criterion 1, the core regression budget**. It is now
*bounded* rather than inconclusive: attributing the projection's cost inside a single run — no
second run, so no cross-run variance — puts it at **at most 6.3% of sync throughput** over
2.27M blocks, against a 5% budget. The bound straddles the budget, so the gate is not yet met
but the pre-optimisation −14% is not the current cost. Criterion 2 was withdrawn on direct
measurement — see below.

### Phase 0 outcome that changes the plan

**Acceptance criterion 2 cannot be satisfied as written.** Measured uncontended on a
fresh preprod sync, history-disabled core production runs at **1,500–3,600 blocks/s**
depending on block density. The only measured sink rate is ADR-038's **59.84 blocks/s**.
These are different workloads and must not be reduced to one ratio, but the gap is
roughly 1.5 orders of magnitude, and ADR-038 attributes only ~82–86% of per-block worker
cost to the decode this design removes. No decode-free sink closes that.

**Withdrawn — measured false.** The ADR-039 DuckLake sink was subsequently measured directly
on a fresh preprod sync: **1,292 blocks/s sink versus 1,193 blocks/s producer**, with the
outbox backlog pinned at the finality window (4,319 blocks) from block 170,000 through 4.8M
and zero drain failures. The sink keeps up with full-speed bootstrap; rollback-safety, not
sink throughput, is the binding constraint.

The original conclusion inferred the new sink's capability from ADR-038's 59.84 blocks/s,
which is the *old replay-worker* rate — repeated decode, contended writer, synchronous
locator, none of which the projection sink does. That inference was invalid.

The superseded reasoning follows, retained because the reasoning error is instructive.

**Superseded.** The conclusion drawn from this — that sink-paced core sync was the only
live option — was wrong, and criterion 2 has since been replaced (see "Revised criteria"
below). The comparison pitted the sink against *accelerated bootstrap* core production,
which asynchrony exists to decouple. At steady state a sink at even 60 blocks/s outruns
mainnet block arrival by three orders of magnitude. The bootstrap deficit is a disk-capacity
question, governed by an aggregate retained-disk budget with visible pause and automatic
resume, not a reason to pace core to sink speed.

### Phase 3 outcome: acceptance criterion 1 also fails

Measured cleanly — four legs alternating, each alone on an idle machine, fresh chainstate,
wall clock to block 1,000,000 on preprod:

```
median history-disabled : 2,410 blocks/s
median history-enabled  : 2,070 blocks/s
delta                   : -14.1%  (plausible range -10% to -18%)
criterion 1 threshold   :  -5.0%
```

Disk cost over the same range: +3.42 GB chainstate, i.e. **3,419 bytes/block on disk**
against ~2,400 bytes/block of logical outbox — roughly **1.4x amplification**. That
quantifies review finding D3.

This ADR already prescribes the response: "the design must first remove avoidable
copies/encoding work. It must not hide the overhead by making the canonical/outbox
boundary best-effort." That optimisation pass has **not** been attempted, so the 14% is
an unoptimised first measurement, not a floor.

Criteria 1 and 2 fail independently. Cheaper encoding would not bring a sink within an
order of magnitude of core production, and accepting sink-paced sync would not recover
the 14%.

### Open question 1 has a concrete, blocking answer

"Which live subsystem is the authoritative contributor for datum/redeemer and
pointer-address projection facts?" is not merely a design preference. The projection row
builder uses `UtxoHistoryDataset`'s resolver-less constructor, which **throws** on a
pre-Conway pointer address. Datum and redeemer facts are fine — they come from the block
itself. Pointer addresses are not, and until this is answered a sink must not be enabled
on a network whose pre-Conway range contains them.

### Corrections to this ADR made during implementation

- §4's illustrative `account-events:v1` is wrong for the shipped dataset: `ACCOUNT_EVENT`
  is at projectionVersion 3, so its section is `account-events:v3`. Section versions are
  now *derived* from `ArchiveSchemas`, so they cannot drift from dataset identity.
- §11's crash-boundary case 9 has a concrete failure mode the text does not name:
  acknowledging the outbox range deletes artifact *references*, so a crash before lease
  release orphans the lease. Startup lease reconciliation is required with the first
  artifact, not after.
- Rollback of pending envelopes must be keyed on the rollback **slot**, not on a chain
  tip read inside a listener; listener order relative to chain-state rollback is
  unspecified.

## Date

2026-08-19

## Source-review reconciliation — 2026-08-19

The source reviews against `feat/adr-038-archive-throughput@5a3cebec` found four
initial blocking omissions plus epoch-artifact durability and retention
clarifications. This revision incorporates them:

- Byron main blocks now require a typed already-decoded projection carrier;
  EBBs produce empty envelopes so coverage remains contiguous.
- One global chain/UTXO/ledger transaction is no longer an implementation
  candidate. Existing per-contributor RocksDB batches, cursors, retained bodies,
  reconciliation, and `BlockBodyRetentionBoundary` form the durability model.
- Replay-worker removal is gated on all four shipped block datasets, not only
  transaction and UTXO parity.
- Core-only rate, concurrent sink rate, outbox bytes/block, and full-sync backlog
  are Phase 0 decision gates.
- The existing epoch staged-file source is retained as the logical-equivalence
  baseline and hardened where it is the selected durable representation;
  immutable live-generation references require per-artifact correctness and
  performance evidence.
- Required staged artifacts must be hardened beyond the current best-effort
  failure isolation and process-restart durability before they satisfy the new
  completeness contract.
- Existing dataset/table/projection-version ownership is preserved initially.
- The hot-RocksDB compaction consequence of a lagging outbox is explicit.
- First-slice UTXO inputs do not require resolved consumed-output data;
  Shelley+ address/credential history does, and Byron retains an archive-owned
  resolver.
- Epoch recoverability is classified per column. Persisted generations,
  conditionally recomputable facts, and non-reconstructible boundary-time joins
  have different capture/durability requirements.
- One-epoch delayed eligibility is not treated as durability. Deferred
  force/digest publication is safe only after an exact reconstruction source or
  minimal immutable boundary evidence is already durable.
- Artifact cleanup requires renewable, fenced reader leases; wall-clock expiry
  alone cannot authorize deletion.
- Persisted epoch-stake generations receive an explicit snapshot-pruning clamp;
  their retained chainstate bytes participate in the aggregate backlog limits.
- The runtime common `RollbackCapableStore` floor is reused as a
  retention-health constraint and checked against the oldest active artifact or
  reward-replay requirement; it is not an archive-finality cutoff.
- `PREPARING` recovery is class-specific; its marker cannot substitute for
  atomic evidence when boundary-time facts are otherwise irreconstructible.

## Decision summary

For a history-enabled node starting from genesis, canonical block application
will produce one ordered, durable, versioned projection stream. A single
configured primary archive sink — DuckLake or standalone SQLite — consumes that
stream asynchronously. Blocks are decoded and stateful facts are resolved once,
while the authoritative live stores already hold the required context.

The stream is implemented as a transactional RocksDB outbox:

```text
canonical block and ledger application
                  |
                  | atomic state/projection boundary
                  v
       canonical projection outbox
                  |
                  | ordered, finality-gated batches
                  v
          one primary archive sink
           DuckLake OR SQLite
                  |
                  v
       optional derived projections
  tx locator / search / alternate store / export
```

The initial release supports exactly one primary sink and only a fresh history
sync. It does not migrate a partially synchronized archive, enable history on an
already synchronized chainstate, or feed multiple primary sinks. Those
restrictions are deliberate simplifications and are enforced at startup.

Small block-level facts are stored inline or in bounded chunks under a logical
block envelope. Large epoch data is not copied into that envelope. Its durable
source is either a hardened immutable staged-file representation or, where a
per-artifact review proves it safer and faster, an epoch-versioned immutable
live-store generation protected by a durable pruning lease. The sink streams the
source and acknowledgement permits its cleanup.

The existing independent archive replay workers remain only as a temporary
differential oracle during implementation. After parity and recovery gates pass,
the block-replay history pipeline and the unused legacy synchronous history path
are removed. The archive schemas, query contracts, DuckLake/SQLite storage
implementations, receipts, and backend conformance tests are retained where
they satisfy this ADR.

## Architecture at a glance

### Contributors complete one logical block envelope

Canonical synchronization does **not** wait for every history contributor before
accepting the next block. Each contributor processes blocks in canonical order
and atomically commits its own state changes, envelope section, and contributor
cursor. The archive sink sees only the completed logical envelope.

```text
                            CANONICAL BLOCK 100
                                     |
                   stored body is the durable replay intent
                                     |
       +-----------------------------+-----------------------------+
       |                             |                             |
       v                             v                             v
+------------------+       +--------------------+       +--------------------+
| TRANSACTION      |       | UTXO_HISTORY       |       | ACCOUNT_EVENT      |
| contributor      |       | contributor        |       | contributor        |
|                  |       |                    |       |                    |
| chain_transaction|       | outputs            |       | account_events     |
|                  |       | output_assets      |       |                    |
| atomic batch:    |       | inputs             |       | atomic batch:      |
| section + cursor |       | datums/redeemers   |       | state + section    |
+---------+--------+       |                    |       | + cursor           |
          |                | atomic batch:      |       +----------+---------+
          |                | state + section    |                  |
          |                | + cursor           |                  |
          |                +----------+---------+                  |
          |                           |                            |
          |                +----------+----------------------------+
          |                |
          |                |       +------------------------+
          |                |       | ADDRESS_TRANSACTION    |
          |                |       | contributor            |
          |                |       |                        |
          |                |       | address_transactions   |
          |                |       | resolver + section     |
          |                |       | + cursor atomically    |
          |                |       +-----------+------------+
          |                |                   |
          +----------------+-------------------+
                                           |
                                           v
                         +----------------------------------+
                         | BLOCK 100 LOGICAL ENVELOPE       |
                         |                                  |
                         | TRANSACTION          complete    |
                         | UTXO_HISTORY         complete    |
                         | ACCOUNT_EVENT        complete    |
                         | ADDRESS_TRANSACTION  complete    |
                         | epoch artifact refs  if required |
                         +----------------+-----------------+
                                          |
                                    envelope complete
```

If a separately accepted product ADR retires a dataset such as
`ADDRESS_TRANSACTION`, it is removed from the archive identity's required
section set. It must not simply be omitted while still marked required.

### Core, contributors, and archive advance independently

```text
time --------------------------------------------------------------->

canonical core       [100] [101] [102] [103]
TRANSACTION          [100] [101] [102]
UTXO_HISTORY         [100] [101] [102]
ACCOUNT_EVENT        [100] [101]
ADDRESS_TRANSACTION  [100] [101]

complete envelopes   [100] [101]
rollback-safe         [100]
primary archive      [100]
```

In this example, core is at block 103 while the primary archive is at block 100.
That is valid. Bodies needed by incomplete contributors remain protected from
pruning. A contributor that restarts resumes from its own cursor and reconstructs
missing sections from those retained bodies.

### Archive flow for each contiguous envelope range

```text
all required contributor sections present?
                 |
          +------+------+
          |             |
         no            yes
          |             |
 wait at this block     v
                 ENVELOPE COMPLETE
                         |
                 rollback-safe yet?
                         |
                  +------+------+
                  |             |
                 no            yes
                  |             |
          wait for finality     v
                   batch consecutive complete envelopes
                                |
                                v
                    DuckLake OR SQLite commit
                    rows + durable receipt atomically
                                |
                       commit/receipt verified?
                                |
                         +------+------+
                         |             |
                        no            yes
                         |             |
                  retry same job       v
                                 acknowledge range
                                         |
                                         v
                              delete outbox chunks and
                              release artifact protection
```

The sink cannot skip an incomplete block. It may batch blocks 100–120 together
only when every envelope in that range is complete, rollback-safe, contiguous,
and compatible with the same sink transaction bounds.

## Context

The current archive architecture reconstructs history by running independent
projections over durable block bodies and durable epoch sources. Block-derived
datasets have separate cursors and worker lifecycles. In practice this means
that transaction, address/account, and UTXO projections can reread and decode
the same block independently, advance at different rates, contend for one
archive writer, and require explicit catch-up-to-live handoff logic.

ADR-038 demonstrated both the value and the limit of optimizing that model:

- Appender-to-staging made archive row insertion fast without weakening logical
  key verification.
- Ordered UTXO prefetch improved the slowest dataset from 31.57 to 59.84
  blocks/s in the accepted production window.
- The remaining synchronous transaction locator later held shared archive
  capacity for seconds to minutes despite being a derived query hint.
- Much of the old UTXO cost was repeated CBOR decoding even though canonical
  block application had already decoded the same transactions and resolved the
  same spent outputs.

The node already has the information required for history while applying a
canonical block:

- decoded transactions and witnesses;
- transaction validity and collateral semantics;
- new outputs, assets, datums, redeemers, and consumed outpoints;
- consumed outpoint identity and, where a later address/credential projection
  requires it, the original consumed output from the live UTXO set;
- same-block transaction ordering;
- resolved ledger transitions and rollback information;
- immutable, epoch-keyed stake, reward, ADA-pot, DRep, and governance outputs.

Re-reading old block bodies to reconstruct those facts duplicates work and
creates a second state-resolution system whose correctness must track the live
ledger implementation. A durable projection outbox captures the already-known
result and lets storage remain asynchronous.

This is not a generic event-bus decision. The outbox is a local durability and
projection boundary for one node. It is not initially a permanent Kafka-like
log, and acknowledged records may be deleted.

## Goals

1. Decode each canonical block once for new history.
2. Reuse authoritative live-path UTXO and ledger resolution.
3. Keep DuckLake and SQLite work outside the core synchronization hot path.
4. Replace separate dataset catch-up/live lifecycles with one ordered backlog.
5. Make rollback, crash replay, receipt verification, and pruning explicit.
6. Support one primary DuckLake or SQLite sink through a common contract.
7. Allow future versioned sections such as governance and epoch artifacts.
8. Make point indexes and search stores optional downstream projections.
9. Remove unused legacy history and, after parity, the current block-replay
   archive workers.
10. Keep the primary archive sufficiently complete to derive later stores and
    query projections without involving the core sync path.

## Non-goals

The initial implementation does not:

- enable history midway through an existing chainstate;
- migrate existing partial archive coverage;
- support two simultaneous primary archive sinks;
- retain the outbox forever for arbitrary future consumers;
- solve the `txHash -> block` point-index design;
- promise complete historical support for a data type not captured by the
  primary archive or retained block bodies;
- archive mutable current-state CFs such as account state by reference;
- make DuckLake, SQLite, or a locator authoritative ledger state;
- support an ordinary rollback deeper than the configured retained rollback
  boundary;
- preserve the current per-dataset worker architecture for compatibility.

## Terms

**Canonical projection envelope** — the logical projection result associated
with one canonical block. It has a header, zero or more versioned inline/chunked
sections, and zero or more immutable artifact references.

**Projection outbox** — the durable RocksDB records holding envelopes until the
primary sink acknowledges them.

**Primary archive sink** — the one configured authoritative historical store for
this node: DuckLake or standalone SQLite.

**Artifact** — a large immutable, generation-addressed source such as a durable
staged epoch file or a proven immutable live-store generation that a sink streams
by reference.

**Artifact lease** — durable metadata preventing pruning of an artifact and any
required recomputation-input closure until the primary sink acknowledges it or
the projection is explicitly abandoned.

**Derived projection** — a rebuildable downstream index or store populated from
the primary archive, not directly acknowledged by the core outbox.

**Projection coordinate** — the greatest contiguous canonical block envelope
durably committed by the primary sink. Epoch-artifact coverage is reported
separately by artifact type and semantic epoch.

## Decision

### 1. Fresh-sync history is an enforced identity invariant

Projection-outbox history may be enabled only when all of the following hold:

1. canonical chainstate is empty/genesis, or a coordinated snapshot explicitly
   includes matching projection metadata;
2. the outbox has no non-genesis projection identity;
3. the selected primary archive is empty, or has the exact expected network,
   genesis, archive, schema, and projection identity;
4. no incompatible legacy/archive coverage is being adopted implicitly.

Starting with history disabled and enabling it after synchronization has begun
is rejected. Pointing an existing chainstate at an empty primary archive is
rejected. Pointing a fresh chainstate at an unrelated archive is rejected.

A future coordinated snapshot format may include chainstate, projection
coordinate, pending outbox, artifact leases, and archive identity. Such a
snapshot counts as a fresh coherent start; a chainstate snapshot alone does not.

The operator choices for an existing node are therefore explicit:

- continue using a release that supports its existing history layout;
- start a fresh sync with projection-outbox history;
- or use a separately designed and verified offline migration.

### 2. One logical envelope per canonical block

The envelope header contains at least:

```text
network/genesis identity
block number, hash, and parent hash
slot, epoch, and block time
canonical projection version
section manifest
artifact-reference manifest
deterministic envelope ID
```

The deterministic envelope ID is derived from canonical identity and projection
version, not from a random retry ID. Replaying the same canonical block therefore
addresses the same logical envelope.

An envelope is logical, not necessarily one RocksDB value. Physical records may
be:

```text
projection/header/<block>
projection/section/<block>/<type>/<chunk>
projection/artifact/<block>/<type>/<generation>
```

The header manifest records section versions, chunk counts, logical row counts,
byte counts, and digests. The primary sink verifies the complete logical
envelope before acknowledgement.

No physical value is allowed to grow without a configured bound. Large
block-level collections are chunked deterministically.

### 3. Initial block-level sections

The first complete vertical slice contains two logical sections and writes the
existing six physical archive tables. Version one preserves the current
dataset-to-table identity and projection versions; it does not silently move
datum/redeemer coverage from `UTXO_HISTORY` to `TRANSACTION`:

```text
transaction:v2
  -> chain_transaction

utxo-history:v5
  -> transaction_outputs
  -> transaction_output_assets
  -> transaction_inputs
  -> transaction_datums
  -> transaction_redeemers
```

Envelope section names are transport groupings, not permission to change
archive dataset identity. Any future repartitioning of tables between logical
datasets requires an explicit projection-version transition, coverage
reconciliation rules, conformance-fixture updates, and query compatibility
decision.

`utxo-history:v5` records output creation when the output is created, even when it
remains unspent indefinitely. A later consuming input is a separate immutable
event. The output row is not moved or deleted from the archive.

During canonical UTXO application, the processor already has the decoded
transaction, validity, input role, new outputs, and consumed outpoint. The
current `transaction_inputs` schema records the referenced outpoint rather than
denormalizing the original consumed output, so the first transaction/UTXO slice
does not require that output lookup for its archive rows. It materializes
self-contained projection facts before mutating `cfUnspent`/`cfSpent`. The
asynchronous sink never needs to reread the block.

Original consumed-output resolution is required by later Shelley+ address and
credential projections such as `address_transaction`; it is not the primary
justification for the first slice. Decode-once during normal operation is the
primary throughput benefit. Address/credential history also requires an
archive-owned sequential resolver for Byron because the live UTXO subsystem
does not apply Byron transactions.

Shelley-and-later validity semantics remain explicit:

- ordinary inputs consume outputs only for a valid transaction;
- collateral inputs consume outputs for a failed transaction;
- reference inputs never consume outputs;
- ordinary outputs are created only for a valid transaction;
- collateral-return output is created for the applicable failed transaction;
- same-block parent/child transactions preserve canonical transaction order.

Addresses, credentials, assets, datum/reference-script material, and witness
facts required by the archive schema are captured while available. A sink does
format translation and batching; it does not repeat ledger resolution.

#### Byron and epoch-boundary blocks

`BlockAppliedEvent.block()` is deliberately `null` for Byron main blocks and
Byron epoch-boundary blocks (EBBs), and multiple existing consumers use that
sentinel. The projection design must not overload or reinterpret it.

When projection history is enabled, `BodyFetchManager` publishes a dedicated
typed Byron projection event carrying the already-decoded raw `ByronMainBlock`
or `ByronEbBlock` after canonical storage. The collector retains the current
Byron normalizer that maps a main block into the projection model. It does not
decode stored CBOR again during normal application. EBBs produce an empty
logical envelope so the block projection coordinate remains contiguous.

Byron projection semantics are an explicit strict subset:

- `chain_transaction.fee` is `NULL` because the normalized Byron transaction
  does not provide a fee and the schema permits null;
- there are no invalid-transaction, collateral, reference-input, datum,
  redeemer, multi-asset, or pointer-address facts;
- Byron addresses use their raw base58 representation;
- Byron address/account participation that needs input funding addresses uses
  an archive-owned resolver seeded from Byron genesis and advanced in canonical
  order.

Crash recovery may decode a retained Byron body to rebuild a missing contributor
section. This is recovery replay, not a second normal-operation decode.

### 4. Extensible versioned sections

Later releases may add sections such as:

```text
account-events:v1
credential-filter:v1
governance-events:v1
certificate-events:v1
withdrawal-events:v1
```

The names above are illustrative until Phase 1 maps them to existing dataset
identity. In particular, the currently shipped `ACCOUNT_EVENT` and
`ADDRESS_TRANSACTION` datasets must receive equivalent sections before their
replay workers can be removed, unless a separately accepted product ADR
explicitly retires those datasets and their public API behavior.

Each section has an independent type and version. Unknown optional sections may
be skipped only when the archive identity says they are not required. A required
section unknown to the configured sink is a startup/processing error, not a
silent omission.

The envelope is not a dump of mutable internal objects. Section schemas are
stable projection contracts with deterministic encoding and explicit evolution
rules. A projection change increments the affected section version and the
archive records the activation coordinate.

### 5. Large epoch data uses a durable artifact source

Suitable large artifacts include immutable epoch-keyed generations of:

- epoch stake;
- rewards and reward-rest data;
- ADA pots;
- DRep distribution;
- governance proposal/status snapshots;
- other finalized epoch/governance snapshots meeting the eligibility contract.

Mutable CFs such as current account state are never referenced. Historical UTXO
balances are derived from archived output creation and consumption. Historical
stake/reward/account semantics are derived from the corresponding certificate,
withdrawal, reward, pot, and immutable epoch artifacts.

The codebase already has a restartable staged-artifact implementation:

```text
EpochArchiveStagingSink
  -> EpochArchiveStagingService
  -> DurableEpochFileSource
```

It streams epoch facts into immutable restartable files, writes a manifest with
deterministic job identity, exposes bounded paged reads and leases, and deletes
the staged source after acknowledgement. Retain this path in the storage
foundation. It is the starting representation for ephemeral facts that exist
only while a transition is evaluated, and it is the logical-equivalence
baseline for all epoch artifacts. It is not yet a proven power-loss-durable
source.

The existing implementation is a baseline, not yet the required-source
contract. `EpochArchiveStagingSink` currently isolates capture failures from the
authoritative transition, and `EpochArchiveStagingService` may disable/discard a
failed boundary. A required fresh-sync artifact cannot disappear that way. Phase
0/5 must either make capture failure fail closed before the source becomes
unreconstructible, or retain a durable replay source from which the exact staged
artifact is regenerated. Required versus optional artifacts are fixed in the
archive identity.

The staged-file commit must also prove power-loss durability: rows, manifest,
parent-directory rename state, completion marker, row count, codec/projection
version, and digest are forced/published in an order where no completed artifact
can reference missing or partial bytes. Process-restart tests alone are not
sufficient. Acknowledgement cannot delete a source with an active read lease.

Recoverability is classified per projected column, not merely per dataset. A
single archive row may combine an immutable value with boundary-time joins from
mutable state. Phase 0 must place every field into one of these classes:

| recovery class | current examples | required source contract |
|---|---|---|
| Exact persisted generation | `EPOCH_STAKE`; `ADA_POT`; the stake-amount columns of `DREP_DISTRIBUTION` | Protect the immutable generation and stream it later. No staged-file force is required at the transition if replay from that generation is byte-for-byte or logically deterministic. |
| Conditionally recomputable | `REWARD`, subject to proof | Retain the complete deterministic input closure and its versions until archive acknowledgement. For rewards this includes every required stake snapshot, pot/fee value, block-production count, protocol parameter, era/rule configuration, source identity, and calculation version; retaining only stake and pot data is not assumed sufficient. |
| Not reconstructible from current persisted state | Boundary-time DRep-state joins such as `stored_expiry`, `dormant_epochs`, `effective_expiry`, and `active`; governance proposal observation/decision semantics not present in its persisted snapshot | Persist the minimal immutable source evidence atomically with the contributor transition, or make the completed staged artifact durable before the transition can make the evidence unreconstructible. |

`DREP_DISTRIBUTION` therefore cannot receive one representation decision for
the whole dataset without further work. Its persisted amount generation is a
candidate live-generation source, while its joined boundary-state columns need
an immutable boundary snapshot or a hardened staged capture. Its
`source_state_version` and artifact manifest identify the representation and
semantic/source version used to produce the row.

`REWARD` remains in the conditional class until Phase 0 proves that replay
retains all inputs and versioned calculation semantics. If that proof fails, it
moves to the non-reconstructible class. The same classification is required for
future `reward_rest` and other artifact types before they enter archive
identity.

An exact persisted generation is not equivalent to an indefinitely retained
generation. `EPOCH_STAKE` currently shares the configured delegation-snapshot
retention window, and epoch-boundary pruning deletes older snapshot keys. A
reference to that generation therefore registers its retention requirement in
the same contributor batch that makes the reference recoverable. Snapshot
pruning clamps its oldest-to-keep boundary to the oldest active artifact or
reward-replay requirement. The generation remains protected until the sink
receipt is acknowledged; ordinary configured retention alone is not evidence
that it will still exist when a lagging sink reads it.

Staged files are not automatically the fastest representation. The current
writer encodes and writes them synchronously while epoch processing is
iterating the facts. For a generation already stored immutably in RocksDB, that
duplicates bytes and may lengthen epoch-boundary latency. Phase 0 must measure
staged-file boundary overhead, including required durability forcing, and
compare it with streaming the retained generation later. The decision is made
per artifact type, not globally.

A live-store generation may be referenced instead of staged only when:

1. keys identify an immutable epoch/generation;
2. the generation is never updated in place after closure;
3. its complete range is deterministic;
4. a versioned reader can translate it into stable projection rows;
5. pruning observes durable leases;
6. it can be scanned without holding the core apply lock for the duration;
7. rollback semantics for its producing boundary are defined;
8. measured epoch-boundary and sink performance is better than or equal to the
   staged representation without increasing recovery risk.

An artifact reference records at least:

```text
artifact type
semantic epoch
producing transition
effective coordinate
source generation/key range
source codec version
source representation = staged-file | immutable-generation
expected row/byte count when cheaply available
content/state digest when available
rollback-safe eligibility coordinate
```

The sink accesses artifacts through an `ArchiveArtifactReader`; DuckLake and
SQLite code do not receive raw CF handles or depend directly on physical key
encoding.

Durability work may run after the authoritative transition only when the exact
source generation or complete replay input closure is already durable and
protected. For non-reconstructible facts, this sequence is unsafe:

```text
write unforced staged bytes
commit authoritative transition
power loss
force/digest/publish staged artifact later
```

A one-epoch eligibility delay does not recover bytes lost between the second
and fourth steps. The preferred low-latency solution is to commit the minimum
immutable boundary evidence in the contributor's existing RocksDB batch and
materialize/force the large staged representation asynchronously. If that is
not possible, durable staged capture is on the transition's fail-closed path
and its latency must pass the Phase 0 gate.

### 6. Epoch artifacts publish one safe boundary later

At transition `E-1 -> E`, the ledger produces immutable state whose semantic
epoch is `E`. It is protected but not immediately eligible for archive
publication. At transition `E -> E+1`, the first canonical block crossing the
boundary may reference the artifact produced at `E-1 -> E`, provided its
producing coordinate is also older than the configured rollback-safe cutoff.

```text
transition E-1 -> E
  create immutable artifact E
  register protection
  keep pending

transition E -> E+1
  publish/reference artifact E if rollback-safe
  create and protect artifact E+1
```

The first block in `E+1` carries the reference, but the row remains labelled by
its semantic epoch `E`. The model distinguishes closing values for the prior
epoch from opening values effective in the new epoch.

One epoch is the normal delay, not the fundamental proof. The required check is:

```text
producing transition coordinate <= rollback-safe cutoff
```

This handles networks with short epochs or unusually large rollback retention.
If a block crosses more than one epoch boundary, the envelope may carry an
ordered list of transitions and newly eligible artifact references.

The delay supplies ample scheduling slack for force, digest, and manifest
publication when a durable reconstruction source or atomic boundary-evidence
record already exists. It is not itself a durability barrier. An artifact is
ineligible until both rollback safety and source durability are proven.

Archive code does not invent a second finality calculation. The greatest
normally eligible block remains:

```text
tip block - ArchiveSafetyWindows.archiveFinalityBlocks
```

`ArchiveSafetyWindows.resolve(...)` derives the security, rollback-retention,
and archive-finality depths from genesis/configuration and enforces:

```text
archiveFinalityBlocks >= rollbackRetentionBlocks >= securityParam
```

Equivalently, combining the depths uses:

```text
tip - max(archiveFinalityBlocks, rollbackRetentionBlocks)
```

That reduces to the existing finality boundary. The resulting block identity is
read consistently with the canonical tip.

The runtime common rollback floor is a different quantity: the maximum of all
`RollbackCapableStore.getRollbackFloorSlot()` values, where a lower slot means
more history remains replayable and zero means unbounded retention. In the
account store it incorporates the earliest retained per-block issuer/fee facts
and the earliest delegation snapshot needed four epochs later by reward replay.
Phase 0 verifies that this calculation covers every input needed to reproduce
the archived reward schema; archive code reuses the result rather than copying
the calculation.

For every pending envelope, artifact, or recomputation closure, record the
oldest rollback target/source slot it still requires. Retention is healthy only
while:

```text
runtime common rollback floor <= oldest active required slot
```

The lease clamps source pruning so the floor cannot cross that requirement.
Equality is an exhaustion warning; a greater floor means required replay data
was lost and triggers the visible fail-closed/pause path. It does not silently
move the finality boundary or skip the affected work. A restore or configuration
change that invalidates this invariant likewise fails closed unless an explicit
reconstruction or exceptional invalidation protocol repairs it.

### 7. Artifact leases are durable pruning contracts

Artifact production/protection and its transition marker use a protocol that
cannot expose an eligible reference without a durable source. A filesystem
artifact and a RocksDB marker do not pretend to share an atomic transaction:
the file reaches forced, digest-verified completion first, then a RocksDB
`READY` marker makes it referenceable; recovery removes orphaned pre-marker
files and fails closed on a marker whose source cannot be verified. For staged
files, acknowledgement is the only normal deletion path. For immutable
live-store generations, source registration/protection is committed in the
contributor batch and pruning uses:

```text
eligible for normal pruning
AND no active projection artifact lease
```

The lease remains from artifact creation until durable primary-sink
acknowledgement. It survives restart and software upgrade. A sink lag therefore
causes visible retained-disk growth rather than silent data loss.

A wall-clock expiry is a liveness signal, not authority to remove protection
from a source that a reader may still use. Readers renew leases and carry a
fenced process/session identity; abandonment cleanup proves that the owner is
stale before releasing protection. Registration, renewal, acknowledgement, and
cleanup are coordinated so acknowledgement cannot remove staged bytes or a
generation while a live reader lease exists. The current staged-file expiry
cleanup and acknowledgement behavior must be hardened to satisfy this contract.

Recovery from `PREPARING` is class-specific:

- for an exact persisted generation, re-derive the staged representation from
  the protected generation;
- for a conditionally recomputable artifact, recompute it only after verifying
  the complete retained input and calculation-version closure;
- for a non-reconstructible artifact, regenerate it from the immutable evidence
  committed in the contributor batch, or fail closed if that evidence is not
  present.

The `PREPARING -> READY` marker is detection and orchestration, not the source of
non-reconstructible-data safety. A design without atomic boundary evidence must
instead force and verify its staged source before the authoritative transition
becomes irreversible.

Native RocksDB snapshots are not held for hours as the default mechanism because
they can pin SST files and impede compaction. A staged immutable file or
epoch-versioned immutable keys plus a logical pruning lease are preferred. An
immutable checkpoint is allowed where the source store cannot provide versioned
generations and its lifecycle is equally explicit.

Staged files freeze a stable projection codec at capture time. Immutable live-CF
references instead require readers for every source codec version still
referenced by a pending artifact. Startup fails before processing if any pending
artifact representation cannot be read.

### 8. Canonical apply and outbox durability contract

The design requires the following invariant:

> An applied canonical block `B` cannot become unreconstructible until every
> required contributor section for `B` is durable. Canonical progress may lead a
> contributor, but the stored body plus contributor cursor remains a durable
> replay intent and pruning protection until the logical envelope is complete.

The current runtime deliberately has no one transaction spanning canonical
chain storage, UTXO state, ledger/account state, and projections. Canonical
`storeBlock` commits before `BlockAppliedEvent`; UTXO and ledger contributors
have their own cursors, RocksDB batches, and replay-from-stored-body recovery.
ADR-039 preserves that production recovery model rather than introducing a
cross-subsystem transaction.

Each required contributor writes its section chunks and contributor coordinate
inside the same existing RocksDB `WriteBatch` as the state and rollback data from
which that section was derived. All contributors use the node's shared RocksDB,
but atomicity is per contributor. A logical envelope becomes complete and sink
eligible only after every required contributor for its projection identity has
durably reached that block with matching canonical identity.

The stored canonical body and contributor cursors are the durable apply intent.
Startup reconciliation replays any missing contributor range in canonical order
before the envelope can be marked complete. Block-body pruning is clamped by the
oldest incomplete required contributor through the existing
`BlockBodyRetentionBoundary` mechanism. A body cannot be pruned merely because
one faster contributor has produced its section.

Phase 0 must still map and test each contributor boundary, required-section
completion, pruning clamp, and replay order. It must not attempt a single batch
across chain, UTXO, ledger, and projection unless source evidence later proves a
specific correctness need that per-contributor replay cannot satisfy.

A best-effort event-bus listener after block commit is rejected. A crash between
canonical progress and an ephemeral listener would create an unrecoverable gap
once block bodies are pruned. An event-driven contributor is acceptable only
when its section and cursor commit atomically with its source state and the
retention/reconciliation contract prevents that gap.

Projection construction is allowed on the hot path; sink I/O is not. The hot
path may perform deterministic encoding and a bounded RocksDB write. It must not
open DuckDB/SQLite archive sessions, wait for an archive writer, or scan a large
epoch artifact.

“Decode once” means once during successful normal operation. Crash/startup
reconciliation may decode a retained body again to reproduce the same
deterministic contributor section. No field may depend on state that is
unavailable or unreconstructible when that replay occurs.

### 9. Finality-gated asynchronous consumption

Envelopes are created immediately with canonical application so rollback can
remove them exactly. The primary sink consumes only the greatest contiguous
range whose blocks are older than the configured rollback-safe cutoff.

There is no separate `CATCHING_UP -> LIVE` archive lifecycle:

```text
small outbox backlog = near-live archive
large outbox backlog = archive catching up
```

The consumer is ordered by canonical block coordinate. It may batch several
envelopes within configured row, byte, block, time, and memory bounds. It must
not reorder stateful section semantics.

Core sync normally continues while the sink is slow or unavailable. Soft limits
degrade archive health and expose lag/disk metrics. A configured hard disk or
outbox bound may pause canonical ingestion rather than discard projection data;
the node must report that condition explicitly.

### 10. Exactly-once effect through receipts and idempotency

The delivery protocol is at-least-once. Deterministic jobs, logical keys,
digests, counts, and durable sink receipts provide exactly-once effect.

```text
read contiguous eligible envelopes
  -> begin deterministic sink job
  -> write all required rows/artifacts
  -> verify keys/counts/digest
  -> commit sink data + durable receipt
  -> acknowledge outbox range
  -> delete acknowledged chunks and release artifact leases
```

On retry, an existing receipt is accepted only when identity, projection
versions, envelope range, counts, and digest match. A differently shaped retry
is rejected.

Cleanup is scoped exactly to a verified receipt. It does not infer success from
the sink's current maximum block or from partial row presence.

### 11. Crash boundaries

At minimum, tests cover:

1. crash after canonical body commit but before one or more contributor sections;
2. crash after a contributor state commit before logical-envelope completion;
3. crash after complete durable outbox commit before sink start;
4. failure after multiple sink staging flushes but before sink commit;
5. crash during artifact streaming;
6. sink commit failure;
7. crash after sink data commit but before outbox acknowledgement;
8. crash after acknowledgement but during chunk cleanup;
9. crash after receipt verification but before artifact-lease release;
10. restart with an old pending section/artifact codec;
11. sink unavailability until soft and hard backlog thresholds;
12. power loss after an epoch transition but before deferred artifact
    force/digest/manifest publication;
13. a live reader exceeds its lease deadline while acknowledgement or stale
    cleanup races it.

Every case must recover without missing facts, duplicate logical rows, cursor
skips, premature pruning, or unbounded retry memory.

### 12. Rollback and invalidation

Before sink eligibility, rollback removes or replaces envelopes and artifact
references atomically with live-state rollback. A replacement canonical block at
the same height has a different deterministic envelope identity.

The primary sink normally contains only data older than the supported rollback
boundary. Ordinary rollback therefore never mutates the immutable archive.

An explicitly supported exceptional deep rollback requires a separate
group-atomic archive invalidation contract and downstream invalidation journal.
It is not silently implemented as row deletion. If deep rollback beyond archive
finality remains unsupported, the node fails closed and requires a coordinated
restore/resync.

### 13. One primary sink in version one

Configuration selects exactly one:

```text
history.projection.sink=ducklake
```

or:

```text
history.projection.sink=sqlite
```

The archive identity records the selected sink and cannot be changed in place.
The sink contract is conceptually:

```java
interface ProjectionSink {
    ProjectionReceipt append(ProjectionBatch batch);
    ProjectionCoordinate coordinate();
    ProjectionCoverage coverage();
}
```

The actual API must also carry identity, versions, receipts, artifact readers,
capacity/backpressure, health, and close semantics. This sketch is not the full
contract.

If both sinks were direct outbox consumers, cleanup would require per-consumer
offsets and the slowest consumer would retain every envelope. Version one avoids
that complexity.

### 14. Future sinks derive from the primary archive

A later SQLite, DuckLake, search, or export store is built from the current
primary archive through a separately designed projection/cutover process:

```text
primary archive snapshot at G
  -> populate target through G
  -> apply source changes G+1..H
  -> final fenced drain
  -> verify identity/counts/digests/coverage
  -> optional active-sink switch
```

Because acknowledged outbox entries are deleted, a new sink cannot assume the
old outbox remains available. It either derives from the primary archive, starts
at an explicit activation coordinate, or requires a fresh sync.

The primary archive must therefore retain stable provenance and all facts needed
by promised downstream projections. A future projection requiring data never
captured by the primary archive needs new section activation, retained-block
replay, or a fresh sync.

### 15. Transaction locator is deferred and downstream

The projection outbox does not itself make a Parquet point lookup fast.
`txHash -> block/job/partition` remains useful for `/txs/{hash}` and input-parent
resolution because a hash predicate over many Parquet files can be slow.

The locator is not part of the primary sink acknowledgement in this ADR. A
future design may use:

- a SQLite locator fed from a durable DuckLake delta journal;
- a long-lived RocksDB transaction index;
- another indexed store;
- or a future native archive indexing capability.

Until then, DuckLake queries may use a slower scan/fallback and APIs requiring a
complete point index must report their limitation. In particular, the separate
per-block credential-filter and `/scan` proposal currently relies on a complete
transaction index for some `/txs/{hash}` and parent-input fallbacks. Those phases
are not claimed complete by this ADR.

Before API cutover, produce a per-endpoint decision table for at least
`/txs/{hash}`, transaction UTXO/input-parent resolution, address history,
account history, and wallet confirmation/status behavior. For each endpoint it
must name the primary table, live fast path, no-locator fallback, completeness
range, latency bound/timeout, and response when the fallback cannot provide the
promised result. “The archive can scan” is not by itself an API contract.

Standalone SQLite may provide indexed transaction lookup natively, but that does
not change the cross-backend contract: point lookup is a query capability, not a
condition for acknowledging canonical archive data.

### 16. Coverage and query semantics

The primary block projection exposes one greatest contiguous committed block
coordinate. It does not expose independent transaction and UTXO cursors for the
same envelope range.

Epoch artifact coverage remains typed because semantic epochs and publication
delay differ from block projection coverage:

```text
blockProjectionThrough: B
epochStakeThrough: E
rewardsThrough: E-1
drepDistributionThrough: E
```

History APIs either:

- serve only ranges covered by every required table/artifact;
- return explicit partial-coverage metadata;
- or return an unavailable/catching-up response.

They never present an outbox row as archived before the primary sink receipt.
Current live state may still answer explicitly current-state APIs, but it is not
silently merged into immutable historical coverage.

### 17. No mutable-CF archive references

Mutable account/current-state CFs are excluded. Historical UTXO-based balances
can be reconstructed at block `H` from outputs created through `H` minus inputs
that consume them through `H`, including native-asset rows. Common expensive
queries may later use derived snapshots plus deltas.

Stake/reward/account semantics not represented by UTXOs are reconstructed from
the archived certificate, withdrawal, reward, ADA-pot, and immutable epoch
artifacts. The design preserves facts and events, not every mutable materialized
view.

## Correctness invariants

The implementation must preserve all of the following:

1. **No canonical gap:** every applied block in a history-enabled fresh sync has
   exactly one required logical projection envelope or a retained canonical body
   plus contributor coordinates that can deterministically complete it.
2. **Canonical identity:** envelope block hash and parent match canonical state
   at the atomic boundary.
3. **Ordered state effects:** UTXO and other stateful facts preserve canonical
   transaction and block order.
4. **No unfinalized archive rows:** the sink consumes only rollback-safe blocks
   and artifacts.
5. **Atomic sink visibility:** all required tables for a projection batch and
   its receipt become visible together.
6. **Receipt-before-cleanup:** no outbox data or artifact protection is removed
   before the matching durable receipt is verified.
7. **Idempotent replay:** a matching retry has no duplicate logical effect; a
   mismatching retry is rejected.
8. **Lease-before-prune:** referenced artifact data and conditionally
   recomputable input closure cannot be pruned while any unacknowledged
   reference depends on them.
9. **Version readability:** every pending required section/artifact has an
   installed compatible reader.
10. **Sink isolation:** archive or optional-index failure cannot corrupt
    canonical state and does not normally block it before an explicit hard
    resource limit.
11. **Complete manifest:** a sink cannot acknowledge a logical envelope with a
    missing chunk or required section.
12. **Fresh identity:** incomplete mid-chain activation is rejected rather than
    represented as genesis-complete coverage.
13. **Contributor-before-prune:** no canonical body required by an incomplete
    contributor may be pruned.
14. **Retention floor below requirements:** the runtime common rollback floor
    cannot advance above the oldest slot required by any pending envelope,
    artifact, or recomputation-input closure. A violation pauses rather than
    narrowing archive eligibility.

## Operational model

At minimum expose:

- canonical tip and projection sink coordinate;
- outbox lag in blocks, rows, and bytes;
- oldest pending envelope age;
- eligible versus finality-waiting envelopes;
- active batch identity and stage;
- sink commit latency and throughput;
- receipt replay count;
- outbox cleanup lag;
- active artifact leases by type/epoch/representation/bytes;
- oldest retained delegation snapshot and the configured versus lease-clamped
  pruning boundary;
- runtime common rollback floor, oldest active required slot, and remaining
  retention margin;
- outbox bytes, staged-file bytes, estimated bytes in pinned live generations,
  total chainstate bytes, and filesystem free space;
- oldest codec version still pending;
- soft/hard backlog state;
- archive and artifact coverage;
- last failure with bounded/sanitized detail.

Shutdown stops new sink batches, lets the active bounded batch finish or abort,
preserves the outbox, and closes the sink. Core shutdown does not delete pending
data. Startup reconciles identity, receipts, acknowledged-but-not-cleaned ranges,
and artifact leases before accepting new projection work.

Soft/hard resource limits cover the whole archive retention obligation, not only
outbox CF values. They include staged files and generations kept in chainstate
beyond normal retention. Because RocksDB SST sharing makes exact attribution
impossible, the policy uses logical/estimated pinned-generation bytes together
with measured total chainstate growth and free space. At the hard limit the node
pauses canonical ingestion visibly; it never satisfies the limit by pruning an
unacknowledged source.

## Performance model and acceptance criteria

The architecture moves deterministic fact encoding and an additional bounded
RocksDB write into canonical processing, while removing repeated block reads,
decodes, and resolver work from history workers. It is accepted only if the
trade is measured.

The outbox absorbs burst variance; it does not create sustainable throughput.
Let:

```text
Rc = concurrent history-enabled canonical production rate in blocks/s
Rs = concurrent primary-sink drain rate in equivalent blocks/s
B  = measured outbox bytes produced per canonical block
```

If `Rs < Rc`, retained bytes grow approximately as
`(Rc - Rs) * B * elapsedSeconds`. Over a genesis sync this can move a material
fraction of the archive into the hot RocksDB, increase its compaction/write
amplification, and eventually throttle core ingestion at the hard limit. That is
a product mode, not a transient backlog, and cannot be accepted accidentally.
This equation covers block-envelope growth only; capacity planning adds staged
files and the logical/physical cost of live generations pinned beyond their
normal retention window.

### Revised criteria — 2026-08-20

**Criterion 2 as originally written is withdrawn.** "Sink drain rate at least 20% greater
than concurrent history-enabled canonical production" measured the sink against
*accelerated bootstrap* core production and therefore asked the wrong question. Canonical
sync is asynchronous: core does not wait for the sink, and the outbox exists precisely so
it need not. Two regimes must be separated:

```text
bootstrap   core ~2,000+ blocks/s   sink drains behind    -> a capacity question
steady tip  core ~0.05 blocks/s     sink far ahead        -> a margin question
```

At mainnet's 20-second slots a sink doing even 60 blocks/s outruns block arrival by three
orders of magnitude. There is no steady-state throughput problem. The bootstrap deficit is
a one-time backlog whose cost is **disk**, not throughput, and the correct control is a
disk budget, not pacing core to sink speed.

Replacement criteria:

**Bootstrap**

- Core may run ahead of the sink; this is the designed behaviour, not a degraded mode.
- Measure the complete backlog and disk requirement for a full genesis sync.
- The projected backlog must fit an explicitly accepted disk budget.
- Measure time-for-core-to-reach-tip and time-for-history-to-catch-up **separately**.
- Sink catch-up time must be measured and bounded.
- Historical APIs report `catching_up` until their required coverage exists.

**Steady state**

- Sink capacity must exceed actual network block arrival with a safe margin.
- The steady-state outbox must remain bounded.
- Maintenance must not cause sustained backlog growth.

**Disk backpressure**

- Continue asynchronous canonical sync while aggregate archive-retained disk usage stays
  below the configured hard limit.
- Pause canonical ingestion visibly when aggregate usage reaches the hard limit, or
  filesystem free space reaches the configured safety reserve.
- Resume automatically once acknowledged cleanup returns usage below a low-water mark.
  Hysteresis is required; resuming at the pause threshold oscillates.
- Never discard envelopes, artifact evidence, receipts, or protected source generations to
  recover space.
- Expose pause reason, current usage, thresholds and required cleanup progress through
  status, readiness and metrics.

Aggregate archive-retained usage covers outbox column families, staged artifact files,
chainstate generations pinned by artifact leases, and expected RocksDB write/compaction
amplification — measured at roughly **1.4x** logical for the first slice.

**In-memory bounds** on staging, batches, queues and chunks remain, but they are local
flow control: a full queue may briefly block its immediate producer, must never lose
projection data, and must never permanently pause core sync. Durable disk limits are the
only authority over how far canonical sync may run ahead over time.

**Readiness** — core readiness and archive readiness are independent. Core may be ready
while historical endpoints report `catching_up`. Historical endpoints must never claim
coverage beyond verified sink receipts.

The first vertical slice must demonstrate:

1. no more than 5% sustained core-sync throughput regression with history enabled versus
   the same fresh-sync workload with history disabled;
2. a bootstrap backlog and disk requirement that fit an accepted budget, a measured and
   bounded sink catch-up time, and a steady-state sink margin over real block arrival;
3. bounded envelope/chunk size and memory;
4. measured average/p95/p99 outbox bytes per block, a full-mainnet-sync
   wall-clock/backlog model, staged and pinned-generation retention, bounded
   normal growth, and accurate total chainstate/disk growth under an
   intentionally stopped sink;
5. logical equivalence with the existing transaction/UTXO archive for the same
   chain range, including rows, logical keys, counts, and digests;
6. no missing/duplicate facts across every crash boundary;
7. correct rollback across blocks, same-block dependencies, and an epoch
   boundary before sink eligibility;
8. no artifact pruning while protected and successful pruning after receipt;
9. startup recovery without full archive scans in the normal case;
10. API coverage/readiness that matches the committed primary archive exactly.

#### Status of the ten, 2026-08-20

| # | Criterion | State |
|---|---|---|
| 1 | <=5% core regression | **BOUNDED, budget straddled** — measured by attribution inside a single run over 2,267,163 blocks: per-block cycle 520,033 ns, apply 159,789 ns (30.7%), projection 32,711 ns (**6.3% of cycle**, 20.5% of apply); the run continued to 3,107,640 blocks with the apply share settling at 18.5%, so the ratio is stable across three million blocks. That 6.3% is an *upper bound* — it assumes apply sits wholly on the critical path — so the true cost is in (0, 6.3%] against a 5% budget. Round 1's −14.1% is not the current cost. Excludes the asynchronous sink drain and the separately-measured ~1.4x write amplification. |
| 2 | bootstrap backlog/disk, catch-up time, steady-state margin | **met on preprod** — full fresh sync 1h07m for 5,076,688 blocks; backlog pinned at the 4,320-block finality window; archive 3.7 GB against 27 GB chainstate. Not yet shown at mainnet scale. |
| 3 | bounded envelope/chunk size and memory | **met and measured, all four sections** — peak retained heap 2,436 KiB at 5 envelopes vs 2,571 KiB at 100 (20x the batch, 5.5% more heap); densest single envelope 5,740 rows / 6,467 KiB. With two sections the same measurement was 1,066 vs 1,111 KiB, so the bound holds as datasets are added rather than only for the first slice. |
| 4 | average/p95/p99 outbox bytes/block, mainnet model, retention, stopped-sink growth | **partial, and now stale** — averages measured (2,817 B/block outbox, 709 B/block Parquet, 1.4x amplification) but **with two sections only**. The address-transaction section alone encodes at 234 B/participation, so a four-dataset node is materially larger; the model must be re-derived. p95/p99 per-block distribution is still not instrumented. |
| 5 | logical equivalence with the existing archive | **met for the covered fixture set, and independently corroborated** — byte-identical rows across 21 differential fixtures (10 original + 7 certificate + address-transaction side-by-side against the live dataset on a real spend). Corroborated on a real chain: two independent genesis syncs, different jars and section sets, produce the same MD5 digest over `tx_hash, output_index, address, lovelace` and the same lovelace sum for blocks 4,050,000-4,060,000. Still not covered: reference scripts, Byron genesis output resolution, era-transition blocks and epoch-boundary rollback. |
| 6 | no missing/duplicate facts across every crash boundary | **met, on both section sets** — graceful restart and `kill -9` on a live preprod archive: 0 gaps, 0 duplicate transactions, 0 duplicate outputs, 0 overlapping receipts. Repeated with all four sections: `kill -9` at tip mid-batch left the acknowledged point unchanged, contributor lag 0, and the node serving again in 21 s. Several restarts with a batch mid-flight left it unacknowledged and rebuilt it identically. |
| 7 | rollback across blocks, same-block dependencies, epoch boundary | **partial** — block- and slot-keyed rollback, same-block dependencies and buffered-batch discard are covered by tests; rollback across an epoch boundary is not. |
| 8 | artifact pruning protected then released | **not applicable yet** — no artifacts until Phase 5. The lease-reconciliation gap is identified and must ship with the first artifact. |
| 8 | artifact pruning protected then released | **met** — epoch stake holds a snapshot clamp lowered only to the oldest referenced epoch and released on acknowledgement; staged evidence holds a read lease and is deleted only after a durable receipt. The startup lease-reconciliation gap the ADR flagged is closed: the outbox's surviving references are the durable contract, replayed at startup and after rollback |
| 9 | startup recovery without full archive scans | **met** — receipt identity is checked against the encoded batch, so a replayed range is recognised without decoding a section; the sink provider no longer builds the replay-worker locator (~61 s saved per restart). |
| 10 | API coverage/readiness matches the committed archive | **not started** — Phase 6. Near tip a block can be final and durable but not yet queryable in the sink for **the linger plus at most one compaction budget** — 15 min + 5 min at the defaults, because maintenance runs on the drain thread and delays the next batch from opening. An endpoint must serve that range from recent storage or report coverage honestly, never report false absence. Note that `pendingBatchAgeSeconds` measures buffer residency, not finality-to-sink latency, and so understates the window by any maintenance delay. |

The 5% threshold is an initial decision target, not a claim already measured.
If deterministic projection encoding exceeds it, the design must first remove
avoidable copies/encoding work. It must not hide the overhead by making the
canonical/outbox boundary best-effort.

Phase 0 cannot pass with only an isolated DuckLake benchmark. It needs the
history-disabled core baseline, history-enabled producer rate, and sink drain
rate measured concurrently on the same retained workload. If the 20% margin is
not met, implementation pauses for an explicit choice among sink optimization,
resource/profile changes, accepted core throttling, or revised scope.

## Branching and repository strategy

### Current repository state

At the time of this ADR, `feat/adr-038-archive-throughput` contains the proven
archive schemas, API/storage contracts, DuckLake and SQLite implementations,
backend conformance tests, and ADR-038 performance/correctness changes. It also
contains the independent replay workers, hot-history lifecycle, prefetch
optimization, synchronous locator integration, and production evidence specific
to the architecture this ADR replaces.

The branch is materially diverged from `main`; raw `main` does not contain
`archive-modules`. Starting implementation from raw `main` would produce a clean
graph but unnecessarily reimplement the valuable backend work. Continuing
inside ADR-038 would mix a closed throughput campaign with a replacement
architecture.

### Required branch shape

Use two branches (and preferably separate worktrees):

```text
main
  |
  +-- feat/archive-storage-foundation
          |
          +-- feat/archive-projection-outbox
```

`feat/archive-storage-foundation` extracts or cherry-picks only reusable pieces
from the current archive line and integrates current `main`:

- archive API identities, schemas, rows, queries, receipts, and errors;
- DuckLake archive initialization, read/write sessions, staging/Appender path,
  bounded capacity, maintenance, and conformance tests;
- standalone SQLite archive schema, read/write sessions, and conformance tests;
- `EpochArchiveStagingSink`, `EpochArchiveStagingService`,
  `DurableEpochFileSource`, their codecs/manifests/leases, and their tests as the
  default durable epoch-artifact baseline;
- shared sink-independent storage tests and query DTOs;
- required application packaging/provider wiring that does not start the old
  worker lifecycle.

It excludes or leaves disabled pending removal:

- `BlockArchiveWorker` and `LiveBlockArchiveWorker` orchestration;
- block replay sources/decoders used only by archive catch-up;
- per-dataset catch-up/live cursors and promotion;
- hot-history row promotion used only by the old lifecycle;
- ordered UTXO prefetch, which optimizes repeated archive decode;
- synchronous SQLite transaction-locator update in the DuckLake commit path;
- ADR-038 runtime tuning and reports as implementation dependencies.

`feat/archive-projection-outbox` begins only after the foundation builds and its
backend conformance tests pass. ADR-039 implementation commits land there.

If extracting the foundation first is temporarily impractical, branch
`feat/archive-projection-outbox` from the stable ADR-038 head, integrate current
`main`, and delete/replace old orchestration early. Do not implement ADR-039
directly on `feat/adr-038-archive-throughput`.

### ADR numbering integration note

Another development line currently uses `ADR-036` for the per-block credential
filters and `/scan` proposal, while this archive line already contains
`ADR-036: Pluggable Hot History Store`. Resolve that number collision during
branch integration. This ADR refers to the proposal by title, not by number,
until the merged repository assigns an unambiguous identifier.

### Commit grouping

Keep changes reviewable in this order:

1. archive storage foundation extraction/integration;
2. ADR-039 envelope, identity, and sink contracts;
3. fresh-sync startup guard and archive identity;
4. outbox persistence, manifests, chunks, metrics, and recovery;
5. transaction/UTXO block-scoped collector;
6. DuckLake primary sink adapter;
7. immutable artifact reader and lease protocol;
8. one epoch artifact vertical slice;
9. parity, crash, rollback, and performance evidence;
10. API cutover;
11. removal of old archive replay workers and unused legacy history;
12. standalone SQLite primary sink adaptation.

Do not mix production samples, unrelated ADR work, or deployment-specific
configuration into the implementation commits.

### Module ownership after cutover

Keep the existing `archive-modules` shape, but replace worker-oriented ownership
with projection-oriented ownership:

```text
archive-modules/archive-api
  stable envelope/section/artifact value types
  projection identity, coordinate, coverage, receipt, and sink contracts
  archive schemas, rows, queries, and errors

archive-modules/archive-core
  RocksDB outbox and serialization
  block-scoped projection collector
  finality-gated ordered consumer
  staged-file and immutable-generation artifact readers
  artifact registry, leases, and cleanup
  recovery, rollback, health, metrics, and backpressure

archive-modules/archive-store-ducklake
  DuckLake ProjectionSink
  physical table/view initialization and migrations
  batch staging/Appender, verification, receipts, reads, and maintenance

archive-modules/archive-store-sqlite
  standalone SQLite ProjectionSink
  physical table/index/view migrations
  batch verification, receipts, reads, and maintenance

runtime/core integration
  contribute authoritative facts during ordered canonical apply
  expose immutable artifact sources and pruning hooks
  never depend on DuckLake or standalone SQLite implementations

app
  select and compose exactly one sink
  enforce fresh-sync/archive identity
  expose configuration, status, readiness, and lifecycle
```

Dependency direction is toward `archive-api`; storage backends do not depend on
runtime internals. Staged files and qualifying immutable live stores expose
artifact readers through an interface, not raw RocksDB handles. The block-scoped
collector must not become a service locator through which archive code reaches
arbitrary mutable node state.

Configuration names are finalized during Phase 1. They must have one source of
truth for rollback/finality rather than a second archive-only interpretation.
Required configuration concepts are:

```text
projection history enabled at genesis
primary sink = ducklake | sqlite
aggregate archive-retention soft/hard byte bounds
outbox soft/hard block bounds
batch block/row/byte/time/memory bounds
sink retry/backoff and bounded shutdown
artifact retention/lease observability
existing ArchiveSafetyWindows as the finality/eligibility calculation
runtime common rollback floor as the retained-source health constraint
required section set and versions
```

## Implementation plan

### Phase 0 — prove boundaries and establish the foundation

Status: required before implementation.

1. Map canonical block persistence, UTXO apply, ledger/epoch transition, event
   publication, and rollback transaction boundaries in source.
2. Prove per-contributor section/cursor atomicity, logical-envelope completion,
   retained-body replay, and the pruning clamp. Do not introduce a global
   cross-subsystem batch without new evidence.
3. Inventory each initial archive column and identify its authoritative live-path
   or recovery-replay source; no field may depend on state unavailable or
   unreconstructible when contributor replay occurs.
4. Prove the dedicated Byron main-block/EBB projection carrier and retained
   normalizer, including empty EBB envelope continuity.
5. Inventory immutable epoch CF generation, mutation, rollback, and pruning;
   compare each candidate with the existing restartable staged-file path and measure
   synchronous epoch-boundary overhead. Audit staged capture failure semantics,
   force/rename durability, completion publication, digest/count/version
   manifests, and acknowledgement versus active leases. Produce a per-column
   recoverability matrix: exact persisted generation, conditionally
   recomputable with a proven complete input/version closure, or not
   reconstructible. Define the atomic evidence or synchronous durability
   boundary for every field in the last class. Implement or specify the
   delegation-snapshot pruning clamp, reuse the runtime common rollback-floor
   calculation as a retention-health assertion, and prove that its
   reward-input/snapshot floors cover the full archived reward-row input
   closure. Keep archive eligibility on the existing `ArchiveSafetyWindows`
   finality calculation.
6. Establish `feat/archive-storage-foundation` and make all retained backend
   conformance tests green on current `main`.
7. Measure history-disabled core rate, history-enabled producer rate, concurrent
   sink drain rate, outbox bytes/block distribution, RocksDB write/compaction
   volume, and block/epoch-boundary latency on representative workloads. Produce
   the full-mainnet-sync wall-clock/backlog model and apply the 20% drain-margin
   decision gate.

Exit: per-contributor durability/replay is demonstrated by crash tests, not only
a diagram; every initial row field has a source; Byron/EBB continuity is proven;
the sink/core/backlog performance gate is met or explicitly decided; and the
clean foundation builds.

### Phase 1 — contracts, identity, and fresh-sync guard

Define versioned envelope/section/artifact types, sink/receipt contracts,
projection coordinates, required-section policy, and archive identity. Reject all
unsupported mid-chain or mismatched starts. Add serialization golden fixtures.
Define an explicit section-to-existing-dataset/table/projection-version mapping
and its future version-reconciliation rules; transport grouping must not
silently change coverage identity.

Exit: malformed, missing, unknown-required, wrong-network, wrong-genesis,
wrong-version, mid-chain activation, and mismatched-sink cases fail closed.

### Phase 2 — durable block outbox

Implement deterministic headers/chunks, atomic persistence or apply-intent
reconciliation, finality eligibility, ordered reads, cleanup, metrics,
soft/hard bounds, and rollback of pending envelopes.

Use a synthetic sink first. Exercise every outbox-only crash boundary before
adding DuckLake.

Exit: long restart/rollback/failure loops show contiguous coordinates and no
lost/duplicated envelope identities.

### Phase 3 — transaction and UTXO vertical slice

Capture `transaction:v2` and `utxo-history:v5` from canonical apply while
preserving the current dataset/table mapping.
Cover valid/invalid transactions, ordinary/collateral/reference inputs,
collateral return, same-block parent/child spends, multi-asset outputs, datum
hashes, inline datums, reference scripts, redeemers, Byron/genesis outputs, and
pointer-address semantics where applicable.

Drive genuine Byron main blocks through the dedicated projection carrier and
genuine Byron EBBs through the empty-envelope path. Preserve nullable Byron fee,
base58 address, parent/EBB bridge, and recovery-replay semantics.

Exit: byte/logical equivalence against the current projection over the same
devnet/preprod fixture, including all six tables and ordered digests.

### Phase 4 — DuckLake primary sink

Adapt the retained DuckLake storage session to consume projection batches and
artifact streams. Commit every required table plus receipt atomically. Remove
the SQLite transaction locator from the acknowledgement/critical path; point
lookup fallback is explicit.

Exit: sink crash matrix, staging failure, matching receipt replay,
mismatching-retry rejection, bounded capacity, and sustained drain-rate gates
pass.

### Phase 4b — remaining shipped block-dataset parity

Add accepted envelope sections for `ACCOUNT_EVENT` and
`ADDRESS_TRANSACTION`, unless a separately accepted product ADR has already
retired either dataset and its APIs. Reuse Shelley+ live resolution where it is
authoritative. Retain an archive-owned sequential outpoint resolver for Byron
address participation, seeded from Byron genesis and updated in canonical
order. Include this resolver in contributor atomicity, rollback, replay, and
pruning protection.

Exit: all four currently shipped block datasets have differential parity across
Byron and Shelley+ ranges, or every omitted dataset has an explicit accepted
removal decision and API migration. Rerun the core-overhead, sink-margin,
bytes/block, and full-sync backlog gates with `ACCOUNT_EVENT` and
`ADDRESS_TRANSACTION` enabled. High-cardinality address-touch projection is not
grandfathered past the performance gate merely because the old worker shipped
it.

### Phase 5 — immutable epoch artifact vertical slice

Start with epoch stake as the representative large artifact because its exact
persisted generation makes the reconstruction boundary testable without mixing
in mutable boundary-state joins. Use the existing staged-file output as the
logical-equivalence baseline, but do not describe its current flush-only path as
power-loss durable. Adopt an immutable-generation reference only if Phase 0
proves its eligibility and performance advantage. Implement
one-safe-boundary-later eligibility, `ArchiveArtifactReader`, streaming sink,
receipt verification, lease/protection release, restart recovery, and pruning.
This slice is an actual A/B comparison: epoch stake already has both staged rows
and a persisted delegation snapshot. Measure boundary latency, sink rate,
retained bytes, replay equivalence, and sink-lag behavior for both before
selecting its representation.

Then add ADA pots. Add rewards/reward-rest only after proving and retaining their
complete deterministic input and calculation-version closure, otherwise capture
them as non-reconstructible evidence. Add DRep distribution per field: stream
the persisted amount generation and atomically snapshot or durably stage the
boundary-time DRep-state columns. Add governance proposal/status snapshots only
after their observation phase and decision semantics have an immutable source.

Exit: rollback before eligibility removes/replaces the reference; sink lag
cannot cause pruning; acknowledgement permits eventual pruning; codec upgrades
with pending artifacts are safe; and required staged artifacts survive the
power-loss/failure matrix without being silently discarded. Power loss between
transition commit and deferred force/publish reconstructs output in either of
the first two recovery classes, or proves that non-reconstructible evidence was
already durable.

### Phase 6 — operational and API cutover

Expose coverage, backlog, leases, health, and resource bounds. Route historical
queries to the primary archive. Document slow/fallback transaction-hash lookup
until a derived index exists. Ensure current-state APIs do not claim immutable
history coverage.

Exit: fresh mainnet/preprod rehearsal meets throughput, recovery, disk, and API
coverage gates with retained logs and exact artifact identity.

### Phase 7 — remove superseded history pipelines

After parity evidence is accepted:

- remove independent block-replay archive workers and sources;
- remove catch-up/live promotion and obsolete hot-history machinery;
- remove prefetch used only by those workers;
- remove unused legacy synchronous history CFs/services/configuration;
- remove duplicate epoch export paths superseded by artifact projection;
- retain only APIs/storage/query code required by ADR-039.

This phase cannot start after parity over only the six transaction/UTXO tables.
Every currently shipped block dataset — `TRANSACTION`, `UTXO_HISTORY`,
`ACCOUNT_EVENT`, and `ADDRESS_TRANSACTION` — must have accepted equivalent
section coverage, including Byron semantics, or a separately accepted product
decision must explicitly retire the missing dataset and affected public APIs.

Deletion occurs in a separate commit after the old path has served as the
differential oracle. Do not keep both architectures active indefinitely.

### Phase 8 — standalone SQLite primary sink

Adapt the retained standalone SQLite backend to the same projection sink
contract. It is an alternative primary sink, not a second simultaneous consumer.
Run the same contract/crash/coverage suite and backend-specific performance
gates.

## Test strategy

The test hierarchy is:

1. serialization golden tests for every envelope/section/artifact version;
2. outbox store conformance tests across crash and rollback boundaries;
3. projection collector tests using genuine decoded blocks and live UTXO state;
4. same-block and cross-block dependency fixtures;
5. primary sink conformance tests shared by DuckLake and SQLite;
6. differential archive tests against the current implementation;
7. pruning-lease race tests;
8. restart and forced-crash integration tests;
9. fresh-sync devnet/preprod tests around epoch transitions;
10. bounded mainnet validation only after all local/preprod gates pass.

Fixtures must include:

- empty and dense blocks;
- valid and failed script transactions;
- collateral and collateral return;
- reference inputs;
- same-block parent/child transactions;
- multi-asset values;
- datum hash, inline datum, reference scripts, and redeemers;
- Byron main blocks, empty EBB envelopes, Byron/genesis resolution, and era
  transitions;
- pre-Conway pointer addresses and Conway pointer behavior;
- rollback before and across an epoch boundary;
- an epoch artifact larger than an inline-envelope bound;
- mixed-source DRep distribution rows whose persisted amount and boundary-time
  state columns have different reconstruction classes;
- reward replay with the complete retained input/version closure, plus rejection
  when any required input or calculation version is absent;
- staged-file versus immutable-generation epoch-boundary timing and bytes;
- power loss before deferred force/digest/manifest publication for each
  recovery class;
- live-reader lease renewal and fenced stale-owner cleanup races;
- a sink lag longer than `snapshotRetentionEpochs`, proving the referenced epoch
  snapshot survives while unrelated unleased generations prune normally;
- common rollback-floor advancement while an artifact lease is acquired,
  renewed, acknowledged, and released, asserting it never exceeds the oldest
  active required slot and that crossing it fails closed without changing
  archive eligibility;
- sink outage long enough to cross soft backlog limits;
- restart after sink receipt but before outbox cleanup.

## Consequences

### Positive

- New history no longer rereads and decodes the chain once per dataset.
- UTXO facts reuse the authoritative resolution that already occurred.
- One projection backlog replaces separate dataset catch-up/live lifecycles.
- DuckLake/SQLite latency and optional locator latency are outside core apply.
- Epoch-scale facts are streamed from immutable retained generations without
  giant envelope copies.
- Qualifying persisted generations such as epoch stake avoid synchronously
  duplicating roughly 1.3 million staged rows at each mainnet epoch boundary;
  Phase 0 must confirm the measured saving and replay equivalence.
- The primary archive becomes a stable source for later derived stores.
- Schema growth is modular through versioned sections and artifacts.
- Crash/pruning ownership is expressed through receipts and leases rather than
  timing assumptions.

### Negative

- Canonical apply performs additional deterministic encoding and RocksDB writes.
- Outbox CFs share the hot chainstate RocksDB. A sustained sink deficit or outage
  therefore increases not only disk usage but hot-DB compaction, cache pressure,
  and write amplification until cleanup catches up or the hard limit throttles
  core sync.
- Artifact retention can consume significant additional disk during a sink
  outage. Non-reconstructible staged artifacts may add synchronous
  encoding/durability I/O at an epoch boundary unless their minimal source
  evidence is persisted atomically; this must pass the performance gate.
- A class-one artifact lease can retain delegation-snapshot keys in the hot
  chainstate beyond `snapshotRetentionEpochs`, increasing SST/compaction and
  disk pressure even when the projection outbox itself is small.
- Fresh-sync-only adoption requires an existing operator to resync or remain on
  an older architecture until a migration is designed.
- Existing block bodies and partial archive coverage cannot be adopted as a
  shortcut. An operator accepting this architecture discards the time and I/O
  already spent on the old backfill unless a separate offline migration is
  designed.
- A removed outbox cannot bootstrap an arbitrary future sink by itself.
- Source CF codecs become compatibility contracts while live-generation
  references remain pending; staged files avoid that source-codec coupling at
  the cost described above.
- Per-contributor section/cursor writes and completion coordination require
  changes to current subsystem integration.
- Preserving `ADDRESS_TRANSACTION` can add high-cardinality row construction and
  outbox volume to canonical processing; it must pass the expanded performance
  gate or be explicitly retired by the credential-filter/API decision.
- DuckLake transaction-hash point lookup remains slow without a derived index.

### Neutral/trade-offs

- Complexity is concentrated rather than eliminated: outbox, receipts,
  finality, versioning, leases, and backpressure replace multiple worker state
  machines.
- The primary archive remains derived data; canonical ledger state remains the
  authority.
- Ordinary archive coverage intentionally trails canonical tip by the supported
  rollback boundary, and epoch artifacts normally trail by one safe boundary.

## Rejected alternatives

### Continue optimizing independent archive workers

ADR-038 proved meaningful speedups, but each new dataset still requires a
reader/decoder/cursor/lifecycle and repeats canonical work. This remains useful
for existing-history migration, not the target fresh-sync design.

### Write DuckLake or SQLite synchronously during block apply

Rejected because archive storage, compaction, locking, or availability would
become part of consensus-critical synchronization latency and failure handling.

### Archive a UTXO only when it is spent

Rejected as the only representation. Never-spent outputs would remain absent
from immutable historical coverage and every historical query would need to
merge live and archive storage. Creation is archived after finality; spending is
a later event.

### Put complete epoch snapshots inside the first block envelope

Rejected because reward/stake/governance distributions can be very large and
would make block-apply latency and outbox values unbounded. A staged immutable
file or protected immutable-generation reference lives outside the block
envelope and is streamed in bounded pages.

### Require live-CF references for every epoch artifact

Rejected because some facts exist only while the transition is evaluated, some
CFs are not immutable/versioned in the required form, and pending source codecs
would become long-lived compatibility contracts. Live-generation references are
an allowed per-artifact optimization after evidence, not the universal format.

### Require staged files for every epoch artifact without measurement

Rejected because the existing staging writer performs synchronous encoding and
file I/O during epoch processing. For an already immutable retained generation,
duplicating every row may worsen the epoch-boundary hot path. Staged-file output
is the logical-equivalence baseline and remains the default candidate for
ephemeral facts, but the current flush-only implementation is not called
power-loss durable. Phase 0 selects and hardens the representation per field
group using correctness and performance evidence.

### Reference mutable live CFs

Rejected because a later in-place update would change the meaning of an already
persisted reference. Only immutable generation-addressed artifacts qualify.

### Assume normal retention is long enough

Rejected because scheduler timing or future configuration changes can prune an
artifact before a slow sink reads it. Durable leases are required.

### Feed multiple sinks directly in version one

Rejected because cleanup then depends on per-consumer offsets and the slowest
consumer. One primary sink is sufficient; later stores derive from it.

### Make the locator part of the primary commit

Rejected because it is a rebuildable query accelerator and has already
demonstrated that it can dominate writer latency. It must not gate authoritative
archive progress.

### Start from raw `main` and rewrite storage backends

Rejected because the existing archive branch contains tested schemas, storage
sessions, receipts, and backend conformance work that remains valid. Extract a
clean foundation instead of discarding it.

### Implement the replacement directly on ADR-038

Rejected because ADR-038 is a closed, evidence-backed throughput campaign for
the current worker model. Mixing replacement work into it obscures both the
decision history and code review.

## Decision — Parquet file granularity, commit batching and compaction

**Raised 2026-08-20 from the first full preprod sync with the real DuckLake sink. Decided and
implemented the same day; the analysis that led there is retained below because two of the
obvious diagnoses were wrong and the record is worth keeping.**

### The observation

A complete preprod archive (5,076,688 blocks, 3.7 GB) is spread across **67,603 Parquet
files** averaging **56 KB**, with a **95 MB** SQLite catalog.

### Two wrong diagnoses, corrected

**Wrong diagnosis 1: `target_file_size` is mis-tuned.** It is not the lever. File count
tracks *commits x tables*:

```
receipts x tables = 10,233 x 6 = 61,398      actual files = 67,603
configured target_file_size = 4 MiB           observed average = 56 KB
```

Each commit flushes its own file per touched table regardless of how far below target it
lands, so 4 MiB was never approached. Raising it — to 8 GB or anything else — changes almost
nothing on its own, because it caps compaction *output*, it does not merge per-commit files.

**Wrong diagnosis 2: the batch cap is too small.** Also not the lever. Measured commit sizes:

```
avg blocks/commit       495.7
max                     2,000   (the configured cap)
commits at the cap         81   of 10,233  = 0.8%
commits under 100 blocks  2,153  = 21%
single-block commits       171
```

The 2,000-block cap binds in under 1% of commits.

### The actual cause

**The drain loop is too eager.** It commits whatever is eligible the moment it becomes
eligible. Because the sink keeps pace with the producer (measured 1,292 vs 1,193 blocks/s),
a large backlog never accumulates, so each pass scoops up only the few hundred blocks that
just crossed the rollback-safety window. The loop's own speed fragments the output.

### The lever that actually works

A **minimum batch size or linger interval**, applied during bootstrap:

- do not commit unless N blocks are available or T seconds have elapsed;
- costs nothing in correctness — the outbox is durable, so waiting is free;
- strictly better than compacting afterwards, because small files are never written;
- archive lag during bootstrap is irrelevant: historical APIs report `catching_up` and
  nothing is querying.

At a 2,000-block floor: ~2,540 commits, roughly **4x fewer files**. At 10,000: ~500 commits,
**20x fewer**. Near tip the floor must drop again so the archive continues to track the
finality window closely, which the measured run does at 4,320 blocks.

### Compaction — secondary, and after tip

`ArchiveBackend.maintain(budget)` implements budgeted `merge_adjacent_files` with deferral on
active reader snapshots and capacity pressure. Before this decision it had exactly one caller,
`HistoryArchiveService` (the replay-worker path), and **`ProjectionSink` had no maintenance
operation at all**, so the projection path could not invoke it even in principle. Adding it was
a contract change, not a scheduler change; `ProjectionSink.maintain(ProjectionMaintenance.Budget)`
now exists and the drain coordinator is its only caller.

Compaction should not run during bootstrap:

- it contends for the same bulk pool as the sink (`maxConcurrentBulkJobs` is constrained
  below `maxConcurrentQueries`), so it would either starve or steal measured throughput;
- it would re-compact the same neighbourhood repeatedly as the sink keeps appending;
- nothing is querying yet, so the small-file penalty costs nothing.

Trigger it instead on the bootstrap-to-tip transition — backlog settled at the finality
window and header gap closed — which is exactly when the bulk pool goes idle.

### On a large target such as 8 GB

Pros: less metadata, faster planning, better compression across large row groups, fewer file
handles.

Cons: **coarse pruning** — DuckLake prunes at file granularity before row groups, so a
selective `tx_hash` lookup pulls a far larger candidate set, landing hardest on the
point-lookup queries §15 already identifies as weak without a locator; **rewrite
amplification**; compaction memory; awkward backup and incremental sync; coarse snapshot
cleanup. Common Parquet practice is 128 MB - 1 GB.

### On "one file per day" for mainnet

Sound as a **compaction/target output** goal: mainnet produces ~4,320 blocks/day, landing
daily files in the hundreds of MB.

Unsound as a **commit interval**: history would be up to 24 hours stale near tip. Commit
frequency and file granularity must stay decoupled — batch floor high during bootstrap, low
at tip, compaction to daily granularity afterwards.

### Catalog scaling

Measured 95 MB for 67,603 files. The bulk is `ducklake_file_column_stats`, which holds
per-column min/max/null statistics for every file, so catalog size scales with
*columns x files* rather than with data volume. Fewer files shrinks it disproportionately.
Extrapolated mainnet: ~380 MB at 3x files, ~950 MB at 10x — workable, so this does not by
itself force mid-sync compaction.

### Decision

1. **A batch policy governs the drain loop**, expressed as *N blocks OR T elapsed, whichever
   comes first*, with two regimes:

   | | preferred block target | maximum linger |
   |---|---|---|
   | bootstrap | 10,000 | 30 s |
   | near tip | 50 | 15 min |

   Near tip the block count is a *preferred target*, not a freshness requirement, and the
   15-minute deadline is the hard bound. At normal Cardano production (20 s slots) the
   deadline fires first, around 40-50 blocks; that is the expected steady state, not a missed
   target.

2. **The regime is derived from the drain backlog, not from producer-at-tip.** A sink that
   falls a long way behind while the node sits at tip gets the bootstrap regime and returns to
   the near-tip regime by itself once it catches up. No separate sync signal is needed and the
   selection cannot disagree with reality.

3. **Hard ceilings override both targets** and exist for memory, not for file size: 256 MiB
   encoded, 2,000,000 rows, 512 MiB estimated heap, 20,000 blocks. A refused offer — an
   envelope that no longer fits — also forces the commit, so the coordinator can never spin
   re-offering an envelope that will never fit.

4. **`ProjectionSink.maintain(budget)` exists and the drain coordinator is its only caller.**
   Housekeeping and compaction are scheduled on *different* conditions, not merely budgeted
   separately: housekeeping becomes due on a wall-clock interval and runs during bootstrap
   (a mainnet bootstrap easily outlasts the snapshot retention window); compaction requires
   the sink to be caught up and no eligible backlog to remain. Maintenance runs on the drain
   thread, never on a worker of its own, and only on a pass that had nothing to commit.

5. `target_file_size` is a compaction *output* target in the 256 MB - 1 GB range. It is not
   the lever for per-commit file count and never was.

6. Measure file count, catalog size, point- and range-query latency, and compaction I/O
   before mainnet.

Not a correctness issue: coverage is contiguous with zero gaps and zero drain failures.

### What wiring the policy actually exposed

The accumulator existed as a tested component before this decision but `drainOnce()` never
consulted it, so batching was inert. Connecting it surfaced four defects that the component
tests could not have caught, because each lives in the interaction rather than the part:

- **The freshness deadline was unreachable.** The drain pass returned early when no new
  envelopes were eligible — which is exactly the situation the deadline exists for. A batch
  could linger indefinitely once arrivals paused. Any pass with a non-empty batch now
  evaluates the decision, including a pass that reads nothing.
- **A rollback could have produced a silent coverage gap.** With blocks 0..19 buffered and
  16..19 rolled back, the next read would have resumed at 20 and the replacement blocks would
  never have been projected. A duplicate would have been caught by the receipt check; a gap
  would not. Rollback now sets a discard flag — checked at the top of each pass and again
  immediately before commit — and a contiguity assertion refuses to commit a batch that does
  not start exactly where the acknowledged range ends.
- **A saturated batch could spin.** An envelope refused for space when no ceiling had yet been
  *reached* would be re-offered every pass forever. A refusal now forces the commit.
- **An accumulating pass must not back off.** Treating "nothing committed" as "nothing to do"
  would have idled the drain loop through an entire bootstrap backlog.

### Measured evidence

**Memory bound (offline, `ProjectionPeakRetentionMeasurementTest`).** Peak retained heap during
row materialisation, measured against a fixture calibrated to the densest genuine preprod
blocks (300 outputs with inline datums, multi-asset bundles and redeemers per envelope):

```
  5 envelopes ->   6,100 rows, peak 1,066 KiB, 4,488 B/row
100 envelopes -> 122,000 rows, peak 1,111 KiB, 4,430 B/row
```

Twenty times the batch costs **4% more retained heap**. A materialise-the-whole-batch
implementation would show roughly 20x. The densest single envelope measures 1,220 rows and
1,125 KiB, which is the figure the singleton safety guard is sized against.

**Before, at tip (old jar, batching inert).** 217 batches for 217 blocks — one commit per
block, each writing one file per touched table. Baseline archive: 69,375 Parquet files,
10,514 commits.

**After, at tip (rebuilt jar, preprod, 2026-08-20).** First live batched commit:

```
reason  = LINGER_EXPIRED          blocks = 37
rows    = 1,788                   encoded = 318,543 B
largest envelope = 33,693 B / 175 rows
acknowledged 5,072,721 -> 5,072,758      drain failures 0
```

Thirty-seven blocks rather than the 40-50 the 20 s slot average predicts, because preprod
production was slower than nominal in that window. Thirty-seven blocks in **one** commit
against thirty-seven separate commits before. The contiguity assertion did not fire, and the
restart resumed at exactly the pre-restart acknowledged block.

The second live commit took the **other** path, which matters because it shows the policy is
genuinely `N blocks OR T elapsed` rather than a deadline with a decorative target:

```
reason MIN_BLOCKS   blocks 50   acknowledged 5,072,758 -> 5,072,808
```

Exactly fifty — the preferred target was reached before the deadline, because preprod produced
faster in that window. Both flush reasons are therefore exercised in production, and the
`flushReasons` distribution distinguishes them without inference.

**Compaction is highly effective, and reclamation is not immediate.** One compaction pass on
the 5.07M-block preprod archive:

| | before | after |
|---|---|---|
| active data files (catalog) | 69,375 | **12,458** |
| files on disk | 69,375 | 71,258 |
| files scheduled for deletion | 0 | 58,800 |
| logical size | 3.7 GB | 3.20 GB |
| size on disk | 3.7 GB | 6.80 GB |

Active file count fell **5.6x** and per-table medians moved from tens of KiB to low MiB:

```
transaction_outputs        454 files   p50 2.7M   p95 4.3M   max 10.5M
transaction_inputs         319 files   p50 2.1M   p95 3.8M
transaction_redeemers      305 files   p50 1.1M   p95 3.8M
chain_transaction          291 files   p50 1.1M   p95 2.5M
transaction_output_assets  286 files   p50 1014K  p95 2.5M
transaction_datums         283 files   p50 100K   p95 1.1M
projection_receipts     10,515 files   p50 3K              <- one row per commit, never merged
```

Two consequences that must be planned for rather than discovered:

1. **Disk roughly doubles for the duration of the cleanup grace.** Compaction's rewritten
   inputs stay on disk until `ducklake_cleanup_old_files(older_than => now() - grace)` may
   remove them, and the inherited default grace is **24 hours**. Peak sink footprint is
   therefore ~2x the logical archive for that window. Sizing a mainnet volume for the logical
   size alone would be wrong.
2. **The catalog does not shrink until snapshots expire.** 10,563 snapshots and a 97.6 MB
   catalog survive compaction, because the inherited snapshot retention is **168 hours** and
   nothing is old enough to expire. Catalog size tracks *columns x files* and will fall
   sharply once expiry becomes eligible, but not before.

Both windows are inherited defaults from the replay-worker backend, which has different
reader assumptions. The projection path has one writer and short-lived readers, so they are
now independently configurable — `yano.history.projection.sink.snapshot-retention-hours`,
`.cleanup-grace-hours`, `.target-file-size-bytes`, `.row-group-size` — with the inherited
values as defaults, so nothing changes until someone chooses. **Choosing them is a product
decision and is not made here.**

`projection_receipts` was the one table compaction left alone, at 10,515 three-KiB files —
small in bytes (0.04 GB) but 84% of the active file count, so it dominated catalog statistics.
The cause was that the compaction loop iterated `DuckLakeSql.tables()`, which enumerates
dataset tables only, so the sink excluded its own bookkeeping table from its own maintenance.
Fixed, and the fix is measured rather than assumed: a 12-commit fixture compacts **12 receipt
files to 1**.

Two further properties were verified rather than argued, because together they decide whether
repeated maintenance is safe:

- **`merge_adjacent_files` is idempotent.** A second compaction pass over an already-compacted
  lake rewrites nothing — receipt and dataset file counts are identical afterwards. This
  matters because every rewrite orphans its inputs and orphans outlive the cleanup grace, so a
  compaction that re-merged the same neighbourhood every interval would grow disk without
  bound. It does not.
- **The compaction gate now counts small files, not all files.** It previously compared the
  *total* active file count against `minFilesToCompact`, which is true forever on any large
  archive, so a pass was attempted every interval — acquiring a bulk lease and scanning for
  nothing. It now reads the size distribution from DuckLake's metadata schema and counts only
  files below the smallness ceiling. A test pins this by setting the ceiling below every file
  and asserting the pass declines; the old gate fails that test, so it discriminates a real
  distribution read from a silent fallback.

### Compaction under the new drain loop, measured (2026-08-20)

The figures above came from a pass on the **old** jar. Verified again under the rewritten
drain loop, with the compaction interval shortened to 5 minutes so a pass could be observed
inside a validation session:

| | before | after |
|---|---|---|
| active data files (catalog) | 12,470 | **1,799** |
| `projection_receipts` active files | 10,516 | **9** |
| logical size | 3.20 GB | 3.16 GB |
| files awaiting the cleanup grace | 58,800 | 69,645 |
| size on disk | 7.8 GB | 8.5 GB |

**1,799 active files for a complete 5.07M-block preprod archive**, against 69,375 before any
compaction — a **38x reduction**. The receipts table, which the sink had been excluding from
its own maintenance, went from 10,516 files to 9.

Post-compaction distribution:

```
transaction_outputs        340   p50 3.0M   p95 6.6M   max 10.5M
transaction_inputs         296   p50 2.1M   p95 4.8M
transaction_redeemers      292   p50 1.1M   p95 4.1M
chain_transaction          288   p50 1.1M   p95 2.5M
transaction_output_assets  282   p50 1013K  p95 2.5M
transaction_datums         281   p50   99K  p95 1.1M
projection_receipts          9   p50  166K
```

Three operational facts this settles:

1. **The post-flush idle window is wide enough in practice.** Compaction fires on the drain
   pass immediately after a commit, when the buffer is empty and nothing is eligible yet. It
   is not merely reachable in principle: `lastMaintenance` went from
   `COMPLETED (housekeeping only) [compaction withheld: sink busy]` to `COMPLETED` on exactly
   that pass.
2. **An incremental pass is short.** The next batch opened **6 seconds** after the compaction
   pass, nowhere near the 5-minute budget. The worst-case staleness bound (linger plus one
   compaction budget) remains the bound, but the typical cost is seconds.
3. **Each legitimate compaction generation adds its inputs to the orphan pool for the whole
   cleanup grace.** Two generations now coexist: 69,645 files awaiting deletion, 8.5 GB on
   disk against 3.16 GB logical — **2.7x**, not the 2x a single generation costs. This is not
   the runaway case (a second pass over an already-compacted lake is a verified no-op); it is
   the cost of two passes that each had real work. Mainnet volume sizing must budget for it.

### Retention decision, and the gates before it may change (2026-08-20)

**Decided: keep the inherited 168 h snapshot retention and 24 h cleanup grace for now.**
Seven-day user-facing time travel is not a requirement, but retention is not shortened on that
basis alone — it is shortened only after Phase 6 demonstrates active-reader fencing, maximum
query duration, restart safety and cleanup behaviour. The target after those gates is **24 h
snapshot / 6 h cleanup**; 2 h cleanup only with evidence.

The knobs exist (`yano.history.projection.sink.snapshot-retention-hours`,
`.cleanup-grace-hours`) with the inherited values as defaults, so nothing changes until someone
decides it should.

### Two maintenance contract gaps, closed

**1. Housekeeping swallowed its failures.** `runHousekeeping` caught every `SQLException`,
returned zero, and let the pass report `COMPLETED`. An archive that had silently stopped
reclaiming space looked healthy. Each of the three steps — snapshot expiration, file cleanup,
orphan deletion — is now mandatory and reports its own outcome; the pass still attempts the
remaining steps after one fails, but returns `PARTIAL` or `FAILED` carrying per-step
diagnostics. A step skipped because the housekeeping budget ran out is reported too, rather
than being indistinguishable from a step that ran and found nothing. A failed `CALL` also
rolls back its aborted transaction, or every later query on that connection — including the
file counts the pass reports — fails as well and the diagnostics become useless.

**2. `maxBytesToRewrite` was an enable flag, not a bound.** Compaction now computes
`max_compacted_files` from the byte budget, divided by the table count because DuckLake applies
that limit *per table* — passing the aggregate through would let a ten-table catalog rewrite
ten times the advertised budget. Tables are visited round-robin so one table that repeatedly
exhausts the reserved memory cannot starve everything behind it.

**Per-call upper bound, documented because it cannot be removed.** DuckLake cannot interrupt a
`merge_adjacent_files` call safely — it would roll the whole call back and lose the work — so
the time deadline is checked only *between* calls. The budget therefore holds as: at most one
in-flight call may run past the deadline, and that call is itself bounded by
`max_compacted_files x target_file_size`. Size the budget so one table's bounded call is an
acceptable overshoot.

Rewritten bytes are now reported from the catalog rather than left at zero. Output bytes are
the right pairing with the budget, since `max_compacted_files` bounds what a call may
*produce*. Two dead ends worth recording: a compacted input is unmeasurable after the fact —
DuckLake removes it from `ducklake_data_file` and records only its path, with no size, in the
deletion queue — and `begin_snapshot` cannot identify this pass's output either, because a
merged file keeps the earliest snapshot its rows came from and so looks older than the pass
that wrote it. Monotonic `data_file_id` is what works.

### The pinned-reader gate, and what it found

A test now spans an open read transaction across snapshot expiration and file cleanup, which is
the gate that must pass before retention is shortened. It found a real constraint:

```
pinned reader open   -> maintenance FAILED, 0 snapshots expired, 0 orphans deleted
                        expire_snapshots: Failed to commit: database is locked
reader released      -> maintenance COMPLETED, 50 snapshots expired
```

**A long-running reader blocks maintenance entirely**, because the DuckLake catalog is SQLite
and the reader's open transaction holds its lock. The reader is never harmed and no file is
deleted underneath it, and the block is transient — the next pass after the reader finishes
succeeds. But cleanup does not merely defer a step, it fails the whole pass.

This is directly relevant to the retention decision: **shortening the windows does not help if
a long query can stall reclamation indefinitely.** It also vindicates the first fix — before
per-step diagnostics existed, this exact run reported `COMPLETED` having reclaimed nothing.
Maximum query duration is therefore not just a Phase 6 nicety; it bounds how far behind
reclamation can fall.

### Two maintenance defects the metrics exposed

Adding `maintenancePasses` / `compactionPasses` to status immediately paid for itself.

1. **The compaction counter was lying.** It incremented on what the *scheduler asked for*, not
   on what the executor was permitted to do. The consumer withholds compaction when the sink is
   not idle, so a run reporting `compactionPasses = 1` had in fact compacted nothing. Worse,
   `recordRun` then stamped `lastCompaction`, deferring the next real attempt by a full
   6-hour interval on the strength of a compaction that never happened. The executor now
   reports back what it was actually offered (`MaintenancePass.compactionOffered`), and only
   that is recorded and counted.

2. **A withheld compaction re-fired on every drain tick.** With `lastCompaction` never
   advancing, compaction stayed permanently due, so a maintenance pass — which acquires a bulk
   DuckDB lease — was attempted roughly every 250 ms. Measured live: **38 passes in a few
   minutes**. After the fix: **1**.

   The root cause was that the scheduler and the executor used *different* idle predicates.
   The scheduler asked "are eligible envelopes waiting?"; the executor asked "are eligible
   envelopes waiting **and** is the buffer empty?". Near tip the buffer is almost always
   non-empty, so they disagreed almost always. `hasDrainBacklog()` is now the exact negation of
   the executor's idle test, and a backstop spaces any future disagreement to one pass per
   housekeeping interval rather than one per tick.

## Open questions requiring review

**Answered since this list was written**

1. ~~Which live subsystem is the authoritative contributor for datum/redeemer and
   pointer-address projection facts?~~ **Answered.** Datum and redeemer facts come from the
   UTXO subsystem's own block batch, alongside the outputs they belong to. Pointer addresses
   are resolved **as of the block being projected**, by the account-state subsystem, through
   `PointerCredentialSource` in `core-api` — not by the sink, and not by an append-only
   approximation. The Shelley ledger's `_ptrs` map removes entries on deregistration, so an
   append-only index would diverge from cardano-ledger; the index therefore stores full
   deregistration coordinates and lookups are interval queries. Measured on a real preprod
   chainstate: **17,036 entries, 42 bytes each, 0.68 MiB — 0.002% of chainstate**, one extra
   put per ~298 blocks. Completeness is tracked (`COMPLETE` / `INCOMPLETE` / `CLEANED`) and a
   node whose index cannot be complete fails closed rather than projecting wrong credentials.

3. ~~What are the exact soft/hard aggregate retention limits...~~ **Partly answered.** The
   aggregate gate exists and is live (`ArchiveIngestGate`, `ArchiveDiskLimits`, defaults
   8 GiB soft / 32 GiB hard / 16 GiB free reserve / 1.4x amplification, all configurable), it
   is registered at startup and now reports `diskBackpressureInstalled` so registration is
   observable rather than inferred, and the operator behaviour at the hard limit is a visible
   pause with automatic resume at the low-water mark. What remains open is whether those
   *specific numbers* are right for mainnet, which needs the mainnet sync to answer.

   Note that the gate counts the **outbox and staged artifacts**, not the sink's own Parquet
   footprint; free-space reserve is what protects against the sink filling the volume. Sizing
   must also account for compaction transiently roughly doubling sink footprint for the
   duration of the cleanup grace — see the granularity decision above.

**Still open**

2. For each epoch field group, which recovery class applies, and does a hardened
   staged representation, an immutable-generation reference, or atomically
   persisted minimal boundary evidence win the correctness/performance
   comparison?
4. Is one-epoch publication delay sufficient on every supported network after
   independently proving source durability and applying the rollback-cutoff
   check?
5. Which historical APIs require a transaction point index before cutover, and
   which can accept a bounded DuckLake fallback scan?
6. Should standalone SQLite ship in the first release or follow DuckLake after
   the architecture is proven?
7. Which archive fields must be preserved to make the primary archive a
   sufficient source for the credential-filter and `/scan` proposal?
8. What coordinated snapshot format is required to avoid treating all snapshot
   restores as unsupported mid-chain activation?
9. At what accepted parity point can the old replay workers and legacy history
   be deleted rather than maintained behind flags?

## Review decision requested

Reviewers are asked to decide, in order:

1. whether fresh-sync-only and one-primary-sink are acceptable product
   constraints;
2. whether the canonical projection outbox is the target replacement for the
   current replay-worker architecture;
3. whether epoch fields use the per-column recovery classification, with the
   current staged output as an equivalence baseline and each selected
   staged/generation/evidence representation required to prove durability and
   performance;
4. whether transaction locator/index design is explicitly downstream and
   deferred;
5. whether the proposed foundation/outbox branch split is accepted;
6. whether Phase 0 per-contributor durability/replay and sink/core/backlog
   performance proofs are hard implementation gates.

~~No implementation beyond storage-foundation extraction should begin until
those six decisions are recorded.~~ **Superseded.** The six were treated as recorded by the
instruction to implement this ADR end to end, and the review closed with no open editorial
items. Phases 0-4 are built and validated to preprod tip; see the status header.
