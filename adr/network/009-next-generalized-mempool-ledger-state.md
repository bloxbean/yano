# ADR-NET-009-NEXT: Generalized Mempool Ledger State and Advanced Transaction Workflows

## Status

Deferred future design note. This is not part of the ADR-NET-009 implementation
or acceptance gate.

## Date

2026-08-15

## Parent Decision

[ADR-NET-009: Bounded Single-Writer Mempool UTXO Overlay for Chained Transactions](009-mempool-utxo-overlay-for-chained-transactions.md)

## Purpose

ADR-NET-009 deliberately solves the immediate transaction-chaining problem with
a bounded UTXO-only overlay. This companion records how that foundation may
evolve after the initial implementation is stable and measured.

The long-term direction is a generalized mempool ledger state similar in
semantics to the Haskell node: each accepted transaction is applied to the
state produced by all transactions before it, and the resulting state is used
to validate the next transaction. This would permit dependencies through any
transaction-driven ledger transition that Cardano rules make immediately
visible, rather than only through created and consumed UTXOs.

This note also records advanced capabilities enabled before and after that
generalization. None of them expand the scope of issue #66.

## Foundation Inherited from ADR-NET-009

The following decisions are intentional foundations, not temporary limitations:

- one authoritative admission path for local, N2N, and diffusion submissions;
- one ordered, single-writer state transition for stateful validation and
  commit;
- a typed admission result instead of separate `contains` and raw `add` calls;
- atomic transaction, conflict, dependency, and accounting mutation;
- successful admission order as the deterministic transaction order;
- bounded transaction bodies and derived state;
- reason-aware removal and canonical-chain reconciliation;
- a semantic `MemPool` contract that permits implementation replacement;
- immutable block-selection snapshots and one in-flight forge attempt in the
  initial producer design.

These properties allow the state representation to grow from a UTXO overlay to
a complete ledger transition without changing the fundamental admission model.

## Concurrent Submission Semantics

Clients may submit transactions concurrently. The single-writer lane orders
stateful admission internally; it does not require all clients to serialize
independent submissions.

| Concurrent submissions | Expected result |
|---|---|
| Independent A and B | Both wait for their turn and normally succeed. |
| Parent P and child C, P enters first | P commits; C resolves P's output and succeeds. |
| Parent P and child C, C enters first | C is rejected with `UtxoNotFound`; the client retries after P succeeds. |
| Conflicting A and B | The first valid commit claims the state; the second is rejected. |
| Duplicate A and A | One insertion; the other receives a duplicate result without another accepted event. |

The client retry limitation applies to unresolved dependencies, not to parallel
submission generally. Package admission described below can remove this race
for a caller that already knows the complete dependency set.

## Validation Throughput Clarification

Yano owns the built-in transaction-validation listener, so arbitrary third-party
plugin behavior is not an immediate operational risk. The relevant initial
tradeoff is that expensive stateful validation, especially Plutus phase-2
execution, delays later admissions on the same lane.

Listener restrictions remain an extension contract in case third-party
stateful validators are supported later. Performance work should first measure
the built-in validator rather than introducing optimistic concurrency in
anticipation of unknown plugins.

## Future-Proof Admission Contract

The public mempool contract should not permanently expose a UTXO-specific
resolver as its top-level abstraction. Prefer an opaque or extensible admission
context, conceptually:

```java
interface MempoolAdmissionContext {
    UtxoResolver utxos();

    // Added only by a future generalized implementation:
    // LedgerStateView ledgerState();
}

MempoolAdmissionResult tryAdmit(
        byte[] txCbor,
        AdmissionValidator validator);
```

Alternatively, keep the context completely internal to `MemPool` and expose
only the semantic `tryAdmit` operation. The important constraint is that callers
must not depend on the concrete produced/spent maps from ADR-NET-009.

## Generalized Mempool Ledger State

The future model is:

```text
canonical ledger snapshot
        |
        v
apply accepted transaction A
        |
        v
mempool ledger state after A
        |
        v
apply accepted transaction B
        |
        v
mempool ledger state after A, B
```

Validation should return evidence of the transition rather than only a boolean:

```text
ValidatedTransition
    validated transaction
    ledger delta
    validation/execution measurements
    optional state read/write keys
```

The implementation must not retain a complete deep copy of ledger state per
transaction. Candidate representations include:

- an immutable base snapshot plus ordered deltas;
- periodic checkpoints plus deltas between checkpoints;
- copy-on-write/persistent state structures;
- ledger tables and transaction-local diffs similar in principle to the
  Haskell consensus implementation.

UTXO indexes from ADR-NET-009 may remain as fast derived indexes. They cease to
be the complete tentative state once generalized ledger deltas are introduced.

## Ledger-State Domains

A generalized transition may include transaction-driven changes to:

- UTXOs, native assets, datums, and reference scripts;
- stake credential registration and deregistration;
- delegation state;
- reward withdrawals and balances;
- pool registration and retirement state;
- deposits and refunds;
- DRep registration and delegation;
- governance proposals, votes, and committee-related state;
- other ledger state required by the active era's transaction rules.

This does not imply that every earlier transaction effect is immediately usable.
Some effects become active only at an epoch boundary or another protocol-defined
transition. The actual Cardano ledger rules, not a custom mempool shortcut,
determine whether a later transaction is valid.

## Removal, Rebase, and Revalidation

Full ledger state makes removal more general than UTXO descendant cascading.
Removing transaction A may invalidate any later transaction whose validation
observed A's state transition, even without a UTXO edge.

Two implementation strategies should be evaluated:

1. **Ordered suffix reapplication:** remove A and reapply every later
   transaction in admission order, dropping those that no longer validate.
   This is simple and matches the ordered virtual-ledger model.
2. **State-key dependencies:** record ledger keys read and written by every
   transaction and revalidate only the affected closure. This can be faster but
   is significantly more complex and requires complete read-set reporting from
   ledger validation.

The initial generalized implementation should prefer suffix reapplication
unless profiling proves it inadequate.

When the canonical chain advances or rolls back:

1. obtain a coherent canonical ledger snapshot at the new point;
2. replay accepted mempool transactions in order;
3. retain transactions that still validate;
4. drop invalid transactions and any later transactions invalidated by their
   absence;
5. atomically publish the rebuilt mempool state.

Reapplication may skip cryptographic work already proven invariant, but it must
repeat all checks that can change with ledger state, slot, or preceding
transactions.

## Future Capabilities

### Transaction package admission

Accept an ordered parent/child package as one operation:

```text
tryAdmitPackage([P, C1, C2])
```

The package is validated against one admission context and committed atomically:
all transactions enter in dependency order or none enter. This removes the
parent/child lock-order race for callers that possess the whole chain.

Package limits must bound transaction count, bytes, execution units, index
growth, and validation time. Package admission is a local mempool guarantee,
not a protocol guarantee that another producer will include the package
atomically.

### Mempool-aware construction and evaluation

Expose an opt-in API that resolves and evaluates against tentative mempool
state. It can support:

- constructing a child before its parent confirms;
- evaluating a complete package;
- reporting the transaction that invalidates a package;
- predicting UTXOs, deposits, refunds, and immediately visible ledger state;
- reporting bytes, fees, execution units, and reference-script costs;
- checking whether a package can fit in a candidate block.

Responses must identify the mempool generation or snapshot used so clients can
recognize that the result is tentative.

### Dependency-aware diffusion

Use dependency data to:

- announce parents before children;
- avoid offering a child before its required ancestors;
- request missing ancestors;
- diffuse bounded packages;
- suppress repeated dissemination of invalid orphan chains.

This requires capability negotiation if package transfer extends the existing
TxSubmission protocol. Parent-first ordering can be improved without a protocol
extension.

### Dependency-aware block packing

Evaluate a transaction together with required ancestors using:

- package fee and fee density;
- total serialized bytes;
- execution-unit memory and steps;
- reference-script cost;
- dependency depth;
- validity interval and expiry risk.

Selection must remain deterministic, topological, and constrained by Cardano
block limits. A policy may prefer a valuable child-plus-parent package without
ever placing the child before the parent.

### Package-aware eviction and admission control

Future policy may consider:

- descendant count and total descendant bytes;
- total validation/execution cost;
- cost of removing a chain root;
- package age and origin;
- conflict-set size;
- capacity reserved for local versus remote admission.

Any replacement or fee-priority policy requires a separate ADR. The initial
reject-newcomer policy remains authoritative until then.

### Conflict sets and replacement policy

Generalized state read/write information can identify transactions that cannot
coexist. A future explicit replacement policy could consider fee improvement,
execution cost, origin, age, and descendant impact. Replacement must never be
an accidental consequence of ordinary admission.

### Parallel validation of independent branches

After serialized admission is profiled, transactions with disjoint state access
may perform expensive validation concurrently:

```text
reads/writes(A) does not conflict with reads/writes(B)
```

Their final commits must still enter one deterministic order and recheck every
state-dependent precondition. UTXO disjointness alone is insufficient once
certificate and governance state is supported.

Parallel validation is a performance optimization, not a prerequisite for
concurrent client submission. It should be introduced only with evidence that
serialized phase-2 validation is a material bottleneck.

### Incremental revalidation

State-key dependencies may allow a block or rollback to revalidate only the
affected transaction closure. This follows, rather than precedes, a correct
full-state replay implementation.

### Persistence and recovery

A future implementation may persist transaction bodies, admission order, and
transition metadata using a journal or embedded store. Recovery must revalidate
against the current canonical ledger; persisted validation success is not
permanent evidence of validity.

Persistence is independent of full ledger-state chaining and requires its own
failure, schema, migration, and durability decision.

### Intent and workflow processing

On top of package validation, applications could submit an ordered intent or
workflow and request:

- all-or-nothing local admission;
- deterministic dependency diagnostics;
- candidate-block simulation;
- status across parent, child, and rollback transitions;
- certificate or governance workflows where ledger rules permit immediate
  state visibility.

This is a local node service, not a new Cardano atomic-transaction primitive.

## Comparison Target

The semantic reference for generalized state is the Haskell consensus mempool,
which validates transactions strictly in list order against the ledger state
produced by earlier entries and reapplies the ordered list after ledger changes:

- [Ouroboros mempool API](https://ouroboros-consensus.cardano.intersectmbo.org/haddocks/ouroboros-consensus/Ouroboros-Consensus-Mempool-API.html)
- [LedgerSupportsMempool](https://ouroboros-consensus.cardano.intersectmbo.org/haddocks/ouroboros-consensus/Ouroboros-Consensus-Ledger-SupportsMempool.html)

Dingo is a useful near-term structural reference for serialized UTXO-overlay
validation, dependency indexing, confirmed versus cascading removal, and
background revalidation:

- [Dingo mempool admission](https://github.com/blinklabs-io/dingo/blob/main/mempool/mempool.go#L1438-L1531)
- [Dingo dependency DAG](https://github.com/blinklabs-io/dingo/blob/main/mempool/dag.go#L31-L159)

Dolos demonstrates the simpler serialized-submission model and a durable
pending-to-finalized transaction lifecycle:

- [Dolos submission path](https://github.com/txpipe/dolos/blob/main/crates/core/src/submit.rs#L13-L79)
- [Dolos mempool contract and UTXO view](https://github.com/txpipe/dolos/blob/main/crates/core/src/mempool.rs#L138-L433)

Yano should use these as comparison points, not copy their storage, concurrency,
or lifecycle mechanisms without local measurements and requirements.

## Phased Roadmap

### Phase 0: Preserve the extension seam during ADR-NET-009

- Keep `MemPool` admission semantic and avoid exposing mutable UTXO indexes.
- Use an opaque/extensible admission context rather than a permanent public
  UTXO-only contract.
- Record validation time, lane wait/hold time, chain depth, and revalidation
  cost.

### Phase 1: UTXO-based advanced capabilities

- package admission;
- mempool-aware evaluation;
- parent-first diffusion;
- dependency-aware block packing;
- package-aware diagnostics and observability.

These features can use the bounded indexes from ADR-NET-009 without full ledger
state.

### Phase 2: Generalized ledger transitions

- define `MempoolLedgerState` and `ValidatedTransition`;
- integrate the active-era ledger transition engine;
- store bounded deltas/checkpoints;
- implement canonical rebase and ordered suffix replay;
- extend block selection to use the same transition semantics.

### Phase 3: Selective and parallel processing

- expose complete state read/write sets from validation;
- introduce affected-closure revalidation;
- validate independent branches concurrently;
- retain deterministic serialized commit.

### Phase 4: Optional durable and policy extensions

- journal/snapshot recovery;
- explicit conflict replacement;
- richer fairness and local/remote capacity policy;
- durable transaction-workflow status.

Each phase after Phase 0 requires a new accepted ADR or an explicit revision of
this deferred note.

## Activation Criteria

Begin a generalized-ledger-state ADR only when at least one of these is true:

- a real application requires certificate, delegation, withdrawal, pool, DRep,
  or governance dependencies across unconfirmed transactions;
- profiling shows repeated full revalidation or UTXO-only modeling is the
  dominant limitation;
- package admission or mempool-aware evaluation has demonstrated product value;
- Yano's ledger engine can return deterministic, bounded transaction deltas and
  reapply previously validated transactions safely.

Parallel validation additionally requires evidence that serialized validation
is a material throughput bottleneck under representative script workloads.

## Risks and Constraints

- Full ledger state increases heap use and revalidation complexity.
- Removing one transaction can invalidate a large ordered suffix.
- Epoch and slot transitions make tentative validity time-dependent.
- A custom approximation of ledger state can diverge from Cardano rules; the
  actual ledger transition engine must remain authoritative.
- State read/write sets are difficult to make complete and safe.
- Parallel execution must not make admission order nondeterministic.
- Mempool-aware APIs expose tentative results and require clear snapshot
  identity and invalidation semantics.
- Package, replacement, and prioritization policies can affect fairness and
  diffusion behavior and therefore require explicit decisions.

## Verification Gates for a Future Full-State Implementation

- certificate registration followed by a dependent transaction, where ledger
  rules make the transition immediately visible;
- delegation, withdrawal, pool, DRep, and governance sequences covering both
  valid and protocol-delayed effects;
- identical results between mempool replay and applying the same ordered prefix
  as block transactions;
- removal from the beginning, middle, and end of an ordered dependency chain;
- canonical roll-forward and rollback followed by deterministic replay;
- transaction package atomic admission and failure rollback;
- block selection preserving exactly the validated order;
- retained-heap and replay-time measurements at configured capacity;
- serialized-versus-parallel validation equivalence for independent branches;
- restart recovery revalidating rather than trusting stale persisted success;
- differential tests against the Haskell node for representative ledger-state
  transitions.

## Non-Decisions

This note does not choose:

- the concrete full ledger-state representation;
- checkpoint frequency;
- a persistence backend;
- a transaction replacement policy;
- fee-priority or fairness rules;
- a package wire protocol;
- a parallel validation scheduler;
- public intent/workflow API shapes.

Those choices remain deferred until measurements and concrete product use cases
justify them.
