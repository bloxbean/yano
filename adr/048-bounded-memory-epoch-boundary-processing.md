# ADR-048: Bound Memory During Epoch Boundary Processing

## Status

Accepted; implemented and mainnet sync validated through tip

## Date

2026-08-28

## Related decisions and evidence

- [ADR-024](024-genesis-staking-bootstrap-via-genesis-event.md) defines the
  genesis staking state that later snapshots and rewards consume.
- [ADR-025](025-bootstrap-partial-state-ledger-and-nonce-boundaries.md) defines
  bootstrap and nonce boundary behavior that must remain restart-safe.
- [ADR-039](in-progress/039-canonical-projection-outbox.md) defines durable
  epoch-artifact capture and the fail-closed projection model.
- [ADR-043](043-shelley-only-epoch-artifact-staging.md) gates epoch artifacts on
  a complete Shelley-or-later source epoch.
- [ADR-044](044-selective-epoch-artifact-projection.md) and
  [ADR-045](045-epoch-artifact-gaps-and-resumable-capture.md) define epoch
  artifact selection, completeness, gaps and recovery.
- [ADR-046](046-archive-aware-epoch-artifact-apis.md) defines the public reads
  that consume completed epoch generations.
- The source-verified findings and amendment evidence are recorded in the
  [ADR-048 review](reports/adr-048-review-2026-08-28.md).
- GraalVM documents that Native Image G1 chooses a maximum heap relative to
  physical memory unless `-Xmx` is supplied. This explains why an allocation
  burst can produce a larger RSS on a larger machine, but it does not by itself
  prove a leak. See [Native Image memory management](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/MemoryManagement/)
  and [memory-footprint guidance](https://www.graalvm.org/dev/reference-manual/native-image/guides/optimize-memory-footprint/).

## Decision summary

Yano will replace epoch-boundary whole-state materialization with ordered,
bounded streams while preserving the existing ledger order and outputs.

1. The mark snapshot and DRep distribution will read the existing complete
   `utxo_stake_balance` index through a consistent, ordered cursor. At a
   pre-Conway boundary, the maintained `utxo_pointer` index contributes the
   small set of resolved pointer-address balances that the stake index
   deliberately excludes. If that derived index is not proven ready, the
   serial pointer-aware UTXO scan remains the fail-closed fallback. No path
   materializes non-pointer UTXO balances into a network-sized map.
2. Whenever the index is disabled, filtered, incomplete or unavailable, Yano
   retains the pointer-aware full-UTXO compatibility fallback. It runs serially
   and without populating the RocksDB block cache.
3. Historical reward calculation will gain a pool-major snapshot projection and
   a streaming calculator/consumer contract. It will retain only one pool's
   delegators and reward outputs at a time.
4. The clean chainstate uses the first explicit boundary journal format (`v1`)
   from genesis. Reward and snapshot populations on mainnet are already large
   enough that their key/value plus reverse-journal lower bound exceeds the
   50 MiB component budget, so the clean format uses bounded chunks and
   resumable rollback-v1 from the outset. There is no second atomic boundary
   mode to maintain in preview software. Readers never treat a partial
   generation or boundary as complete.
5. Phase 0 will separate Java/native-image heap growth from RocksDB native
   memory by independently pinning heap size and RocksDB background concurrency.
   If RocksDB is a material or host-size-dependent contributor, shared cache,
   memtable and background-job budgets land before the algorithmic phases. Final
   budgets are retuned after the allocation live set is reduced. A heap limit is
   a guardrail, not a substitute for bounded algorithms.
6. The legacy paths will remain available during differential validation. The
   new paths become defaults only after byte-for-byte ledger parity, crash and
   rollback recovery, mainnet-scale memory, and throughput gates pass.

The long-term optimization target remains a native mainnet epoch-transition
process footprint at or below 600 MiB. For the current rollout, the
operator-approved target is at most 1.5 GiB with a 2 GiB ceiling. The choice of
raw process RSS or macOS physical footprint as the formal cross-platform
acceptance metric remains an operator decision; both are reported so the
decision does not change or hide the evidence. Correctness is the hard gate:
an otherwise attractive memory result is rejected if it causes an allocation
failure, incomplete rollback, AdaPot mismatch, archive divergence or regular-
sync instability.

The operator-approved epoch-boundary timing target is under 60 seconds, with
120 seconds the maximum acceptable boundary time on the documented reference
machine. The legacy comparison remains valuable diagnostic evidence, but the
earlier relative `<= 5%` gate is superseded by these absolute limits.

### Implementation evidence — 2026-08-29

The reference run uses the internal Apple NVMe, Oracle GraalVM 25.3.4.1 Native
Image with G1, the same retained mainnet checkpoint, streaming rewards, the
ordered stake-balance index and fail-fast AdaPot verification.

| Heap cap | Epoch | Reward | Complete boundary | AdaPot | Result |
|---:|---:|---:|---:|:---:|---|
| 1536 MiB | 296 | 37.017 s | 42.243 s | pass | optimized MultiGet path |
| 1536 MiB | 297 | 35.275 s | 40.523 s | pass | optimized MultiGet path |
| 1536 MiB | 298 | 33.186 s | 38.615 s | pass | optimized MultiGet path |
| 1280 MiB | 299 | 37.975 s | 44.695 s | pass | provisional only |
| 1280 MiB | 300 | 38.063 s | 43.516 s | pass | provisional only |
| 1280 MiB | 301 | 33.476 s | 49.977 s | pass | included 11.050 s MIR credit |
| 1280 MiB | 302 | 36.253 s | 41.829 s | pass | no spendable MIR rows |

The 1536 MiB run peaked at 2.105 GiB RSS. The 1280 MiB run peaked at
1.882 GiB RSS, but it is rejected: after four correct boundaries it exhausted
the G1 heap during ordinary application of block 6,498,621. Restarting the same
chainstate at 1536 MiB passed integrity validation, restored nonce state at
block 6,498,620, completed exact-point UTXO/account rollback and resumed sync.
Therefore the smallest proven heap cap remains 1536 MiB and the 1.5 GiB RSS
target and 2 GiB rollout ceiling are both still open at that cap; correctness
takes precedence over the nominally lower RSS of the failed run. Correctness and
boundary-time gates are met. The next evidence-gated memory investigation is the
ordinary ledger-apply queue: queued work retains decoded block graphs while its
current byte budget accounts only raw CBOR size. Queue behavior and defaults
must not change until a heap dump or equivalent profile confirms that retained
graph as a material contributor.

### Final mainnet acceptance evidence — 2026-08-30

The final accepted artifact was built from `008b7ecd` with Oracle GraalVM
25.3.4.1, Java 25.0.4.1 and G1 GC. Its SHA-256 is
`7f2cb1b3e45d9c8841c67e59df08b7091df2129e71cf4160ab58d9ca79f1b411`.
The authoritative internal-NVMe run used `-Xmx1024m`, a 64 MiB decoded apply
queue, two RocksDB background jobs and `exit-on-epoch-calc-error=true`.

The run reached mainnet steady state at epoch 652 with `inSync=true`, a zero
block gap, a RUNNING peer and no degraded runtime state. The embedded verifier
reported 326 consecutive AdaPot passes for epochs 296 through 621 with no
failure. The post-commit Koios monitor compared every epoch from 613 through
652: 40/40 exact treasury/reserves matches with no gaps. Those validated Koios
values now extend the embedded mainnet fixture through epoch 652 for future
fail-fast runs.

For the final ten boundaries, total boundary time was 46.1–55.4 seconds,
streaming rewards were 34.9–44.5 seconds, and the credential-ordered DRep pass
was 1.48–1.60 seconds. Peak boundary heap was 724 MiB. The macOS physical
footprint was normally 0.98–1.1 GiB and peaked at 1.5 GiB. One transient raw-RSS
sample reached 2.007 GiB while physical footprint remained 1.1 GiB; it fell on
the next sample and did not coincide with swap, OOM, degraded state or peer
failure. The accepted log contains no AdaPot mismatch, OOM, fatal error,
stale-peer disconnect, no-progress recovery, missing block body or unrepaired
nonce state.

The pointer-index differential gate passed across retained pre-Conway clones,
including rebuild and scan-shadow comparisons. The ordered DRep merge produced
the same 866/866 per-DRep rows as the point-lookup baseline on the same Conway
state before final rollout. Trial-only oracle removal and the independently tracked
DBSync DRep-lifecycle discrepancy remain follow-up work in issues #100 and #99;
neither changes the accepted AdaPot or same-state mechanism parity evidence.
A deliberate live Conway crash-after-SNAP drill was performed on a disposable
epoch-532 clone on 2026-08-30. The process was killed after the durable snapshot
write and before COMPLETE. Restart resumed at step 3 without repeating rewards
or snapshot, completed with exact AdaPot, and took 2.5 seconds. The accepted tip
store was not disturbed; the drill and its evidence are recorded in the ADR-048
[acceptance note on issue #97](https://github.com/bloxbean/yano/issues/97#issuecomment-5465411499)
and the [PR review record](https://github.com/bloxbean/yano/pull/101#issuecomment-5466928345).

### Optional low-memory resource profile

`yano.resource-profile=low-memory` is an explicit, opt-in performance profile;
there is no host-memory auto-detection. It changes these defaults:

| Setting | Default | Low-memory |
|---|---:|---:|
| RocksDB block cache | 32 MiB | 16 MiB |
| RocksDB write-buffer manager | 64 MiB | 32 MiB, stall allowed |
| RocksDB background jobs | 2 | 1 |
| RocksDB open files | 256 | 128 |
| RocksDB target file size | 64 MiB | 128 MiB |
| decoded ledger-apply queue | 64 MiB | 32 MiB |
| body-fetch range | 5,000 blocks | 1,000 blocks |

Index and filter blocks remain inside the shared cache. The low-memory profile
also enables memory-optimized filters and pins the L0/top-level index within
that bounded cache. Every individual property overrides the profile default,
and startup logs the effective values. The profile does not set Java or native
image heap size; launcher caps, a Serial-GC experiment, and Linux 2 GiB live
acceptance are deliberately separate follow-up validation items.

The epoch status endpoint exposes the last boundary's per-phase wall/process/
thread times, path labels, heap, RSS, RocksDB cache/memtable/table-reader/pending-
compaction values and SST count. When native-image management beans do not
provide GC counters, it reports `gcMetricsAvailable=false` and retains heap
before/after/peak values rather than treating `-1` counters as measurements.

### Preview chainstate compatibility

Yano is still preview software and its current chainstate format is not a stable
operator contract. This ADR chooses a clean format break rather than carrying a
mixed unversioned/v1 boundary journal and coordinate migration indefinitely.

The accepted implementation introduces an `epoch-boundary-state-v1` format
marker. A new/empty store initializes it before applying blocks. A non-empty
store without that marker is rejected at startup with a clear backup-and-resync
instruction; it is never opened read/write and never silently migrated during
normal startup. Existing chainstate may be retained read-only for differential
fixtures, but running the accepted ADR-048 format requires syncing a new store
from genesis. This permission removes the compatibility decoder and makes the
clean journal format `v1`, not `v2`.

The marker is a fail-closed format check, not an automatic migration system.
The current preview format has only version `v1`: unsupported marker values are
rejected, and startup has no speculative `v2` constants, promotion branches or
compatibility decoder. If a future change genuinely requires a new chainstate
format, that change must make an explicit migrate-versus-resync decision and add
only the transition logic it needs. Stake-balance and pointer-index readiness
markers remain independent integrity evidence; their startup checks do not
modify the epoch-boundary format marker.

## Context

Normal synchronization has a comparatively small live set. At a mainnet epoch
boundary, observed process memory rises from approximately 400 MiB to 1.5–2 GiB
for the JVM build. Native executables have been observed at approximately
6–8 GiB on machines with more RAM. The native peak scales with host memory.

That observation does not identify a single owner. It is consistent with Native
Image G1 allowing the heap to grow as a percentage of physical RAM during a
large temporary allocation burst. It is also consistent with RocksDB native
memory and compaction concurrency scaling with the machine. The current
`DirectRocksDBChainState` enables `setIncreaseParallelism(availableProcessors)`
when tuning is enabled, creates table configurations without a shared explicit
block-cache budget, and does not set database/column-family write-buffer limits.
The hot UTXO configurations also use partitioned filters and pinned L0 filter
and index blocks without an explicit shared cache policy. The resulting
cache/index/filter, memtable and background-job memory is not presently governed
by one Yano process budget.

The current epoch path creates several mainnet-sized object graphs at once and
then builds large native RocksDB batches while those graphs remain reachable.
This ADR is based on source-level allocation and lifetime analysis. Phase 0 will
measure each contribution independently before an optimization is accepted.

### Current ordering

`BodyFetchManager.publishEpochTransitionEventsIfNeeded` publishes pre-, epoch-
and post-transition events synchronously when it sees the first block belonging
to a new epoch. The previous block has already completed canonical application;
the first new-epoch block has not yet been applied.

`DefaultAccountStateStore` delegates the substantive work to
`EpochBoundaryProcessor`, whose current order is:

```text
effective protocol parameters
  -> rewards and AdaPot
  -> mark stake snapshot
  -> pool reaping
  -> Conway/PV10 DRep reverse-index maintenance
  -> governance ratification/enactment and DRep distribution
  -> final AdaPot and epoch artifacts
  -> boundary complete marker
```

This order is ledger-significant and is not changed by this ADR. In particular,
SNAP remains before POOLREAP and governance, and the reward/AdaPot phase remains
before SNAP.

### Allocation and retention analysis

| Area | Current behavior | Peak-memory effect |
|---|---|---|
| Current UTXO stake | `UtxoBalanceAggregator` decodes every unspent output and address into a `HashMap<CredentialKey, BigInteger>`. Its own comment estimates 30–60 seconds on a mainnet SSD. | A credential-sized Java map is built concurrently with rewards, then retained through snapshot and governance. Address decoding creates high allocation churn. |
| Historical reward input | `EpochRewardCalculator.getStakeSnapshot` loads all N-4 snapshot rows into a map. `buildPoolStates` groups them into per-pool lists and copies each list into a set. | The same delegator population is represented by snapshot records, map entries, lists, `Delegator` objects and sets at the same time. |
| Reward eligibility | The reward path materializes every registered credential and scans time-major stake events into maps and sets used by the reward library. | Several network-wide credential sets overlap the snapshot and pool object graphs. |
| Reward result | `cf-rewards-calculation` 1.0.2 constructs member reward objects, including zero-valued results, and retains per-pool result sets until the complete epoch result is released. Yano ignores non-positive rewards when crediting accounts. | The full input and full output graphs overlap. |
| Reward persistence | One reward phase holds a native `WriteBatch`, a Java `List<DeltaOp>`, a `BatchStateOverlay`, copied key/value arrays and finally one contiguous encoded boundary-delta byte array. | The logical mutation is represented several times just before commit, creating a sharp heap and native-memory peak. |
| Stake snapshot persistence | One native `WriteBatch` contains every snapshot row. The snapshot path also builds a full latest-deregistration map by scanning stake events. | A large native batch overlaps the current UTXO map. The existing credential-major deregistration coordinate index is not used. |
| Governance | DRep distribution receives and retains the complete current UTXO balance map while it iterates DRep delegations and reads account rewards/deposits. | Governance extends the current UTXO map lifetime even though its final DRep and pool distributions are much smaller. |
| RocksDB process budget | Tuned startup derives parallelism from host cores but does not configure a shared block-cache ceiling or aggregate write-buffer/memtable ceiling. Hot UTXO table configurations enable partitioned filters and pinned L0 filter/index blocks. | Native resident memory can vary with column-family/SST state, write pressure and host core count. Phase 0 must distinguish this from managed-heap growth rather than treating either diagnosis as established. |
| Other phases | Parameters, AdaPot arithmetic, proposal lifecycle and pool reaping operate on smaller populations. | They are not expected to be primary peak drivers, but Phase 0 will still measure them. |

The parallel UTXO scan currently hides some wall time behind reward calculation,
but it deliberately overlaps the two largest temporary populations. Merely
serializing that same scan would reduce peak memory while potentially adding its
full 30–60 second runtime to the boundary. It is an emergency fallback, not the
end state.

### The existing stake-balance index

The runtime already maintains a `utxo_stake_balance` column family keyed by:

```text
[credential type: 1 byte][credential hash: 28 bytes] -> CBOR BigInteger
```

The shipped configuration enables it by default:

```yaml
yano:
  account:
    stake-balance-index-enabled: true
```

Enabled does not mean ready. `UtxoState.isStakeBalanceIndexReady()` is true only
when all of the following are true:

- the UTXO store and stake-balance feature are enabled;
- a stake-balance column family and readiness marker exist;
- the `epoch-boundary-state-v1` and stake-index schema versions match; and
- the UTXO source is complete and unfiltered.

An empty unfiltered store is marked ready. Enabling a UTXO storage filter clears
readiness because a complete network-wide aggregate can no longer be proven.
The index is updated atomically with normal UTXO writes. This ADR does not add a
regular-sync indexing cost; it consumes an index that is already maintained.

At the E to E+1 transition, synchronous event ordering means the live index is
exactly the UTXO stake state after the final applied block of E and before the
first applied block of E+1. It is therefore available at mark-snapshot time.

The existing index deliberately excludes pointer stake references. Conway and
later ignore pointer-address stake for this purpose, so the index is sufficient
by itself for current mainnet Conway boundaries. Pre-Conway snapshots still
resolve effective pointer credentials. They use the ordered index for all base
and script stake credentials and overlay only balances from pointer-address
UTXOs discovered by a serial, cache-bypassing pass. The pointer overlay is
normally tiny; if its resolver is unavailable, the boundary fails closed.

## Decision drivers

- Keep native mainnet epoch-transition RSS within 500–600 MiB rather than a
  host-RAM-dependent multi-gigabyte heap.
- Preserve byte-identical reward balances, epoch stake, AdaPot, DRep
  distribution, proposal outcomes and archive facts.
- Preserve synchronous boundary order, restart, exact-point rollback and
  projection completeness.
- Avoid converting the memory fix into a large epoch processing delay.
- Avoid measurable regular-sync overhead; the current stake index is already in
  the normal apply batch.
- Prefer sequential RocksDB access and bounded native batches over millions of
  point lookups or network-sized Java maps.
- Roll out in independently measurable phases with a safe legacy fallback.

## Constraints and invariants

1. Ledger output is authoritative. Performance or memory pressure must never
   turn a failed reward, snapshot or governance phase into a skipped phase.
2. The first new-epoch block must not be published as applied until the complete
   epoch transition succeeds, matching current synchronous behavior.
3. Reward, SNAP, POOLREAP and governance order must not change.
4. The current-epoch stake index may be used only when its readiness and source
   completeness are proven. Before Conway, its pointer-excluding semantics must
   be completed by a coordinate-bound, resolver-backed pointer-only overlay.
5. Index absence must be explicit. An absent index row means zero only inside a
   proven-complete index view; it must not mean zero when the index is unready.
6. Snapshot rows include zero-balance registered delegators because pool owners
   with zero active stake can still affect reward inputs.
7. Registration, re-registration, deregistration, stale delegation, pool
   retirement and pool re-registration rules must remain exact at transaction
   and certificate coordinate granularity.
8. A partial chunked boundary must never be exposed as a complete state. On
   restart it must resume or roll back deterministically before ordinary reads
   and block application continue.
9. A snapshot or DRep generation is readable and projectable only after its
   durable completion marker exists.
10. Epoch artifacts retain the identity, digest, gap and lease semantics defined
    by ADR-039 and ADR-043 through ADR-046.
11. Boundary rollback applies phases in reverse ledger order and chunks within a
    phase in descending sequence order.
12. The old full-scan and legacy reward paths remain available until the new
    paths pass differential and live-network gates.
13. No acceptance claim may be based only on Java heap. RSS, native batch memory,
    RocksDB cache/memtable memory, thread stacks and mapped/code pages must be
    included.
14. A live stake-balance view must prove, not merely log, the exact canonical
    UTXO coordinate represented by the view. A mismatch or unverifiable
    coordinate fails closed before boundary mutation; it never falls back to a
    different live source and guesses an as-of view.
15. Each ledger phase opens its input view only after prerequisite phase commits.
    A RocksDB snapshot must not span the whole boundary because SNAP must observe
    reward credits and governance must observe the ledger-defined post-SNAP and
    post-POOLREAP state.
16. When the conditional chunk-journal trigger is met, the bounded-memory
    invariant applies to forward processing and rollback-v1.
    Rollback may not collect all phase blobs or all reverse operations in one
    Java map or native `WriteBatch`.
17. A non-empty chainstate without `epoch-boundary-state-v1` is incompatible and
    fails startup before mutation. There is no mixed legacy/v1 write or rollback
    mode.

## Decision

### 1. Add phase-level memory and time observability before changing behavior

Instrument every boundary with a stable boundary identifier and the following
phases:

```text
params
mir-credit
reward-input
reward-calculate
reward-distribute
reward-commit
snapshot-input
snapshot-write
pool-reap
pv10-drep-reverse-index
governance-input
governance-ratify
governance-enact
governance-distribution
artifact-finalize
complete
```

At phase entry, phase exit and periodic chunk checkpoints record:

- wall and CPU time;
- JVM/native-image used and committed heap where available;
- process RSS and peak RSS;
- GC count and pause time;
- RocksDB block-cache usage, pinned usage, memtable usage and pending compaction
  bytes;
- active batch operation count and estimated encoded bytes;
- delta operation count, previous-value bytes and encoded journal bytes;
- input, emitted, skipped and zero-value row counts; and
- selected path and reason: `stake-index`, `utxo-scan`, `legacy-reward` or
  `streaming-reward`.

Metrics use phase and path labels, not epoch or credential labels. Logs contain
the epoch, boundary slot and canonical block coordinate for correlation. A
single summary log reports phase peaks and the process peak at completion.

This phase also adds a deterministic output digest for validation. It hashes
ordered logical facts, not RocksDB file bytes:

- reward facts and final account reward balances;
- snapshot `(epoch, credential, pool, amount)` rows;
- AdaPot fields;
- DRep and pool distributions;
- governance decisions/refunds; and
- archive staged facts before representation-specific framing.

The digest is diagnostic and does not become consensus state.

### 2. Expose a consistent ordered stake-balance view

Extend `UtxoState` with a storage-neutral cursor rather than exposing RocksDB
types or a materialized map. The final naming may follow existing core API
conventions, but the semantic contract is:

```java
record StakeCredentialBalance(
        int credentialType,
        byte[] credentialHash,
        BigInteger lovelace
) {}

interface StakeBalanceView extends AutoCloseable {
    CanonicalBlockReference coordinate();
    boolean advance();
    StakeCredentialBalance current();
}

Optional<StakeBalanceView> openStakeBalanceView(
        CanonicalBlockReference expectedCoordinate
);
```

The view contract requires:

- strict unsigned byte ordering by credential type and 28-byte hash;
- no zero-valued stored rows;
- one logical row live at a time;
- a consistent RocksDB snapshot for the view lifetime;
- an exact canonical block number, slot and hash captured from the same RocksDB
  snapshot as the index rows;
- `ReadOptions.fillCache(false)` for the sequential boundary walk;
- an empty result only when the complete index genuinely has no rows; and
- `Optional.empty()` when disabled, filtered, unready, unsupported or unable to
  establish a consistent view.

`isStakeBalanceIndexReady()` remains the cheap status method. Opening the view
rechecks readiness and source completeness so a caller cannot observe a stale
positive status across a rebuild or configuration transition.

The current UTXO metadata stores only last-applied block number and slot. Phase 1
first adds `META_LAST_APPLIED_HASH` and writes block number, slot and hash in the
same UTXO apply batch as UTXO mutations, delta/cursor state and stake-balance
deltas. UTXO rollback restores or deletes all three coordinate fields together.
The `epoch-boundary-state-v1` marker is not created on a populated pre-hash
store; by the preview-chainstate policy above, that store must be resynced rather
than entering a weaker scan mode.

Opening the view compares
the coordinate captured inside the RocksDB snapshot with the boundary
processor's expected final applied coordinate before E+1. Missing coordinate
metadata, block-number/slot/hash mismatch, or advancement during view creation
throws a typed consistency failure before boundary mutation. `auto` mode does
not turn that failure into a scan fallback: the live UTXO CF and live aggregate
index have no historical slot dimension and cannot reconstruct spent outputs as
of an earlier coordinate after a concurrent writer has advanced.

The current synchronous writer exclusion should make the check pass. The
fail-closed assertion makes that guarantee executable and prevents a later
fast-sync or asynchronous-boundary refactor from silently weakening it.

The pointer-aware full-scan fallback has the same coordinate requirement. Its
creation-slot filter alone is insufficient after a concurrent writer advances,
because outputs spent after the requested slot are already absent from the live
UTXO CF. The fallback therefore captures and validates the exact canonical
coordinate from its RocksDB snapshot and fails closed on mismatch.

### 3. Stream the mark snapshot from ordered indexes

For Conway-or-later and a ready stake-balance view, replace the
`Map<CredentialKey, BigInteger>` with an ordered merge over:

- pool delegation rows, ordered by credential;
- current stake accounts/reward balances, ordered by credential;
- current registration coordinates, ordered by credential;
- the snapshot-validity latest-deregistration index, ordered by credential; and
- the UTXO stake-balance view, ordered by credential.

This is not a speculative key-layout dependency. `PREFIX_POOL_DELEG`,
`PREFIX_DREP_DELEG`, `PREFIX_ACCT`, `PREFIX_ACCT_REG_SLOT` and a new
`PREFIX_ACCT_LAST_DEREG_COORD` all use the same
`[credential type: 1][credential hash: 28]` suffix as
`utxo_stake_balance`. The implementation must centralize comparison/suffix
decoding and add layout-contract tests so a future key migration cannot silently
break the merge.

SNAP opens a phase-scoped read view after the reward phase has committed. That
view covers the stake-balance and account-state merge inputs at one stable
boundary coordinate, or is protected by an equivalent writer-exclusion token
whose validity is checked on close. It must see committed reward credits. It is
closed before POOLREAP. Governance opens a new phase-scoped view after SNAP and
POOLREAP so its account reads see those prior commits. No read snapshot is kept
across the entire epoch transition.

Retired pools and current pool registration coordinates may remain in bounded
pool-sized maps. They are much smaller than the credential population and are
needed repeatedly during the merge.

For each pool delegation, the merge performs the existing checks in the same
order:

1. the stake account is currently registered;
2. the pool is not retired for the snapshot epoch;
3. the delegation is not older than the pool's current registration;
4. a later deregistration did not invalidate the delegation;
5. the delegation is not older than the credential's current registration; and
6. stake equals indexed UTXO lovelace plus withdrawable reward balance.

An absent balance row in a complete view contributes zero. A zero total is still
written for an otherwise eligible delegator.

`PREFIX_ACCT_DEREG_COORD` is not used as the SNAP completeness source. It is a
pointer-resolution history index: upgraded stores may not have genesis coverage,
and `cleanupPointerIndex` is allowed to delete its entries. Treating it as
complete could accept stale pool delegations on an upgraded or cleaned store.

Add a separate derived latest-deregistration index:

```text
PREFIX_ACCT_LAST_DEREG_COORD /
    [credential type: 1][credential hash: 28]
        -> [slot: 8][transaction index: 2][certificate index: 2]
```

It is updated in the same apply/rollback batch as every authoritative
`PREFIX_STAKE_EVENT` deregistration. It has a versioned completeness marker and
is initialized from genesis in `epoch-boundary-state-v1`. A bounded repair tool
may rebuild it from the authoritative time-major event log by point-updating one
latest coordinate at a time and publishing readiness only after the complete
scan; a missing/unready marker forces the legacy event-scan SNAP path or strict
mode failure. `cleanupPointerIndex` neither touches this namespace nor its
marker.

`PREFIX_ACCT_LAST_DEREG_COORD` and `PREFIX_ACCT_REG_SLOT` replace the snapshot
path's network-wide stake-event map only when their readiness contracts pass.
This substitution is limited to current delegation validity, for which the
latest coordinate is the required fact. It does not replace reward eligibility's
historical event semantics. Missing `PREFIX_ACCT_REG_SLOT` remains
backward-compatible/null-tolerant exactly as in the current snapshot logic; it
does not by itself reject an otherwise complete view.

#### Canonical address and credential extraction

Index/scan equivalence also requires one credential extractor. Today the full
scan accepts a Shelley address stored as hexadecimal bytes when direct string
parsing fails, while `DefaultUtxoStore` and `StakeBalanceIndexRebuilder` use a
separate direct-string parser. The implementations also carry separate address
type-to-credential-type switches. A UTXO accepted only by the scan's hex path
can therefore be omitted from the index without making the index unready.

Before the index becomes an epoch input, move address-representation parsing and
stake-credential classification to one shared implementation used by:

- incremental stake-index writes;
- stake-index startup rebuild;
- the full-UTXO differential/fallback and pointer-overlay scans; and
- test or migration oracles.

The shared extractor distinguishes parsing/classification from era policy.
Pointer resolution remains available only to the pre-Conway full scan and
pointer-only overlay; the current index continues to exclude pointers. Byron,
enterprise and reward addresses remain non-contributors. Invalid Shelley
payment-address representations fail the rebuild/boundary instead of being
silently dropped, while known Byron representations remain explicitly
skippable.

Changing the extractor increments the stake-index schema/readiness version and
invalidates any mismatched ready marker. In the accepted clean v1 chainstate it
is used from genesis; a bounded repair rebuild may republish readiness after
verification, while a pre-ADR populated store is rejected and resynced.

#### Conditional snapshot generation and chunking

Every new-format snapshot carries completion evidence. If Section 6 is deferred,
one atomic batch writes all rows, the `COMPLETE` marker,
`META_LAST_SNAPSHOT_EPOCH` and the artifact reference, preserving today's
visibility boundary. If Section 6's measured trigger is met, rows instead use a
resumable generation state:

```text
BUILDING(epoch, boundary coordinate, format version)
  -> chunk 0
  -> chunk 1
  -> ...
  -> COMPLETE(epoch, row count, logical digest, boundary coordinate)
```

Each chunk is bounded by both operation count and estimated encoded bytes. The
initial tuning values are 10,000 operations or 4 MiB of key/value payload,
whichever is reached first. They are hypotheses to validate, not permanent
consensus constants.

The credential-major snapshot remains the authoritative compatibility layout.
Readers, pruning and archive contribution require the `COMPLETE` marker. A
crash with `BUILDING` either resumes at the last durable cursor or deletes and
rebuilds that incomplete generation before block application continues. It is
never interpreted as an empty or complete epoch.

Today the epoch-stake artifact reference and `META_LAST_SNAPSHOT_EPOCH` are in
the same batch as all snapshot rows, and the archive writer commits only after
that batch succeeds. Chunking moves this publication boundary to generation
completion. Row chunks publish no artifact reference and do not advance
`META_LAST_SNAPSHOT_EPOCH`. The finalization sequence is:

1. finish and fsync/finalize the staged archive facts with recoverable evidence;
2. in one RocksDB commit, write `COMPLETE`, row count/digest,
   `META_LAST_SNAPSHOT_EPOCH` and the ADR-039 epoch-stake artifact reference; and
3. idempotently complete/acknowledge the archive writer if its storage contract
   requires a post-RocksDB action.

Restart reconciles the two durable evidence sources under ADR-039/045 rules. A
`BUILDING` generation is reported as pending/incomplete to ADR-045 gap logic,
never as a gap, empty generation or publishable artifact. Reward archive facts
follow the same rule: chunks may stage facts, but the phase completion commit is
the only publication point.

Snapshot rows are derived generations, not account mutations. The current
rollback path has no `PHASE_SNAPSHOT`; it deletes snapshot epochs at or beyond
the rolled-back boundary and repairs `META_LAST_SNAPSHOT_EPOCH`. Both persistence
modes preserve that delete-and-rebuild semantic rather than writing reverse
deltas for every snapshot row. Generation keys occupy explicit epoch ranges. If
journal-v1 is enabled, rollback removes each `BUILDING` or `COMPLETE`
credential-major and pool-major generation with a range tombstone when
supported, or bounded delete chunks otherwise, then repairs the last-complete
marker through the resumable state machine. If Section 6 is deferred, the
existing atomic rollback remains and must pass its no-regression memory gate.

### 4. Stream DRep distribution from the same live index

Governance opens a new consistent stake-balance view after SNAP and POOLREAP.
The UTXO component has not changed during those phases. Account reward/deposit
components are read from the authoritative account state at the ledger-defined
governance point.

This independent view also fixes an existing crash-resume defect. Today, a
restart after `STEP_SNAPSHOT` skips rebuilding `utxoBalances` and passes `null`
to DRep distribution. Its fallback reads snapshot `amount` (already UTXO plus
reward), adds account reward again, and has no UTXO amount for DRep-only
delegators absent from the pool snapshot. The new governance phase never uses
that fallback: after restart it opens the same coordinate-bound live stake view
as a no-crash run. DRep distribution after crash-resume must therefore equal the
new path's no-crash result.

Use an ordered merge between DRep delegation rows and UTXO stake balances. The
calculator adds the same reward balance, reward-rest and proposal deposit/refund
components as the current implementation. It emits aggregate DRep totals
directly; it does not construct or retain a credential balance map.

The merge must preserve virtual DRep handling, effective expiry, dormant epochs,
active/inactive status and proposal-voting inputs. The final DRep and pool maps
may remain in memory because their cardinality is bounded by representatives and
pools rather than stake credentials. If measurement disproves that assumption,
they receive the same generation/chunk treatment in a later phase.

Before Conway, the ordered path merges its base/script balances with a
pointer-only overlay from the same boundary coordinate. If any boundary cannot
open a complete index view, `auto` mode logs a reason and uses the pointer-aware
full scan. Strict validation mode fails before SNAP instead of silently falling
back, allowing operators to prove that a run exercised the bounded path.

### 5. Remove avoidable reward graph duplication

This phase is split so low-risk lifetime reductions ship before the Yano-owned
pool-at-a-time orchestrator.

#### 5a. Shorten lifetimes on the current calculator

- Build final collection types directly and pre-size them from known counts;
  remove list-to-set copies in `buildPoolStates`.
- Canonicalize decoded pool hashes in a boundary-local map so approximately one
  string exists per pool, not one duplicate string per snapshot row. Do not use
  the JVM-global `String.intern()` pool.
- Stop materializing the complete credential-major snapshot map before
  `buildPoolStates`. Accumulate total active stake and group one pass directly
  from the snapshot iterator; perform a second cache-bypassing iterator pass for
  the rare account-set fallback if needed.
- Release each pool's member reward set immediately after all positive rewards
  have been credited and staged. Do not retain zero-valued member reward objects
  after their existing non-observable status has been verified by differential
  tests.
- Return a compact scalar summary after distribution rather than keeping the
  complete `EpochCalculationResult` reachable through AdaPot persistence.
- Replace `getAllRegisteredCredentials()` materialization with ordered
  membership iteration or narrowly scoped point membership where required.
- Keep historical deregistration semantics exact. The current time-major event
  scan starts at slot zero intentionally; it must not be replaced by only the
  latest deregistration coordinate. Phase 5b introduces a compact historical
  summary/index only after parity proves registration-cycle behavior.

These changes do not alter reward mathematics or crash visibility.

#### 5b. Add a pool-major historical snapshot projection

Keep the existing credential-major epoch snapshot for public APIs and
compatibility. Add a derived layout optimized for reward calculation:

```text
[epoch: 4][pool hash: 28][credential type: 1][credential hash: 28]
    -> active stake
```

It is written in the same logical snapshot generation and carries its own
format version and completion evidence. For retained epochs created before the
upgrade, a bounded startup/background migration derives the pool-major rows from
the immutable credential-major generation. Streaming rewards are not enabled
until every reward-required generation is complete. The migration never needs
to decode live UTXOs.

This layout permits one pool's delegators to be read sequentially and discarded
before the next pool. It also permits total active stake and per-pool active
stake to be accumulated with scalar counters.

For reward account sets, add the minimum ordered historical summary needed to
reproduce current semantics. Its design must distinguish:

- current registration;
- last event before a cutoff;
- any registration before a cutoff for pool reward addresses;
- deregistration before and after the stability window; and
- deregistration/re-registration cycles.

A credential-major event key such as
`[credential][slot][tx index][certificate index] -> event type` is an acceptable
derived index because it supports streaming per-credential state. The exact
layout is selected after Phase 0 measures the current scan and disk estimate.
The existing time-major event log remains authoritative and supplies migration
and differential truth.

#### 5c. Add a streaming reward-calculation contract

Do not fork or require a public API change in
`cf-rewards-calculation`. Version 1.0.2 already exposes
`PoolRewardsCalculation.calculatePoolRewardInEpoch`. Yano owns the bounded outer
orchestrator, pins the dependency version, computes global scalar inputs once,
and invokes that public method one pool at a time. A conceptual Yano contract is:

```java
interface RewardSink {
    void leaderReward(RewardFact reward);
    void memberReward(RewardFact reward);
}

RewardPotSummary calculateEpochRewardPots(
        EpochRewardContext context,
        PoolStateCursor pools,
        RewardAccountSets accountSets,
        RewardSink sink
);
```

The implementation reproduces the epoch-level scalar accumulation around the
public per-pool call, then processes one pool and its ordered delegators at a
time. It emits positive ledger reward facts to a bounded sink and retains only
scalar totals needed for treasury, reserves, distributed, undistributed,
total-reward and pool-reward pots.

Parity must cover the library's exact outer semantics, including reward pot and
treasury cut, MIR adjustments, retired-pool deposits, unspendable and
undistributed treatment, reserves/treasury routing and Allegra bootstrap
adjustment. For each pool, the eligibility set is its reward address plus its
delegators, intersected with deregistered/late-deregistered sets exactly as the
epoch API does. Pool traversal follows the library's produced-block pool input
semantics and proves order cannot change facts or totals.

It must preserve exact integer/rational arithmetic and every era-specific rule.
The iteration order must not affect any result. If the current library relies on
unordered collection behavior, the replacement first defines a deterministic
order and proves the resulting facts and totals equal on every retained golden
epoch.

A design that runs the whole reward calculation twice to avoid retaining output
is rejected because it can add approximately 50–100% calculation time. The
selected design is one logical calculation with streaming output.

### 6. Chunk state mutation and rollback journals

Every `epoch-boundary-state-v1` store writes chunk-aware `delta-v1` entries and
uses the same progress and rollback protocol. This prevents old unversioned
entries or an alternative atomic-mode state machine from coexisting with bounded
v1 chunks.

The original conditional design used the following triggers:

- reward commit, snapshot write, governance write or rollback contributes more
  than 50 MiB incremental RSS above its input live set;
- the combined native batch, Java delta/overlay and encoded reverse journal for
  one phase exceeds its 50 MiB component budget;
- rollback across a newly written boundary exceeds the 600 MiB process target;
  or
- the overall 600 MiB target cannot be met and the residual is attributed to
  monolithic persistence.

The trigger is accepted before implementation from a conservative serialized
lower bound, not from an unmeasured RSS claim. Mainnet snapshot/reward phases
contain more than one million credential facts; key bytes plus value and reverse
journal metadata exceed 50 bytes per fact, already exceeding the 50 MiB
component budget before Java/native batch overhead. Because preview chainstates
resync into the clean v1 format, maintaining a second atomic implementation
would add recovery branches without providing a production compatibility
benefit. Phase 0 and mainnet acceptance still measure actual RSS and performance;
this source-level trigger does not waive those gates.

Replace the single phase-wide batch with a bounded writer that owns:

- a native `WriteBatch`;
- a bounded `List<DeltaOp>`;
- a bounded state overlay;
- the staged reward writer; and
- scalar phase totals and an ordered resume cursor.

The writer flushes at the lower of the configured operation and byte limits.
Each flush atomically commits mutations, the encoded reverse delta, the chunk
sequence and the resume cursor.

Journal-v1 keys are versioned and chunk-aware:

```text
delta-v1/[boundary slot][phase][chunk sequence] -> reverse operations
phase-v1/[boundary slot][phase] -> IN_PROGRESS | COMPLETE,
                                   next cursor,
                                   chunk count,
                                   logical digest,
                                   scalar totals
boundary-v1/[boundary slot] -> IN_PROGRESS | COMPLETE,
                               previous epoch,
                               new epoch,
                               canonical coordinate
rollback-v1/account-state -> IN_PROGRESS,
                             target coordinate,
                             next timeline slot/kind,
                             boundary phase/chunk cursor,
                             snapshot-generation cursor,
                             retained block/slot/hash candidate
```

The existing phase byte remains part of the key, but its numeric value does not
define ledger order. The current implementation uses the hand-maintained
`BOUNDARY_PHASE_REVERSE_ORDER` array. Journal v1 replaces that implicit
convention with a versioned phase descriptor table that declares forward and
reverse ledger order and the journal codec for every phase. Startup validation and a unit test
must prove that every persisted phase is registered exactly once. Adding a new
phase or codec without extending the descriptor table fails startup/tests rather
than silently changing rollback order. Snapshot generation cleanup is a
separate declared rollback step because snapshots do not currently have a
boundary-delta phase.

There is no mixed-format decoder. `epoch-boundary-state-v1` always stores
`delta-v1`; bounded mode adds the v1 progress markers and additional chunk
sequences. A pre-ADR non-empty store is rejected by the preview-chainstate
compatibility rule.

#### Recovery

- The initial synchronous commit stores `STEP_STARTED` together with the exact
  `(previous epoch, new epoch, boundary slot, boundary block number)` metadata.
  Startup restores this boundary context before resuming any phase; a missing or
  mismatched coordinate fails closed. The coordinate-bound stake view resolves
  the corresponding canonical block hash through the durable chain reader and
  still requires an exact number/slot/hash match.
- Before the first chunk, persist `boundary IN_PROGRESS` and
  `phase IN_PROGRESS`.
- After each chunk, the reverse delta and next cursor are durable in the same
  RocksDB write as the state mutations.
- On restart, reconcile artifact evidence and phase markers, then resume from
  the last cursor. If a calculator cannot resume internally, deterministic
  inputs may be replayed from the beginning while already committed ordered
  facts are skipped and checked against the stored digest. This is a crash-only
  cost, not the normal path.
- The final chunk atomically stores the phase scalar result and `COMPLETE`.
- The boundary becomes `COMPLETE` only after every ledger phase and required
  artifact is complete.
- Any ledger/API reader that could observe partial account or governance state
  checks boundary health. While recovery is required it fails closed or waits;
  it never reports an ordinary successful balance from a partial boundary.

#### Rollback

The current rollback is not used as the bounded v1 algorithm. It clones every
phase blob for a boundary slot into `Map<Byte, byte[]>`, decodes all operations
into one Java structure, and keeps block undo, boundary undo, snapshot deletion and
metadata repair in one native `WriteBatch` until the complete target is reached.
Forward chunking alone would increase the number of blobs without bounding that
peak.

Rollback-v1 is a fail-closed, resumable state machine:

1. Resolve and validate the exact rollback target, then durably write
   `rollback-v1 IN_PROGRESS` before the first state mutation. Ordinary account,
   epoch and governance reads and block application are unavailable while this
   marker exists.
2. Traverse block and boundary deltas in one reverse-chronological timeline.
   At an equal slot, undo the applied block before the boundary, matching current
   semantics.
3. For a boundary, seek directly to the last chunk of the last phase from the
   versioned phase descriptor table. Load and decode only that chunk. Apply at
   most the configured operation/byte budget in one `WriteBatch`.
4. Atomically commit the restored state, deletion/consumption of that reverse
   chunk, and the next rollback cursor. No map of all phase entries and no batch
   spanning multiple v1 chunks is permitted.
5. Continue chunks in descending sequence order and phases in explicit reverse
   ledger order. Once account phases are undone, remove snapshot generations,
   AdaPot derivatives, boundary/generation markers and artifact references using
   their declared rollback steps. Snapshot deletion uses a range tombstone or
   bounded cursor-driven deletes and records progress like any other step.
6. Only after all state and derived generations beyond the target are removed,
   atomically repair last-applied/last-snapshot metadata, complete the projection
   rollback contract and clear `rollback-v1`. Ordinary service may then resume.

If the process stops at any point, startup gives an existing rollback marker
precedence over interrupted forward-boundary recovery and resumes the exact next
undo item. Because each undo mutation and cursor advance share one RocksDB
write, repeating startup cannot duplicate or skip an undo. If the target cuts
off an `IN_PROGRESS` forward boundary, rollback reverses only its committed
chunks and deletes its incomplete generations; it must not resume that boundary
forward first.

Fault injection must prove rollback from both `IN_PROGRESS` and `COMPLETE`
boundaries and must crash before and after every rollback cursor commit,
snapshot-generation deletion and final marker clear.

#### Accumulated reward semantics

Multiple reward facts for one credential can occur in one boundary. Chunking
must preserve the overlay's sequential read-after-write behavior across flushes.
After a flush, subsequent updates read the newly committed account value. The
reverse delta for each chunk records the value immediately before that chunk,
so reverse chunk order reconstructs the pre-boundary value exactly.

### 7. Attribute and bound native and RocksDB memory in two stages

Resource budgeting is not deferred wholesale until the algorithmic work is
complete.

During Phase 0, run an attribution matrix from identical cloned checkpoints:

1. current heap policy and current RocksDB concurrency;
2. fixed explicit `-Xmx` with current RocksDB concurrency;
3. current heap policy with fixed RocksDB background-job/parallelism counts; and
4. both heap and RocksDB concurrency fixed.

Keep archive selection, checkpoint, storage, peers and workload constant. Record
managed heap, RSS, block-cache/pinned bytes, table-reader/index/filter memory,
all memtables, pending compaction bytes and background thread counts. Where the
RocksJava build cannot expose an exact component, record the limitation and use
native memory/process deltas; do not reclassify unexplained RSS as heap.

If RocksDB contributes more than 50 MiB to the boundary peak, materially scales
with host cores/RAM, or prevents the 600 MiB budget, an early Phase 0B lands and
validates:

- one explicitly owned shared bounded RocksDB block cache across applicable
  column families;
- explicit index/filter cache behavior, including accounting for pinned L0 and
  partitioned-filter memory;
- a database-wide write-buffer manager or otherwise proven aggregate
  memtable/write-buffer ceiling;
- explicit maximum background jobs/compactions independent of host core count;
  and
- `fillCache(false)` on epoch sequential scans.

Phase 0B precedes the ordered-index and streaming work. Its budgets are
conservative interim limits chosen to avoid cache-thrash or compaction-stall
regressions; it is not required to force the existing allocation-heavy boundary
under 600 MiB by itself. If measurement shows RocksDB is immaterial, the knobs
and metrics still land, while their final defaults may remain unchanged until
the final sweep.

After the live-set changes, final tuning additionally:

- sets an explicit native `-Xmx` in reference launch profiles rather than relying
  on the host-RAM-derived G1 maximum;
- includes archive/DuckDB memory in the same process budget and avoids scheduling
  optional archive maintenance concurrently with the epoch peak; and
- retains headroom for code pages, thread stacks, native batches and GC copying.

An initial 320–384 MiB heap range may be evaluated for a 600 MiB RSS target. A
352 MiB preprod run completed the boundary itself but subsequently OOMed on an
ordinary heavy block, so the implementation uses a configurable 384 MiB native
default while the mainnet gate remains open. The cap is a safety guardrail, not
evidence that every mainnet workload fits it.

### 8. Configuration and rollout modes

Introduce temporary rollout controls:

```yaml
yano:
  epoch-boundary:
    stake-source: auto       # auto | index | scan
    reward-mode: legacy      # legacy | streaming
    max-batch-operations: 10000
    max-batch-bytes: 4194304
```

Semantics:

- `auto` uses the index when readiness and source completeness allow it, adding
  a pointer-only overlay before Conway. An index unavailable before view
  creation logs the reason and selects the full scan. A canonical coordinate
  failure discovered while opening the phase-scoped view may select the
  historical full view only before any snapshot mutation; failure of either
  selected source is fatal.
- `index` is strict and fails the boundary before mutation if the complete view
  cannot be opened. It is used for acceptance and operations that require a
  proven bounded path.
- `scan` preserves the legacy pointer-aware source for differential and
  compatibility testing.
- `legacy` and `streaming` select reward implementations while both are shipped.
- Batch limits apply only when the measured Section 6 trigger enables
  journal-v1; atomic mode ignores them.

The current `yano.account.stake-balance-index-enabled` default remains `true`.
Changing the epoch source does not bypass `isStakeBalanceIndexReady()`.

Once mainnet acceptance passes, `reward-mode` defaults to `streaming` and
`stake-source: auto` remains the network-neutral default. Legacy code is removed
only after at least two released versions or the project's normal compatibility
window, with retained golden fixtures capable of detecting later drift.

## Implementation plan

### Phase 0 — Baseline and attribution

1. Add phase metrics, RSS sampling, batch/delta byte estimates and logical output
   digests without changing execution.
2. Capture three comparable mainnet Conway boundaries with JVM and native builds
   from cloned checkpoints near a boundary.
3. Run the four-leg heap/RocksDB concurrency attribution matrix from Section 7.
   The fixed-heap diagnostic value must be large enough to run the legacy
   boundary without turning attribution into an artificial OOM test.
4. Run one leg with the parallel UTXO scan disabled to quantify overlap and one
   diagnostic leg with archive projection disabled to isolate archive memory.
5. Record heap, native/off-heap, RSS and phase elapsed-time baselines in
   `adr/reports/adr-048-phase-0-*`.
6. If the Section 7 threshold is met, implement Phase 0B shared-cache,
   memtable/write-buffer and background-job bounds and repeat regular-sync plus
   boundary performance tests before Phase 1 begins.

Exit gate: every unexplained RSS component larger than 50 MiB has an attributed
owner or a documented measurement plan. Output digests match the uninstrumented
baseline. When Phase 0B is triggered, its interim budget is enabled and regular
sync throughput, compaction debt and boundary time pass the performance gates.

### Phase 1 — Ordered live stake index

Likely touch points:

- `core-api/.../utxo/UtxoState.java` for the storage-neutral view;
- `runtime/.../utxo/DefaultUtxoStore.java` for the consistent RocksDB cursor;
- `runtime/.../migration/StakeBalanceIndexStartupMigration.java` and its tests
   for readiness invariants; and
- `ledger-state/.../UtxoBalanceAggregator.java` for a serial, cache-bypassing
   fallback.

This phase first introduces the shared canonical address/credential extractor,
uses it in incremental writes, rebuild and scan, and introduces a versioned index
readiness marker in the new chainstate format. The ADR marker is version 2 so a
pre-shared-extractor version-1 index is never accepted as complete; malformed
Shelley-prefixed addresses fail closed while supported Byron representations are
excluded consistently. It persists
`META_LAST_APPLIED_HASH` atomically with block/slot and adds the dedicated
snapshot latest-deregistration index and marker. It then adds the
coordinate-bound cursor and phase-scoped account merge views. It also initializes
`epoch-boundary-state-v1` and writes current atomic boundary deltas as exactly one
`delta-v1` sequence-zero chunk per phase; it does not yet enable multi-chunk
progress or resumable rollback.

Also land the low-risk fallback fixes here: use `fillCache(false)`, store
fallback aggregation keys as credential type plus a value-semantic 28-byte key
rather than a hex string (not a record whose `byte[]` uses identity equality),
and remove the 300-second timeout-then-inline-rescan behavior. A scan
future is awaited once; an execution failure fails the boundary, and a configured
watchdog may cancel/fail it, but Yano never starts a second concurrent full scan.
For pre-Conway epochs, the same serial pass keeps only pointer-address balances
and merges that bounded overlay with the ordered base index. Missing pointer
resolution fails closed.

Exit gate: ordered cursor tests pass for empty, populated, filtered, disabled,
coordinate-mismatch and rollback states. Old non-empty chainstate rejection is
explicit and non-destructive. A full mainnet index walk has
constant Java heap and reports the expected total and credential count. The
canonical extractor's scan and index outputs match for every stored address
representation accepted by the UTXO codec.

### Phase 2 — Streaming SNAP and governance

1. Refactor snapshot creation into a credential merge pipeline.
2. Use the ready registration and dedicated latest-deregistration indexes instead
   of the full snapshot-time stake-event map.
3. Keep current phase-wide persistence atomicity until the Section 6 trigger is
   evaluated; add snapshot `COMPLETE` evidence, last-snapshot metadata and the
   artifact reference to that same atomic batch.
4. Refactor DRep distribution to open its own ordered stake view on every normal
   or resumed run; remove the snapshot-amount fallback.
5. Keep the old scan/map adapter for no-crash differential mode.

Exit gate: new no-crash snapshot, DRep, pool distribution and artifact digests
match no-crash legacy baselines across synthetic era fixtures, retained preprod
boundaries and at least two retained mainnet Conway boundaries. New-path
crash-resume after SNAP matches new-path no-crash output exactly. SNAP plus
governance peak incremental RSS is below 100 MiB above the regular-sync baseline.

### Phase 3 — Immediate reward lifetime reductions

1. Remove collection copies and shorten current result lifetimes.
2. Canonicalize pool hashes locally and group directly from the snapshot iterator
   without first creating the complete snapshot map.
3. Separate scalar AdaPot output from the complete library result.
4. Add targeted reward allocation benchmarks and heap histograms.
5. Do not yet change reward math or persistence atomicity.

Exit gate: identical reward/artifact digests and no reward phase slowdown above
5%. Document the memory recovered before proceeding.

### Phase 4 — Bounded persistence and rollback-v1

1. Record the Section 6 serialized-size trigger and repeat persistence/rollback
   attribution after Phases 1–3 for tuning evidence.
2. Implement the bounded writer, journal-v1, resume cursors, read
   gate, explicit phase descriptor table and rollback-v1 coordinator.
3. Implement snapshot-generation cleanup and the COMPLETE publication protocol
   for snapshot/reward artifacts.

Exit gate: exhaustive forward/rollback fault injection, bounded restart and
rollback acceptance, snapshot cleanup and artifact parity, and v1
forward/rollback RSS within their component/process budgets.

### Phase 5 — Pool-major inputs and Yano reward orchestration

1. Add versioned pool-major snapshot keys and completion evidence using the
   active atomic or chunked persistence mode.
2. Build a bounded migration from retained credential-major generations created
   earlier in the new chainstate.
3. Add the minimum ordered historical account summary and prove
   registration-cycle queries against the authoritative time-major event log.
4. Implement the Yano-owned outer reward orchestrator over the existing public
   per-pool CF API and feed it from the pool-major iterator.
5. Integrate bounded reward staging with ADR-039/045 publication semantics.

The orchestrator must preserve the CF epoch wrapper's historical block-count
rule: when `0 < d < 0.8`, every per-pool calculation receives the non-OBFT block
count; otherwise it receives the total block count. A golden test with unequal
counts in that decentralization range is required before the streaming mode can
be used for pre-Vasil mainnet epochs.

Exit gate: every reward-required input generation is complete or forces legacy
reward mode; exact reward/AdaPot and CF epoch-level scalar parity passes; disk
growth/migration duration is documented; and reward peak incremental RSS is
below 150 MiB above regular sync.

### Phase 6 — Native budget, performance tuning and rollout

1. Sweep heap sizes on the reference machines and, when journal-v1 is enabled,
   sweep chunk byte/operation limits.
2. Retune the Phase 0B shared RocksDB cache/memtable/background-job budget, or
   select final defaults from the Phase 0 evidence when Phase 0B was not
   triggered.
3. Run preprod correctness/recovery acceptance, then explicit mainnet memory and
   performance acceptance.
4. Make streaming reward mode the default only after all gates pass.

Exit gate: the acceptance criteria below pass for three consecutive mainnet
boundaries on each supported native reference memory class.

### Phase 7 — Cleanup

Remove the full credential map handoff, obsolete reward adapters and temporary
comparison flags after the compatibility window. Retain the serial scan executor
for the pre-Conway pointer overlay and the unready/differential full-scan
implementation.

## Test plan

### Unit tests

#### RocksDB resource budgets

- The applicable table configurations share the intended cache instance and
  expose cache, pinned and table-reader memory metrics without double-counting.
- Aggregate memtable/write-buffer memory remains under the configured process
  ceiling across all opened column families under concurrent writes.
- Background-job/compaction counts remain fixed when the same configuration is
  run with different reported CPU counts.
- Sequential epoch iterators use `fillCache(false)` and do not evict a warmed
  point-lookup working set beyond the measured tolerance.
- Cache, write-buffer manager, table options, statistics and iterator resources
  close in a defined ownership order on normal shutdown, failed open and
  snapshot restore/reopen.

#### Chainstate v1 format

- An empty store initializes `epoch-boundary-state-v1` before the first block.
- A non-empty unversioned store is rejected without writes and reports the exact
  backup/resync action.
- Atomic mode writes one `delta-v1` sequence-zero entry per committed phase and
  no unversioned delta key or bounded-progress marker.
- Atomic-mode restart and rollback results match the current implementation even
  though the key envelope is versioned.

#### Stake-balance view and readiness

- Empty unfiltered database: startup marks the index ready and the cursor is
  valid but empty.
- Populated ready database: cursor order is credential type then unsigned hash;
  each aggregate equals the point getter and a full UTXO oracle.
- Incremental create/spend in one block: the index update and UTXO mutation are
  atomic; a balance returning to zero deletes the row.
- Every apply batch persists block number, slot and hash with UTXO/index changes;
  rollback restores all three and cursor contents to the prior coordinate.
- A non-empty pre-v1 database is rejected without mutation and reports the
  backup/resync action; an empty database initializes v1.
- A mismatched v1 index readiness marker cannot serve an epoch view until the
  bounded repair/rebuild completes.
- Disabled index, disabled UTXO store, filtered store, missing column family,
  rebuild in progress and corrupt marker: no complete view is returned.
- Readiness changes between status check and open: open fails closed.
- Missing canonical metadata, wrong block number, wrong slot, wrong hash and a
  writer advancing between status/open checks all fail closed before SNAP. They
  do not fall back to a live scan.
- Iterator resources, RocksDB snapshots and `ReadOptions` close on normal exit,
  consumer failure and boundary failure.
- Sequential cursor does not increase block-cache usage beyond a small fixed
  tolerance.
- A slow asynchronous fallback scan is consumed once. Timeout/failure never
  starts a second concurrent inline scan or leaves the first result live.

#### Era and pointer semantics

- Byron, enterprise and reward addresses contribute no stake credential.
- Base key/key, key/script, script/key and script/script addresses map to the
  correct credential type and hash.
- Bech32 and hex encodings of every Shelley address representation accepted by
  the UTXO codec produce identical canonical extraction in incremental index
  writes, startup rebuild and the scan oracle.
- A malformed Shelley payment address fails index maintenance/rebuild; an
  explicitly recognized Byron address is skipped consistently.
- Pre-Conway pointer UTXOs resolve through the pointer-only overlay and are
  included exactly as before without retaining non-pointer balances.
- Conway-or-later pointer UTXOs are excluded by both old and index paths.
- `stake-source: index` accepts a pre-Conway boundary only with a complete index,
  an exact coordinate and an available pointer resolver.
- `stake-source: auto` chooses index plus pointer overlay before Conway, index
  alone for a ready Conway boundary and full scan for an unready boundary.
- A pointer overlay never retains a non-pointer credential and fails closed when
  its resolver or historical UTXO coordinate is unavailable.

#### Streaming snapshot merge

- Key-layout contract tests prove the credential suffix ordering for
  `PREFIX_POOL_DELEG`, `PREFIX_DREP_DELEG`, `PREFIX_ACCT`,
  `PREFIX_ACCT_REG_SLOT`, `PREFIX_ACCT_LAST_DEREG_COORD` and
  `utxo_stake_balance`.
- Fresh sync maintains the latest-deregistration index from genesis in the same
  batch as stake events; randomized rollback restores the previous latest
  coordinate.
- Missing/unready latest-deregistration evidence forces the legacy event-scan
  input or strict failure. A bounded rebuild from `PREFIX_STAKE_EVENT` publishes
  readiness only after complete traversal.
- `cleanupPointerIndex` can delete `PREFIX_ACCT_DEREG_COORD` rows without
  changing the snapshot index, its marker or the snapshot digest.
- Credentials appearing only in the stake index, only in delegation, or only in
  account state exercise all merge advances.
- Missing balance in a complete index produces zero UTXO stake, not a skipped
  delegator.
- Reward balance is added once, including reward-rest at the correct boundary.
- Unregistered credentials, retired pools, delegation before pool
  re-registration, deregistration after delegation, and delegation before
  credential re-registration match current behavior.
- Equal slot cases compare transaction and certificate indexes correctly.
- Zero-balance pool owners remain in the snapshot.
- Batch limits flush at one less than, exactly at and one more than both limits.
- A generation is invisible while `BUILDING` and visible only after `COMPLETE`.
- Empty complete generation differs from an incomplete/missing generation.
- While `BUILDING`, `META_LAST_SNAPSHOT_EPOCH` and the epoch-stake artifact
  reference remain unchanged and ADR-045 reports pending rather than a gap. The
  final publication commit advances the marker, reference and `COMPLETE`
  evidence together.
- Rollback deletes only snapshot generations at or beyond the target in bounded
  steps, preserves earlier generations, and repairs the last-complete marker.

#### Streaming governance

- Ordinary, always-abstain and always-no-confidence DRep delegations match the
  map implementation.
- Account rewards, reward-rest, proposal deposits/refunds and treasury
  withdrawals are included at the same phase as today.
- DRep expiry, dormant epochs, registration/deregistration and PV10 reverse-index
  rebuild cases match existing fixtures.
- Proposal ratification, enactment, expiry and refund outputs are identical when
  the input arrives through the stream.
- Crash/restart after SNAP and before governance produces the exact no-crash
  distribution, including DRep-only delegators, and never adds reward twice.

#### Reward calculation

- Pool-major and credential-major snapshot totals match per credential, pool
  and epoch.
- Pool owner active stake, pledge, margin, fixed cost, shared reward address,
  retired pool and zero-member cases match the current `PoolState` assembly.
- Pre-/post-Babbage deregistration and stability-window cases cover multiple
  register/deregister cycles and same-transaction certificate ordering.
- Pre-/post-Vasil reward rules, decentralization values 0 and 1, MIR from both
  pots, unclaimed deposits and early Shelley epochs have golden expected values.
- Streaming and legacy calculators emit exactly the same positive leader/member
  facts and all AdaPot scalar totals.
- The Yano per-pool orchestrator matches the pinned CF epoch API's reward facts
  and every scalar pot field on all golden fixtures, including retired deposits,
  MIR, unspendable/undistributed routing and Allegra bootstrap adjustment.
- Randomized pool/delegator ordering cannot change results or digest.
- A high-cardinality pool proves memory is bounded by one pool; the benchmark
  documents that exceptional bound explicitly.

#### Chunk journal, recovery and rollback

These tests are mandatory when the Phase 4/Section 6 trigger enables journal-v1.

Inject a crash before and after every durable action:

1. boundary `IN_PROGRESS`;
2. phase `IN_PROGRESS`;
3. each reward/snapshot/governance chunk;
4. phase `COMPLETE`;
5. artifact file fsync and directory fsync;
6. artifact outbox reference;
7. generation `COMPLETE`; and
8. boundary `COMPLETE`.

Separately inject a crash before and after:

1. writing `rollback-v1 IN_PROGRESS`;
2. every block-delta or boundary-chunk undo commit;
3. transition between every registered reverse phase;
4. credential-major and pool-major snapshot generation deletion;
5. AdaPot/marker/artifact cleanup;
6. final metadata repair; and
7. clearing `rollback-v1`.

For every point assert:

- restart produces one copy of every logical mutation and artifact fact;
- scalar totals and logical digests equal a no-crash run;
- already committed reward credits are neither duplicated nor lost;
- two rewards for one credential straddling a chunk boundary observe the first
  committed value and produce the same accumulated total as one atomic batch;
- ordinary reads fail closed while a boundary is incomplete;
- retry is idempotent after a second crash at the same point;
- rollback to before the boundary restores exact previous values;
- reversing multiple chunks touching the same credential restores its original
  value;
- a restarted rollback resumes before interrupted forward-boundary recovery;
- no rollback step materializes more than one v1 chunk or exceeds the configured
  batch byte/operation limits;
- ordinary reads and block application remain fail-closed until the rollback
  marker is cleared;
- snapshot generations at or beyond the target are absent, older completed
  generations remain readable, and `META_LAST_SNAPSHOT_EPOCH` is exact; and
- the phase descriptor registry contains every persisted phase exactly once and
  reverse order matches ledger execution rather than phase-byte numeric order.

### Property and differential tests

Generate randomized but valid combinations of:

- UTXOs and all address kinds;
- stake registration/delegation/deregistration cycles;
- pool registration, retirement and re-registration;
- DRep registration, delegation, expiry and dormant epochs;
- reward balances, MIR, fees, blocks and protocol parameters; and
- rollback points before, during and after a boundary.

Generate accepted reference outputs only from no-crash legacy runs. Run the new
implementation without a crash from equivalent logical input, then run each
new-path crash schedule against the new no-crash result. Do not use a crashed
legacy boundary as an oracle. Compare ordered logical rows and final
column-family key/value contents for
account state, boundary deltas, epoch snapshots, AdaPot and governance. Exclude
only explicitly versioned journal/marker representation differences and compare
their decoded semantics instead.

Use retained real-network golden fixtures for at least:

- the first Shelley reward-bearing boundaries;
- a pre-Conway pointer-bearing boundary;
- Babbage and Vasil rule transitions;
- the Conway transition; and
- two recent mainnet Conway boundaries with non-trivial DRep/proposal state.

### Microbenchmarks

Add JMH or the repository's benchmark equivalent for:

- full UTXO decode versus ordered stake-index walk;
- map lookup versus ordered pool merge;
- for DRep distribution, ordered merge versus one point lookup per DRep
  delegation across real mainnet densities and cache states;
- credential-major load/group/copy versus pool-major streaming;
- legacy result retention versus streaming reward sink;
- one large RocksDB batch versus 1, 2, 4, 8 and 16 MiB byte limits;
- current unversioned monolithic versus v1 chunk delta encoding; and
- current monolithic rollback versus v1 bounded rollback, including snapshot
  cleanup and crash-resume overhead.

Report throughput, allocation bytes/op, peak live heap, native batch bytes and
RocksDB bytes read/written. A benchmark improvement alone cannot satisfy ledger
parity or live-network gates.

### Module and integration tests

At minimum, run:

```text
./gradlew :core-api:test :runtime:test :ledger-state:test
./gradlew :app:test
./gradlew :app:quarkusBuild
./gradlew :app:build -Dquarkus.native.enabled=true
```

Target existing suites including `StakeBalanceIndexStartupMigrationTest`,
`DefaultUtxoStoreTest`, `UtxoBalanceAggregatorTest`,
`EpochBoundaryProcessorTest`, `EpochRewardCalculatorTest`,
`DRepDistributionCalculatorTest`, `GovernanceEpochProcessorTest` and
`BoundaryRecoveryIdempotencyTest`, then add focused ADR-048 suites beside their
owners.

### Live preprod validation

Use a dedicated retained test directory and current code/configuration. Preserve
and rotate logs; never delete the source chainstate as part of a retry. Kill only
the process started for that directory.

1. Run a JVM sync across at least two epoch boundaries with legacy and new modes
   in separate retained directories at equivalent logical chain points. Initialize
   the new directory from empty under `epoch-boundary-state-v1`; never overwrite
   or delete the legacy source directory.
2. Compare `/api/v1/node/status`, boundary digests, epoch nonce and archive
   coverage after every boundary.
3. Perform a graceful restart shortly before a boundary and verify completion.
4. If journal-v1 is enabled, repeat with `kill -9` during reward chunks,
   snapshot chunks and governance; otherwise crash at the existing atomic phase
   boundaries. Verify deterministic recovery and no repeated no-progress loop.
5. Exercise rollback before and after the boundary, including a header-only
   rollback and a body-backed rollback within retention.
6. If rollback-v1 is enabled, repeat the body-backed rollback with `kill -9`
   during a boundary-chunk undo and during snapshot-generation cleanup; verify
   startup resumes rollback before forward recovery and reaches the same
   nonce/digest.
7. Disconnect and reconnect upstream peers during the boundary window; verify
   recovered sessions and resumed body flow.
8. Confirm pruning retains enough block bodies for configured rollback depth;
   the mainnet-safe floor remains `43200 * rollbackRetentionEpochs` where that
   policy applies.
9. Run equivalent native validation after JVM correctness passes.

Nonce mismatch, missing body inside promised retention, unrepaired rollback,
projection gap without the ADR-045 outcome, or repeated no-progress after
restart is a release blocker.

### Live mainnet validation

Mainnet is explicitly required for the memory target. Use cloned checkpoints in
separate directories near a known epoch boundary; do not reuse a mutable source
directory between comparison legs.

For each supported native reference machine class:

1. Run legacy and new code against logically equivalent chain points with
   identical archive selection, peers and heap/cache settings. Because formats
   are intentionally incompatible, use separate directories; the new directory
   must have been synced under `epoch-boundary-state-v1`, not copied from legacy.
2. Capture at least three consecutive boundaries in new mode so retained
   generations, pruning and cache warming are represented.
3. Sample RSS at one-second resolution and collect phase metrics, GC/native heap
   data and RocksDB statistics. Record swap and host pressure.
4. Compare logical digests, REST epoch outputs, nonce, canonical tip and archive
   receipts.
5. Repeat one boundary with a graceful restart and one with `kill -9` during a
   non-final chunk when journal-v1 is enabled, otherwise at an existing phase
   boundary.
6. Roll back across one completed boundary within configured retention and
   resync it. If rollback-v1 is enabled, sample rollback RSS and verify its 600
   MiB gate.

If a full mainnet replay is used to validate pre-Conway fallback, keep it a
separate correctness run; do not mix its pointer-aware scan peaks into the
Conway steady-state target without labeling them.

## Acceptance criteria

All criteria are measured on documented reference hardware, storage,
configuration, chain point and archive selection.

### Correctness

- Reward facts and account balances, epoch stake rows, AdaPot, DRep/pool
  distributions, governance decisions/refunds and archive facts are logically
  identical to the accepted no-crash legacy result.
- Snapshot, phase and boundary completion markers survive graceful and abrupt
  restart with no duplicate or lost mutation.
- Rollback before, during and after a boundary restores exact state and resyncs
  to the same digest.
- UTXO block number, slot and hash remain atomic with UTXO/index mutation and are
  restored together on rollback.
- SNAP consumes the dedicated latest-deregistration index only with complete
  version evidence; pointer-index cleanup cannot change its result.
- Pre-Conway pointer behavior is unchanged: the pointer-excluding index is
  always paired with the resolver-backed pointer overlay.
- No incomplete generation or projection gap is reported as empty/complete;
  `BUILDING` is pending and cannot advance last-snapshot metadata or publish an
  artifact reference.

### Memory

- Current rollout: the native mainnet target is at most 1.5 GiB and the ceiling
  is 2 GiB through at least three consecutive boundaries and the ordinary block
  flow between them. Until the operator selects the formal cross-platform
  acceptance metric, every report includes both raw process RSS and macOS
  physical footprint when available. The 600 MiB goal remains follow-up
  optimization.
- The platform acceptance metric does not scale materially with host RAM when
  workload and explicit budgets are unchanged; the larger-machine result must
  remain within 10% of the smallest reference machine's result.
- There is no swap, OOM recovery loop or allocation failure.
- Phase telemetry must still attribute SNAP, governance, rewards, MIR and
  RocksDB rather than hiding a large component inside the process ceiling.
- Rollback-v1 across a completed mainnet boundary must remain within the same
  2 GiB rollout ceiling and the configured chunk/batch limits.

### Performance

- The ordered stake-index walk is faster than the full UTXO decode scan on the
  same checkpoint and storage.
- On the reference machine, total epoch-transition wall time targets less than
  60 seconds and must not exceed 120 seconds. AdaPot and recovery correctness
  cannot be traded for a faster boundary.
- Same-checkpoint legacy measurements remain diagnostic evidence for finding
  algorithmic regressions; they are not an independent rollout veto when the
  absolute timing gate passes.
- Normal sync throughput outside the boundary regresses by less than 2%, and
  p99 block-apply latency outside the boundary does not materially regress.
- Chunking and RocksDB budgets may make an individual write phase slower, but
  the total must remain within the absolute boundary gate. Write amplification
  must not increase by more than 10%; any exception requires a measured decision
  update.

### Operations

- Metrics identify the selected source, fallback reason, phase peak, chunk
  progress, incomplete boundary and recovery action.
- Strict index mode proves the accepted mainnet run used the bounded path.
- Operators can distinguish index enabled, ready, rebuilding, filtered and
  unsupported-era states.
- Startup refuses ordinary service when an incomplete boundary cannot be safely
  resumed or rolled back.
- Startup rejects a non-empty pre-v1 chainstate without modifying it and reports
  the required backup/resync procedure.

## Expected processing-time impact

The principal snapshot change should reduce time as well as memory. Walking
`utxo_stake_balance` reads one compact aggregate row per stake credential;
today's scan reads and decodes every UTXO and parses every address. Ordered
pool/DRep merges also avoid millions of random point reads.

Streaming rewards performs the same arithmetic once while reducing allocation,
copying and GC pressure. It is expected to be neutral or faster, especially in
native mode. When triggered, chunked RocksDB commits add write and WAL overhead
to the affected write phases. That overhead is a tuning constraint: ordered
index reads and lower allocation/GC must keep the complete boundary below the
60-second target and 120-second maximum on the reference machine.

Serializing the existing full UTXO compatibility scan alone could add its
documented 30–60 seconds because it would no longer overlap rewards. Normal
pre-Conway processing still visits each UTXO to identify pointers, but retains
only pointer balances and overlaps that pass with rewards. The network-sized
map is retained only for unready/differential fallback and is not the expected
mainnet path.

## Consequences

### Positive

- Mainnet credential populations no longer determine heap size for current
  stake snapshot and DRep calculation.
- Reward input/output becomes bounded by one pool; persistence is bounded by one
  configured chunk.
- Explicit heap and RocksDB budgets stop peak RSS from scaling materially with
  host RAM or core count.
- Ordered compact reads should recover or improve snapshot time.
- Generation and phase markers make partial boundary state explicit and more
  testable than one opaque large batch.
- The existing default-enabled index gains a second high-value consumer without
  adding normal block-apply work.

### Negative

- The pool-major reward projection and optional credential-major event summary
  consume additional disk and require a bounded migration.
- Existing non-empty preview chainstate cannot be opened by the accepted format;
  operators must retain/backup it if needed and sync a separate v1 store from
  genesis.
- Chunking replaces phase-wide atomic visibility with durable progress plus a
  read gate. Recovery and rollback logic become more complex.
- Yano owns the epoch-level outer reward orchestration and must track semantic
  changes when updating the pinned CF dependency.
- Multiple implementations coexist during rollout and expand the temporary test
  matrix.
- Strict memory budgets may expose unrelated native consumers that previously
  hid inside an unconstrained heap/process.

## Alternatives considered

### Set only `-Xmx` or change GC

Rejected as the primary fix. It can turn the current allocation burst into an
OOM or excessive GC without reducing the live object graph or native batches.
An explicit diagnostic maximum is used in Phase 0 for attribution; the final
production maximum is selected after the algorithm is bounded.

### Keep the full scan but run it after rewards

Retained only as fallback. It reduces overlap but can add the full scan duration
to the boundary and still builds a credential-sized map.

### Use point lookups into `utxo_stake_balance`

Rejected for the SNAP main path because it covers nearly every delegated
credential; one RocksDB get per delegation creates high random-read and JNI
overhead, while the matching ordered key suffixes make a merge scan simpler and
faster. DRep delegators can be a smaller subset and the existing DRep path
already performs account-state point reads. A bounded DRep point-lookup variant
is therefore not prohibited: Phase 0/benchmark data may select it when real
mainnet cardinality and cache-state tests show lower wall time without exceeding
memory or block-cache budgets. It must use the same coordinate-bound view and
produce the same digest.

### Use UTXO deltas to reconstruct the epoch snapshot

Rejected for this decision. Correctness depends on complete retained deltas,
pointer-era rules, bootstrap removal, rollback and every index migration. The
already-maintained complete current aggregate is a smaller proof surface.

### Reuse `PREFIX_ACCT_DEREG_COORD` for SNAP

Rejected. That namespace was introduced for pointer resolution, lacks proven
genesis coverage on upgraded stores and has a cleanup API that deletes history.
SNAP instead uses a dedicated latest-deregistration index with its own versioned
completeness evidence and authoritative event-log repair source.

### Use the live index alone before Conway

Rejected because it excludes pointer stake references. The accepted design
pairs it with a coordinate-bound pointer-only overlay, preserving pointer-era
ledger semantics without rebuilding the non-pointer credential map.

### Keep one giant atomic RocksDB batch

Rejected for the clean preview format. The serialized lower bound of mainnet
snapshot/reward facts already exceeds the component budget, and carrying atomic
and chunked recovery modes would increase the consensus-critical proof surface.
Chunking with durable progress and read gating is required.

### Preserve the unversioned journal with a mixed v2 decoder

Rejected for preview chainstate. Compatibility was the only reason to call the
new format v2, and it would add mixed key decoding, phase-order and rollback
branches to the highest-risk code. The accepted implementation backs up/rejects
old non-empty state, uses journal-v1 from genesis, and enables bounded
rollback-v1 only if the measured trigger fires.

### Calculate rewards twice

Rejected because it trades memory for a potentially near-doubling of reward
calculation time. The dependency must stream results from one logical
calculation.

### Disable rewards, governance or epoch artifacts

Rejected. These are ledger or selected archive outputs, not optional cache
work. Memory optimization cannot alter node correctness or silently create
history gaps.

## Rollback and disable plan

- Before the default cutover, set `stake-source: scan` and
  `reward-mode: legacy` to restore the old algorithms.
- A deployment may disable the new path only at a complete boundary. When
  journal-v1 is enabled, startup first resumes any `rollback-v1 IN_PROGRESS`
  state; only when no rollback is in progress may it reconcile
  `boundary-v1 IN_PROGRESS`.
- There is no downgrade to the pre-ADR chainstate format. Rollback of the
  software deployment means restoring a retained pre-upgrade directory with the
  old binary or starting a new compatible chainstate; binaries never mix formats.
- Pool-major rows are derived. They may be rebuilt from complete retained
  credential-major snapshots, but deletion is an explicit migration after no
  streaming reader depends on them.
- The existing stake-balance index must not be disabled merely to roll back this
  ADR if account APIs or other consumers use it. Selecting the scan path is
  sufficient.
- Any parity failure, incomplete recovery, unexplained nonce change, archive
  identity change or mainnet platform acceptance metric above the gate returns
  rollout to legacy mode and preserves the failing checkpoint/logs for
  analysis.

## Open questions to close with measurements

1. What heap, RocksDB cache and memtable split leaves adequate native-image GC
   headroom while first holding RSS below the 2 GiB rollout ceiling, then moving
   toward the 1.5 GiB and long-term 600 MiB targets on supported build vendors?
2. If the Section 6 trigger fires, is 4 MiB/10,000 operations the best chunk
   limit, or should reward and snapshot phases use different byte limits?
3. What is the mainnet disk cost and migration duration of the pool-major
   snapshot and historical account event summary?
4. Does the largest real pool fit the per-pool memory budget, or does the reward
   dependency also need a delegator substream within a pool?
5. Which read endpoints should wait briefly versus immediately return a
   boundary-in-progress response? All must share the same fail-closed health
   source.
6. Does optional archive maintenance overlap a boundary on supported profiles,
   and if so, is coordination or a stricter independent native budget preferable?

These questions affect tuning and implementation ownership. They do not weaken
the correctness, completeness, rollback, 2 GiB rollout ceiling or absolute
boundary-time gates.
