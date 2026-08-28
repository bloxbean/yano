# ADR-041: Apply Byron Main Blocks to the Live UTXO Store

## Status

Accepted

## Date

2026-08-26

## Related decisions

- [ADR-NET-005](network/005-yaci-yano-data-flow-and-ordered-ledger-apply.md) —
  ordered block application, durable derived-state cursors, and reconciliation
- [ADR-025](025-bootstrap-partial-state-ledger-and-nonce-boundaries.md) —
  partial-state nodes must not present incomplete derived state as full ledger
  truth
- [ADR-039](in-progress/039-canonical-projection-outbox.md) — canonical
  projection history and its existing, archive-owned Byron carrier

## Related tracking

- [Issue #89](https://github.com/bloxbean/yano/issues/89) — point-aware
  rollback for Byron EBBs and same-slot successor blocks

## Context

Yano persists complete Byron main-block bodies in `ChainState`, but it does not
apply their transactions to the live UTXO store.

`BodyFetchManager.applyByronBlock` currently publishes a
`BlockAppliedEvent(Era.Byron, ..., block = null)`. Both
`DefaultUtxoStore.applyBlock` and the account-state stores return immediately
when `BlockAppliedEvent.block()` is null. UTXO reconciliation also detects
stored Byron bodies and skips them.

Consequently, the live UTXO store currently:

- inserts the Byron genesis distribution;
- does not consume genesis outputs spent by Byron transactions;
- does not create outputs produced by Byron transactions;
- cannot resolve a Shelley-era input that spends a Byron-created output;
- retains stale genesis entries until the existing Allegra bootstrap cleanup;
- cannot derive a reliable Shelley-start UTXO total for a custom network with
  Byron history.

This omission has not generally caused AdaPot verification to fail because
public networks bootstrap Shelley reserves from known Shelley-start constants,
not from the live UTXO column family. Byron addresses also carry no Shelley
stake credential, and AdaPot verification compares treasury and reserves rather
than enforcing a global UTXO conservation invariant. Those facts mask the live
UTXO defect; they do not make the state correct.

As amended by ADR-042, the archive projection consumes Byron main blocks from
`ByronMainBlockAppliedEvent` inside this UTXO batch. EBBs alone retain the
dedicated `ByronBlockProjectionEvent` because they have no UTXO transition.

### Constraints

- Do not change the established meaning of `BlockAppliedEvent.block() == null`
  for Byron and EBB consumers without a complete subscriber migration.
- Do not activate Shelley-only account, stake, nonce, governance, or app-chain
  logic for Byron transactions. The era-neutral projection hook may consume the
  UTXO transition as specified by ADR-042.
- Keep the existing Shelley+ `DefaultUtxoStore.applyBlock` path stable. This
  change must not turn it into an era-branching method with many Byron special
  cases.
- Byron and Shelley+ UTXO mutations must use the same column families, delta
  encoding, rollback mechanism, and durable applied cursor.
- A UTXO cursor may advance only in the same RocksDB `WriteBatch` as the state
  transition it acknowledges.
- Byron continuity is not a simple one-block/one-slot sequence: an EBB shares
  its block number with its predecessor and its slot with its successor.
  Cursor, reconciliation, and rollback rules must preserve both aliases.
- Reconciliation must reproduce the live path exactly from retained canonical
  bodies and must fail closed on malformed bodies.
- Byron epoch-boundary blocks do not change the UTXO set.

## Decision

### 1. Introduce one UTXO-relevant Byron event, for main blocks only

Add `ByronMainBlockAppliedEvent` to the public event model. It carries the
canonical coordinates and the native decoded `ByronMainBlock`:

```java
public record ByronMainBlockAppliedEvent(
        long slot,
        long blockNumber,
        String blockHash,
        ByronMainBlock block
) implements Event {}
```

`BodyFetchManager.applyByronBlock` publishes this event after the canonical body
has been stored and accepted, at the point where derived state is allowed to
apply. The existing generic `BlockAppliedEvent` with a null block remains for
compatibility with lifecycle and external subscribers. The existing projection
event also remains until projection ownership is changed by a separate decision.

There is no UTXO-facing EBB event. `BodyFetchManager.applyByronEbBlock` continues
to store and announce the canonical EBB and may emit its projection event, but
it does not ask the UTXO store to apply anything.

The explicit `MainBlock` name is intentional: an EBB cannot accidentally enter
the UTXO delta or cursor path through a broad `ByronBlockAppliedEvent` union.

### 2. Add a separate native-Byron apply method

Extend the UTXO writer contract with an era-specific entry point implemented by
`DefaultUtxoStore`:

```java
void applyByronBlock(ByronMainBlockAppliedEvent event);
```

The synchronous and asynchronous UTXO event handlers subscribe to the new
event. In asynchronous mode, Byron-main and Shelley+ work must use the same
single-thread executor so their order is preserved at the Byron/Shelley
boundary.

The new handler does not publish a second acknowledgement. The compatibility
`BlockAppliedEvent(block = null)` remains ordered after the native Byron event;
its existing handler publishes exactly one `UtxoStateAppliedEvent`, and only
after the preceding Byron UTXO commit has succeeded. If native Byron apply
fails synchronously, publication never reaches the compatibility event. In
asynchronous mode, the Byron task records failure under
`ByronMainBlockAppliedEvent.class`; the already-queued compatibility task sees
that failure and exits without acknowledging. The async handler's
`throwIfFailed` and `recordAsyncFailure` coverage must therefore include the
new event type. Client mode already forces async UTXO apply off, so historical
sync in that mode remains synchronous.

`DefaultUtxoStore.applyByronBlock` owns synchronization, the RocksDB batch, the
shared delta/cursor staging, and the atomic commit. It delegates interpretation
of the native Byron transaction model to a package-private collaborator defined
below.

The Byron application performs only Byron-relevant work:

- resolve and consume transaction inputs from `cfUnspent`;
- resolve outputs created earlier in the same block;
- write consumed records to `cfSpent` for rollback;
- create base58-address, lovelace-only outputs in `cfUnspent`;
- maintain the general address-hash index in `cfAddr`;
- record created and spent outpoints in the standard `UtxoDeltaCodec` format;
- atomically write the standard last-applied metadata.

It does not process:

- stake-balance deltas;
- Shelley payment-credential indexes;
- invalid-transaction or collateral rules;
- native assets, datum, or reference scripts;
- certificates, withdrawals, rewards, or governance;
- nonce evolution;
- Allegra transition work;
- the Shelley+ projection contribution path.

Byron outputs do not carry Shelley stake credentials, so skipping
`addStakeBalanceDelta` and `applyStakeBalanceDeltas` is an explicit era rule, not
an optimization that changes ledger meaning.

### 3. Compose a package-private `ByronUtxoApplier`

`DefaultUtxoStore` owns one package-private, final `ByronUtxoApplier`. The
applier is not a second UTXO store and does not extend `DefaultUtxoStore`.
Inheritance is rejected because Byron application shares the same database,
column families, cursor, rollback journal, pruning lifecycle, and query surface;
it is an era-specific mutation strategy, not an alternative store type.

The collaborator stages Byron-specific mutations into a caller-owned batch and
returns the outpoints needed for the common delta:

```java
final class ByronUtxoApplier {
    record ApplyResult(
            List<UtxoDeltaCodec.OutRef> created,
            List<UtxoDeltaCodec.OutRef> spent) {}

    ApplyResult stageBlock(
            ByronMainBlockAppliedEvent event,
            WriteBatch batch) {
        // Native Byron inputs, outputs, intra-block spends, and address index.
    }
}
```

`ByronUtxoApplier` may read `cfUnspent` to resolve inputs and stages writes to
`cfUnspent`, `cfSpent`, and the general `cfAddr` index. It never:

- calls `db.write`;
- opens or commits its own `WriteBatch`;
- writes `cfDelta` or last-applied metadata;
- owns synchronization, reconciliation, rollback, pruning, or reinitialization;
- subscribes to events;
- invokes account, stake, hard-fork, or projection logic.

RocksDB does not expose earlier writes in a `WriteBatch` to ordinary reads.
Input resolution therefore reads committed `cfUnspent` plus an explicit
intra-block output map. The map is updated as each transaction output is
staged, consumed in transaction order, and never replaced by an attempted read
from the uncommitted batch.

An unresolved ordinary input is observable. Both apply paths increment a
dedicated counter and emit a rate-limited warning containing the era, block
point, and outpoint. In an unfiltered, genesis-complete store, native Byron
apply fails the block before commit because such an input means the derived
state is already incomplete or the block was decoded incorrectly. A configured
selective store may legitimately omit an input; those misses use a separate
filtered-store counter and do not claim ledger-complete health. This also closes
the observability gap tracked by issue #87: after a complete Byron replay, a
Shelley input that cannot resolve a Byron-created output is always a defect,
not a condition to skip silently.

The initial policy is deliberately asymmetric: unresolved native Byron inputs
fail closed in an unfiltered full-state store, while the existing Shelley path
gains the counter and warning but remains warn-only. Harmonizing Shelley to
fail closed requires a separate compatibility decision and fixture evidence;
implementers must not infer it from the stricter Byron rule.

The store remains the sole commit owner:

```java
public synchronized void applyByronBlock(ByronMainBlockAppliedEvent event) {
    try (WriteBatch batch = new WriteBatch();
         WriteOptions options = new WriteOptions()) {
        var result = byronApplier.stageBlock(event, batch);
        stageDeltaAndCursor(batch, event.blockNumber(), event.slot(),
                event.blockHash(), result.created(), result.spent());
        db.write(options, batch);
    }
}
```

After a snapshot restore reopens RocksDB and replaces column-family handles,
`DefaultUtxoStore.reinitialize` must recreate or reinitialize the collaborator
before further application.

#### Byron genesis staging and AVVM classification

The existing Byron genesis orchestration remains store-owned. Its native Byron
output encoding and general address-index writes move to
`ByronUtxoApplier.stageGenesisOutputs` so genesis and transaction-created Byron
outputs cannot drift in base58 address handling, UTXO CBOR encoding, outpoint
keys, or address-index construction.

Fresh full-state initialization must preserve `nonAvvmBalances` and
`avvmBalances` as distinct inputs until their outputs have been staged. It must
not flatten them through `ByronGenesisData.getAllByronBalances()` before the
store records which outpoints are AVVM redeem outputs. A compatibility method
that accepts only a combined map cannot be used for this fresh initialization
path.

The applier returns the staged genesis outpoint keys, including the subset that
came from `avvmBalances`. `DefaultUtxoStore` commits the batch, marks stake-index
readiness where required by store lifecycle, and persists only that AVVM subset
for the Allegra transition:

```java
var result = byronApplier.stageGenesisOutputs(
        nonAvvmBalances, avvmBalances,
        slot, blockNumber, blockHash, batch);
stageByronGenesisOutpointKeys(batch, result.avvmOutpointKeys());
markStakeBalanceIndexReady(batch);
markByronMainApplyCapable(batch);
db.write(options, batch);
```

The existing Allegra bootstrap-removal wiring and `processAllegraRemoval` remain
in `DefaultUtxoStore`: they execute during a Shelley-family hard-fork transition
and are not Byron transaction application. They remove only persisted AVVM
outpoints that remain unspent at the boundary. Non-AVVM genesis outputs remain
ordinary UTXOs. This matches `cardano-ledger`'s
[Byron translation](https://github.com/IntersectMBO/cardano-ledger/blob/15eee8cb0adfe94f8e26a7057b734b85ff8ee94e/eras/shelley/impl/src/Cardano/Ledger/Shelley/API/ByronTranslation.hs#L111-L119),
which stashes only bootstrap redeem outputs, and its
[Allegra translation](https://github.com/IntersectMBO/cardano-ledger/blob/15eee8cb0adfe94f8e26a7057b734b85ff8ee94e/eras/allegra/impl/src/Cardano/Ledger/Allegra/Translation.hs#L50-L71),
which returns only those outputs to reserves. They may be
extracted later into a distinct `AllegraBootstrapUtxoRemover`, but must not be
folded into `ByronUtxoApplier` merely because they reference Byron genesis keys.

AVVM-key persistence is staged by the store into the same fresh-genesis batch
described in section 9. Keys never become durable before their corresponding
UTXOs commit.

This requires the chain `metadata` column-family handle and
`ByronGenesisUtxoMetadataStore` staging support to be wired before the
fresh-genesis batch runs. The current conditional wiring inside
`wireEpochBoundaryProcessor` is too late and may be skipped when its ledger
features are disabled; general runtime composition must install this dependency
before `runBootstrapRecovery`.

`computeTotalUtxoLovelace`, `removeUtxosByOutpointKeys`, and
`injectBootstrapUtxos` remain store-owned. They are era-neutral store operations;
the latter's “bootstrap” means partial-state injection rather than Byron.

### 4. Preserve two clear algorithms and share only persistence invariants

The existing Shelley+ `applyBlock` algorithm remains recognizably unchanged.
The Byron implementation is small and separate. A limited amount of duplicated
input/output plumbing is accepted to avoid `if (era == Byron)` branches
throughout collateral, stake, script, index, and projection code.

Only small, mechanically common persistence helpers should be shared. At
minimum, both paths use one helper that stages the delta and cursor:

```java
private void stageDeltaAndCursor(
        WriteBatch batch,
        long blockNumber,
        long slot,
        String blockHash,
        List<UtxoDeltaCodec.OutRef> created,
        List<UtxoDeltaCodec.OutRef> spent) {

    batch.put(cfDelta, blockKey(blockNumber),
            UtxoDeltaCodec.encode(blockNumber, slot, blockHash, created, spent));
    batch.put(cfMeta, META_LAST_APPLIED_SLOT, encodeLong(slot));
    batch.put(cfMeta, META_LAST_APPLIED_BLOCK, encodeLong(blockNumber));
}
```

The helper stages writes only; its caller commits the same `WriteBatch` that
contains all created, spent, and index mutations. Neither era writes cursor
metadata separately from its state transition.

Additional low-level helpers for encoding a created or spent record may be
shared when extraction is demonstrably behavior-preserving, but a broad
refactor of the Shelley path is not a prerequisite for this decision.

### 5. Define the UTXO cursor as the last applied main block

`META_LAST_APPLIED_BLOCK` and `META_LAST_APPLIED_SLOT` identify the latest main
block whose UTXO transition committed successfully.

For a Byron main block, the cursor advances to that block's difficulty-derived
block number and absolute slot. For an EBB, the cursor does not move. Byron's
actual sequence at an epoch boundary is:

```text
preceding main:  block N,   slot < S
EBB:             block N,   slot S
successor main:  block N+1, slot S
```

In other words, an EBB shares its block number with its predecessor and its
slot with its successor. This is correct for the UTXO cursor because an EBB:

- has no UTXO transition;
- does not advance Byron chain difficulty;
- shares the preceding main block's block-number coordinate.

Therefore, while the canonical tip is an EBB, the UTXO slot can remain at the
preceding main block. Its reported block number equals that preceding main
block, so reconciliation may correctly conclude that the UTXO store is current;
there is no missing state transition. When the successor main block arrives,
the cursor advances from block `N` to `N+1` even though the EBB and successor
main block have the same slot.

Both `applyBlock` and `applyByronBlock` initially use one era-neutral
applied-point observer. It records a warning and counter for apparent gaps or
conflicting duplicate coordinates but does not reject the block in this ADR's
first implementation. An identical replay of the already-applied main block
may be treated as idempotent only when its full point matches.

The observer must classify, rather than warn on, legitimate nonstandard cursor
states: no cursor after rollback before the first retained delta; the genesis
pseudo-point `(block 0, zero hash)` followed by a network whose first real block
is also numbered zero; and the partial-state bootstrap-injection cursor. Making
this observer fail closed is a later hardening step gated on a fixture matrix
covering mainnet, preprod, preview, block-producing devnet, and ADR-025
bootstrap state. Exact-point rollback validation remains fail-closed; only the
new apply continuity policy begins as diagnostic.

Main-block continuity must not require the incoming main block's `prevBlock`
hash to equal the latest UTXO delta's hash. Immediately after an EBB, the
incoming main block's direct parent is the ignored EBB, while the latest UTXO
delta belongs to the main block before it. Block-number monotonicity remains
continuous; direct-parent validation, if performed here at all, must resolve
the EBB bridge through canonical chain state.

### 6. Reconcile Byron main blocks through the same method

`DefaultUtxoStore.reconcile` no longer skips all stored Byron bodies. For a
stored Byron body it must:

1. identify the Byron envelope kind from the outer discriminator (`0` = EBB,
   `1` = main) without treating decoder success or malformed CBOR as an EBB;
2. deserialize a main block with `ByronBlockSerializer` and invoke
   `applyByronBlock`;
3. deserialize and ignore a valid EBB;
4. throw if the body is neither a valid main block nor a valid EBB.

Shelley+ bodies continue through `BlockSerializer` and `applyBlock`.
Account-state reconciliation continues to skip Byron because this ADR adds no
Byron account-state transition.

Rebuild reconciliation also reproduces the live Byron-to-Shelley boundary
capture. Immediately before applying the first non-Byron body, if
`shelley_start_utxo_total` is absent, it invokes the same shared
`LedgerStateSubsystem` helper used by `handleEraTransition`: compute the total
from the fully replayed Byron `cfUnspent`, persist it through
`EraMetadataStore`, and rebuild the lazy custom-network configuration. It does
not publish a synthetic `BlockAppliedEvent`, which would activate unrelated
subscribers. This makes clearing and re-deriving the value during the rebuild
procedure safe for custom networks.

Live sync and reconciliation thus meet at the public era-specific methods:

```text
live ByronMainBlock ───────────────────────> applyByronBlock

stored Byron body -> Byron deserializer ──> applyByronBlock

live Shelley+ Block ───────────────────────> applyBlock

stored Shelley+ body -> BlockSerializer ──> applyBlock
```

### 7. Preserve exact point identity through rollback

No Byron-specific rollback method is introduced. Both apply paths write the
same `UtxoDeltaCodec` records and the same `cfSpent` representation. Existing
rollback restores/deletes outpoints without needing to know the producing era.

Rollback identity is a complete chain point `(slot, blockHash)`, not a slot.
This matches Ouroboros Consensus, where non-genesis points contain both slot
and header hash and ledger/header rewind accepts only the exact point. Yano's
`RollbackEvent` already carries that identity in its ChainSync `Point`; no
rollback layer may discard the hash before selecting retained state.

This matters at the Byron boundary sequence shown above. After `main N+1` has
been applied at slot `S`, rollback to the EBB at `S` must remove the successor
main block and its UTXO delta. Rollback to `main N+1` at the same slot must
retain it. Slot-only comparison cannot distinguish those outcomes.

#### Point convention

For rollback targets, origin is identified by a null hash. Yaci's
`Point.ORIGIN` is `(0, null)`, while existing post-store compensation constructs
`(-1, null)`; both are accepted and normalized internally to one origin case.
The slot has no chain-coordinate meaning when the hash is null. A non-origin
target has a non-null, valid block hash; slot zero with a hash is therefore an
ordinary point, including the mainnet genesis EBB. An invalid non-null hash
fails before any store is mutated.

#### ChainState rollback

`ChainState` is a Yaci interface and its required `rollbackTo(Long)` method
cannot be replaced by Yano. Introduce a Yano-owned extension capability,
following `OriginRollbackCapable` and `ByronEbHeaderStore`:

```java
public interface PointRollbackCapable {
    void rollbackTo(Point target);
}
```

`DirectRocksDBChainState` and `InMemoryChainState` implement the capability in
addition to the external `ChainState` contract. Canonical network rollback,
post-store compensation, and Yano orchestration require the capability and pass
the original point without reducing it to a slot. If a configured ChainState
does not implement it, point-sensitive rollback fails closed.

The external `rollbackTo(Long)` override remains for binary and source
compatibility and is demoted to a documented legacy/admin slot-selection
helper. It is not removed, and production ChainSync or compensation paths do
not call it.

The RocksDB implementation resolves the supplied hash against the existing
main-block and EBB indexes and finishes only when both the resulting tip slot
and hash equal the target. The exact-point deletion predicate is used in both
`performFullRollback` and `performHeaderOnlyRollback`; the latter is not allowed
to stop merely because its current header has the target slot. In particular:

- targeting the EBB at `S` removes a same-slot successor main-block header and,
  for full rollback, its body, while retaining the EBB;
- targeting the successor main block at `S` retains that main block;
- a same-slot hash that is neither the indexed EBB nor the canonical main block
  fails closed;
- targeting origin uses the existing origin-clearing path.

Post-store compensation verifies the complete restored point. The existing
`ebb_by_slot0`, `slot_to_hash`, hash-keyed bodies/headers, and number indexes
contain enough identity. No ChainState schema migration or new index is
required.

#### UTXO rollback

`DefaultUtxoStore.rollbackTo(RollbackEvent)` reads `target.getHash()` once and
uses the block hash already stored in every standard `UtxoDeltaCodec` record.
While walking deltas newest-first, a delta is retained when:

```text
delta.slot < target.slot
    or
delta.slot == target.slot && delta.blockHash == target.hash
```

A same-slot delta with a different hash is reversed. For an EBB target there is
no EBB UTXO delta, so this reverses the successor main block at `S` and then
retains the preceding main block at an earlier slot. For a successor-main
target, the matching delta is retained.

For an origin target (null hash), the store bypasses the retain predicate,
reverses every retained delta, and clears the applied cursor.

Non-origin targets must provide a valid hash. If a same-slot retained candidate
has no stored hash, rollback fails closed rather than accepting ambiguous
state. Hash comparison happens on the delta already decoded by the existing
rollback loop; it adds no RocksDB lookup and requires no UTXO schema migration.

`DefaultUtxoStore.rollbackTo(RollbackEvent)` and its duplicated
`rollbackToSlot(long)` implementation are collapsed onto one private rollback
loop that accepts a validated point. Add a point-capable companion to
`RollbackCapableStore` for orchestration; `DefaultUtxoStore` implements it.
The required legacy `rollbackToSlot(long)` method remains as an explicitly
slot-selected compatibility entry point, but canonical and adhoc Yano paths do
not use it for the UTXO store.

The startup adhoc rollback command may still accept a slot from configuration,
but `RuntimeNode` first asks ChainState to resolve the canonical stored point at
or before that slot and logs the selected hash. At a slot containing both an
EBB and a successor main block, the legacy slot command deterministically
selects the main block; targeting the EBB requires an exact point. RuntimeNode
then passes the resolved point to point-capable derived stores, rolls ChainState
back last through `PointRollbackCapable`, and verifies slot and hash afterward.
Stores whose state genuinely has no same-slot distinction may continue to use
the resolved slot, but they are included in the identity-loss audit.

All rollback-capable stores and rollback/compensation callers are audited for
places that reduce a point to a slot. Stores with no state transition at an EBB
may retain the preceding main block as their semantic applied cursor, but they
must not retain state from the successor main block at the same slot or any
other state strictly after the requested point.

`DefaultAccountStateStore` was audited and remains slot-based by exemption: it
has no Byron/EBB state transition, and the successor main block at a shared
Byron slot also has no account-state transition. Its first state transition is
Shelley-family and therefore cannot create this same-slot ambiguity.

The ADR-039 projection outbox is included in that audit. Although projection
ownership remains separate from live UTXO state, it records Byron envelopes and
therefore cannot reduce `RollbackEvent.target()` to a slot. Its exact-point
rollback removes a same-slot different-hash successor and retains a matching
target when that target is still pending. The legacy slot method remains for
non-event administrative callers.

### 8. Preserve projection and filtering boundaries

The first implementation does not route Byron through the Shelley+
`CanonicalProjectionContributor.contributeBlock` hook. The archive's dedicated
Byron projection path remains responsible for Byron sections and EBB envelopes.
Removing the archive-owned Byron address resolver after the live UTXO store is
proven complete is a possible follow-up, not part of this decision.

`StorageFilter.acceptUtxoOutput(UtxoFilterContext, Block, TransactionBody)` is a
Shelley-shaped contract. Byron application must not invoke it with null or
fabricated Shelley objects. Add a binary-compatible, default-true Byron method:

```java
default boolean acceptByronUtxoOutput(
        UtxoFilterContext ctx,
        ByronMainBlock block,
        ByronTx tx) {
    return true;
}
```

`StorageFilterChain` and the plugin-context facade delegate this method just as
they delegate the Shelley method. The built-in address filter overrides both
methods through one context-only predicate. Existing third-party filters remain
binary compatible and accept Byron outputs by default; a plugin that wants
Byron-specific selection opts in by overriding the new method. No plugin
receives null `Block` or `TransactionBody` values.

Configured storage filters otherwise retain their existing meaning: a
selectively configured UTXO store is not a promise of a complete ledger UTXO
set. The default, unfiltered store applies every Byron output. Logs and health
metadata identify filtered mode so expected unresolved inputs are not confused
with corruption in a genesis-complete store.

### 9. Do not silently accept pre-decision databases as complete

A database that has crossed Byron under the old behavior can have a Shelley+
UTXO cursor while lacking all Byron-created outputs. Replaying only Byron blocks
on top of that post-Byron state is unsafe because Shelley outputs and stale
genesis entries may already be present.

Persist a versioned marker such as `utxo.capability.byron_main_apply.v1` in
`utxo_meta`. Its mechanics are part of the compatibility contract:

- For a fresh, genesis-complete database, a store-owned genesis batch stages
  the separately classified Byron non-AVVM and AVVM distributions, AVVM
  removal keys, stake-index readiness, and the capability marker. For a
  non-producer it also stages the Shelley genesis distribution. The marker is
  committed only with that batch, including on networks whose distributions
  are empty. After genesis configuration is resolved, this batch runs whenever
  both ChainState tips are empty and the full UTXO store is enabled; it is
  independent of genesis-file presence and client/server role. Thus in-memory
  devnets and fresh producer databases receive the marker too. In producer
  mode, Shelley initial funds are deliberately deferred to the existing
  producer path after the real genesis block is committed, so records carry
  that block hash and stake deltas are applied exactly once. Existing
  per-distribution methods may remain compatibility wrappers but are not a
  marker-writing authority.
- A successful main-block apply does not create the marker. Otherwise the first
  post-upgrade Byron or Shelley block could bless incomplete pre-decision
  state.
- Before fresh initialization or ordinary startup reconciliation, a missing
  marker with either a ChainState body tip or header tip means the database is
  historical and incompatible. Refuse startup with a
  rebuild/migration-required diagnostic regardless of era metadata or UTXO
  cursor presence; never backfill the marker. The only automatic marker source
  is the fresh-genesis batch while ChainState is empty.
- A full-state snapshot is accepted only when the restored UTXO state contains
  the marker. Marker validation runs after `reinitialize()` and before
  reconciliation or service resume. An unmarked full-state snapshot is
  refused, not upgraded in place.
- Bootstrap/partial-state databases remain governed by ADR-025, carry their
  partial-state identity separately, and do not use this marker to claim a
  genesis-complete history.

The supported rebuild procedure is explicit:

An operator selects it at startup with the one-shot
`yano.utxo.rebuild-unmarked-from-genesis=true` setting. The setting acts only
when ChainState is non-empty and the capability marker is absent. Once a
successful rebuild writes the marker, subsequent starts ignore it with a
warning so stale configuration cannot repeatedly rebuild state. Bootstrap
partial-state mode rejects the operation. Operators remove the setting after
success.

1. Stop apply, reconciliation, snapshot, and prune workers and hold the normal
   maintenance lock.
2. Clear `utxo_unspent`, `utxo_spent`, `utxo_addr`, `utxo_block_delta`,
   `utxo_meta`, `script_ref`, and `utxo_stake_balance`.
3. Clear chain-metadata values derived from that UTXO history:
   `allegra_bootstrap_done`, `byron_genesis_utxo_keys`, and
   `shelley_start_utxo_total`.
4. Explicitly re-seed both Shelley and Byron genesis distributions through the
   fresh-genesis batch, even though ChainState already has a non-null tip. The
   normal `initializeGenesisUtxos` tip-null guard will not do this for a rebuild.
5. Replay every retained canonical main-block body from genesis, ignoring valid
   EBBs. When replay first crosses from Byron to Shelley, recapture and persist
   `shelley_start_utxo_total` through the shared boundary helper before applying
   the first Shelley body. Only then finish replay and resume services.

`BlockPruner` may already have deleted old Byron bodies. If any canonical body
required by step 5 is absent, local reconstruction is impossible; the remedy is
a full resync (or a separately validated complete checkpoint/migration), not a
partial replay followed by writing the marker.

## Event and apply order

For a Byron main block, the ordered body-apply flow is:

```text
validate and store canonical Byron body
    -> publish epoch-transition events when applicable
    -> publish ByronMainBlockAppliedEvent
         -> apply Byron UTXO writes, delta, and cursor atomically
    -> publish compatibility BlockAppliedEvent(block = null)
         -> publish exactly one UtxoStateAppliedEvent after Byron commit
    -> publish Byron projection event
    -> record body applied
```

A synchronous UTXO failure propagates and causes the existing post-store
compensation/recovery path to run. An asynchronous failure is recorded by the
same ordered UTXO worker under the native event type; it suppresses the later
compatibility acknowledgement and is repaired from canonical bodies during
startup reconciliation, consistently with ADR-NET-005. Async apply is disabled
in client mode, so the normal historical-sync path receives the synchronous
failure semantics.

For an EBB, the UTXO steps are absent.

## Alternatives considered

### Put a normalized Byron `Block` in `BlockAppliedEvent`

Rejected for this change. It would activate every current non-null-block
subscriber, including Shelley account state, nonce evolution, app-chain
observers, mempool eviction, and the Shelley+ projection classification. Making
that event universally normalized may be a valid future contract, but it
requires a complete subscriber migration.

### One branch-heavy `applyNormalizedBlock`

Rejected. Byron needs only input/output UTXO transitions, while Shelley+ needs
collateral, invalid-transaction handling, stake deltas, payment credentials,
scripts, datum, assets, filters, transition behavior, and projection hooks.
Sharing the whole algorithm would introduce many era branches into a
correctness-critical hot path and increase Shelley regression risk.

### `ByronDefaultUtxoStore` extends `DefaultUtxoStore`

Rejected. Byron is not a different store implementation: it mutates the same
column families and participates in the same cursor, delta journal, rollback,
pruning, reconciliation, query, and RocksDB lifecycle. Inheritance would either
duplicate that ownership or expose implementation internals as protected
extension points. A package-private composed `ByronUtxoApplier` expresses the
actual relationship without creating a second store identity.

The name `ByronUtxoApplier` is also preferred over `ByronBlockApplier`. The
canonical block has already been validated and stored by `BodyFetchManager` and
`ChainState`; this collaborator derives only UTXO mutations from that block and
must not imply ownership of general block application.

### Two new UTXO events, one for main blocks and one for EBBs

Rejected. An EBB has no UTXO transition and must not advance the UTXO cursor.
An EBB event would create an opportunity to write an empty delta or incorrectly
move metadata to a non-state-changing slot.

### Reuse `ByronBlockProjectionEvent`

Rejected. Projection and live UTXO application have different ownership,
atomicity, failure, and EBB requirements. A projection-named carrier must not
become the runtime ledger contract by accident. ADR-042 therefore uses the
existing UTXO event for main blocks and narrows this carrier to EBBs.

### Continue storing only Byron genesis UTXOs

Rejected. It leaves live UTXO queries incomplete, prevents resolution of
Byron-created outputs, and makes custom-network Shelley-boundary accounting
depend on stale state.

### Defer point-aware rollback to a separate decision

Rejected. Public-network Byron history is immutable, but fresh historical
replay still uses the common post-store compensation path. Failure while
applying the main block immediately after an EBB can make that EBB the required
recovery target. Adding Byron UTXO state without making the existing recovery
path understand exact same-slot points would leave the feature only partially
correct. The required hashes and indexes already exist, so rollback is included
in this decision; issue #89 tracks that implementation work.

## Consequences

Positive:

- Fresh full syncs maintain a correct live Byron UTXO set.
- Shelley transactions can resolve outputs created during Byron.
- Rollback and post-store compensation distinguish an EBB from its same-slot
  successor using exact point identity.
- Ordinary current-era rollback retains its existing result while gaining a
  same-slot hash-mismatch safety check.
- Custom Byron networks can capture a meaningful Shelley-start UTXO total.
- Shelley+ application remains isolated from Byron policy branches.
- EBB semantics are explicit and cannot corrupt UTXO cursor metadata.

Tradeoffs:

- `DefaultUtxoStore` has two era-specific entry points and owns an additional
  package-private collaborator with some deliberate input/output plumbing
  duplication.
- Fresh mainnet sync performs UTXO writes throughout Byron and therefore uses
  more CPU, write bandwidth, and temporary storage than the old incomplete
  path.
- A new event and handler wiring are required for both synchronous and
  asynchronous modes.
- ChainState rollback APIs and their production callers must migrate from a
  slot coordinate to a complete point.
- Existing full-state databases require rebuild or an explicit migration.
- The archive temporarily retains a separate Byron outpoint resolver even
  though the live store will also know those outputs.

## Implementation plan

Implement issue #89 first in the change sequence before enabling Byron UTXO
apply. It may ship in the same PR when review and tests preserve that ordering;
this keeps a Byron-populated UTXO database from ever depending on slot-only
rollback.

1. Define null-hash origin normalization for both Yaci `(0, null)` and legacy
   compensation `(-1, null)`, and add the Yano-owned `PointRollbackCapable`
   extension implemented by direct RocksDB and in-memory ChainState; retain the
   external `rollbackTo(Long)` contract as legacy.
2. Apply exact same-slot hash selection to both full and header-only ChainState
   rollback, and migrate ChainSync plus compensation to the point capability.
3. Collapse both `DefaultUtxoStore` rollback loops onto one exact-point
   implementation. Add the derived-store point capability, make startup adhoc
   rollback resolve its hash through ChainState first, and migrate the Byron-
   aware projection outbox plus any remaining stores/callers that lose point
   identity.
4. Complete the point-aware rollback and compensation tests before proceeding
   with the Byron apply work below.
5. Add marker validation, the role-independent empty-ChainState fresh-genesis
   batch, snapshot admission checks, and the explicit one-shot operator rebuild
   setting before any code can publish the new Byron event. Producer mode
   defers only Shelley funds to its real-genesis-block path.
6. Add `ByronMainBlockAppliedEvent` to `core-api` and `applyByronBlock` to
   `UtxoStoreWriter`.
7. Add the package-private final `ByronUtxoApplier`, with `stageBlock` and
   classified `stageGenesisOutputs` methods that write only to caller-owned
   batches; recreate it after snapshot reinitialization.
8. Add `StorageFilter.acceptByronUtxoOutput`, chain/facade delegation, and the
   built-in address-filter override before applying filtered Byron outputs.
9. Before startup recovery, wire the chain metadata handle needed by the
   genesis batch. Preserve non-AVVM/AVVM classification, delegate output/address
   staging to the applier, and persist only AVVM outpoint keys for Allegra.
10. Register the new event in both UTXO handlers, with one ordered async worker,
    exactly-once compatibility acknowledgement, and native-event failure
    propagation.
11. Publish the event from `BodyFetchManager.applyByronBlock`; never publish it
    from `applyByronEbBlock`.
12. Extract the common delta/cursor staging helper and add the era-neutral
    continuity/idempotency observer in warning-and-counter mode.
13. Implement native Byron input/output, committed-plus-intra-block resolution,
    spent records, address indexing, and unresolved-input instrumentation.
14. Update reconciliation to deserialize/apply Byron main bodies, ignore valid
    EBBs, fail closed on malformed or missing required bodies, and invoke the
    shared Shelley-start-total capture when replay crosses the era boundary.
15. Correct Allegra cleanup to remove only still-unspent AVVM redeem outputs,
    then run the full cross-era accounting tests.
16. Measure Byron sync throughput and RocksDB growth before enabling migration
    for existing databases.

## Tests

Add focused tests for:

1. A Byron main block spends a stored genesis output and creates the expected
   base58-address output.
2. The mainnet block-3,314 fixture consumes
   `a12a839c25a01fa5d118167db5acdbd9e38172ae8f00e5ac0a4997ef792a2007#0`;
   afterward `/addresses/Ae2tdPwUPEZ3DdaWu8jn553npu6jwEPAJiahruj3xQjPXxgoxfYDWusJz7x/utxos`
   does not return that output.
3. Genesis and main-block Byron outputs use identical UTXO encoding, outpoint
   keys, and general address-index construction.
4. AVVM and non-AVVM genesis outputs are both seeded, but only AVVM outpoint
   keys are persisted for Allegra removal.
5. Genesis metadata and the capability marker cannot commit without the
   caller-owned genesis batch.
6. `ByronUtxoApplier` stages mutations but cannot commit or write cursor
   metadata independently.
7. A later Byron main block spends an output created by an earlier Byron block.
8. Two transactions in one Byron main block create and consume an output in
   order.
9. Committed-store plus intra-block resolution works without relying on batch
   read visibility.
10. An unresolved input in an unfiltered Byron store increments the diagnostic
    counter, identifies the point/outpoint, and aborts before commit; filtered
    mode uses its separate expected-miss diagnostic. The equivalent Shelley
    fixture increments its warning counter without changing existing apply
    behavior.
11. Existing filters default-accept Byron without null Shelley arguments, the
    built-in address filter applies the same predicate in both eras, and a
    Byron-aware plugin can reject a Byron output.
12. The Byron delta contains the exact created/spent outpoints and the block
   hash.
13. `META_LAST_APPLIED_SLOT` and `META_LAST_APPLIED_BLOCK` commit with the Byron
   UTXO transition.
14. A failed RocksDB batch leaves both UTXO state and cursor unchanged.
15. Snapshot restore refreshes the applier's RocksDB handles.
16. A Byron EBB creates no delta and does not change either cursor value.
17. Reconciliation applies stored Byron main bodies, ignores valid EBBs, and
    fails closed for malformed Byron-tagged or missing required bodies. A main
    block apply failure is never caught by classification or retried as an EBB.
18. An absent marker with either ChainState tip is rejected even when the UTXO
    cursor and Byron era metadata are absent; an unmarked full-state snapshot is
    rejected before reconciliation or service resume.
19. Empty-chain initialization writes the marker for relay/client, fresh
    block-producer, file-backed devnet, in-memory devnet, and empty-distribution
    configurations.
20. Rebuild explicitly seeds both genesis distributions despite a non-null
    ChainState tip, recaptures `shelley_start_utxo_total` at the transition, and
    refuses when `BlockPruner` has removed a required Byron body. The one-shot
    startup property reaches this operation for an unmarked database.
21. Given `main N -> EBB N -> main N+1`, both full and header-only rollback to
    the EBB remove the same-slot successor and restore the exact EBB tip.
22. Rollback to `main N+1` retains its matching-hash UTXO delta.
23. A same-slot hash mismatch, an invalid non-null target hash, and an equal-slot
    delta without a hash all fail closed.
24. Both Yaci `Point.ORIGIN` `(0, null)` and compensation `(-1, null)` clear to
    origin in both ChainState implementations, while `(0, validHash)` remains a
    non-origin block point.
25. Post-store compensation from `main N+1` restores the preceding same-slot
   EBB by both slot and hash.
26. Direct RocksDB and in-memory ChainState implementations produce equivalent
    exact-point results.
27. Rollback within Byron restores the inputs and removes the outputs created
    by every reverted main block.
28. Rollback across the Byron/Shelley boundary re-derives the UTXO cursor from
    the surviving Byron delta, including its hash.
29. A Shelley transaction resolves and spends a Byron-created output.
30. Stake-balance state is unchanged by Byron main-block application.
31. Synchronous and asynchronous handlers preserve cross-era order and publish
    exactly one `UtxoStateAppliedEvent` after a successful Byron commit; failure
    records the native event type and publishes no acknowledgement.
32. Allegra removes and returns to reserves only still-unspent AVVM redeem
    outputs; non-AVVM outputs remain, including on a custom-network fixture.
33. The diagnostic continuity observer does not warn for a missing cursor after
    rollback, genesis block-zero aliasing, or bootstrap injection across a
    mainnet/preprod/preview/block-producing-devnet/bootstrap fixture matrix.
34. Existing Shelley+ UTXO, filtering, acknowledgement, and rollback tests pass
    unchanged.
35. Projection rollback to a Byron EBB removes its same-slot main-block
    successor, rollback to the successor retains it, and origin clears pending
    projection state.
36. Producer startup stages the capability marker without Shelley funds, then
    the producer genesis path writes those funds with the real genesis hash and
    leaves both UTXO total and stake balances at exactly one copy.
37. Envelope classification proves `0` selects EBB and `1` selects main without
    using either permissive Byron body decoder as a type probe.

At minimum, run:

```bash
./gradlew :core-api:test :runtime:test :ledger-state:test
```

The implementation is not complete until a fresh Byron-to-Shelley fixture or
public-network replay demonstrates identical final UTXO outpoints and lovelace
total against a trusted ledger reference at the Shelley boundary.
