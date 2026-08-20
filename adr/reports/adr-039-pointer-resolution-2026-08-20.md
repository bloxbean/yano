# ADR-039 pointer-address resolution — design, divergence, and open decision

**Date:** 2026-08-20
**Branch:** `feat/adr-039-canonical-projection-outbox`

## What was built

Pointer-address resolution moved from an archive-private sequential resolver to the
authoritative account-state contributor, resolved at **capture** time.

```
DefaultAccountStateStore  implements PointerCredentialSource   (core-api SPI)
        -> RuntimeNode.pointerCredentialSource()
        -> Yano.pointerCredentialSource()
        -> ProjectionHistoryService
        -> CanonicalProjectionCollector
        -> ProjectionPointerResolution.resolve(fact, blockSlot, source)
```

The collector writes one of `pointer_resolved` / `pointer_unresolved` /
`pointer_not_effective` into the fact before the envelope is complete. Consequences:

- the sink holds **no resolver state** and can never fail for lack of it;
- `archive-store-ducklake` keeps its `archive-api`-only dependency;
- a missing mapping is `pointer_unresolved`, never an exception.

Resolution consults, in order: an **in-block overlay** built from this block's own
registration/deregistration certificates sorted by `(txIndex, certIndex)`, then the
authoritative mapping.

### Why it is replay-deterministic

Core's mapping is **append-only**: `DefaultAccountStateStore` writes
`PREFIX_POINTER_ADDR | slot | txIdx | certIdx -> credType | credHash` on registration
(one `batch.put`, delta-tracked for rollback) and there is **no corresponding delete**
anywhere in the file. So:

- a coordinate resolvable when block N was first applied stays resolvable on replay;
- a coordinate registered *after* block N cannot satisfy a pointer in block N, since the
  key's slot is the registering certificate's own slot.

A pointer address can encode arbitrary numbers, so resolution additionally refuses any
coordinate whose slot is **newer than the block being projected**. Without that guard a
coordinate registered later in the chain could resolve during a replay while being
unresolvable on the first pass. Two tests cover this directly.

## The divergence, and it is not a free choice

12 differential tests run against the **real** shipped sequential resolver as oracle. All
agree except one case, which is pinned by
`crossBlockDeregistrationBehaviourIsRecorded`:

| scenario | shipped sequential resolver | this implementation |
|---|---|---|
| register (block 1), deregister (block 2), pointer used (block 3) | `pointer_unresolved` | `pointer_resolved` |

The shipped resolver calls `deleteCredential`, removing every coordinate that maps to a
deregistered credential. Core's append-only mapping keeps it.

### Which is correct

**The shipped resolver matches Cardano ledger semantics, and this implementation does
not.** In the Shelley-era ledger the delegation state holds
`_ptrs :: Map Ptr (Credential 'Staking)`, and processing a key-deregistration certificate
removes the credential from the reward and delegation maps *and* removes the pointer
entries that map to it. A pointer address whose target credential has been deregistered
therefore resolves to no stake credential.

Core's mapping is not wrong for core's own purpose: it is consulted while building
delegation snapshots, where a deregistered account is already excluded by account state,
so a stale pointer entry is harmless there. It is only wrong as a *direct* source for the
archive's `transaction_outputs.stake_credential`.

This is recorded as a **fidelity gap**, not a preference. Per the project's standing rule
that cardano-ledger is authoritative for ledger semantics, the shipped behaviour is the
target.

### Why it was not simply fixed

Every obvious fix breaks replay determinism, which ADR-039 requires
("contributor recovery must not depend on mutable state that has advanced beyond the
replayed block"):

1. **Delete the mapping on deregistration in core.** Replaying block N while the store is
   at N+100 would then see a coordinate deleted at N+50 as already gone, producing a
   different answer than the first pass.
2. **Consult account registration status at resolution time.** Same defect: "is this
   credential registered" is a property of *now*, not of block N.
3. **Keep a deregistration tombstone in the contributor's own column family.** Replay-safe
   and ledger-correct, but reintroduces an archive-owned pointer lifecycle — the thing
   moving resolution to core was meant to remove.
4. **Resolve as-of a slot.** Correct in principle and replay-safe, but requires core to
   retain a *historical* registration timeline keyed by slot, which it does not have. This
   is the only option that is both correct and clean, and it is a real core change.

### Scope of the gap

Narrow, and quantifiable:

- pointer addresses are pre-Conway only — Conway records `pointer_not_effective` and is
  unaffected;
- core's own javadoc puts pointer usage at **under 1% of mainnet UTXOs**;
- of those, only outputs whose target credential was deregistered in a **later block** are
  affected — same-block deregistration is handled correctly by the overlay and agrees with
  the oracle.

The affected rows record a credential where the shipped archive records none. No row is
lost, no exception is raised, and coverage is unaffected.

## Decision requested

Option 4 (as-of-slot resolution backed by a retained registration timeline in core) is the
only variant that is both ledger-correct and replay-deterministic. It is a genuine core
change and was not undertaken without a decision, since ADR-039's instruction is to keep
core changes minimal.

Until that decision is recorded, this is **not complete** and should not be treated as
such. The three viable paths:

1. accept the narrow divergence and document it in the archive's query contract;
2. implement option 4 (core retains a slot-keyed registration/deregistration timeline);
3. implement option 3 (contributor-owned tombstones, atomic with the contributor batch)
   and accept that the archive keeps a small pointer lifecycle after all.

## Test coverage

`PointerResolutionDifferentialTest` — 12 tests, oracle = the shipped sequential resolver
backed by a real `RocksDbHotHistoryStore`:

same-block registration and use; cross-block registration and use (genuine two-block
oracle); missing mapping becomes unresolved rather than throwing; Conway not-effective;
same-block deregistration; re-registration after deregistration; unrelated deregistration
does not remove the coordinate; future-coordinate pointer stays unresolved on replay;
replay with an advanced mapping reproduces the same result; resolution is idempotent;
non-pointer addresses untouched; and the cross-block deregistration divergence above.

Deliberately **not** used as oracle: the resolver-less `UtxoHistoryDataset` constructor.
Comparing two resolver-less paths is tautological for pointer addresses, which is exactly
how the original crash-on-pointer defect stayed hidden through a full parity suite.
