# ADR-027: Durable Epoch Nonce State Repair

## Status

Accepted - implementation in progress

## Date

2026-05-22

## Problem

Yano tracks Cardano epoch nonce state in relay/client mode so the node can expose
and verify epoch nonces while syncing. The nonce algorithm is cumulative: each
block's VRF output evolves internal state, and the next epoch nonce is derived
from that accumulated state at the epoch boundary.

Today Yano persists two related pieces of state independently:

```text
ChainState body tip
  durable cursor: slot, block number, block hash

EpochNonceState
  durable nonce variables only:
    currentEpoch
    epochNonce
    evolvingNonce
    candidateNonce
    labNonce
    ticknPrevHashNonce
```

The current serialized nonce state does not record the body point that it
represents. On startup Yano restores the serialized nonce state from RocksDB and
continues evolving it from new block events. It does not verify that the nonce
state corresponds to the durable body tip, and it does not replay stored block
bodies to repair a mismatch.

This creates a correctness gap:

```text
1. LedgerApplyProcessor applies block Bn.
2. BodyFetchManager calls ChainState.storeBlock(Bn).
3. ChainState durably advances body_tip to Bn.
4. Process stops before NonceEvolutionListener persists nonce state for Bn.
5. Restart restores:
      body_tip     = Bn
      nonce_state  = Bn-k
6. Sync resumes after Bn, so nonce evolution for the missing blocks is skipped.
7. The next epoch nonce and all later epoch nonces diverge.
```

Rollback has a related gap. Live rollback uses in-memory checkpoints in
`EpochNonceState`. Those checkpoints are bounded and are lost on restart. If a
rollback is processed after restart and no in-memory checkpoint exists, nonce
rollback currently logs a warning and may leave nonce state inconsistent.

## Evidence

In the native-image mainnet test run, epoch nonce values matched known mainnet
reference data through epoch 304. The first mismatch appeared at epoch 305 after
a restart while syncing around epoch 304.

This indicates:

- mainnet epoch 259 `extraEntropy` was applied correctly
- the nonce formula and era handling were correct before the restart window
- the divergence is caused by persisted nonce state not being aligned with the
  durable body tip

## Current Behavior

Live block apply:

```text
BlockFetch delivers Bn
  |
  v
LedgerApplyProcessor
  |
  v
BodyFetchManager.onBlock(Bn)
  |
  +-- ChainState.storeBlock(Bn)
  |
  +-- publish BlockAppliedEvent(Bn)
        |
        v
      NonceEvolutionListener
        |
        +-- evolve EpochNonceState with Bn VRF output
        +-- save in-memory checkpoint at Bn.slot
        +-- overwrite RocksDB epoch_nonce_state
        +-- if epoch boundary, store epoch_nonce_by_epoch_<epoch>
```

Nonce persistence today has two concepts:

```text
epoch_nonce_state
  overwritten after each block
  full evolving nonce variables
  no durable cursor

epoch_nonce_by_epoch_<epoch>
  written at epoch boundary
  stores only the stable epoch nonce value for API/reference use
  not enough to rebuild full nonce state
```

The historical epoch nonce value is one value per epoch, but it is not enough for
repair. Replay needs the full internal nonce state: `epochNonce`,
`evolvingNonce`, `candidateNonce`, `labNonce`, `ticknPrevHashNonce`, and the
cursor point.

## Decision

Make epoch nonce state self-describing and repairable by adding durable cursors
and epoch-boundary full-state checkpoints.

Yano will persist:

```text
latest_nonce_state
  full EpochNonceState snapshot
  cursor: slot, block number, block hash
  overwritten after each applied block

epoch_nonce_checkpoint_<epoch>
  full EpochNonceState snapshot
  cursor: slot, block number, block hash
  written at epoch boundary

epoch_nonce_by_epoch_<epoch>
  existing historical epoch nonce value
  kept for API/reference compatibility
```

The implementation may use the existing `epoch_nonce_state` key for
`latest_nonce_state`, but its payload must include a format version and cursor.
Since Yano is not released yet, old nonce metadata does not require a production
migration. The format should still use an explicit new version so the persisted
blob is unambiguous.

The durable envelope will be a separate `NonceStateSnapshot` codec rather than
adding ChainState cursor fields directly to `EpochNonceState.serialize()`.
`EpochNonceState` remains responsible only for nonce variables and nonce math.
`NonceStateSnapshot` owns the persistence envelope:

```text
version
cursor slot
cursor block number
cursor block hash
serialized EpochNonceState bytes
```

This keeps cursor ownership explicit and allows the durable format to evolve
without changing the core nonce-state object.

## Startup Repair Algorithm

On startup, after ChainState is opened and before client sync starts:

```text
body_tip = ChainState.getTip()
latest   = read latest nonce snapshot

if body_tip is null:
    initialize nonce from genesis or configured seed

else if latest exists and latest.cursor == body_tip:
    restore latest and continue

else if latest exists and latest.cursor < body_tip:
    restore latest
    replay stored block bodies from latest.cursor + 1 through body_tip
    persist repaired latest snapshot at body_tip

else if latest exists and latest.cursor > body_tip:
    checkpoint = newest epoch_nonce_checkpoint where checkpoint.cursor <= body_tip
    if checkpoint exists:
        restore checkpoint
        replay stored block bodies from checkpoint.cursor + 1 through body_tip
        persist repaired latest snapshot at body_tip
    else:
        restore from genesis
        replay stored block bodies from first non-Byron block through body_tip
        persist repaired latest snapshot at body_tip

else:
    restore from genesis
    replay stored block bodies through body_tip
    persist latest snapshot at body_tip
```

Startup repair must run after startup derived-state recovery has finalized any
interrupted epoch boundary. This is required because nonce replay resolves
historical TPraos `extraEntropy` through the epoch parameter tracker; replaying
before boundary recovery can reproduce the block sequence with incomplete epoch
parameters.

`EpochNonceState` must also know the first non-Byron slot before genesis nonce
initialization. Use persisted era metadata when available, then the configured
or genesis-derived `firstNonByronSlot`. This prevents a fresh mainnet/preprod
sync from initializing at epoch 0 and performing a false TICKN at the first
Shelley block.

If required block bodies are missing, Yano must fail startup with a clear error.
Continuing with an unverifiable nonce state is not acceptable.

## Replay Scope

Nonce replay must be narrow. It must not replay full ledger-state events.

```text
Stored block body/header
  |
  v
NonceReplayService
  |
  +-- resolve era
  +-- extract slot
  +-- extract previous hash
  +-- extract VRF output
  +-- resolve TPraos extraEntropy at epoch boundaries
  |
  v
EpochNonceState
```

The replay path should share the same nonce-evolution logic as
`NonceEvolutionListener` to avoid formula drift. Extract a small helper, for
example `EpochNonceEvolver`, that is used by both live event handling and startup
repair.

## Rollback Behavior

Live rollback keeps the existing fast path:

```text
RollbackEvent(R)
  |
  v
EpochNonceState.rollbackTo(R.slot)
  |
  +-- restore in-memory checkpoint if present
  +-- persist latest nonce snapshot
  +-- prune historical epoch nonce values after restored epoch
```

If an in-memory checkpoint is not available:

```text
1. Find newest durable epoch_nonce_checkpoint <= rollback target.
2. Restore that full checkpoint.
3. Replay stored block bodies up to rollback target.
4. Persist latest nonce snapshot at the post-rollback body tip.
5. Prune epoch_nonce_by_epoch and epoch_nonce_checkpoint entries after the
   restored epoch.
```

If repair cannot be performed because stored block bodies are missing, fail the
rollback path. Do not continue with a warning-only inconsistent nonce state.
The durable nonce cursor must be origin or a complete body-tip cursor
(`slot`, `blockNumber`, `blockHash`). Partial cursors such as
`slot=rollbackPoint, blockNumber=-1` are rejected.

## Storage Impact

The latest snapshot is overwritten every block, as today. It remains one RocksDB
value.

Epoch-boundary full checkpoints are small. A snapshot is a few hundred bytes plus
cursor metadata. Even thousands of epoch checkpoints should be small compared to
block bodies, UTXO state, and ledger-state indexes.

This ADR intentionally does not store a historical nonce snapshot for every
block. In-memory per-block checkpoints remain bounded to the recent rollback
window and are used only for live rollback.

Yano should retain all epoch nonce checkpoints for now. The storage cost is tiny
and keeping them simplifies manual rollback, restart repair, and debugging. A
future pruning policy can be added if the checkpoint set ever becomes
meaningful compared to the rest of ChainState.

The practical replay limit is block-body availability, not nonce checkpoint
availability. If block bodies are pruned, nonce repair can only replay across
the retained block-body window.

## Block Body Retention Alignment

`yano.rollback-retention-epochs` is an epoch-based umbrella setting. It controls
how far operators expect manual rollback and repair workflows to work.

`yano.chain.block-body-prune-depth` is a block-count setting. When it is `0`,
block-body pruning is disabled. When it is non-zero, older block bodies are
deleted once the body tip is more than that many blocks ahead.

If both are configured, Yano must prevent an unsafe mismatch where rollback
metadata is retained for several epochs but block bodies required for replay are
pruned much sooner.

Use a conservative estimate derived from genesis:

```text
estimatedBlocksPerEpoch = ceil(epochLength * activeSlotsCoeff)
safeBlocksPerEpoch      = estimatedBlocksPerEpoch * 2
minimumPruneDepth       = rollbackRetentionEpochs * safeBlocksPerEpoch
```

If `activeSlotsCoeff` is missing or invalid, fall back to:

```text
estimatedBlocksPerEpoch = epochLength
```

This fallback over-retains but is safe.

When `yano.chain.block-body-prune-depth` is non-zero and lower than
`minimumPruneDepth`, Yano should raise the effective prune depth to
`minimumPruneDepth` and log the adjustment. This preserves the operator's
rollback-retention expectation while still allowing block-body pruning.

Mainnet example:

```text
epochLength = 432000
activeSlotsCoeff = 0.05

estimatedBlocksPerEpoch = ceil(432000 * 0.05)
                         = 21600

safeBlocksPerEpoch = 21600 * 2
                   = 43200

yano.rollback-retention-epochs = 5
minimumPruneDepth = 5 * 43200
                  = 216000 blocks
```

So this configuration is unsafe as written:

```text
yano.rollback-retention-epochs = 5
yano.chain.block-body-prune-depth = 2160
```

The effective block-body prune depth should be raised to `216000`.

## Atomic StoreBlock Batch Consideration

`DirectRocksDBChainState.storeBlock(...)` already writes the block body and body
tip with a RocksDB `WriteBatch`. In principle, Yano could compute the new nonce
snapshot before committing the block and contribute nonce keys to that same
batch. That would close the narrow crash window between `storeBlock(Bn)` and
`storeEpochNonceState(Bn)`.

This is worth considering, but it is not sufficient as the whole solution and
should not be the first implementation step.

Reasons:

- The current apply order is:

  ```text
  storeBlock(Bn)
  publish epoch-boundary events if needed
  publish BlockAppliedEvent(Bn)
  NonceEvolutionListener evolves nonce from BlockAppliedEvent
  ```

  The epoch-boundary events run before the nonce listener. That ordering matters
  because TPraos epoch boundaries need finalized epoch parameters, including
  historical `extraEntropy`. Moving nonce evolution into `storeBlock` would move
  it before the current boundary-processing path unless the apply flow is
  refactored.

- Epoch-boundary processing, rewards, AdaPot, governance, UTXO, and account
  listeners are not part of the `storeBlock` batch. Yano already needs derived
  state reconciliation/repair semantics for work that happens after body
  storage. Nonce currently lacks that repair path.

- Rollback has the same consistency problem in the opposite direction. A crash
  after ChainState rollback but before nonce rollback persistence can leave
  `nonce_cursor > body_tip`. Atomic block-apply batching alone does not repair
  rollback or restart cases where in-memory nonce checkpoints are gone.

- Coupling nonce evolution directly into `DirectRocksDBChainState.storeBlock`
  would mix ChainState persistence with consensus nonce math and epoch parameter
  lookup. Keeping a separate `NonceStateSnapshot` codec plus replay path keeps
  the ownership cleaner.

Therefore, this ADR chooses durable cursors, epoch checkpoints, and replay repair
as the primary correctness mechanism.

After that is in place, Yano may add an optimization where the latest nonce
snapshot for `Bn` is written in the same RocksDB batch as `storeBlock(Bn)`.
That optimization must preserve the existing epoch-boundary parameter semantics
and must not replace the replay repair path.

## Extra Entropy

Replay must use the same extra-entropy behavior as live sync:

- apply `extraEntropy` only on TPraos epoch boundaries, Shelley through Alonzo
- treat null entropy as neutral nonce
- never apply entropy for Babbage or later eras
- keep the mainnet epoch 259 guard so missing entropy is visible

The observed mainnet run matched through epoch 304, so this ADR does not change
the nonce formula or entropy semantics.

## Failure Rules

```text
latest cursor == body tip
  -> restore, prune future epoch nonce/checkpoint entries, and continue

latest cursor behind body tip
  -> replay forward from latest cursor

latest cursor ahead of body tip
  -> restore nearest durable epoch checkpoint <= body tip and replay forward

no usable checkpoint
  -> replay from genesis if stored blocks are available

missing required block body
  -> fail startup or rollback with explicit error

missing in-memory rollback checkpoint
  -> repair from durable epoch checkpoint, not warning-only continue
```

## Non-Goals

- Do not change Cardano nonce math.
- Do not change reward, AdaPot, governance, UTXO, or account-state logic.
- Do not store a full nonce checkpoint for every block in RocksDB.
- Do not couple nonce evolution directly into `DirectRocksDBChainState.storeBlock`
  in the first implementation. Atomic latest-snapshot writes can be considered
  later, but replay repair remains required.
- Do not trust header tip as the nonce cursor. Nonce state follows body apply.

## Implementation Plan

### Phase 1: Snapshot Model

- Add a durable `NonceStateSnapshot` envelope:
  - format version
  - cursor slot
  - cursor block number
  - cursor block hash
  - serialized full `EpochNonceState`
- Replace or wrap the current `epoch_nonce_state` payload with this envelope.
- Keep the existing historical `epoch_nonce_by_epoch_<epoch>` value.
- Keep `EpochNonceState.serialize()` focused on nonce variables only.

### Phase 2: Epoch Checkpoints

- Add RocksDB keys for full nonce checkpoints by epoch.
- Write a checkpoint at each successful epoch transition after the nonce state is
  advanced for the boundary block.
- Prune checkpoint entries after rollback when their epoch is newer than the
  restored nonce epoch.

### Phase 3: Shared Evolver

- Extract nonce evolution logic from `NonceEvolutionListener` into a helper that
  accepts:
  - era
  - slot
  - previous hash
  - VRF output
  - epoch parameter provider
- Use it from live block apply and replay.

### Phase 4: Startup Repair

- Validate latest nonce snapshot cursor against ChainState body tip.
- Add forward replay from latest snapshot to body tip.
- Add backward repair from nearest epoch checkpoint when latest cursor is ahead
  of body tip.
- Fail with a clear error if stored blocks required for repair are missing.

### Phase 5: Rollback Repair

- Keep in-memory checkpoint rollback as the first choice.
- If no in-memory checkpoint exists, repair from durable epoch checkpoint and
  replay forward to the rollback target.
- Remove warning-only continuation for unrepaired nonce rollback.

### Phase 6: Observability

Add logs and health fields for:

- nonce snapshot cursor
- body tip used for validation
- replay start and end points
- number of replayed blocks
- repair source: latest, epoch checkpoint, or genesis
- repair failure reason

### Phase 7: Block Body Retention Guard

- When `yano.rollback-retention-epochs > 0` and
  `yano.chain.block-body-prune-depth > 0`, compute the minimum safe prune depth
  from genesis epoch length and active slots coefficient.
- Raise the effective prune depth if the configured value is too low.
- Log the configured value, computed minimum, and effective value.
- Use `epochLength` as the fallback estimated block count when
  `activeSlotsCoeff` is missing or invalid.

### Phase 8: Tests

Required tests:

- clean restart where nonce cursor equals body tip
- simulated crash after `storeBlock` and before nonce persistence, then repair
  forward to body tip
- latest nonce cursor ahead of body tip, repaired from epoch checkpoint
- missing in-memory rollback checkpoint repaired from durable epoch checkpoint
- missing required block body fails startup or rollback
- epoch 259 mainnet `extraEntropy` still applied
- epoch-boundary checkpoint stores full state, not only epoch nonce
- `GET /api/v1/node/epoch-nonce` reports repaired current nonce state
- block-body prune depth is raised when below rollback-retention estimate
- fallback block-body retention estimate uses epoch length when active slots
  coefficient is unavailable

## Open Questions

- Should the REST nonce endpoint include the nonce cursor point for diagnostics?

## References

- `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/blockproducer/EpochNonceState.java`
- `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/blockproducer/NonceEvolutionListener.java`
- `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/blockproducer/NonceStateStore.java`
- `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/chain/DirectRocksDBChainState.java`
- `runtime/src/main/java/com/bloxbean/cardano/yano/runtime/Yano.java`
- ADR-NET-005: Yaci-Yano Data Flow and Ordered Ledger Apply
- ADR-NET-006: Yaci-Yano Sync, Rollback, and Disconnect Flow
