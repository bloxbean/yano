# ADR-043: Stage Epoch Artifacts Only After a Shelley+ Source Epoch

## Status

Accepted (implemented)

## Date

2026-08-27

## Related decisions and tracking

- [ADR-039](in-progress/039-canonical-projection-outbox.md) — staged-file
  evidence for irreproducible reward, DRep-distribution and governance history
- [ADR-025](025-bootstrap-partial-state-ledger-and-nonce-boundaries.md) —
  Byron-to-Shelley ledger initialization and boundary processing
- [Issue #91](https://github.com/bloxbean/yano/issues/91) — a Byron EBB
  permanently disables reward artifact staging

## Context

Epoch ledger processing and epoch archive staging currently share the same
boundary callback, but they do not have the same era domain:

- ledger processing must run at the Byron-to-Shelley boundary to bootstrap
  Shelley AdaPot and related state;
- reward, DRep-distribution and governance artifacts describe Shelley+ ledger
  behavior and have no Byron-era facts to capture.

On mainnet, the first Byron epoch-boundary block (EBB) publishes the transition
`0 -> 1` at slot 21,600 with difficulty/block number 21,586. An EBB does not
advance difficulty, so that number belongs to the preceding main block.
`slot_by_number[21586]` consequently resolves the main block's earlier slot,
not the EBB slot.

Mainnet builds the CF network configuration eagerly. The existing
`EpochBoundaryProcessor` guard only defers pre-Shelley work while that
configuration is absent, so the full boundary processor runs during Byron.
Its pool-reap phase opens the enabled reward staged-file writer even when no
pool can exist. `EpochArchiveStagingService.writer` resolves the boundary by
block number, observes the main-block/EBB slot mismatch, and persists
`epoch-source/FAILED`.

The failure is global and durable by design: a staged artifact is
irreproducible after its boundary, so continuing silently could produce a
healthy-looking archive with gaps. Once the erroneous Byron failure is
latched, every later Shelley+ reward artifact is suppressed across restarts.

## Constraints

- Do not skip or reorder core epoch processing. In particular, mainnet
  transition `207 -> 208` must still bootstrap Shelley AdaPot.
- Do not add network-magic tables or hard-coded mainnet/preprod epoch numbers.
- Preserve fail-closed behavior when a Shelley+ artifact writer is invoked
  without a prepared boundary or encounters a genuine staging failure.
- Do not create completion markers for boundaries where no staged artifact is
  applicable.
- Do not automatically clear an existing `FAILED` marker. Such an archive has
  already missed irreproducible history and cannot be declared complete by
  resuming at the current tip.
- Keep the change in the app-owned archive staging layer. Ledger state,
  transition events, chain indexing and the core staging SPI remain unchanged.

## Decision

### 1. Derive the first post-Byron epoch from resolved network parameters

`ProjectionHistoryService` constructs the existing `EpochSlotCalc` from the
already-loaded `NetworkGenesisConfig` values for:

- Shelley+ epoch length;
- Byron slots per epoch; and
- first non-Byron slot, using an explicit value already present in `YanoConfig`
  or the same `DefaultEpochParamProvider` resolver used by runtime startup.

It passes `EpochSlotCalc.firstNonByronEpoch()` to
`EpochArchiveStagingService`. This is network-neutral and uses the same epoch
schedule as runtime synchronization:

- mainnet resolves 208;
- preprod resolves 4;
- preview, sanchonet and Shelley-only devnets resolve 0; and
- custom Byron networks derive the value from their configured/genesis
  schedule.

This must not call the fail-fast `YanoConfig.getEpochLength()` or
`getByronSlotsPerEpoch()` accessors. The packaged app initializes projection
history before `RuntimeNode.start()` propagates parsed genesis values back into
that mutable configuration. Genesis supplies both lengths directly; the shared
runtime resolver supplies the public-network transition when no explicit value
was set.

No new configuration property or known-network switch is introduced. An
explicitly configured custom Byron transition remains authoritative. Custom
Shelley-only/devnet networks follow the runtime resolver and start at epoch 0.

### 2. Classify by the epoch that just ended

At `beginBoundary`, staging is skipped exactly when:

```text
boundary.previousEpoch < firstPostByronEpoch
```

The previous epoch is the source epoch whose boundary facts are being
finalized. Therefore:

- mainnet `0 -> 1` through `207 -> 208` are staging no-ops;
- mainnet staging begins at `208 -> 209`;
- preprod staging begins after its first complete post-Byron epoch; and
- a Shelley-only network stages from `0 -> 1`.

Use an explicit `skipCurrentBoundary` flag, defaulting to `false`. It becomes
`true` only for a recognized pre-Shelley source epoch and resets after both
completion and abort.

The inverse `stageCurrentBoundary = false` default is rejected: it would turn
an accidentally missing `beginBoundary` call into a silent omission. With
`skipCurrentBoundary = false`, an unprepared writer still reaches the existing
null-boundary guard and latches a visible failure, preserving the archive's
fail-closed contract.

### 3. A skipped boundary has no staging lifecycle artifacts

For a skipped boundary:

- `enabled(dataset)` returns false, so all `open*` calls are no-ops;
- `completeBoundary` writes no completion marker;
- `abortBoundary` deletes no completion marker; and
- both paths clear the boundary and reset the skip flag.

The ledger processor, account-state batches, snapshots, protocol parameters,
AdaPot bootstrap and event publication continue unchanged.

### 4. Existing failed archives remain failed closed

Upgrade does not delete or ignore `history/epoch-source/FAILED`. Deleting only
that marker would allow future capture while leaving all previously missed
reward epochs absent, with no honest complete-history claim.

Operators requiring complete reward history must use the corrected build and
rebuild/replay the archive from genesis. Retained block bodies may avoid a new
network download when a future operator workflow supports local full replay,
but current archives cannot reconstruct the missing rows from tip state.

## Files

Production:

1. `app/.../ProjectionHistoryService.java` — derive/pass the first post-Byron
   epoch.
2. `app/.../EpochArchiveStagingService.java` — boundary classification and
   lifecycle suppression.

Tests:

3. `app/.../EpochArchiveStagingServiceTest.java` — era matrix and lifecycle
   regressions.

No core-api, ledger-state, runtime or database-schema file changes.

## Alternatives considered

### Skip `EpochBoundaryProcessor` before Shelley

Rejected. It would also skip the `207 -> 208` AdaPot bootstrap and other core
ledger initialization.

### Make the archive coordinate lookup EBB-aware

Rejected as the primary fix. It would permit creating empty Byron archive
artifacts and completion markers for a domain that begins in Shelley. EBB-aware
lookup may still be useful elsewhere, but staging should not run for Byron
source epochs.

### Delay the epoch transition until the first Shelley/main block

Rejected. The transition event is a ledger concern and the EBB is the actual
epoch boundary. Reordering it broadens the change into consensus/account-state
behavior for no archive benefit.

### Default staging to disabled outside a boundary

Rejected. A missed `beginBoundary` would silently omit irreproducible rows.
Unprepared Shelley+ staging must remain a visible, persistent failure.

### Automatically clear the old failure marker

Rejected. Resuming future staging does not restore prior reward history and
would make an incomplete archive look repaired.

## Consequences

Positive:

- Byron EBBs no longer poison Shelley+ reward history.
- Core ledger behavior and storage formats remain untouched.
- The rule works for public and custom networks through existing epoch math.
- Genuine Shelley+ staging failures remain durable and operator-visible.

Tradeoffs:

- Existing affected archives require a full corrected replay for completeness.
- Staging now depends on one resolved schedule value supplied at construction;
  initialization already requires those values for epoch processing.

## Verification plan

1. Mainnet-shaped unit boundary matrix (`firstPostByronEpoch = 208`):
   `0 -> 1` and `207 -> 208` skip; `208 -> 209` stages.
2. Preprod-shaped matrix (`4`): `3 -> 4` skips and `4 -> 5` stages.
3. Shelley-only/devnet matrix (`0`): `0 -> 1` stages.
4. A skipped boundary creates no source file, completion marker or `FAILED`
   marker and never resolves a canonical block coordinate.
5. Completion and abort both reset the skip flag; the next eligible boundary
   stages normally.
6. Calling an enabled writer without `beginBoundary` still creates `FAILED`,
   proving fail-closed behavior was not weakened.
7. An existing `FAILED` marker remains effective after construction/restart.
8. Run the app archive tests and broad affected-module regression suites.
9. Start the packaged application with an uninitialized epoch-parameter view,
   as production does, and verify projection initialization derives the
   preprod threshold from loaded genesis without failing.
10. Package the application and run an isolated preprod `wallet` sync across
   Byron and at least one complete Shelley+ epoch boundary. Confirm no epoch
   staging error during Byron, reward staging becomes eligible only after the
   first post-Byron source epoch, sync continues, and graceful restart retains
   healthy staging.

## Release note

Epoch staged-file history now begins only after a complete Shelley+ source
epoch. Existing archives containing `history/epoch-source/FAILED` with
`epoch boundary slot mismatch` are not automatically repaired; complete reward
history requires replay from genesis with the corrected build.

## Implementation and validation

Implemented on `fix/shelley-epoch-staging-boundary`. The production change is
limited to the two app archive classes listed above; no core epoch processor,
ledger-state schema or network profile changed.

Automated validation on 2026-08-27:

- focused staging tests passed;
- `:core-api:test`, `:ledger-state:test`, `:runtime:test`,
  `:archive-modules:archive-core:test` and `:app:test` passed (2,512 tests,
  0 failures, 3 pre-existing skips); and
- `:app:quarkusBuild` passed.

An isolated packaged preprod `wallet` sync at
`/Users/satya/Downloads/yano-issue91-preprod-20260827-v2` verified:

- boundaries `0 -> 1`, `1 -> 2`, `2 -> 3` and `3 -> 4` completed without an
  epoch staging failure or completion marker;
- the unchanged core path bootstrapped AdaPot at `3 -> 4`;
- `4 -> 5` at slot 518,400/block 21,645 staged reward evidence and passed
  AdaPot verification;
- status reported archive `READY`, contributor `HEALTHY`, zero drain failures
  and a null `epochStagingError`; and
- graceful restart restored epoch-5 nonce state, archive identity and sync.

The first packaged validation attempt also caught an implementation-order bug:
projection initialization precedes runtime propagation into `YanoConfig`.
Resolution now reads epoch lengths from the already parsed genesis and uses the
shared first-non-Byron-slot resolver; a regression test covers that production
ordering.

Additional kill-9 testing found a separate pre-existing recovery gap outside
this decision: a crash inside a Shelley+ boundary can make startup call
`EpochBoundaryProcessor.recoverInterruptedBoundary()` without reconstructing
and preparing the lost in-memory artifact boundary coordinate. The existing
fail-closed artifact guard then rejects the recovered delegation snapshot.
This ADR does not weaken that guard or silently skip the artifact; the recovery
integration requires its own change because it needs durable/exact boundary
coordinate reconstruction and affects all Shelley+ staged artifacts, not Byron
boundary eligibility.
