# ADR-042: Route Byron Main-Block Projection Through the UTXO Apply Batch

## Status

Accepted / Implemented (2026-08-27)

## Date

2026-08-26

## Related decisions

- [ADR-039](in-progress/039-canonical-projection-outbox.md) — canonical
  projection outbox; §"Byron and epoch-boundary blocks" defines the current
  archive-owned Byron carrier and outpoint resolver
- [ADR-041](041-byron-main-block-utxo-application.md) — Byron main blocks are
  applied to the live UTXO store; §8 explicitly defers routing Byron through the
  Shelley+ projection hook to a follow-up. This is that follow-up.
- [ADR-NET-005](network/005-yaci-yano-data-flow-and-ordered-ledger-apply.md) —
  ordered apply and durable derived-state cursors

## Related tracking

- [PR #86](https://github.com/bloxbean/yano/pull/86) — added the archive-owned
  `ByronOutputAddressIndex` resolver and the per-block Byron carrier commit
- [Issue #88](https://github.com/bloxbean/yano/issues/88) — Byron projection
  capped at ~121 blocks/s by one durable write per Byron block
- [PR #90](https://github.com/bloxbean/yano/pull/90) — ADR-041 implementation;
  this decision is implemented on a branch stacked on PR #90

## Context

ADR-039 stages Shelley+ projection sections into the UTXO store's `WriteBatch`
(`DefaultUtxoStore.applyBlock` → `CanonicalProjectionContributor.contributeBlock`).
Sections, UTXO delta, UTXO cursor and the outbox contributor cursor therefore
commit atomically, with default (non-sync) write options.

Byron was different because, before ADR-041, no Byron transaction was ever
applied to the UTXO store, so there was no batch for Byron sections to join.
PR #86 made Byron projectable on mainnet with two archive-owned mechanisms:

1. `ProjectionHistoryService.subscribeByronCarrier` commits Byron sections in
   their own `ProjectionOutboxStore.commit(...)`, which uses
   `WriteOptions.setSync(true)`. That is one `F_FULLFSYNC` per Byron block —
   4.49M on mainnet. Issue #88 measured 8.01 ms per sync on macOS/APFS, a hard
   ceiling of ~125 blocks/s and ~10 hours for the Byron era, versus ~16 minutes
   without the archive (measured 2026-08-26 on the ADR-041 build).
2. `ByronOutputAddressIndex` (`proj_byron_utxo` column family): an append-only
   outpoint → address copy of every Byron output, seeded from Byron genesis on
   the first main block and consulted in *every* era as a fallback for consumed
   addresses. It existed only because `cfUnspent` held no Byron outputs.

After ADR-041 (PR #90) the live UTXO store applies every Byron main block. In
an unfiltered full-state store it holds every Byron-created output, resolves
each when spent — in Byron and in Shelley — and can capture the address of a
consumed output at the moment it deletes it, exactly as the Shelley+ path
already does. A fresh mainnet run of the PR #90 build on 2026-08-26 observed
zero unresolved Byron or Shelley inputs through the Allegra boundary, a captured
Shelley-start UTXO total of 31,111,977,147.073356 ADA (45,000,000,000 minus the
network's known Shelley-start reserves of 13,888,022,852.926644 ADA) and an
Allegra AVVM sweep of 318,200,635 ADA. Those observations and the subsequent
ADR-042 mainnet replay are recorded in
`adr/reports/adr-042-implementation-report-2026-08-27.md`; they satisfy the
numeric part of the ADR-041 completion gate. The UTXO outpoint-set comparison
at the boundary remains open, so the completeness claim here rests on the
recorded totals, the zero unresolved-input counters, and the outpoint
spot-checks in that report.

Consequently the archive now carries:

- a per-block durable commit that the rest of the chain does not pay;
- a second copy of every Byron output on disk, plus genesis seeding logic;
- a second atomicity model (Byron sections commit on their own cursor, Shelley
  sections commit with UTXO state), i.e. two crash-recovery stories.

### Constraints

- Projection identity and section semantics defined by ADR-039 must not change:
  the same datasets, the same rows, the same block coordinates.
- Byron EBB coordinate semantics are those PR #86 established: an EBB reports
  its predecessor's difficulty, so most EBBs share a coordinate with an existing
  main block and are validated no-ops for the projection; only an EBB whose
  coordinate is unclaimed (the genesis EBB, block 0) writes an empty envelope
  so the coordinate stays contiguous. `main N → EBB N → main N+1` must leave
  `main N` intact and the sequence contiguous.
- Byron EBBs have no UTXO transition (ADR-041 §1/§5) and must not enter the UTXO
  delta or cursor path.
- The UTXO store remains the sole commit owner of its batch (ADR-041 §3); the
  contributor stages only.
- A node with history disabled must pay nothing beyond the existing
  `projectionContributor.enabled()` check.
- Outbox cursors (`identity`, per-section contributor cursors) never move
  backward except through the exact-point rollback path.
- Existing archives created by PR #86 must still open.

## Decision

### 1. Byron main blocks take the Shelley path

`DefaultUtxoStore.applyByronBlock` invokes the projection contributor with the
batch still open, after `stageDeltaAndCursor` and before `db.write`, using the
same staging writer as the Shelley+ path:

```java
public synchronized void applyByronBlock(ByronMainBlockAppliedEvent event) {
    ...
    var result = byronUtxoApplier.stageBlock(event, batch, capture);
    stageDeltaAndCursor(batch, ...);
    if (projectionContributor.enabled()) {
        projectionContributor.contributeByronMainBlock(
                event, result.consumedAddresses(),
                (cf, key, value) -> batch.put(rocksContext.handle(cf), key, value));
    }
    db.write(options, batch);
}
```

Sections, Byron UTXO delta, UTXO cursor and outbox contributor cursor commit in
one non-sync RocksDB write. This is the ADR-039 atomicity argument applied to
Byron: a section cannot become durable without its state, or the reverse.

### 2. Consumed-output addresses: the Shelley+ mechanism, shared

The Shelley+ path already does exactly what Byron needs: when
`projectionContributor.needsConsumedOutputAddresses()` is true,
`DefaultUtxoStore.applyBlock` keeps a per-block `consumedAddresses` map,
filled on spend from the stored record it deletes and on creation for outputs
consumed inside the same block, and hands it to the contributor as a
`ConsumedOutputAddresses` lambda keyed by `consumedKey(txHash, index)`.

That mechanism is **extracted into one shared, package-private capture helper**
(map, key function, lambda adapter, and the "only allocate when needed" rule)
and used unchanged by both `applyBlock` and `ByronUtxoApplier`. Byron adds no
second implementation and no Byron-specific data structure: the applier calls
the same `recordSpent(outpoint, storedRecord)` / `recordCreated(outpoint,
address)` entry points at the same two moments the Shelley loop does, and
returns the helper's view in `ApplyResult`. When the contributor does not need
addresses the helper is `null` for both eras and no record is decoded for it.

### 3. Contributor API: one Byron main-block method

Add to `CanonicalProjectionContributor`:

```java
default void contributeByronMainBlock(ByronMainBlockAppliedEvent event,
                                      ConsumedOutputAddresses consumed,
                                      ProjectionStagingWriter writer) { }
```

`CanonicalProjectionCollector` implements it with today's
`ByronBlockNormalizer.normalizeMain` and `contribute(..., BYRON_MAIN, writer)`.
The normalizer, the section builders and the block-identity header are
unchanged; only the staging writer and the address source differ.

### 4. EBBs keep the dedicated carrier, and only EBBs

`BodyFetchManager.applyByronEbBlock` continues to publish
`ByronBlockProjectionEvent.epochBoundary(...)`, and `subscribeByronCarrier`
continues to commit through `outbox.commit(...)`. `contributeByronBlock`
handles EBBs only and keeps PR #86's coordinate rule: if
`hasBlockIdentity(blockNumber)` the EBB is a validated no-op; otherwise it
writes the empty envelope and advances the contributor cursors. Mainnet has
~200 EBBs, so their per-event durable commit costs ~2 s in total and is not
worth a second mechanism.

`BodyFetchManager.applyByronBlock` stops publishing the event, and the
main-block half of `ByronBlockProjectionEvent` is **removed immediately, with
no deprecation cycle**: `main(...)`, `mainBlock()`, `isEpochBoundary()` and the
`ByronMainBlock` field go; `epochBoundary(...)` and `epochBoundaryBlock()`
remain; the constructor takes a non-null `ByronEbBlock`. The type keeps its
name and can only represent an EBB, so an invalid main-block event is
unrepresentable — the "carrier rejects a main-block event" runtime branch and
its test disappear, replaced by a compile-time guarantee. Main blocks already
have `ByronMainBlockAppliedEvent`, so no functionality is lost. The class
Javadoc, and the Javadoc of
`CanonicalProjectionContributor.contributeByronBlock(...)`, describe an
EBB-only carrier and no longer mention a main-block variant. Tests that build
`ByronBlockProjectionEvent.main(...)` are migrated to
`contributeByronMainBlock(...)`, not deleted.

This is a public `core-api` source-breaking change made without deprecation.
It is acceptable because **no released compatibility contract includes this
API**: Yano is in preview, and external source use, while technically
possible, is unsupported during preview. The same policy governs the removal
of `ProjectionCfNames.PROJ_BYRON_UTXO` in §5, and the release notes list both
removals together.

Ordering invariant: the EBB carrier commits synchronously on the publishing
thread while, in asynchronous UTXO mode, a Byron main apply is queued on the
UTXO worker. An EBB at coordinate `N` could therefore observe `hasBlockIdentity(N)`
false before the queued `main N` contribution commits and write an empty
envelope that `main N` then overwrites. Client sync forces asynchronous UTXO
apply off (ADR-041 §2, `UtxoSubsystem`), which is the only mode in which
historical Byron blocks are applied. This decision adds no runtime ordering
mechanism; it relies on that resolution and pins it: the invariant is
documented at the point where `yano.utxo.applyAsync` is resolved, and a test
asserts that a client configuration with projection history enabled resolves
asynchronous apply to `false`. If asynchronous historical apply is ever
enabled, the EBB carrier must first be ordered through the same worker, and
that change must revisit this section.

### 5. Filters stay uniform across eras; address history requires an unfiltered store

UTXO storage filters keep the semantics PR #90 gave them: **one rule for every
era**. A configured filter (the built-in `AddressUtxoFilter` or a plugin
`StorageFilter`) selects which outputs are stored in Byron exactly as in
Shelley+, through the existing `acceptUtxoOutput` / `acceptByronUtxoOutput`
pair; a filtered store's unresolved inputs are counted (`…filteredStoreUnresolvedInputs`)
rather than failing closed, in both eras, as ADR-041 §3/§8 already define.
Nothing in the filter SPI, chain, facade or applier changes.

The resolver's removal is justified by the store that address history needs,
not by every store: `ProjectionAddressParticipation.resolve` already
**throws** when an input's address cannot be resolved (a silent skip would
drop a participation), so address history over a filtered store fails today —
late, at the first spend of a filtered-out output, in any era. The resolver
never held Shelley outputs and so never masked that. This decision makes the
incompatibility explicit and early:

- **`ADDRESS_TRANSACTION` requires an unfiltered UTXO store.** When the
  projection identity requires it and the effective filter chain is
  non-empty, projection history fails initialization with a diagnostic naming
  the filters (this runs before the runtime starts).
- The check runs **before projection opens the sink, mutates the outbox, starts
  the drain or installs the contributor**. A drain that starts first is not
  safe merely because sync has not started: on restart it can acknowledge and
  prune an already-pending suffix before the later filter check fails.
- The runtime therefore exposes a read-only configured-filter preflight. It
  resolves the built-in filter from runtime configuration and plugin filters
  from the contributions registered during `PluginManager.discoverAndInit`
  (which has completed before `ProjectionHistoryService.initialize`), without
  starting the runtime. `ProjectionHistoryService` calls it after constructing
  the proposed projection identity and before opening the sink.
- The preflight and `UtxoSubsystem.initializeFilterChain` use one shared
  resolver over the same inputs. Runtime startup asserts that the assembled
  chain agrees with the accepted preflight; disagreement fails closed before
  sync starts.
- The rule is era-neutral; it is not a Byron special case.
- Other datasets keep working on filtered stores as before.

With that in place, delete `ByronOutputAddressIndex`,
`CanonicalProjectionCollector.recordByronOutputs`, its genesis seeding, the
resolver fallback in `addressResolution()` (which reverts to "captured
addresses only"), and the collector's genesis-provider dependency.

#### Legacy `proj_byron_utxo` column family

Yano is in preview and PR #86 shipped in no release, so no downgrade story is
owed. The family is physically removed, but its removal must respect ownership
of the shared RocksDB instance: `DirectRocksDBChainState`, not
`ProjectionOutboxStore`, opens the database and owns every column-family
handle.

The public `ProjectionCfNames.PROJ_BYRON_UTXO` constant and its entry in
`ProjectionCfNames.ALL` are removed — without deprecation, under the same
preview policy as §4: no released compatibility contract includes it — as is
the normal chain-state descriptor.
The chain-state implementation retains only a private legacy name for the
one-time physical removal. Before `RocksDB.open`, the chain-state opener lists
the families already present. If and only if `proj_byron_utxo` exists, it adds
a legacy descriptor so the existing database can be opened — RocksDB requires
every existing family to participate in the open. Immediately after open,
while the chain-state layer still has sole ownership and before it publishes
`ChainstateRocksAccess`, it:

1. calls `dropColumnFamily` using that conditionally opened handle;
2. removes the handle from its name-to-handle map;
3. closes the handle exactly once; and
4. fails startup if any part of the drop cannot complete.

A fresh database never includes the descriptor and therefore never creates the
family. `ProjectionOutboxStore` no longer accepts, opens, resolves or closes a
`proj_byron_utxo` handle. Downgrade to a PR #86 build is unsupported: its
`createMissingColumnFamilies` path can recreate the family empty, but it cannot
reconstruct non-genesis Byron outputs and would eventually produce incomplete
address history. That is acceptable in preview and is noted in the release
notes.

### 6. Reconcile, rebuild and rollback

- **Ordinary reconcile** (`DefaultUtxoStore.reconcile` replaying the gap
  between the UTXO cursor and the chain tip) re-enters `applyByronBlock` /
  `applyBlock` and therefore re-contributes. The collector applies the
  following ordered cases for a block at height `h` with hash `H`, using
  `acknowledgedThrough()`, `identityCursor()` and the stored pending header:

  | case | condition | action |
  |---|---|---|
  | 1 | `h <= acknowledgedThrough` | skip: already committed to the sink (and possibly pruned); touch nothing |
  | 2 | pending header at `h`, same hash, all required sections present | skip; no cursor write |
  | 3 | pending header at `h`, same hash, some required section missing | repair: stage only the missing sections; cursors use max/unchanged semantics |
  | 4 | pending header at `h`, different hash | fail closed: a rollback was missed; never overwrite |
  | 5 | no header at `h` and `acknowledgedThrough < h <= identityCursor` | fail closed: outbox corruption |
  | 6 | `h > identityCursor` | contribute normally |

  Cursor mechanics: today `putBlockIdentity`, `putSection` and
  `advanceContributor` write `h` unconditionally, which can move a cursor
  backward. They gain **max/unchanged semantics** — a staged cursor value is
  `max(existing, h)` — so case 3 repairs never rewind, and case 6 advances as
  before. This rule replaces the unconditional overwrite for both eras.
- **Explicit full UTXO rebuild** (ADR-041 §9,
  `yano.utxo.rebuild-unmarked-from-genesis`) replays from genesis while the
  archive is already canonical: its outbox keys have been acknowledged and
  pruned, its cursors are far ahead, and the drain thread is running because
  projection history starts before runtime recovery. Re-contributing would
  recreate pruned keys, rewind cursors and race the drain. This is not a Byron
  problem: the rebuild replays every era, and the Shelley+ path already
  re-contributes on replay today, so the hazard exists for Shelley the moment
  ADR-041's rebuild is used with projection enabled. Projection contribution
  is therefore **suspended for the duration of the rebuild**, for all eras: the
  runtime swaps the store's contributor to `NOOP` (or sets a rebuild gate)
  before the rebuild starts and restores it in a `finally`, so a failed
  rebuild cannot leave projection silently disabled. The archive is left
  untouched; the drain keeps acknowledging and pruning its pending suffix as
  usual. On completion the rebuild validates that the UTXO applied point
  equals the canonical state-transition tip's full point (block number, slot
  and hash) — the numbered main-block owner when the physical tip is a no-op
  Byron EBB — not merely that it is not behind `acknowledgedThrough`, and
  reports any difference as an error.
- **Rollback** is not atomic across the UTXO batch and the outbox: the two
  exact-point handlers (ADR-041 §7 / PR #90) run on the same `RollbackEvent`
  and each completes idempotently. A crash between them leaves one store
  rolled back and the other not. The UTXO side is repaired by its existing
  startup reconcile. The outbox side has **no** startup reconciliation today —
  only the live `RollbackEvent` subscriber — and `startConsumerAndDrain`
  proceeds straight to the drain. This decision adds a **pre-drain
  reconciliation step** in `ProjectionHistoryService`:
  1. read the canonical body tip as a full point (block number, slot, hash). A
     missing body tip is origin, not a synthetic block-0 point;
  2. validate committed history before using `h <= acknowledgedThrough` as a
     replay skip. At origin, both the sink coordinate and acknowledgement must
     be empty. At a non-origin tip, acknowledgement must not be above the tip,
     and a present sink coordinate must resolve to the same canonical numbered
     point (block number, slot and hash) in chain state. The lookup is
     header/index based so it does not depend on retained bodies; at a Byron
     shared coordinate the numbered main block owns the coordinate, while the
     unclaimed genesis EBB is resolved explicitly. A sink point that is above
     the tip or has a different slot/hash fails closed — committed history
     cannot be repaired by trimming the pending outbox;
  3. call exact-point `outbox.rollbackToPoint(...)` for the canonical tip, or
     its origin form when the chain is empty, removing any stale pending suffix
     (a no-op when nothing is stale);
  4. reconcile artifact leases (`artifactReader.reconcileAfterRestart`) —
     after the rollback, so no lease points at a removed envelope;
  5. only then start the drain.
  Crash tests inject a failure between the handlers in both orderings (UTXO
  first, outbox first) and assert the next startup converges.
- **Asynchronous UTXO mode**: Byron contribution runs on the UTXO worker inside
  the apply, as Shelley contribution does, subject to the §4 ordering
  invariant.
- **Devnet snapshot restore**: projection drain passes hold the runtime
  maintenance gate's read lease. Snapshot restore therefore waits for an
  in-flight outbox read/commit before replacing RocksDB. During UTXO
  reinitialization the installed contributor refreshes the outbox's database
  and column-family handles from `ChainstateRocksAccess`, before the exclusive
  maintenance lease is released. The archive sink itself is not rewound or
  replaced; its directory must remain outside the replaceable RocksDB
  directory, as production defaults already require.

### 7. Metrics

The ADR-039 apply-share instrument (`projection.ns`, `apply.ns`) attributes
Byron contribution the same way it attributes Shelley contribution, so the
Byron cost of the archive is reported by the node rather than inferred.

## Alternatives considered

### Keep the carrier, drop `sync=true`

Fixes #88's fsync cost but keeps two atomicity models, keeps the duplicate
outpoint index, and still writes Byron sections in a batch separate from the
UTXO state they are derived from. Rejected: it fixes the symptom and leaves the
structural duplication ADR-041 made unnecessary.

### Stage the EBB envelope in the next main block's batch

Removes the last separate commit. Rejected: it defers state across events,
complicates ordering at the first main block after an EBB, and the ~200 EBBs
cost ~2 s in total.

### Keep the resolver as a permanent independent source

Rejected. It duplicates every Byron output on disk, requires genesis seeding
inside the archive, and its "consulted in every era" fallback exists only to
paper over the gap ADR-041 closed. Keeping two sources of the same fact
invites drift with nothing to detect it.

### Exempt Byron from UTXO filters (store every Byron output regardless)

Considered: make Byron always complete so the resolver's removal never
depends on configuration. Rejected: it adds an era-specific rule to the
filter semantics, makes it impossible to filter *for* a Byron address, and
buys nothing — address history needs an unfiltered store in every era anyway,
and that is enforced at startup.

### Skip Byron entirely when a filter is configured

Rejected. An address the operator filters for may hold Byron-era outputs;
skipping Byron would drop those outputs and leave their later Shelley spends
unresolvable — a wrong balance for the very address the filter selects.

### Gate address history only when the runtime assembles the live filter chain

Rejected. Although this is before sync applies a new block, projection
initialization has already started the drain. On an existing archive that
thread can commit and prune pending envelopes before runtime startup reaches
`initializeFilterChain`; an eventual filter failure is therefore not a clean,
read-only rejection. The configured-filter preflight is required before any
projection worker or sink mutation, and its shared resolver prevents it from
drifting from the later live chain.

### Retain the resolver only for filtered configurations

Rejected. Address history on a filtered store is refused at startup, so there
is no configuration left for a second source of Byron addresses to serve.

### Deprecate the main-block half of `ByronBlockProjectionEvent` for one release

Considered. Rejected: the deprecated `main(...)` would exist only to fail at
runtime in the carrier, and no released compatibility contract includes the
API. Immediate removal makes the invalid event unrepresentable instead.

### Rename the EBB-only carrier

Considered (`ByronEpochBoundaryProjectionEvent` or similar). Rejected as
cosmetic churn: the narrowed type already states what it carries, and the name
change would be a second break for no behavioural gain.

### Resumable key cleanup of `proj_byron_utxo` under a completion marker

Considered as the conservative way to retire the family while keeping every
open path unchanged and allowing downgrade. Rejected for now: Yano is in
preview, PR #86 was never released, and the owner-controlled conditional
open-and-drop leaves no dormant family behind.

## Consequences

Positive:

- Byron projection throughput bounded by section building, not by fsync.
  Issue #88 measured the residual archive cost at ~1.4×; from the ADR-041
  build's ~5.1k blocks/s that projects to ≈3.6k blocks/s, ≈21 min for the Byron
  era instead of ~10 h.
- One atomicity model and one crash-recovery story for transaction-bearing
  blocks in every era; EBBs retain their explicit no-transition carrier.
- `proj_byron_utxo` disk usage and its genesis seeding disappear.
- Byron address resolution comes from the same live store the REST API serves,
  through the same capture code as Shelley+.
- Address history over a filtered store, which today crashes the archive at
  the first filtered-out spend, is refused at startup with a clear diagnostic.
- The replay rule, the rebuild suspension and the pre-drain reconciliation
  fix era-neutral gaps that ADR-041's rebuild operation made reachable for
  Shelley as well.

Tradeoffs:

- Byron section building now runs on the apply thread (accepted; same as
  Shelley).
- Archives from PR #86 have `proj_byron_utxo` dropped on first open; downgrade
  to a PR #86 build is unsupported because it would recreate an incomplete
  resolver (preview; no release affected).
- The main-block half of `ByronBlockProjectionEvent` is removed without
  deprecation (preview; no released compatibility contract includes it); the
  type is EBB-only and subscribers move to `ByronMainBlockAppliedEvent`.
- `ADDRESS_TRANSACTION` and UTXO storage filters become mutually exclusive
  (they were already incompatible in practice); filter semantics themselves
  are unchanged and uniform across eras.
- Reconcile's contribution rule changes from overwrite to the §6 case table
  for both eras.

## Implementation plan

1. Branch `feat/byron-projection-unify` from `feat/byron-utxo-apply` (PR #90
   head). Open the PR stacked onto that branch; retarget to `main` when #90
   merges.
2. Mainnet Shelley-boundary observations recorded in the ADR-042 implementation
   report (2026-08-27) — done; the UTXO outpoint-set comparison stays on
   ADR-041's list.
3. Shared configured-filter resolver and read-only runtime preflight:
   projection identity requires `ADDRESS_TRANSACTION` + non-empty configured
   filter chain → fail before sink open, outbox mutation, drain startup and
   contributor installation; `initializeFilterChain` asserts agreement with
   the preflight and filter semantics remain untouched.
3a. `ProjectionOutboxStore`: max/unchanged cursor semantics for
   `putBlockIdentity`, `putSection`, `advanceContributor`.
3b. Pre-drain outbox reconciliation in `ProjectionHistoryService`: null body
   tip means origin; validate acknowledgement and the sink's full canonical
   coordinate through a header/index point lookup; fail closed on a committed
   non-canonical/ahead point; exact-point or origin `rollbackToPoint` →
   artifact-lease reconcile → drain.
4. Extract the Shelley+ consumed-address capture from `applyBlock` into the
   shared helper; use it from `applyBlock` unchanged and from
   `ByronUtxoApplier`; expose the view in `ApplyResult`.
5. `CanonicalProjectionContributor.contributeByronMainBlock` (default no-op,
   `NOOP` unchanged); `CanonicalProjectionCollector` implementation with the
   §6 idempotent/monotonic contribution rule applied to both eras.
6. `DefaultUtxoStore.applyByronBlock`: in-batch contribution and metrics
   attribution.
7. `BodyFetchManager`: remove the `ByronBlockProjectionEvent` publish from
   `applyByronBlock`. Narrow the event: remove `main(...)`, `mainBlock()`,
   `isEpochBoundary()` and the `ByronMainBlock` field; constructor takes a
   non-null `ByronEbBlock`. `contributeByronBlock` becomes EBB-only by type;
   update its Javadoc and the class Javadoc; migrate tests that used
   `main(...)` to `contributeByronMainBlock(...)`; document the async
   ordering invariant.
8. Rebuild: swap the contributor to `NOOP` before the explicit rebuild and
   restore it in `finally`; validate the rebuilt UTXO applied point equals the
   canonical state-transition tip's full point (the numbered main owner at an
   EBB tip) and report any difference as an error.
8a. Snapshot restore: hold the runtime maintenance read lease around every
    projection drain pass and refresh the outbox's RocksDB/CF handles through
    the installed contributor during UTXO reinitialization, before services
    resume. Keep the devnet e2e archive directory outside the replaceable
    chain-state directory.
9. Remove `ByronOutputAddressIndex`, `recordByronOutputs`, the resolver fallback
   and the genesis-provider dependency. Remove the public
   `PROJ_BYRON_UTXO` constant, its `ProjectionCfNames.ALL` entry and its fresh
   descriptor; make the chain-state RocksDB owner conditionally open an
   existing legacy family using a private migration name, drop it before
   publishing shared access, remove and close its handle, and fail startup on
   cleanup failure.
10. Amend the normative `ByronBlockProjectionEvent` descriptions in ADR-039
    and ADR-041 so they describe the EBB-only carrier; historical reports stay
    historical. Update the event and
    `CanonicalProjectionContributor.contributeByronBlock(...)` Javadocs. The
    release notes classify and list together both immediate, no-deprecation
    preview API removals — the event's main-block API and
    `ProjectionCfNames.PROJ_BYRON_UTXO` — plus the physical CF drop and
    address-history/filter exclusivity.
11. Run the acceptance gate below before merge — completed 2026-08-27; see
    `adr/reports/adr-042-implementation-report-2026-08-27.md`.

## Tests

1. A Byron block that creates and spends an output in one block projects the
   spent output's address through the shared capture helper, without a store
   read — the same test shape as the existing Shelley same-block case.
2. A Byron block spending a committed Byron or genesis output projects the
   consumed address captured from the deleted record, via the same helper the
   Shelley path uses.
3. A Shelley block spending a Byron-created output projects the consumed
   address from the live store, with no resolver present.
4. Byron sections, UTXO delta and cursors are absent together when the batch
   fails, and present together when it commits.
5. Reconcile replay, one test per §6 case: acknowledged/pruned block skipped;
   pending same-hash complete block skipped with no cursor write; pending
   same-hash incomplete block repaired with only the missing sections and no
   cursor rewind; pending different-hash block fails closed; missing header
   inside `(acknowledgedThrough, identityCursor]` fails closed; block above
   `identityCursor` contributes normally. Cursor values are asserted
   monotonic throughout.
6. EBB semantics: `main N → EBB N → main N+1` leaves `main N` intact and
   contiguous; the EBB is a validated no-op; the genesis EBB at an unclaimed
   coordinate writes the empty envelope. Compile-time/API-shape assertions
   confirm that the event exposes only `epochBoundary(...)` and a non-null
   `ByronEbBlock`, with no main-block factory, accessor or discriminator.
7. Rollback to an EBB with projection enabled: both exact-point handlers
   complete idempotently and the same-slot successor's sections and UTXO delta
   are both gone afterward. With a crash injected between the handlers — once
   with the UTXO handler first, once with the outbox handler first — combined
   startup recovery (UTXO reconcile plus pre-drain outbox reconcile) converges
   to the same state. A fresh origin start is accepted only with empty
   acknowledgement/sink state; a sink coordinate above the canonical tip or at
   a non-canonical slot/hash makes startup fail closed.
8. `SIGKILL` during Byron with projection enabled: restart shows matching UTXO
   and outbox cursors, no missing or duplicate envelope.
9a. With the drain quiescent and no pending suffix, explicit UTXO rebuild with
    projection enabled leaves the projection column families byte-for-byte
    unchanged, never rewinds a cursor, and ends with the UTXO applied point
    equal to the canonical state-transition tip's full point; the contributor
    is restored afterward, including when the rebuild throws.
9b. With a pending suffix, the drain continues to acknowledge and prune during
    rebuild. The only projection mutations are those normal drain operations:
    rebuild contribution creates no envelope and rewinds no identity or
    section cursor.
10. Gate: `ADDRESS_TRANSACTION` + the built-in address filter, and + a plugin
    filter, each fail in preflight with the filter named before sink/outbox
    mutation or drain startup; an existing pending outbox remains byte-for-byte
    unchanged and no drain thread survives. Other datasets with the same
    filters start normally; the preflight/live-chain agreement test and PR
    #90's existing Byron/Shelley filter tests pass unchanged.
11. Legacy archive: an archive with `proj_byron_utxo` opens through the
    conditional legacy descriptor, the owning chain-state layer drops it and
    closes/removes its handle exactly once, and it is absent on the next open;
    a fresh archive never creates it. Injected drop failure aborts startup
    before shared RocksDB access is published.
12. Async resolution: a client configuration with projection history enabled
    resolves `yano.utxo.applyAsync` to `false` even when the property is set
    to `true`, and the resolution site carries the §4 invariant.
13. History disabled: `applyByronBlock` performs no projection work and
    allocates no capture map.
14. Devnet snapshot restore with projection enabled: the drain is quiesced
    before RocksDB replacement, the outbox rebinds to the reopened handles,
    snapshot restore and a subsequent projection-backed rollback both
    complete, and no native handle access survives the maintenance boundary.

Acceptance gate: preserve the accepted ADR-039 phase-7a differential result
(`adr/reports/adr-039-phase-7a-parity-2026-08-22.md`) for the section builders
and datasets this decision does not change. For the changed path, compare a
PR #86 carrier leg and this implementation on fresh mainnet state through
Byron and into Shelley at the fixed block-5,081,500 coordinate. Every
`address_transactions` field must be identical for Byron spends and Shelley
spends of Byron outputs. Then a fresh mainnet sync with the `wallet` profile
must complete Byron at ≥ 3,000 blocks/s on the #88 reference machine with
zero unresolved inputs.

Completed 2026-08-27: the exact bidirectional comparison found zero rows on
either side across 14,667,701 rows (3,184,885 transactions), including the
deterministic `archive_job_id`; all 51 independent 100,000-block digest
buckets also matched. The candidate completed Byron at 3,215.9 blocks/s with
zero unresolved-input, continuity or projection-drain failures. Full evidence
and the validation methodology are recorded in
`adr/reports/adr-042-implementation-report-2026-08-27.md`.
