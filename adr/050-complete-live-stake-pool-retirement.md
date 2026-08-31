# ADR-050: Complete Live Stake-Pool Retirement and Ordered Pool Certificates

## Status

Accepted for phased implementation after independent review; review amendments
incorporated

## Date

2026-08-31

## Related decisions and source evidence

- [Issue #62](https://github.com/bloxbean/yano/issues/62) reports that effective
  pool retirement leaves stale live pool and delegation state and that a later
  registration in the same RocksDB batch can fail to cancel retirement.
- [Issue #49](https://github.com/bloxbean/yano/issues/49) owns the separate
  decision about the meaning and public representation of Yano's aggregate
  AdaPot `deposits` field.
- [ADR-025](025-bootstrap-partial-state-ledger-and-nonce-boundaries.md) defines
  restart and rollback boundaries that this transition must preserve.
- [ADR-048](048-bounded-memory-epoch-boundary-processing.md) fixes the boundary
  order, bounded-memory policy, boundary journal v1, resumable chunk model and
  preview-chainstate compatibility policy reused here.
- [ADR-049](049-prepare-steady-state-rewards-after-stabilization.md) may move
  reward preparation earlier, but keeps final reward application, SNAP and
  POOLREAP in ledger order. It does not change this ADR's live-state semantics.
- Current Yano `main` invokes only the refund method in its phase named
  POOLREAP. See
  [EpochBoundaryProcessor.java](https://github.com/bloxbean/yano/blob/f89c8dbafda50ae243ed3eff9657d1f273cc98bf/ledger-state/src/main/java/com/bloxbean/cardano/yano/ledgerstate/EpochBoundaryProcessor.java#L462-L483)
  and
  [EpochRewardCalculator.java](https://github.com/bloxbean/yano/blob/f89c8dbafda50ae243ed3eff9657d1f273cc98bf/ledger-state/src/main/java/com/bloxbean/cardano/yano/ledgerstate/EpochRewardCalculator.java#L1760-L1818).
- Current pool registration and retirement processing reads committed RocksDB
  state while writing the enclosing block batch. See
  [DefaultAccountStateStore.java](https://github.com/bloxbean/yano/blob/f89c8dbafda50ae243ed3eff9657d1f273cc98bf/ledger-state/src/main/java/com/bloxbean/cardano/yano/ledgerstate/DefaultAccountStateStore.java#L2983-L3220).
- Current snapshot filtering supplies correct defensive behavior for ordinary
  retirement but does not correct live state. See
  [DefaultAccountStateStore.java snapshot path](https://github.com/bloxbean/yano/blob/f89c8dbafda50ae243ed3eff9657d1f273cc98bf/ledger-state/src/main/java/com/bloxbean/cardano/yano/ledgerstate/DefaultAccountStateStore.java#L4367-L4484).
- The current Haskell `POOL` rule keeps an active pool's deposit and delegators,
  places updated parameters in future state, and removes a re-registered pool
  from `psRetiring`. See
  [Pool.hs](https://github.com/IntersectMBO/cardano-ledger/blob/9714940b3f6527633ba37e47b93342b882ff7e67/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Pool.hs#L262-L324).
- The current Haskell `POOLREAP` rule removes the retired pools' stake
  delegations, active pool state and retirement entries, removes their deposits
  from the deposited pot, refunds registered reward accounts, and sends
  unclaimed deposits to treasury. See
  [PoolReap.hs](https://github.com/IntersectMBO/cardano-ledger/blob/9714940b3f6527633ba37e47b93342b882ff7e67/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/PoolReap.hs#L180-L240).
- The current Conway ledger test covers re-registration after expiry,
  re-registration before expiry and `RetirePool` followed by `RegPool` in one
  transaction. See
  [DelegSpec.hs](https://github.com/IntersectMBO/cardano-ledger/blob/9714940b3f6527633ba37e47b93342b882ff7e67/eras/conway/impl/testlib/Test/Cardano/Ledger/Conway/Imp/DelegSpec.hs#L473-L539).

## Decision summary

Yano will implement stake-pool retirement as a complete live ledger transition,
not only as a deposit-refund side effect.

1. Pool certificates in one block will read and update a block-local overlay so
   their effects are observed in exact `transaction index -> certificate index`
   order before the RocksDB `WriteBatch` commits.
2. Re-registering an active pool before retirement becomes effective will
   cancel the pending retirement, retain the original pool deposit and retain
   existing stake-pool delegations.
   A genuinely new lifecycle will use the pool deposit from protocol parameters
   effective at that registration epoch, never an epoch-zero lookup.
3. At effective retirement, after SNAP and before governance, Yano will remove:
   the live pool entry, its retirement marker, its live pool-registration-slot
   metadata and every current stake-pool delegation to that pool. DRep/vote
   delegations and historical records remain unchanged.
4. Pool-delegation removal will use a bounded sequential scan of the existing
   credential-major pool-delegation prefix. A reverse pool-delegator index will
   not be introduced initially.
5. Large POOLREAP mutations will use the existing boundary journal v1 sequence
   envelope plus a pool-reap progress marker. No rollback-v2 or second journal
   format is introduced.
6. Refunds, live cleanup and phase completion will be restart-safe and exactly
   once. A partial chunk must never cause recovery to classify POOLREAP as
   complete.
7. Pool lifecycle history and pool-parameter history remain queryable. Only
   live/current state is removed.
8. This semantic correction requires a clean replay. A new
   `pool-lifecycle-state-v1` readiness marker will be initialized on an empty
   account store. A non-empty store without it will fail closed with the
   existing backup-and-resync guidance. The epoch boundary and rollback journal
   remain version `v1`.
9. The in-memory store remains a test-only implementation. It will reproduce
   the ordered certificate and live pool-lifecycle behavior used by tests, but
   it will not be presented as a durable consensus-state alternative to the
   RocksDB implementation.
10. A persisted pool-reap progress marker makes the ledger temporarily
    non-servable. Startup/recovery must finish or roll back that phase before
    the node reports readiness, accepts validation work or resumes block sync.

The existing boundary order remains:

```text
reward calculation/application
    -> SNAP
    -> complete POOLREAP
    -> governance
    -> spendable reward_rest
```

This ADR does not change reward formulas, reward timing, snapshot stake for a
normally retiring pool, governance formulas, or the public epoch-history
contract.

### Operational rollout cost

The readiness marker deliberately rejects every account chainstate created
before ADR-050, including the current mainnet tip store and the retained
e447/e505/e610/e628 checkpoint ladder. Landing this change therefore requires a
full mainnet replay and a rebuilt checkpoint ladder. On the current reference
hardware this is a multi-day rollout cost, not a lightweight application
restart.

The old mainnet store is retained or archived according to the separate
operator disk-space decision; ADR-050 does not delete it automatically. The
implementation and release notes must make this cost visible before an operator
upgrades.

## Context

### Current Yano behavior

The current boundary processor labels its post-SNAP phase `POOLREAP`, but it
only invokes `EpochRewardCalculator.processPoolDepositRefunds` and commits that
reward batch. The refund method credits a registered reward account. It does not
delete the pool, retirement marker, registration-slot metadata or delegations.

As a result, after a retirement becomes effective:

- `isPoolRegistered` can remain true;
- `getPoolRetirementEpoch` and `listPoolRetirements` can expose an already
  effective retirement as pending/current;
- `getDelegatedPool` and `listPoolDelegations` can expose delegations that the
  ledger removed;
- `CertStateBridge` can project the stale pool and delegation into Scalus
  validation state; and
- re-registering the pool later relies on snapshot filters to hide old
  delegations instead of beginning from the correct live state.

The RocksDB store applies all valid transactions and certificates from one block
to one `WriteBatch`. Pool retirement writes a retirement key to that batch.
Pool registration subsequently calls `db.get` for the retirement key. RocksDB
point reads do not observe uncommitted operations in a plain `WriteBatch`, so:

```text
same block:
  RetirePool(pool, r)
  RegPool(pool)
```

can commit the retirement even though the later registration must cancel it.
The reverse order must continue to leave retirement scheduled.

There is a related deposit mismatch. A Yano re-registration currently encodes
the current configured pool deposit into the live pool record. The Haskell rule
explicitly preserves the deposit belonging to the existing pool lifecycle.
Preserving it matters because that exact stored deposit is later refunded.

### What is already correct

The RocksDB snapshot path currently excludes a delegation when its retirement
marker is already effective for the snapshot epoch and excludes a delegation
that predates a new post-retirement pool-registration slot. Those guards produce
the intended result for ordinary retirement and later re-registration on the
existing store.

They are not a substitute for correct live state:

- the same-block bug can create a false retirement marker and later corrupt
  snapshot/reward input; and
- live query and transaction-validation state is already incorrect before a
  later snapshot happens to filter it.

The filters remain as defensive and historical-compatibility logic. This ADR
does not rewrite them. A clean replay with complete POOLREAP naturally removes
the stale live rows they previously had to mask.

### Reference ledger semantics

For an active pool that re-registers, Haskell:

- keeps the active pool state and its original deposit;
- records future parameters;
- deletes the pool from the retiring map; and
- leaves both stake and vote delegations intact.

When retirement becomes effective, Haskell:

- selects pools whose retirement epoch equals the new epoch;
- computes claimed and unclaimed deposit refunds from the pool state;
- removes stake-pool delegation for the retired pools' delegators;
- leaves vote/DRep delegation intact;
- removes active pool and retiring state; and
- adjusts the deposit and treasury/reward-account pots.

SNAP precedes POOLREAP. Therefore stake delegated to a pool retiring at the
incoming boundary is still represented in the snapshot taken immediately
before the live pool is reaped. This ordering must not change.

## Decision drivers

- Match the reference ledger's live pool and delegation state.
- Ensure Scalus validation starts from the correct certificate state.
- Preserve exact certificate ordering within and across transactions in one
  block.
- Keep ordinary retirement snapshot and reward results unchanged.
- Preserve historical APIs while correcting live APIs.
- Reuse ADR-048's bounded memory and rollback-v1 mechanisms.
- Avoid adding an index and migration path before measurements show that one is
  required.
- Prefer a clean preview-era resync over an ambiguous repair of already
  corrupted lifecycle state.
- Keep the implementation small enough for a human to trace through block
  application, boundary recovery and rollback.

## Invariants

1. Valid transaction bodies are processed in block order, and certificates are
   processed in list order. Later certificates see every earlier pool mutation
   from that block.
2. `RetirePool -> RegPool` cancels retirement; `RegPool -> RetirePool` schedules
   retirement.
3. Re-registration before effective retirement preserves pool stake
   delegations and the original deposit.
4. Effective retirement clears only stake-pool delegation. It does not clear
   stake registration, reward balance or DRep/vote delegation.
5. Re-registering after effective retirement creates a fresh lifecycle and does
   not restore prior delegations.
6. The pre-POOLREAP snapshot and the post-POOLREAP live state must each match
   their reference-ledger point in time.
7. A registered reward account receives the pool deposit exactly once. An
   unregistered reward account does not receive a credit; its deposit follows
   the existing treasury calculation exactly once.
8. A crash may leave a resumable BUILDING/progress state, but never a false
   COMPLETE phase.
9. Rollback across POOLREAP restores pool, retirement, delegation, refund and
   lifecycle metadata exactly.
10. Historical pool parameters, registrations, retirements and delegation facts
    are not deleted by live cleanup.
11. POOLREAP memory is bounded by configured chunk limits, not by delegator
    count.
12. Production live-state cleanup is not silently skipped merely because an
    optional archive writer or public reward-history projection is disabled.
13. While a pool-reap progress marker exists, the boundary is not externally
    readable as complete and governance cannot start.

## Detailed decision

### 1. Add one pool-state overlay per applied block

`DefaultAccountStateStore.applyBlock` will create a `BatchStateOverlay` for pool
state alongside the existing DRep delegation overlays. Pool certificate helpers
will use `getStateWithOverlay`, `putStateWithDelta` and
`deleteStateWithDelta` for at least:

- the live pool registration/deposit key;
- the live retirement key;
- the pool registration-slot key; and
- any pool-parameter-history key written more than once in the block.

The overlay stores both values and deletion tombstones. It lives only for the
block, is cleared in `finally`, and is never shared across threads.

Every mutation still enters the same canonical RocksDB `WriteBatch` and block
delta. The overlay changes read visibility, not durability or transaction
boundaries.

For registration:

```text
read live pool through the block overlay

if no live pool exists through that overlay:
    create a new lifecycle
    store the pool deposit from protocol parameters at the registration epoch
    write the lifecycle registration slot
else:
    preserve the existing lifecycle deposit
    preserve the existing lifecycle registration slot
    cancel any pending retirement visible through the overlay
```

The existing activation-epoch history behavior is left unchanged by this ADR
unless a differential test demonstrates a separate mismatch. Historical pool
parameters are not the live-registration existence key.

The overlay-aware existence test is also authoritative for repeated
registrations in one block. A same-block `RegPool -> RegPool` pair observes the
first registration as live for the second certificate, so it cannot be
misclassified as two fresh lifecycles, choose the wrong `+2`/`+3` parameter
activation cadence, or rewrite the lifecycle registration slot.

### 2. Separate the POOLREAP plan from its application

Before mutating live pool state, the boundary creates a deterministic
`PoolReapPlan` for retirement epoch `N`. It contains, in unsigned pool-hash
order:

- pool hash;
- exact stored lifecycle deposit;
- reward account credential, when decodable;
- whether that credential is registered at the POOLREAP point; and
- the current pool/retirement/lifecycle values needed for validation.

The plan is bounded by the number of pools retiring in one epoch, not by stake
credential or UTXO count. A malformed or missing live pool record for a current
retirement fails the boundary closed. Yano must not silently substitute a
hard-coded 500 ADA deposit or skip live cleanup when the authoritative record is
missing.

The same plan feeds monetary handling and live cleanup. This prevents one scan
from refunding a different pool set than the scan being removed.

The reward calculation continues to account for unclaimed retired-pool deposits
in treasury. Registered reward-account refunds continue to use the normal
reward-account credit and archive path, typed as `REFUND`. Live-state cleanup is
owned by the ledger boundary coordinator rather than hidden inside a method
whose name and feature gate only describe reward credits.

If the required monetary result is unavailable in a production ledger run, the
boundary fails closed. It does not delete the pool while omitting its refund or
treasury effect.

### 3. Preserve SNAP-before-POOLREAP ordering

For transition `N-1 -> N`:

1. reward calculation obtains the retirement information it requires;
2. SNAP commits the `N-1` mark generation;
3. the `N` POOLREAP plan is validated;
4. live delegations and pools are reaped; and
5. governance opens its post-POOLREAP phase view.

No iterator or RocksDB snapshot is held across these phase commits. The plan is
created from the live state appropriate to POOLREAP and is discarded after the
phase completes.

### 4. Use a bounded credential-major delegation scan

Yano currently stores live pool delegation as:

```text
PREFIX_POOL_DELEG | credential-type | credential-hash
    -> pool-hash | delegation-coordinate
```

There is no pool-major reverse index. The initial implementation will:

1. return immediately when no pool retires at `N`;
2. put the retiring pool hashes in a small lookup set;
3. open a sequential RocksDB iterator with block-cache population disabled;
4. scan `PREFIX_POOL_DELEG` once;
5. decode only the delegation value needed to compare the pool hash; and
6. journal/delete matching keys in bounded chunks.

The scan does not materialize all delegations or credentials. It has
approximately constant memory plus the retiring-pool set and the current write
chunk.

The existing snapshot path has just scanned the same ordered prefix, so the
additional pass is expected to be I/O-bound and materially cheaper than a UTXO
scan. It must nevertheless be measured on mainnet data.

### 5. Reuse rollback-v1 with a POOLREAP progress marker

The existing boundary delta key already includes:

```text
boundary-slot | phase-id | sequence
```

POOLREAP will use `PHASE_POOLREAP` and monotonically increasing sequence
numbers. It will not introduce a new phase-byte ordering rule or a v2 codec.

A versioned `META_POOL_REAP_PROGRESS` value records:

- target epoch;
- stage: `DELEGATIONS` or `POOLS`;
- last completely examined delegation key, when in `DELEGATIONS`;
- last completely applied pool hash, when in `POOLS`; and
- the next boundary-delta sequence number.

Each chunk atomically commits:

- its live-state mutations;
- the updated progress value; and
- its rollback-v1 delta entry.

The retirement markers remain live while the delegation scan is in progress,
so restart can reconstruct and validate the same retiring-pool set. After the
delegation stage completes, sorted pool cleanup/refund work is applied in
bounded chunks. The final chunk deletes the progress marker and commits the last
pool cleanup/refund mutations.

`getCommittedBoundaryPhases` will treat `PHASE_POOLREAP` as incomplete whenever
the progress marker exists, just as an active reward-progress marker prevents a
partial reward phase from being classified as complete.

Application readiness will also treat that marker as an unfinished ledger
transition. Recovery runs before public state is advertised as ready and before
new blocks or transaction-validation requests are accepted. Chunking therefore
does not expose a partially reaped state as a valid chain tip.

Recovery behavior is therefore:

```text
progress exists
    -> validate epoch and boundary slot
    -> resume after the durable cursor
    -> never repeat a committed refund or deletion

progress absent + PHASE_POOLREAP delta exists
    -> phase complete
    -> advance the boundary step if its marker lagged

progress absent + no phase delta
    -> phase not started, or a no-retirement no-op
```

Rollback uses the existing declarative phase reverse order and reverses sequence
chunks within `PHASE_POOLREAP`. The progress marker is itself delta-journaled, so
rollback removes/restores it consistently. Crash during rollback continues to
use ADR-048's `META_ROLLBACK_TARGET_SLOT` state machine.

### 6. Delete live state and preserve history

For each effectively retired pool, the final live cleanup deletes:

- `poolDepositKey(poolHash)`, which is the current pool registration and stored
  lifecycle deposit;
- `poolRetireKey(poolHash)`;
- `poolRegSlotKey(poolHash)`; and
- every `poolDelegKey(credential)` whose current value points to the pool.

It does not delete:

- `PREFIX_POOL_PARAMS_HIST` rows;
- account-history registration, update or retirement facts;
- historical delegation facts;
- epoch snapshot generations already committed;
- the stake credential's account or reward balance; or
- its DRep delegation or DRep reverse-index entry.

Live listing APIs and `CertStateBridge` then become correct without adding
query-time retirement filters.

### 7. Keep aggregate deposit-pot API work in issue #49

This ADR makes the exact per-pool lifecycle deposit authoritative:

- a new pool stores the deposit charged to that lifecycle;
- an active re-registration preserves it; and
- POOLREAP refunds that exact value once and removes the live obligation.

Yano's existing aggregate `META_TOTAL_DEPOSITED` and public AdaPot `deposits`
field do not yet represent every Haskell ledger deposit category. Pool
registration is one known gap; proposal deposits are another. Updating only the
retirement subtraction would make that partial aggregate less coherent and can
underflow a chainstate that never added the corresponding pool registration.

Therefore issue #49 remains responsible for defining and correcting the full
aggregate/category model. ADR-050 tests the per-pool refund and monetary
conservation directly and must not redefine the public AdaPot field by accident.
When #49 implements a complete deposit pot, its pool component consumes the
lifecycle events and cleanup defined here.

### 8. Make production and in-memory responsibilities explicit

`DefaultAccountStateStore` is the durable production implementation and must
pass all crash, restart and rollback gates.

`InMemoryAccountStateStore` already observes same-block certificate order
because it mutates maps directly. It will gain deterministic effective-retirement
cleanup for unit-test parity:

- remove the active pool and retirement entry;
- remove stake-pool delegations to it;
- preserve DRep delegation; and
- ensure re-registration after retirement does not restore delegations.

Its class/provider documentation will state that it is non-durable and
test-only. Production node startup must not silently fall back to it when the
RocksDB account store fails or is incompatible. Full monetary and rollback
acceptance remains a RocksDB requirement.

### 9. Require a clean preview chainstate

The accepted ADR-048 chainstate can already contain:

- pools that should have been removed at an earlier POOLREAP;
- delegations that should have been cleared; and
- false retirement markers left by `RetirePool -> RegPool` in one block.

A startup scan cannot reliably distinguish all three cases from current live
rows. In particular, the incorrect batch result has already lost the intended
cancellation from the live representation. Reconstructing it requires ordered
certificate history or full block replay and becomes a migration project.

Yano is still preview software, so this ADR selects resync:

- an empty account store writes `pool-lifecycle-state-v1` with the existing
  epoch-boundary/index readiness markers before block application;
- a non-empty store missing that marker is opened read-only for the check and
  rejected before mutation;
- the error identifies the incompatible account chainstate and instructs the
  operator to retain a backup and sync a new directory; and
- unknown marker versions also fail closed.

The missing or unsupported marker throws the typed
`IncompatibleChainStateException` from `core-api` rather than a generic wrapper.
Application startup must preserve that type through the accepted fail-fast path
so Quarkus terminates instead of leaving a half-started process.

This marker records semantic readiness; it does not create
`epoch-boundary-state-v2`, `rollback-v2`, compatibility decoders or promotion
branches. If a future layout or semantic change needs migration, that future
change will make its own migrate-versus-resync decision.

## Configuration and observability

The initial implementation reuses ADR-048's bounded-write defaults unless
measurement shows that an independently tunable POOLREAP limit is necessary.
If separate settings are added, they use the same operation/byte semantics and
validation as reward and snapshot chunks; zero or negative limits fail startup.

Boundary telemetry will report at least:

- retiring pool count;
- delegation rows examined and removed;
- pool rows removed;
- registered refunds and unclaimed deposits;
- chunk count and resume count;
- scan and pool-cleanup time separately; and
- heap/RSS before, peak and after the phase through the existing boundary
  telemetry.

Logs must not emit full stake credential or reward-account values at info level.
A failed consistency check names the pool hash and invariant but does not log
unnecessary account data.

## Expected performance impact

Same-block overlay reads are in-memory map lookups over the small number of pool
keys touched by one block and should have negligible regular-sync cost.

POOLREAP adds one sequential scan of the live pool-delegation prefix only when
at least one pool retires. It does not scan UTXOs and does not build a
network-sized map. The write cost is proportional to the delegations actually
removed.

The expected trade-off is a modest increase in POOLREAP time in exchange for
correct live state. The existing operator targets remain:

- under 60 seconds for a typical mainnet boundary on the reference machine;
- 120 seconds maximum; and
- the accepted ADR-048 native memory envelope, with correctness taking
  precedence.

If the scan causes the total boundary to exceed the accepted limit, the next
decision is a pool-major reverse delegation index maintained during ordinary
block application and built by clean replay. That index is not preemptively
added in this ADR.

## Implementation plan

### Phase 0 — Reproduce and pin the reference behavior

1. Add a RocksDB regression fixture with one pool and five stake credentials.
2. Reproduce `RetirePool -> RegPool` in one transaction and in two transactions
   in one block.
3. Reproduce effective retirement leaving the live pool, retirement and
   delegation rows behind.
4. Record the current pre-POOLREAP snapshot digest so later cleanup cannot
   silently change snapshot/reward input.
5. Measure a no-retirement boundary and a retirement boundary on a retained
   mainnet-scale delegation prefix.

Exit gate: the tests fail for the expected current reasons and do not depend on
timing, hash-map order or external services.

### Phase 1 — Ordered pool certificate overlay

1. Create and close the pool overlay with each applied block.
2. Route live pool, retirement, lifecycle-slot and affected history reads/writes
   through overlay-aware delta helpers.
3. Read the new-lifecycle deposit from protocol parameters at the certificate's
   registration epoch; remove the hard-coded epoch-zero lookup.
4. Preserve the original deposit for active re-registration.
5. Prove that overlay-aware `prev == null` handling gives a same-block
   `RegPool -> RegPool` pair fresh-then-update cadence and one lifecycle slot.
6. Add transaction-order, block-order, multiple-update and block-rollback
   tests.

Exit gate: all order permutations match Haskell behavior and block rollback
restores byte-identical state.

### Phase 2 — Complete bounded POOLREAP

1. Introduce the deterministic `PoolReapPlan`.
2. Move live cleanup ownership into the boundary/store layer while retaining
   the reward calculator's monetary calculation seam.
3. Implement the cache-cold sequential delegation scan.
4. Implement bounded delegation and pool/refund chunks.
5. Delete live lifecycle rows and preserve historical rows.
6. Add the progress marker and recovery classification.

Exit gate: live APIs, Scalus state and per-pool refund results match the
reference scenarios without increasing snapshot/reward output.

### Phase 3 — Recovery, rollback and provider contract

1. Inject failure before and after every durable POOLREAP chunk boundary.
2. Resume from each marker stage and prove exactly-once refund/cleanup.
3. Roll back partial and complete POOLREAP in reverse sequence order.
4. Add the in-memory lifecycle behavior and test-only documentation.
5. Verify archive staging abort/complete behavior around a resumed phase.

Exit gate: graceful restart, kill/restart and rollback/replay all produce the
same live and historical state as uninterrupted execution.

### Phase 4 — Preview compatibility and documentation

1. Add `pool-lifecycle-state-v1` initialization and fail-closed checks that
   throw `IncompatibleChainStateException` without wrapping it.
2. Reject a populated pre-marker fixture without modifying it.
3. Verify an empty store initializes all required readiness markers atomically.
4. Update account-state, rollback and manual-debugging documentation.
5. Document that manual rollback includes POOLREAP lifecycle rows and refunds.
6. Document that the current mainnet tip and e447/e505/e610/e628 checkpoint
   ladder are incompatible, require full replay and must be rebuilt.

Exit gate: incompatible stores cannot start, the error has a clear resync action,
and no epoch-boundary or rollback v2 identifier exists.

### Phase 5 — Network and performance validation

1. Run a short-epoch deterministic devnet through every reference scenario.
2. Sync preprod from a clean store with fail-fast epoch calculation enabled and
   validate selected effective retirements against ledger/db-sync or Koios
   evidence where the external API exposes equivalent state.
3. Keep `yano.exit-on-epoch-calc-error=true` and compare treasury/reserves with
   the accepted oracle fixture and Koios through tip.
4. Sample POOLREAP time, boundary time, heap, physical footprint/RSS and RocksDB
   memory at retirement boundaries.
5. Exercise graceful restart, kill/restart and manual rollback across at least
   one effective retirement.

Exit gate: correctness gates pass through tip, typical/max boundary targets hold
and no unexplained live pool/delegation discrepancy remains.

The clean mainnet replay is a post-merge rollout step rather than a Phase-5 test
gate. The readiness marker intentionally rejects the old account chainstate, so
the rollout must schedule a multi-day clean sync and rebuild the useful
checkpoint ladder from that accepted replay. Mainnet-scale POOLREAP scan
performance is already gated by Phase 2's read-only measurement on the retained
epoch-652 store.

## Test plan

### Certificate-order unit tests

- Active pool: retire in block A, re-register in block B before expiry; the
  retirement disappears, original deposit and delegations remain.
- Same transaction: `RetirePool -> RegPool`; retirement disappears.
- Same transaction: `RegPool -> RetirePool`; retirement remains.
- Same block, different transactions: both orders follow transaction index.
- Multiple retire/register pairs in one block use the final ordered result.
- An invalid transaction contributes no pool mutation.
- Block rollback restores the pre-block pool, deposit, retirement, lifecycle
  slot and history keys.

### POOLREAP semantic tests

- SNAP immediately before retirement contains the expected stake.
- Immediately after POOLREAP, the pool is not registered, retirement is absent
  and all five pool delegations are absent.
- Stake credentials and DRep delegations remain.
- A registered reward account receives the exact stored lifecycle deposit once.
- An unregistered reward account is not credited and the same deposit is
  accounted as unclaimed exactly once.
- Re-registering in the retirement epoch creates a live pool but no old
  delegation reappears.
- Re-registering several epochs later has the same result.
- A new delegation after re-registration becomes current.
- Re-registration before expiry keeps all existing delegations through and
  after the cancelled expiry.
- Pool/delegation history and historical pool parameters remain queryable.
- Live listing APIs and `CertStateBridge` agree with direct store reads.

### Boundedness and recovery tests

- Zero retiring pools opens no delegation scan and performs no live mutation.
- A pool with delegators across more than three configured chunks completes
  within the operation and byte limits.
- A scan with many nonmatching rows remains constant-memory.
- Iterator reads use cache-disabled options and close on success and failure.
- Crash before first chunk, after every delegation chunk, between stages, after
  every pool/refund chunk and after final commit resumes correctly.
- A durable progress marker prevents `getCommittedBoundaryPhases` from marking
  POOLREAP complete.
- A durable progress marker keeps application readiness false until recovery
  completes or rollback removes the partial transition.
- Final commit before the boundary step marker is recognized as complete and is
  not repeated.
- Rollback during partial progress and after completion restores every chunk in
  reverse order.
- Crash during rollback resumes through the existing rollback-v1 target marker.

### Chainstate compatibility tests

- Empty account state initializes `pool-lifecycle-state-v1` atomically.
- Non-empty state without the marker is rejected without writes.
- Marker value other than v1 is rejected.
- Both rejections retain `IncompatibleChainStateException` through application
  startup and terminate the process cleanly.
- A current clean store reopens without mutation.
- The rejection propagates to application startup and exits rather than falling
  back to an in-memory store.

### Differential and integration tests

- Port the current Conway `Delegate, retire and re-register pool` sequence as a
  Yano golden test.
- Compare uninterrupted execution, graceful restart and kill/restart digests.
- Compare pre-POOLREAP snapshot rows and post-POOLREAP live rows separately.
- Verify reward, treasury, reserves and pool refund conservation at each tested
  retirement boundary.
- Verify history/archive completion is published only after the logical phase
  completes.
- Verify manual rollback and replay reproduce the same final digest.

## Acceptance criteria

- The current issue #62 reproducer passes for same-transaction, same-block and
  separate-block ordering.
- Reference-ledger pool, retirement and delegation state matches at every
  tested point.
- Active re-registration preserves the original deposit and effective
  retirement refunds it exactly once.
- Ordinary retirement does not change the pre-POOLREAP snapshot/reward result.
- Same-block cancellation prevents the false future retirement and preserves
  future snapshot/reward stake.
- Live APIs and Scalus never expose an effectively retired pool or its cleared
  stake delegations.
- Historical APIs retain the corresponding facts.
- Crash, restart and rollback tests pass at every chunk boundary.
- No boundary or rollback v2 format is introduced.
- A pre-ADR-050 populated account store fails closed with a resync instruction.
- Regular-sync throughput changes by less than 2% on the reference run.
- Typical and maximum epoch-boundary timing remain within 60 and 120 seconds.
- Mainnet native memory remains inside the accepted ADR-048 envelope.
- Preprod and mainnet treasury/reserves remain exact against accepted oracles
  through the validation endpoint.

## Alternatives considered

### Keep the snapshot filters and ignore live state

Rejected. It leaves transaction validation and live APIs wrong, and a false
same-block retirement marker can eventually corrupt snapshot input itself.

### Filter retired pools only at query time

Rejected. Consensus validation must start from canonical live state, and query
filters would not restore correct certificate ordering, refunds or rollback.

### Delete only the pool and retirement marker

Rejected. Haskell also clears stake-pool delegations. Keeping them would allow a
later registration to resurrect delegations from the old lifecycle.

### Clear DRep delegation with pool delegation

Rejected. The reference ledger clears stake-pool delegation but preserves vote
delegation.

### Put every delegation deletion in one WriteBatch

Rejected. A large retired pool could recreate the unbounded boundary-memory and
rollback peak that ADR-048 removed.

### Add a pool-major reverse delegation index immediately

Deferred. It would reduce POOLREAP reads but add an index write to ordinary
delegation processing, another readiness contract and more rollback surface.
The bounded sequential scan is simpler and must be measured first.

### Repair the existing chainstate at startup

Rejected for preview. Current rows cannot always distinguish a valid effective
retirement from a retirement that should have been cancelled by a later
certificate hidden in the old batch. Exact repair requires ordered history
replay and is more complex than a clean resync.

### Introduce rollback-v2 for pool-reap chunks

Rejected. Boundary journal v1 already has phase and sequence fields and reverse
ordering. A phase-specific progress marker supplies the missing completion
evidence without a new format.

### Change the public CF rewards-calculation API

Rejected. The existing reward calculation already accepts retired pools and
handles unclaimed-deposit treasury accounting. Yano can complete live cleanup
and exact registered refunds through its existing orchestration seam.

### Correct the entire public AdaPot deposit model here

Rejected. That model spans stake, pool, DRep and governance proposal deposits
and is already tracked by issue #49. This ADR preserves/refunds the exact
per-pool lifecycle deposit without prematurely defining an incomplete aggregate.

## Consequences

### Positive

- Live pool and delegation state matches the Haskell ledger.
- Same-block certificate order becomes deterministic and correct.
- Scalus validation no longer receives retired pools or delegations as current.
- Re-registration behavior is correct before and after effective retirement.
- Boundary memory remains bounded and rollback-v1 remains the only journal.
- Historical facts remain available independently from live state.

### Negative

- A retirement boundary performs an additional sequential pool-delegation scan.
- POOLREAP recovery gains a small phase-specific progress state machine.
- Existing preview account chainstates, including the current mainnet tip and
  retained checkpoint ladder, require a clean multi-day resync and rebuilt
  checkpoints.
- Full public deposit-pot parity remains open under issue #49.

### Risks and mitigations

- **Risk:** partial POOLREAP is mistaken for completion.
  **Mitigation:** progress-marker-aware phase classification and fault injection
  at every chunk boundary.
- **Risk:** cleanup changes the snapshot at the retirement boundary.
  **Mitigation:** retain SNAP-before-POOLREAP order and compare the pre-change
  snapshot digest.
- **Risk:** a refund is repeated after restart.
  **Mitigation:** refund and cursor/delta sequence commit atomically, followed by
  exact-once recovery tests.
- **Risk:** re-registration restores stale delegations.
  **Mitigation:** effective POOLREAP deletes the forward rows; fresh lifecycle
  registration does not synthesize them.
- **Risk:** the sequential scan breaches the boundary-time target.
  **Mitigation:** cache-disabled ordered iteration, measurement and a separately
  reviewed reverse-index fallback only if required.
- **Risk:** an existing chainstate is silently left partially corrected.
  **Mitigation:** fail-closed readiness marker and explicit resync.

## Review decisions requested

Reviewers should explicitly confirm or reject these three deliberate choices:

1. clean preview resync rather than an ordered-history migration;
2. a bounded credential-major delegation scan before adding a reverse index;
3. exact per-pool deposit/refund correctness here while leaving the aggregate
   AdaPot deposit API decision to issue #49.
