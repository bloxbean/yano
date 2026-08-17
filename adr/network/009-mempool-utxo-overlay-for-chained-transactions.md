# ADR-NET-009: Bounded Single-Writer Mempool UTXO Overlay for Chained Transactions

## Status

Accepted

## Date

2026-08-14

## Context

[Issue #66](https://github.com/bloxbean/yano/issues/66) identifies a gap in
transaction admission when one unconfirmed transaction spends an output created
by another transaction already in Yano's mempool.

The original field report attached to the issue was withdrawn: that submission
failed because its signature was calculated over re-encoded transaction bytes,
not because a mempool output was missing. The source-level gap remains.

Yano currently has two relevant validation paths:

- Mempool admission calls `TransactionValidationService.validate(byte[])`,
  which resolves regular, reference, and collateral inputs only from persistent
  `UtxoState`.
- Block selection calls `TransactionValidationService.validate(byte[],
  resolver)` with `BlockBuildUtxoOverlay`. That overlay tracks inputs spent by
  earlier selected transactions, but it does not expose outputs created by
  those transactions.

Consequently, when the built-in validator is enabled:

1. transaction A can be accepted into the mempool;
2. transaction B, which spends `A#n`, cannot find that output in persistent
   `UtxoState` and is rejected with `UtxoNotFound`;
3. adding an admission-only lookup would be insufficient, because block
   selection could later reject B for the same reason.

Scanning and deserializing every mempool transaction for every input lookup
would avoid a new index, but admission would become proportional to total
mempool size. With the existing defaults of 10,000 transactions and 128 MiB of
transaction bodies, this is unsuitable for transaction diffusion and creates a
CPU denial-of-service surface.

ADR-NET-008B already establishes these relevant constraints:

- one authoritative in-memory mempool;
- one admission path for local and network transactions;
- bounded transaction count, transaction-body bytes, and TTL;
- transaction bodies stored once;
- removal on confirmation and capacity/TTL eviction;
- no durable mempool journal in the current design.

The derived UTXO view must follow the same ownership and bounds. It must not
become a second permanent UTXO store.

The current admission flow is sequential for one transaction but not across
transactions. `TransactionValidateEvent` listeners run synchronously and in
order on each publisher thread, while `TxSubsystem.admitTransaction()` holds a
shared read lock. REST, N2N, diffusion, and direct callers can therefore run
different validation chains concurrently. `DefaultMemPool.addTransaction()`
serializes individual map calls, but validation plus insertion is not an atomic
state transition. Two conflicting transactions can validate against the same
canonical UTXO and both enter the current mempool.

The Haskell node, Dingo, and Dolos use different implementation mechanisms, but
the applicable design principle is the same: there is one authoritative order
of mempool state transitions, and a transaction is validated against the state
produced by previously accepted transactions before that state advances.

This ADR is intentionally limited to UTXO dependencies. It does not model
tentative certificate, withdrawal, minting-policy, governance, or other ledger
state transitions across mempool transactions. For example, transaction B is
not made valid merely because transaction A in the mempool registers the stake
credential that B delegates. Supporting those dependencies requires a broader
mempool ledger-state transition model and is outside the scope of issue #66.
The deferred evolution path is recorded in
[ADR-NET-009-NEXT](009-next-generalized-mempool-ledger-state.md).

## Decision

Extend `DefaultMemPool` with a bounded, derived UTXO overlay and make it the
single authority for stateful transaction admission.

Admission is a serialized state transition. A transaction is validated against
canonical `UtxoState` plus all previously accepted mempool effects, and the
transaction entry and every derived index are committed atomically before the
next admission can validate. The initial implementation deliberately does not
use optimistic snapshots, resolution proofs, view versions, automatic retries,
or parallel stateful validation.

Work that cannot depend on mempool state may happen before the serialized
transition, including transaction hashing, bounded CBOR decoding, transaction
size checks, and other demonstrably stateless prechecks. UTXO resolution,
ledger validation, conflict checks, capacity checks, and commit share the
single admission order.

The overlay supports arbitrary-depth UTXO chains in insertion order. It is an
in-memory index, not canonical ledger state and not durable state. Its lifetime
is the lifetime of the corresponding mempool entries.

Admission and block selection resolve an outpoint in this order:

1. If the outpoint is consumed by an accepted transaction in the relevant
   mempool or block-local view, return missing.
2. If the outpoint is produced by an accepted transaction in that view, return
   its projected UTXO.
3. Otherwise, fall back to persistent `UtxoState`.

This decision covers regular, reference, and collateral input resolution. Only
regular inputs of a transaction admitted as valid are added to the
consumed-outpoint index. Reference inputs are read-only. Collateral inputs of a
transaction that passed phase-2 validation are not consumed merely by entering
the mempool.

## Public Contract and Encapsulation

Overlay maps, dependency maps, accounting, and locks remain internal to
`DefaultMemPool`. `TxSubsystem` must not manipulate or rebuild those structures.
A future mempool implementation can replace `DefaultMemPool` at its construction
site without changing admission semantics; a factory may be introduced later if
multiple implementations become an active requirement.

Replace the raw `addTransaction(byte[])` mutation assumption with one semantic,
typed admission operation on `MemPool`, conceptually:

```text
tryAdmit(txCbor, admissionValidator) -> MempoolAdmissionResult
```

The exact Java callback types are an implementation detail. The contract is
that the implementation supplies an admission-scoped UTXO resolver to the
validator and keeps validation plus commit in one serialized state transition.
The result distinguishes at least:

- accepted and newly inserted;
- duplicate and already present;
- malformed projection;
- ledger-validation rejection;
- conflicting spend;
- transaction/byte/index capacity rejection.

No public method exposes the produced, spent, parent, or child maps. Read-only
status and block-selection snapshots remain semantic operations rather than
access to mutable indexes.

`MempoolAdmissionResult` replaces the current separate `contains(txHash)` and
`addTransaction(txCbor)` calls. A duplicate returns the existing admission
result without inserting, incrementing accounting, or republishing
`MemPoolTransactionReceivedEvent`. This also removes the racy `alreadyPresent`
rollback snapshot.

All production ingress remains routed through `TxSubsystem`. Direct raw-byte
insertion must not bypass projection, validation, conflict checks, or index
accounting.

## Serialized Admission

`DefaultMemPool` owns a fair, single-writer admission/mutation lane. A lock is
the preferred initial implementation; a dedicated actor or executor is not
required. Every operation that can change transaction ownership or an overlay
index participates in that same serialization domain.

`TxSubsystem.admissionGate` remains a lifecycle gate. Admissions may continue
to hold its read side so `stop`, `close`, and `clear` can take the write side and
wait for in-flight work. It is not the mempool correctness lock. The fixed lock
order is the `TxSubsystem` lifecycle gate followed by the internal mempool lane;
code must never acquire them in reverse order.

Admission proceeds as follows:

1. Perform bounded stateless parsing and hashing outside the admission lane.
2. Enter the single-writer admission lane.
3. Return `DUPLICATE` if the transaction hash already exists.
4. Calculate projected transaction, raw-byte, and index capacity and reject the
   newcomer if any hard limit would be exceeded.
5. Resolve regular, reference, and collateral inputs from the stable mempool
   overlay followed by canonical `UtxoState`.
6. Run the synchronous validation chain against that resolver.
7. Reject any regular input already claimed in `spentByOutpoint` and verify
   every discovered mempool parent is still live.
8. Commit the transaction entry, outputs, consumed-input claims, dependency
   edges, and accounting atomically.
9. Publish the accepted-transaction event before releasing the admission lane.
   If publication fails and remains admission-fatal, undo the new entry and all
   of its derived records before another admission can observe it.
10. Leave the admission lane and return the typed result.

Because the resolver and indexes cannot change during steps 5 through 9, no
resolution proof, mempool generation number, stale-view error, or automatic
validation retry is needed. Concurrent conflicting transactions are ordered by
the admission lane: the first valid commit claims the outpoint and the second
is rejected with a stable mempool-conflict error.

Clients that require parent/child ordering must await successful parent
admission before submitting the child. If parent and child are submitted
concurrently, whichever enters the admission lane first is processed first. A
child processed before its parent is rejected with `UtxoNotFound`; the node does
not queue or automatically retry unresolved children. If the parent is
processed first, the child resolves its output and can be admitted immediately.

Serialization intentionally includes stateful phase-1 and phase-2 validation.
This can reduce admission throughput for script-heavy workloads, but it is the
smallest correctness model and matches the current requirement. Hashing,
bounded deserialization, and stateless checks may be parallelized. Parallel
stateful validation is a later optimization that requires profiling and a
separate decision.

### Validation listener contract

The existing built-in validator is a synchronous
`TransactionValidateEvent` listener. Keeping the current event contract means
the listener chain runs on the serialized admission lane in the initial
implementation. The repository currently has only the built-in production
listener, but external plugins can register additional listeners.

Validation listeners therefore must:

- complete synchronously;
- not submit another transaction and wait for that admission;
- not wait for another thread that requires a mempool mutation;
- not mutate the mempool directly;
- keep non-validation side effects idempotent or defer them to accepted-event
  handling.

Recursive admission from the admission thread must fail immediately with a
stable error rather than block. Slow-listener duration must be observable. This
is an explicit initial constraint, not a claim that arbitrary plugin code is
safe under a lock. If stateful validation plugins become a real extension
requirement, Yano should split stateless policy hooks from the internal
serialized ledger validator in a follow-up ADR.

`MemPoolTransactionReceivedEvent` is published only for a newly committed
transaction. If the event remains part of the admission transaction, failure
rollback occurs before releasing the lane, when no child can yet depend on the
new entry. A later reliable-notification/outbox design may move this event
outside admission without changing the overlay model.

### Validator-disabled admission

`yano.validation.default-validator-enabled=false` disables built-in ledger-rule
validation; it does not disable mempool data-structure invariants.

Every admission path still deserializes and projects the transaction under the
same single-writer ordering. `DefaultMemPool` enforces duplicate handling,
regular-input conflict checks, dependency consistency, and every capacity limit
even when no built-in validator listener is registered. Full UTXO existence and
phase-1/phase-2 validation remain disabled, so this mode is unsafe for untrusted
submissions.

Projection records a parent edge when an input names an output already produced
by the mempool. An unresolved input may remain admissible in this explicitly
unsafe mode, but its regular outpoint is still claimed in
`spentByOutpoint`. If an unresolved child commits before its parent later
arrives, the dependency edge is not backfilled. Later removal of that parent
therefore cannot cascade through the missing edge. This accepted limitation is
confined to validator-disabled mode; edge backfill is out of scope.

## Ownership and Lifetime

The indexes do not retain entries forever. Every index record has an owning
mempool transaction or a dependency between live entries. Transaction storage
and every derived index change atomically under the mempool mutation lane.

| Mempool transition | Required behavior |
|---|---|
| New transaction accepted | Add its projected outputs, consumed regular inputs, and dependency edges atomically. |
| Admission fails | Add nothing; no partial index records remain. |
| Duplicate submission | Return the existing result without duplicating records, accounting, or accepted events. |
| Transaction expires | Remove it and recursively remove descendants that can no longer resolve their parent. |
| Count/byte/index limit reached during admission | Reject the newcomer without evicting existing entries. |
| Configured count/byte limit reduced below current usage | Evict oldest roots as background reconciliation and cascade to newly orphaned descendants. |
| Transaction included in a block candidate | Keep it and every index entry in the mempool until canonical application. |
| Transaction confirmed in an applied block | Remove it without cascading to descendants; its outputs are now supplied by canonical `UtxoState`. This applies regardless of how or by whom the block was produced. |
| Block forging fails | Change nothing in the mempool. |
| Rollback | Revalidate the remaining derived view against restored `UtxoState`; remove transactions whose inputs no longer resolve and cascade to descendants. |
| Clear/close | Clear transaction entries and every derived index in the same serialized transition. |
| Process restart | Restore nothing; the mempool and indexes are in-memory only. |

Canonical UTXO application must precede confirmed-entry removal. In synchronous
UTXO mode this is event ordering. An asynchronous UTXO implementation must
delay mempool confirmation cleanup until its apply acknowledgement. The initial
design does not add an output-only confirmation bridge: retaining the bounded
mempool entry until canonical visibility is simpler and prevents the
forge-to-apply resolution gap.

## Index Model

The initial implementation maintains these logical indexes:

```text
transactionsByHash
    txHash -> MempoolEntry

producedByOutpoint
    outpoint -> ProducedOutput(ownerTxHash, outputProjection)

spentByOutpoint
    regularInputOutpoint -> spendingTxHash

parentsByTransaction
    childTxHash -> parentTxHashes

childrenByTransaction
    parentTxHash -> childTxHashes
```

`parentsByTransaction` includes dependencies discovered through regular,
reference, and collateral inputs. Reference inputs do not consume an outpoint,
but removal of their unconfirmed producer still invalidates the child.

`MempoolEntry` may hold a transaction's extracted UTXO projection so the body is
parsed once. It must not copy the raw transaction body. The existing
`MemPoolTransaction.txBytes` remains the single CBOR owner.

The physical representation should minimize heap amplification:

- use compact immutable binary transaction-id/outpoint keys instead of repeated
  hex `String` values;
- never use raw `byte[]` directly as a map key; implement value equality with an
  immutable wrapper and defensive copying;
- share a transaction-id object within an entry and its indexes;
- reference an output projection rather than copying it between indexes;
- do not retain a complete CCL `Transaction` after extracting required fields;
- do not duplicate datum or reference-script bytes when immutable entry-owned
  data can be referenced safely;
- remove empty parent/child sets immediately.

The output projection preserves the validation-relevant information exposed by
persistent `UtxoState`: address, lovelace, native assets, datum hash, inline
datum, and reference-script information.

Only normal outputs of a transaction admitted as valid enter
`producedByOutpoint`. A collateral-return output materializes only on phase-2
failure and is not indexed as a produced output for an admitted valid
transaction (`isValid=true`). Validator-disabled mode also does not treat the
collateral-return field as a normal output.

## Ordering and Block Selection

A child can only resolve a mempool output after its parent commits. Insertion
order is therefore a topological order for UTXO dependencies:

```text
A accepted -> B accepted -> C accepted
```

Block selection iterates an insertion-ordered snapshot and applies each
accepted candidate to a block-local overlay. Applying a selected transaction:

1. marks its regular inputs consumed;
2. adds each normal output under `(txHash, outputIndex)`;
3. leaves reference inputs unconsumed;
4. makes its outputs available to later candidates.

The snapshot is captured under the mempool lane, then validated and forged from
its immutable transaction-body references after releasing the lane. It cannot
observe a partially committed admission or partially removed chain.

Yano initially enforces one in-flight block-selection/forge attempt per block
producer. The selector does not remove or reserve individual mempool entries.
Selected entries remain ordinary live entries and remain visible to admission
while the block is forged and applied. A child submitted in that window can
therefore resolve a selected parent's output. A failed forge leaves the
mempool unchanged.

A subsequent selection attempt cannot start until the previous attempt has
either failed or reached local block-apply handling. This single-producer
invariant replaces per-entry `PENDING`/`RESERVED` states, reservation ids,
cancellation transitions, and leases. If Yano later supports concurrent block
builders, that requires a separate reservation design.

Transactions omitted because of block size or execution budget remain in the
mempool. If a parent is selected but its child is omitted, canonical application
removes the parent without cascading to the child; the child then resolves the
parent output from `UtxoState`.

A parent rejected during block selection is not applied to the block-local
overlay. Remove it with reason `INVALIDATED` and cascade to its descendants
rather than silently retaining a chain that cannot be selected.

TTL reconciliation can race a forge snapshot without making the candidate
block invalid because the snapshot owns the selected transaction bodies and
uses its own UTXO overlay. It may reduce availability by removing an unconfirmed
chain while a block is being forged. This is accepted for the initial design;
the normal TTL is far longer than forge-to-apply latency. Selection does not
extend transaction lifetime or create unbounded protected entries.

## Memory Bounds and Pressure Control

The existing limits make the overlay finite, but serialized CBOR byte size is
not a sufficient heap guarantee. Maps, sets, keys, assets, and UTXO projections
can amplify retained heap.

The overlay therefore has an independent hard cardinality budget:

```yaml
yano:
  tx:
    mempool:
      max-txs: 10000
      max-bytes: 134217728
      ttl-seconds: 10800
      max-utxo-index-entries: 100000
```

`max-utxo-index-entries` counts physical records across produced, spent, parent,
child, and reference-script indexes. The default is 100,000. Operators with a
larger measured heap envelope can raise it explicitly.

The acceptance profile on 2026-08-16 filled an adversarial mempool with 9,615
transactions containing 25 outputs and one regular input each. The index stopped
at 249,990 physical records under a temporary 250,000-record limit. After forced
GC, used heap increased from 31.9 MiB with an empty mempool to 567 MiB at the
limit: approximately 535 MiB retained, or 2.2 KiB per physical record for that
transaction shape. Raw transaction CBOR was only 16.4 MiB.

That result closes the profiling gate and makes 250,000 too costly as a general
default. The 100,000-record default would retain approximately 214 MiB if the
profiled workload scaled linearly; this is sizing guidance, not a JVM-independent
guarantee. It still permits the 10,000-transaction limit for common independent
payments (about three records per transaction) and simple parent-linked payments
(about five records per transaction), while complex transactions encounter
explicit `INDEX_CAPACITY` backpressure sooner.

Before commit, admission calculates every record the candidate would add. If
transaction count, raw bytes, or index cardinality would exceed a hard limit,
it rejects the newcomer without mutating or evicting existing entries. This
reject-newcomer policy avoids allowing a small submission stream to repeatedly
evict an old chain and all descendants. TTL, confirmation, operator clear, or
background reconciliation after a configured limit reduction frees capacity.

Expose at least:

- current and configured transaction count and raw bytes;
- current and configured UTXO-index record count;
- produced-output, consumed-outpoint, and dependency-edge counts;
- cascaded-removal count by reason;
- duplicate, conflict, and capacity rejection counts;
- current admission queue/wait count;
- admission-lane wait and hold duration;
- ledger-validation duration;
- slow validation-listener count;
- estimated index bytes, explicitly labeled as a structural estimate rather
  than a retained-heap forecast.

Exact retained heap is JVM- and workload-dependent. In the acceptance profile,
the existing structural estimate reported about 62 MiB while the entire mempool
overlay retained about 535 MiB. The values measure different things: the latter
also includes transaction bodies, projections, decoded UTXOs, collection
capacity, and object overhead. Operators must therefore use the hard cardinality
limit and measured workload profiles for heap sizing, not treat the structural
metric as a heap commitment. A calibrated whole-overlay retained-heap estimator
is deferred until multiple representative transaction shapes have been profiled.

## Removal Semantics

Removal APIs use an explicit reason. A generic
`removeByTxHashes(Set<String>)` cannot express dependency behavior.

Initial reasons are:

- `CONFIRMED`
- `EXPIRED`
- `CONFIG_RECONCILIATION`
- `INVALIDATED`
- `EVENT_ROLLBACK`
- `CLEAR`

`CONFIRMED` applies to any mempool entry included in an applied block, regardless
of whether Yano produced that block. It removes the transaction body,
regular-input claims, produced-output records, and obsolete dependency edges
without cascading to descendants. Canonical `UtxoState` now supplies the
confirmed outputs.

`EXPIRED`, `CONFIG_RECONCILIATION`, `INVALIDATED`, and `EVENT_ROLLBACK` removal
of a parent cascade transitively to descendants that depend on it. `CLEAR`
removes the complete mempool and all indexes in one transition.

Background reconciliation may remove more transactions than the original
count/byte excess when the oldest entry is a chain root. Logs and metrics must
distinguish directly selected removals from cascaded removals.

## Canonical-State Concurrency

The single-writer lane serializes mempool mutations; it does not by itself stop
canonical ledger application on another subsystem thread. For the initial
implementation, Yano requires the following event order:

1. apply the block to canonical `UtxoState`;
2. acknowledge UTXO visibility;
3. process confirmed mempool removal under the mempool lane.

This prevents removing a confirmed parent's overlay outputs before its children
can resolve them canonically. It also prevents releasing mempool spent claims
before canonical removal is visible. Async UTXO mode must use its completion
acknowledgement rather than raw `BlockAppliedEvent` publication for step 3.

A chain-state change can still begin while a long admission validation is in
progress. The initial implementation accepts this narrow existing race and
revalidates candidates during block selection against the latest block-local
view. Closing it requires a canonical ledger snapshot/version protocol and is
outside the issue #66 fix.

## Failure Handling

Failure to deserialize, project, resolve, or validate a transaction is an
admission failure and leaves the mempool unchanged.

An invariant failure during index mutation fails closed:

1. stop accepting new transactions;
2. rebuild indexes from authoritative insertion-ordered transaction entries;
3. resume only if the rebuilt view passes consistency checks;
4. clear the in-memory mempool if safe rebuild is impossible.

The node must not continue admission with transaction entries and indexes known
to disagree.

## Alternatives Considered

### Scan and deserialize the full mempool for every admission

Rejected. Admission cost becomes proportional to mempool size and transaction
complexity, creating avoidable CPU and latency risk under diffusion.

### Keep only a produced-output index

Rejected. It supports simple chaining but does not prevent conflicting spends
or support correct descendant removal.

### Optimistic parallel validation with resolution proofs

Deferred. It keeps phase-2 evaluation outside the commit lock but requires
immutable admission snapshots, UTXO fingerprints, proof rechecks, transient
stale-view errors, plugin idempotency rules, and retry semantics. That
complexity is not justified before serialized admission is measured.

### Per-entry block reservations and leases

Rejected for the initial single-producer design. Keeping selected entries until
canonical application and enforcing one in-flight forge attempt closes the
forge-to-apply gap without pending/reserved states or lease recovery.

### Admit chains but defer conflict checking to block selection

Rejected. It wastes validation and diffusion resources and lets incompatible
transactions consume bounded capacity.

### Persist the overlay in RocksDB

Rejected. Mempool state is intentionally ephemeral. Persistence introduces
recovery, migration, and canonical/tentative reconciliation without being
required for issue #66.

### Copy mempool outputs into persistent `UtxoState`

Rejected. `UtxoState` represents canonical applied-chain state. Tentative
outputs would contaminate queries, rollback, stake accounting, and other ledger
consumers.

## Consequences

Positive:

- Sequential parent/child submission works to arbitrary UTXO-chain depth.
- Conflicting spends cannot both commit to the mempool.
- Admission lookup cost depends on candidate inputs, not total mempool size.
- Admission and block selection use equivalent UTXO overlay semantics.
- Insertion order is dependency order for accepted chains.
- Parent removal cannot leave silent orphan descendants.
- Index ownership, lifetime, and memory bounds are explicit.
- `DefaultMemPool` remains replaceable behind the `MemPool` semantic contract.

Tradeoffs:

- Stateful ledger validation, including Plutus phase-2, is serialized.
- A slow validation plugin stalls later admissions and must obey the listener
  contract.
- A concurrently submitted child can be rejected if it reaches the lane before
  its parent.
- The overlay consumes bounded but material heap; the profiled 100,000-record
  default is expected to retain roughly 214 MiB under the adversarial acceptance
  workload, with actual retention dependent on transaction shape and JVM.
- Removing one non-confirmed parent can cascade through a large chain.
- Correct confirmation cleanup requires ordering against canonical UTXO apply.
- Transactions removed as confirmed are not automatically re-admitted if that
  block is later rolled back; clients or peers must resubmit them.
- Only UTXO chaining is supported; cross-transaction certificate and governance
  state dependencies remain unsupported.

## Implementation Plan

1. Introduce compact immutable transaction-id/outpoint keys and an extracted
   UTXO projection that does not duplicate transaction CBOR.
2. Replace raw `addTransaction` with typed `tryAdmit` semantics and make
   `DefaultMemPool` own the fair single-writer admission/mutation lane.
3. Add produced, spent, parent, and child indexes plus exact cardinality
   accounting under that lane.
4. Pass the lane-stable UTXO resolver to the built-in validation listener and
   enforce the validation-listener contract, including fail-fast recursive
   admission.
5. Commit transaction storage and every index atomically; publish the accepted
   event only for a newly inserted entry and make event-failure rollback atomic.
6. Add reject-newcomer transaction, byte, and index capacity enforcement.
7. Replace generic removal internals with reason-aware atomic removal and
   transitive descendant cleanup.
8. Extend the block-local overlay to add outputs, retain selected entries until
   apply, and enforce one in-flight block-selection/forge attempt.
9. Order confirmed removal after synchronous UTXO apply or async UTXO apply
   acknowledgement, and add rollback revalidation.
10. Add metrics and heap-profile representative and adversarial full-mempool
    workloads; use the measured result to set the accepted 100,000-entry default.

## Verification

Unit and integration tests must cover:

- parent/child and at least three-level chains;
- chains using regular, reference, and collateral input resolution;
- native assets, inline datum, datum hash, and reference-script outputs;
- exclusion of collateral-return outputs for valid transactions;
- child-before-parent rejection;
- submit-await-submit parent/child success;
- concurrently submitted parent/child ordering in both lock orders;
- sequential and concurrent double-spend rejection;
- duplicate submission without duplicate accounting or accepted events;
- typed admission results and removal of the `alreadyPresent` race;
- binary key value equality and defensive-copy behavior;
- serialized stateful validation and atomic index commit;
- fail-fast recursive admission from a validation listener;
- slow-listener and admission-lane timing metrics;
- validator-disabled malformed projection, conflict, duplicate, and capacity
  behavior;
- validator-disabled unresolved-child edges are not backfilled;
- atomic rollback after accepted-event publication failure;
- complete index cleanup for every removal reason;
- transitive TTL and configuration-reconciliation removal;
- transaction, byte, and index pressure rejects the newcomer without evicting
  existing chains;
- parent confirmation without child removal, including confirmation by another
  producer;
- canonical UTXO visibility before confirmed overlay cleanup in synchronous and
  asynchronous apply modes;
- rollback invalidation and index rebuild;
- complete-chain block selection in insertion order;
- child admission while its parent remains present during forge-to-apply;
- child retention when its parent fits but it does not fit the block;
- forge failure leaves the mempool unchanged;
- a second selection cannot start while one forge attempt is in flight;
- block-local revalidation after a canonical-state change during admission;
- exact index-capacity rejection without temporary overshoot;
- retained-heap measurements at configured capacity;
- admission throughput and latency for script-free and script-heavy workloads.

After automated tests, perform a deliberate signed back-to-back chained submit
on devnet and preprod. Verify that both transactions are accepted before the
parent is confirmed, diffuse normally, and can be selected in dependency order.
